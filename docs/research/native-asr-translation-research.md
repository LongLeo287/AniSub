# Native runtime, ASR and translation research

Verified 2026-10-06 from primary sources. Static source/doc review only; no engine, model or
benchmark executed. Supplemental upstreams absent from the lexical sheet selection are labelled.

## Evidence anchors

| Upstream | Sheet row | Inspected commit |
|---|---:|---|
| 0xShug0/audio.cpp | 1583 | d894789017cfd349581dec9e79752e0e4562fbf3 |
| welcomyou/sherpa-vietnamese-asr | 1453 | 350f5a4c569d714cfe574e36e9a2913b81912ba1 |
| QwenLM/Qwen3-ASR | 963 | 7c6daf77a2421100f5fb066495372c00129d39ff |
| VinAIResearch/PhoWhisper | 1020; model row 418 | b06f1937995cfee75b9e4ad3e2ae0798faf1a562 |
| ggml-org/whisper.cpp | supplemental runtime | 60c0be6ac8fa71b1a2ae2dd938a31a34a508e774 |
| k2-fsa/sherpa-onnx | supplemental upstream | 99ddefaa92129858b80a71a426903dd4215c83fa |
| SYSTRAN/faster-whisper | supplemental runtime | f6ef59b5e3b70d58e46641417894c3b73eaddb7a |
| OpenNMT/CTranslate2 | supplemental runtime | 998bb99f7b80e061ff999f69f3764784e265ccbc |

Commits resolved through GitHub REST metadata before its unauthenticated rate limit was reached.
Remaining reads used pinned raw files and public primary pages. NOASSERTION in GitHub metadata
is not proof of missing license: the actual audio.cpp and Vietnamese ASR LICENSE files were read.

## audio.cpp: evaluate a provider backend, not AniSub's coordinator

