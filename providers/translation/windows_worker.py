"""Pinned local EN->VI + Vietnamese preset speech, JSON-lines worker. No network."""
import argparse
import collections
import contextlib
import hashlib
import io
import json
import logging
import math
import os
from pathlib import Path
import sys
import shutil
import subprocess
import time
import uuid
from content_policy import canonicalize, policy_key, validate_policy

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "work" / "python-deps"))
os.environ.update(HF_HUB_OFFLINE="1", TRANSFORMERS_OFFLINE="1", HF_HUB_DISABLE_TELEMETRY="1")
logging.disable(logging.CRITICAL)

PACKS = {
    "989c9fb9ec63987901022baf0182dcec3e149be6": ("Helsinki-NLP/opus-mt-en-vi", {"README.md", "config.json", "generation_config.json", "pytorch_model.bin", "source.spm", "target.spm", "tokenizer_config.json", "vocab.json"}),
    "aba295eb96a6fa6003ebe417cc1f2802a7adc1dc": ("pnnbao-ump/VieNeu-TTS-v3-Nano", {"README.md", "config.json", "constants.npz", "text_encoder.onnx", "duration_predictor.onnx", "vector_estimator.onnx", "codec_decoder.onnx"}),
}

class DiscardDiagnostics:
    def write(self, value):
        return len(value)
    def flush(self):
        pass

def verify_pack(directory, revision):
    manifest = json.loads((directory / "manifest.json").read_text(encoding="utf-8"))
    repo, required = PACKS[revision]
    listed = [item["file"] for item in manifest["artifacts"]]
    if manifest["revision"] != revision or manifest["repo"] != repo or len(listed) != len(required) or set(listed) != required:
        raise ValueError("Model revision mismatch")
    for item in manifest["artifacts"]:
        target = directory / item["file"]
        if target.resolve().parent != directory.resolve() or target.stat().st_size != item["bytes"]:
            raise ValueError("Artifact size/path mismatch")
        digest = hashlib.sha256()
        with target.open("rb") as source:
            for block in iter(lambda: source.read(1024 * 1024), b""):
                digest.update(block)
        if digest.hexdigest() != item["sha256"]:
            raise ValueError("Artifact digest mismatch")

