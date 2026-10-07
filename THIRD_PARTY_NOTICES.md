# Third-party notices

AniSub is licensed under GPL-3.0-or-later (see `LICENSE`). It includes or downloads the following
third-party components. No third-party model data is stored in this repository.

## Bundled in the Android APK

| Component | Version | License | Source |
|---|---|---|---|
| sherpa-onnx (`libsherpa-onnx-jni.so`, Android build with statically linked ONNX Runtime) | 1.13.8 | Apache-2.0 | https://github.com/k2-fsa/sherpa-onnx/tree/v1.13.8 |
| ONNX Runtime (statically linked) | as pinned by sherpa-onnx 1.13.8 `cmake/onnxruntime*.cmake` | MIT | https://github.com/microsoft/onnxruntime |
| espeak-ng, piper fork (statically linked) | as pinned by `cmake/espeak-ng-for-piper.cmake` | GPL-3.0-or-later | https://github.com/espeak-ng/espeak-ng |
| piper-phonemize | as pinned by `cmake/piper-phonemize.cmake` | MIT | https://github.com/rhasspy/piper-phonemize |
| kaldi-native-fbank, kaldi-decoder, kaldifst, OpenFst, simple-sentencepiece | as pinned in `cmake/` | Apache-2.0 | see sherpa-onnx `cmake/` |
| nlohmann/json; Eigen | as pinned in `cmake/` | MIT; MPL-2.0 | see sherpa-onnx `cmake/` |
| Google ML Kit Translate (`com.google.mlkit:translate`, native `libtranslate_jni.so`) | 17.0.3 | Proprietary, ML Kit Terms of Service (https://developers.google.com/ml-kit/terms); closed source accepted by the owner 07-10-2026 | https://developers.google.com/ml-kit/language/translation |
| Google ML Kit Language Identification, bundled model (`com.google.mlkit:language-id`, `liblanguage_id_l2c_jni.so`) | 17.0.6 | Proprietary, ML Kit Terms of Service | https://developers.google.com/ml-kit/language/identification |
| Their dependencies: Google Play services tasks/basement/base, Firebase components / datatransport, AndroidX, Kotlin stdlib | as resolved by Gradle | Apache-2.0 (AndroidX, Kotlin, Firebase components); Android SDK / Google APIs terms (Play services libraries) | Maven Google / Maven Central |
| Java JNI binding `com.k2fsa.sherpa.onnx.*` (trimmed, modified) | derived from 1.13.8 java-api | Apache-2.0, Copyright 2024 Xiaomi Corporation | `apps/android/app/src/main/java/com/k2fsa/sherpa/onnx/` |

The native libraries are not committed: `apps/android/tools/fetch-native.ps1` downloads the
official release archive and verifies it against `apps/android/native-artifacts.json`. Corresponding
source for the binaries is the sherpa-onnx v1.13.8 tag and the dependency versions pinned in its
`cmake/` directory.

## Downloaded at runtime after user consent (not in the APK)

| Component | License |
|---|---|
| Piper voice `vi_VN-vais1000-medium` (sherpa-onnx ONNX conversion) | MIT (rhasspy/piper-voices) |
| VAIS-1000 training corpus (attribution) | CC BY 4.0 |
| Piper voice `en_US-ljspeech-medium` (sherpa-onnx ONNX conversion), trained from scratch by Bryce Beattie | MIT (rhasspy/piper-voices) |
| LJ Speech Dataset (Keith Ito, LibriVox recordings) | Public domain |
| espeak-ng-data subset | GPL-3.0-or-later |
| ML Kit translation models (Google, downloaded by ML Kit from dl.google.com) | ML Kit Terms of Service |

The ML Kit components are not GPL-compatible free software. They are a separate, optional feature
the owner chose (as AniBox did for OCR); AniSub removes their telemetry backend
(`TransportBackendDiscovery`) and start-up providers, and downloads models only after consent.
Details and caveats: [docs/voices.md](docs/voices.md).

## Windows prototype

The Windows/desktop prototype downloads its own models into the Git-ignored `models/` folder;
see `docs/windows-app.md` and `model-manager/quality-catalog.json` for their sources and licenses.