The root project covers multiple audio tasks on desktop through ggml and native CLI/server
surfaces. Windows packaging exists. That breadth does not establish an Android service/NDK
delivery path or per-model feature parity. Adopt it provisionally as a Windows backend candidate,
behind a narrow adapter. Do not make AniSub's session/protocol depend on its model registry.
[README](https://github.com/0xShug0/audio.cpp/blob/d894789017cfd349581dec9e79752e0e4562fbf3/README.md).

The inspected `include/audiocpp.h` exposes ABI 0.2.0 with opaque registry/model/session handles,
runtime capability queries and borrowed output buffers. Individual handles are not thread-safe.
Copy PCM/text before mutating/freeing handles; serialize each handle on one provider executor.
Streaming is pull-based (`stream_policy/start/push/next_event/finish/reset`), rather than a
cross-FFI callback. Offline `session_run` is synchronous. The header exposes no general abort
token, so `reset` must not be assumed safe as concurrent cancellation of an active blocking call.
[Header](https://github.com/0xShug0/audio.cpp/blob/d894789017cfd349581dec9e79752e0e4562fbf3/include/audiocpp.h),
[C API implementation](https://github.com/0xShug0/audio.cpp/blob/d894789017cfd349581dec9e79752e0e4562fbf3/src/capi/audiocpp.cpp).

Build integration is opt-in via `AUDIOCPP_BUILD_C_API`; shared-library target wraps engine_runtime.
Native model-manager is also optional. CMake explicitly identifies static eSpeak-ng as GPL;
therefore dependency attribution cannot be inferred from the root Apache-2.0 license.
[CMake](https://github.com/0xShug0/audio.cpp/blob/d894789017cfd349581dec9e79752e0e4562fbf3/CMakeLists.txt),
[root license](https://github.com/0xShug0/audio.cpp/blob/d894789017cfd349581dec9e79752e0e4562fbf3/LICENSE).

Important VieNeu parity gap: current family is `vieneu_v3_turbo`, previously `vietneu_tts`.
Its specific port documentation lists streaming as not yet ported and requires a supplied
speaker embedding for accurate reference-voice cloning because CAM++ is not ported. Normal
Vietnamese text also needs the SEA-G2P frontend/library/data; preset voice assets are additional
inputs. The upstream Python VieNeu API's capabilities cannot be projected onto this port.
[Port documentation](https://github.com/0xShug0/audio.cpp/blob/d894789017cfd349581dec9e79752e0e4562fbf3/docs/community_models/vieneu_v3_turbo.md),
[session code](https://github.com/0xShug0/audio.cpp/blob/d894789017cfd349581dec9e79752e0e4562fbf3/src/community_models/vieneu_v3_turbo/session.cpp).

AniSub consequence: implement `probe(model, task, mode)` before selecting streaming. Start with
a process-isolated Windows adapter if a blocking native call cannot meet stop deadlines; this is
a proposed deployment option, not a license workaround. Retire revisions immediately, discard late
PCM, stop host playback, and release/reset the backend only through its safe owner thread.
Pin model/frontend/tokenizer/voice assets separately. Upstream performance reports are not AniSub measurements.

## ASR comparison

| Candidate | Source-supported role | AniSub verdict and open gate |
|---|---|---|
| OpenAI Whisper, row 781 | multilingual transcription; Python reference; translate task targets English | reference baseline; does not translate arbitrary source into Vietnamese |
| whisper.cpp, supplemental | C/C++ runtime, Android example, Windows builds | first native ASR portability spike; model size/codec/ABI/API/device measurements still required |
| faster-whisper, supplemental | CTranslate2-backed Python ASR with VAD/word timestamps | Windows comparator/worker, not a drop-in Android module |
| PhoWhisper, rows 1020/418 | Vietnamese fine-tuned Whisper family, tiny through large | evaluate VI audio only; it is not Japanese/Thai input coverage |
| Qwen3-ASR, row 963 | multilingual 0.6B/1.7B ASR includes VI/Thai/JA | desktop quality candidate; backend footprint and streaming deployment need measurement |
| sherpa-onnx, supplemental | native offline/online APIs and Android/Windows support | Android ASR framework candidate, select model independently |
| sherpa-vietnamese-asr, row 1453 | Windows app built on sherpa/ONNX with offline segment decoding | extract VI frontend/config/calibration ideas; do not copy its whole desktop/service stack |
| SenseVoice, supplemental | Small lists ZH/YUE/EN/JA/KO | optional JA input experiment; no VI/Thai default based on this model's documented languages |
| Silero VAD, supplemental | speech/silence detector, not transcription | endpointing aid; does not identify speakers or solve BGM recognition |

Primary links for rows above: [Whisper](https://github.com/openai/whisper),
[whisper.cpp](https://github.com/ggml-org/whisper.cpp),
[faster-whisper](https://github.com/SYSTRAN/faster-whisper),
[PhoWhisper](https://github.com/VinAIResearch/PhoWhisper),
[Qwen3-ASR](https://github.com/QwenLM/Qwen3-ASR),
[sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx),
[Vietnamese app](https://github.com/welcomyou/sherpa-vietnamese-asr),
[SenseVoice](https://github.com/QwenAudio/SenseVoice), [Silero](https://github.com/snakers4/silero-vad).

### Code-specific integration findings

`whisper.h` has a new-segment callback and a ggml abort callback before computation. Use the latter
with generation/cancel state; collect timestamps only when available. This is stronger evidence
for cooperative cancellation than assuming every inference framework has an interrupt API.
[Pinned header](https://github.com/ggml-org/whisper.cpp/blob/60c0be6ac8fa71b1a2ae2dd938a31a34a508e774/include/whisper.h).

faster-whisper's transcribe path yields segments from a generator and offers VAD/word timestamps.
A generator is not proof of incremental online ASR, and stopping iteration does not automatically
guarantee a currently executing native decode aborts. Windows GPU deployment has CUDA/cuDNN
dependencies; preserve a CPU comparator. [Implementation](https://github.com/SYSTRAN/faster-whisper/blob/f6ef59b5e3b70d58e46641417894c3b73eaddb7a/faster_whisper/transcribe.py).

The Vietnamese wrapper's `streaming_asr.py` uses VAD-triggered `OfflineRecognizer.from_transducer`.
Its stop flag ends the worker loop; that differs from a streaming transducer decoder or an
interrupt inside inference. Distinguish live capture plus segment decoding from model-level streaming.
GUI/pyannote/Transformers dependencies would unnecessarily enlarge a minimal AniSub worker.
[Source](https://github.com/welcomyou/sherpa-vietnamese-asr/blob/350f5a4c569d714cfe574e36e9a2913b81912ba1/streaming_asr.py),
[requirements](https://github.com/welcomyou/sherpa-vietnamese-asr/blob/350f5a4c569d714cfe574e36e9a2913b81912ba1/requirements.txt).

Sherpa's C API separates offline recognizer/stream objects from online recognizer/stream objects.
An online API does not make every supported checkpoint online. Build the adapter's streaming bit
from selected checkpoint and API, not library name. [Header](https://github.com/k2-fsa/sherpa-onnx/blob/99ddefaa92129858b80a71a426903dd4215c83fa/sherpa-onnx/c-api/c-api.h).

Qwen3-ASR's recognition language list includes Vietnamese and Thai; the separate ForcedAligner
list has 11 languages and excludes those two. Do not promise VI/Thai word alignment from ASR
language coverage. Its documented streaming path uses the vLLM backend; the Transformers example
alone is insufficient evidence for that capability. [Pinned documentation](https://github.com/QwenLM/Qwen3-ASR/blob/7c6daf77a2421100f5fb066495372c00129d39ff/README.md).

Whisper/faster-whisper use MIT code; sherpa-onnx/Qwen3-ASR use Apache-2.0 code. Vietnamese wrapper
LICENSE is MIT despite GitHub's NOASSERTION detector. PhoWhisper repository is BSD-3-Clause;
its selected small model card declares BSD-3-Clause too. Still record exact checkpoint terms,
tokenizer and training-data references rather than extrapolating licenses across all conversions.
[Vietnamese LICENSE](https://github.com/welcomyou/sherpa-vietnamese-asr/blob/350f5a4c569d714cfe574e36e9a2913b81912ba1/LICENSE),
[PhoWhisper-small model card](https://huggingface.co/vinai/PhoWhisper-small).

## Translation: engine and language-pair model are separate choices

The sheet has wrappers but no verified default JA→VI/TH→VI local translation provider. Add a
translation benchmark before selecting one. A cloud API wrapper does not satisfy offline mode.
Preserve cue boundaries, names, honorifics, ambiguity and glossary overrides; test dialogue with
context rather than ranking solely by generic sentence BLEU.

Supplemental CTranslate2 is an MIT runtime with Windows Python packages and C++ integration.
Its official conversion guide supports MarianMT/M2M100/NLLB and warns about required tokenizer
special tokens. Evaluate in a Windows worker; Android NDK/service viability remains unverified.
[Runtime](https://github.com/OpenNMT/CTranslate2),
[conversion guide](https://opennmt.net/CTranslate2/guides/transformers.html),
[installation](https://opennmt.net/CTranslate2/installation.html).

| Supplemental model | Verified card facts | Verdict |
|---|---|---|
| Helsinki-NLP/opus-mt-en-vi | EN→VI, Marian, Apache-2.0 tag | small-direction baseline candidate; does not cover JA/Thai by itself |
| facebook/m2m100_418M | MIT tag; lists JA/TH/VI; target-language token required | multi-pair comparator; measured subtitle quality required |
| facebook/nllb-200-distilled-600M | CC-BY-NC-4.0; research-oriented | research-only comparator, not default commercial provider |

[EN→VI card](https://huggingface.co/Helsinki-NLP/opus-mt-en-vi),
[M2M100 card](https://huggingface.co/facebook/m2m100_418M),
[NLLB card](https://huggingface.co/facebook/nllb-200-distilled-600M).

`llama.cpp` appears at row 1247 and is a possible local LLM runtime; selecting it does not select
a translator or prove Japanese/Thai/Vietnamese dialogue quality. An LLM provider must bound output,
preserve cue ids and reject malformed/hallucinated outputs. It is lower priority than establishing
the deterministic direct-text contract. [Upstream](https://github.com/ggml-org/llama.cpp).

ONNX Runtime has an official Android deployment example, but that is not proof that every ONNX
export's operators, size, API floor, quantization and acceleration work on the target TV. Pin the
artifact/ABI/API and test CPU first; treat NNAPI/device acceleration as an optional measured path.
[Android deployment](https://onnxruntime.ai/docs/tutorials/mobile/deploy-android.html).

## Provider gates for the first implementation

1. Start fake-core contracts without linking audio.cpp/Whisper/ONNX. Decide core language through
   a no-model Windows/Android fixture spike; provider language must not force coordinator language.
2. Windows TTS: compare upstream VieNeu ONNX with audio.cpp VieNeu offline mode and a permitted
   preset. Verify phonemizer dependencies and speech expiry; avoid voice cloning as a first milestone.
3. ASR: native whisper.cpp versus a selected sherpa checkpoint; Windows faster-whisper and
   Qwen3-ASR are optional quality comparators. VI-only PhoWhisper has a distinct input-language role.
4. Translation: EN→VI baseline, then JA→VI/TH→VI M2M100 comparison with native reviewers; reject
   release adoption while model license or target-pair quality is unresolved.
5. Require per-adapter hard/soft cancel reporting, process crash containment, bounded PCM buffers,
   warm/cold latency, RSS/VRAM, drop counts and no obsolete-revision output before runtime activation.