class LocalProvider:
    def __init__(self, model, tts, session=None):
        self.model_dir, self.tts_dir = model, tts
        self.mt = self.tokenizer = self.voice = None
        self.turbo = (tts / 'onnx_update').is_dir()
        self.cache = collections.OrderedDict()
        self.audio = collections.deque()
        sessions = ROOT / "work" / "speech-session"
        occupied = sum(path.stat().st_size for path in sessions.glob("*/*.wav") if path.is_file())
        if occupied > 100 * 1024 * 1024:
            raise OSError("Speech cache quota exceeded; clean retained test/aborted output")
        session = session or uuid.uuid4().hex
        if len(session) != 32 or any(character not in "0123456789abcdef" for character in session):
            raise ValueError("Invalid owned speech session")
        self.output = ROOT / "work" / "speech-session" / session
        self.output.mkdir(parents=True)

    def load_mt(self):
        if self.mt is not None:
            return
        verify_pack(self.model_dir, "989c9fb9ec63987901022baf0182dcec3e149be6")
        import torch
        from transformers import MarianConfig, MarianMTModel, MarianTokenizer
        torch.set_num_threads(2)
        self.tokenizer = MarianTokenizer.from_pretrained(str(self.model_dir), local_files_only=True)
        config = MarianConfig.from_pretrained(str(self.model_dir), local_files_only=True)
        # Old official .bin is loaded with restricted weights_only, never remote code.
        self.mt = MarianMTModel(config)
        weights = torch.load(self.model_dir / "pytorch_model.bin", map_location="cpu", weights_only=True)
        if "lm_head.weight" not in weights and config.tie_word_embeddings:
            weights["lm_head.weight"] = weights["model.shared.weight"]
        self.mt.load_state_dict(weights, strict=True)
        self.mt.tie_weights()
        self.mt.eval()

    def load_voice(self):
        if self.voice is not None:
            return
        if self.turbo:
            sys.path.insert(0, str(ROOT / 'providers/tts'))
            from turbo_local import load_turbo
            self.voice = load_turbo(self.tts_dir)
        else:
            verify_pack(self.tts_dir, "aba295eb96a6fa6003ebe417cc1f2802a7adc1dc")
            from vieneu.v3nano import V3NanoVieNeuTTS
            self.voice = V3NanoVieNeuTTS(onnx_dir=str(self.tts_dir), threads=2, steps=16)

    def voice_catalog(self):
        path = ROOT / ('work/python-deps/vieneu/assets/voices_v3_turbo.json' if self.turbo else 'work/python-deps/vieneu/assets/voices_v3_nano.json')
        if path.stat().st_size > 4 * 1024 * 1024:
            raise ValueError("Voice catalog exceeds bound")
        data = json.loads(path.read_text(encoding="utf-8"))
        presets = data.get("presets", {})
        if not isinstance(presets, dict) or not 1 <= len(presets) <= 64:
            raise ValueError("Invalid voice catalog")
        voices = []
        for name, preset in presets.items():
            description = preset.get("description", "")
            gender = preset.get("gender", "")
            if not isinstance(name, str) or not 1 <= len(name) <= 80 or not isinstance(description, str) or len(description) > 256 or gender not in ("male", "female"):
                raise ValueError("Invalid voice metadata")
            parts = [part.strip() for part in description.split("·")]
            region = {"Bắc": "north", "Trung": "central", "Nam": "south"}.get(parts[1], "unknown") if len(parts) >= 2 else "unknown"
            voices.append({"name": name, "gender": gender, "region": region, "description": description})
        default = data.get("default_voice")
        if default not in presets:
            raise ValueError("Default voice unavailable")
        return {"voices": voices, "defaultVoice": default, "voiceProfile": 'Turbo' if self.turbo else 'Nano'}

    def execute(self, operation, text, target_ms=0, voice_name=None, content_policy=None):
        if operation == "validate-policy":
            validate_policy(content_policy)
            return {"text": "Policy valid"}
        if operation == "voice-catalog":
            return self.voice_catalog()
        if operation == "probe-voice":
            self.load_voice()
            return {"text": "Offline Vietnamese preset ready: " + ('Turbo' if self.turbo else 'Nano 16 steps')}
        if operation == "release":
            path = Path(text).resolve()
            if path.parent != self.output.resolve() or path.suffix != ".wav":
                raise ValueError("Not an owned speech artifact")
            if path in self.audio:
                path.unlink(missing_ok=True)
                self.audio.remove(path)
            return {}
        if operation == "probe":
            self.load_mt(); self.load_voice()
            return {"text": "Offline EN→VI / Vietnamese Nano preset ready"}
        if operation == "translate":
            policy = validate_policy(content_policy)
            key = (text, policy_key(policy))
            self.load_mt()
            if key in self.cache:
                self.cache.move_to_end(key)
                return dict(self.cache[key])
            import torch
            prefix = ">>vie<< " if ">>vie<<" in self.tokenizer.supported_language_codes else ""
            tokens = self.tokenizer(prefix + text, return_tensors="pt", truncation=False)
            if tokens["input_ids"].shape[1] > 256:
                raise ValueError("Cue exceeds 256 tokens")
            with torch.inference_mode():
                output = self.mt.generate(**tokens, max_new_tokens=128, num_beams=1)
            result = self.tokenizer.decode(output[0], skip_special_tokens=True)
            if len(output[0]) >= 129 and int(output[0][-1]) != self.mt.config.eos_token_id:
                raise ValueError("Translation output exceeded bound")
            reply = canonicalize(text, result, policy)
            self.cache[key] = reply
            if len(self.cache) > 256:
                self.cache.popitem(last=False)
            return dict(reply)
        if operation == "synthesize":
            if len(text) > 512:
                raise ValueError("Speech cue exceeds 512 characters")
            if len(self.audio) >= 16:
                raise BufferError("Speech lease cap reached; release consumed output")
            if not isinstance(target_ms, (int, float)) or not math.isfinite(target_ms) or target_ms < 0 or target_ms > 30_000:
                raise ValueError("Invalid speech budget")
            self.load_voice()
            import numpy as np
            import soundfile as sf
            # Validate explicitly; no typo/missing preset may silently fall back.
            if voice_name is None:
                voice_name = self.voice._default_voice
            if not isinstance(voice_name, str) or not 1 <= len(voice_name) <= 80:
                raise ValueError("Invalid voice selection")
            self.voice.get_preset_voice(voice_name)
            audio = self.voice.infer(text, voice=voice_name) if self.turbo else self.voice.infer(text, voice=voice_name, steps=16, sway=-1, apply_watermark=False)
            sample_rate = 48_000 if self.turbo else 24_000
            if len(audio) > sample_rate * 30 or not np.isfinite(audio).all():
                raise ValueError("Speech audio exceeds bound")
            natural_ms = len(audio) * 1000 / sample_rate
            tempo = min(1.35, max(1.0, natural_ms / target_ms)) if target_ms else 1.0
            if tempo > 1.01:
                executable = shutil.which("ffmpeg")
                if not executable:
                    raise RuntimeError("Timing adapter FFmpeg unavailable")
                fitted = subprocess.run([executable, "-hide_banner", "-loglevel", "error",
                    "-f", "f32le", "-ar", str(sample_rate), "-ac", "1", "-i", "pipe:0",
                    "-af", "atempo=" + format(tempo, ".5f"), "-f", "f32le", "pipe:1"],
                    input=np.asarray(audio, dtype="<f4").tobytes(), stdout=subprocess.PIPE,
                    stderr=subprocess.DEVNULL, timeout=10, check=True)
                if len(fitted.stdout) > sample_rate * 30 * 4:
                    raise ValueError("Adapted audio exceeds bound")
                audio = np.frombuffer(fitted.stdout, dtype="<f4").copy()
            if len(audio) == 0 or not np.isfinite(audio).all():
                raise ValueError("Empty or invalid speech waveform")
            # Millisecond edges suppress clicks, never remove spoken samples.
            edge = min(sample_rate * 8 // 1000, len(audio) // 2)
            if edge:
                audio[:edge] *= np.linspace(0, 1, edge)
                audio[-edge:] *= np.linspace(1, 0, edge)
            path = self.output / (uuid.uuid4().hex + ".wav")
            try:
                sf.write(str(path), audio, sample_rate, subtype="PCM_16")
            except Exception:
                path.unlink(missing_ok=True)
                raise
            self.audio.append(path)
            return {"audioPath": str(path), "audioDurationMs": round(len(audio) * 1000 / sample_rate, 2),
                    "naturalDurationMs": round(natural_ms, 2), "tempo": round(tempo, 5), "steps": 16, "voiceName": voice_name}
        raise ValueError("Unsupported operation")

    def close(self):
        # Only files created by this instance; never recursive deletion or user media.
        for path in self.audio:
            path.unlink(missing_ok=True)
        self.output.rmdir()

def main():
    args = argparse.ArgumentParser()
    args.add_argument("--model", type=Path, required=True); args.add_argument("--tts", type=Path, required=True)
    args.add_argument("--session")
    options = args.parse_args()
    provider = LocalProvider(options.model, options.tts, options.session)
    try:
        while True:
            line = sys.stdin.readline(65537)
            if not line:
                break
            request_id = 0
            started = time.perf_counter()
            try:
                if len(line) > 65536 or not line.endswith("\n"):
                    raise ValueError("Request exceeds bound")
                request = json.loads(line)
                request_id = request["id"]
                text = request["text"]
                if not isinstance(text, str) or len(text) > 4096:
                    raise ValueError("Invalid text")
                # Third-party diagnostics may contain text: keep them out of the wire/logs.
                with contextlib.redirect_stdout(DiscardDiagnostics()), contextlib.redirect_stderr(DiscardDiagnostics()):
                    result = provider.execute(request["operation"], text, request.get("targetMs", 0), request.get("voiceName"), request.get("contentPolicy"))
                reply = {"id": request_id, "ok": True, **result}
            except Exception as exc:
                reply = {"id": request_id, "ok": False, "error": type(exc).__name__}
            reply["latencyMs"] = round((time.perf_counter() - started) * 1000, 2)
            print(json.dumps(reply, ensure_ascii=False), flush=True)
    finally:
        provider.close()

if __name__ == "__main__":
    main()
