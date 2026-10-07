# AniSub R8 rules (ANISUB-004). Shrinking only: names stay readable (GPL corresponding source,
# stack traces), so -dontobfuscate. ML Kit and Play services ship their own consumer rules.
-dontobfuscate
# sherpa-onnx JNI reads these classes' fields and calls their methods by name from native code.
-keep class com.k2fsa.sherpa.onnx.** { *; }
# AniSub's own classes are small; keep them whole (service/activity entry points, JSON contract).
-keep class com.anisub.runtime.** { *; }
