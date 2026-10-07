"""Offline opt-in benchmark over own SAPI fixture; prints metrics, never transcript."""
import argparse
import json
from pathlib import Path
import re
import sys
import time
import wave
import numpy as np

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'providers/capture'))
from live_asr import RollingRecognition, LocalDecoder
from windows_capture_worker import verify_asr_model

EXPECTED = 'The battery lasts for two hours, but performance drops when you unplug the charger. This is not a problem with the screen. You should not buy this device only for its battery life. Yes, yes, the price matters too.'


def tokens(text):
    return re.findall(r"[a-z]+(?:'[a-z]+)?", text.lower())


def wer(reference, hypothesis):
    a, b = tokens(reference), tokens(hypothesis)
    previous = list(range(len(b) + 1))
    for i, word in enumerate(a, 1):
        row = [i]
        for j, other in enumerate(b, 1):
            row.append(min(row[-1] + 1, previous[j] + 1, previous[j-1] + (word != other)))
        previous = row
    return round(previous[-1] / len(a), 3)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--wav', required=True)
    parser.add_argument('--threads', type=int, default=2)
    parser.add_argument('--device', choices=['cpu', 'auto'], default='cpu')
    args = parser.parse_args()
    with wave.open(args.wav) as wav:
        if wav.getsampwidth() != 2: raise ValueError('PCM16 fixture required')
        rate, channels = wav.getframerate(), wav.getnchannels()
        samples = np.frombuffer(wav.readframes(wav.getnframes()), dtype=np.int16).astype(np.float32) / 32768
    samples = samples.reshape(-1, channels).mean(axis=1)
    if rate != 16000:
        samples = np.interp(np.arange(int(len(samples) * 16000 / rate)) * rate / 16000, np.arange(len(samples)), samples).astype(np.float32)
    model_path = ROOT / 'models/whisper-small-536b066'
    verify_asr_model(model_path)
    model = LocalDecoder(model_path, device=args.device, cpu_threads=args.threads)
    warm, _ = model.transcribe(samples[:64000], language='en')
    list(warm)
    metrics = {}
    for mode, seconds in [('old_fixed4', 4), ('rolling2', 2), ('rolling3', 3)]:
        output, infer, first_horizon = [], [], None
        state = RollingRecognition()
        # Silence drains held boundary words from the last real block.
        padded = np.concatenate((samples, np.zeros(32000, dtype=np.float32)))
        size = seconds * 16000
        for index, offset in enumerate(range(0, len(padded), size), 1):
            audio = padded[offset:offset+size]
            if len(audio) < size: audio = np.pad(audio, (0, size-len(audio)))
            rolling = mode.startswith('rolling')
            if rolling: audio, start, end = state.window(audio, index)
            began = time.monotonic()
            segments, _ = model.transcribe(audio, language='en', task='translate' if mode == 'old_fixed4' else 'transcribe',
                beam_size=1, vad_filter=True, condition_on_previous_text=False,
                word_timestamps=rolling, initial_prompt=state.prompt or None)
            segments = list(segments)
            text = state.utterance(state.commit(segments, start, end), end) if rolling else ' '.join(s.text for s in segments)
            if text and first_horizon is None: first_horizon = index * seconds
            output.append(text)
            infer.append(round((time.monotonic()-began)*1000))
        text = ' '.join(output)
        words = tokens(text)
        metrics[mode] = dict(backend=model.backend, audioSeconds=round(len(samples)/16000, 2), inferenceMs=sum(infer),
            maxWindowInferenceMs=max(infer), realTimeFactor=round(sum(infer)/1000/(len(samples)/16000), 3),
            wordErrorRate=wer(EXPECTED, text), negationsRecognized=words.count('not'),
            repeatedYesRecognized=words.count('yes'), windows=len(infer),
            firstEmissionAudioHorizonSeconds=first_horizon, emittedUtterances=sum(bool(t) for t in output))
    print(json.dumps(metrics))


if __name__ == '__main__': main()
