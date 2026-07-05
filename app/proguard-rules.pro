# R8 keep rules for the release build.
#
# Every rule here exists because something is reached from outside the
# Kotlin call graph: JNI symbol lookup, ONNX Runtime's internal
# reflection, or Room's generated code. Removing any of these produces
# builds that compile and then crash at runtime, so treat this file like
# CMakeLists' -O3 override: never trim without re-verifying on hardware.

# --- whisper.cpp JNI bridge ---
# Native symbols are resolved by the mangled Java_<package>_<class>_<method>
# names compiled into libwhisperjni.so. If R8 renames WhisperBridge or its
# methods, System.loadLibrary succeeds but the first external call throws
# UnsatisfiedLinkError. Keep the class name and every native member name.
-keepclasseswithmembernames,includedescriptorclasses class dev.zyriel.voicejournal.whisper.WhisperBridge {
    native <methods>;
}

# --- ONNX Runtime ---
# Required by the official docs for R8-minimized builds using
# com.microsoft.onnxruntime:onnxruntime-android; the library reaches its
# own classes reflectively (protobuf) and crashes at runtime without this.
# https://onnxruntime.ai/docs/build/android.html
-keep class ai.onnxruntime.** { *; }

# --- Room ---
# Room ships consumer rules that cover its runtime, but the entity and the
# type converters are resolved from generated code; keeping them named makes
# schema debugging sane and costs nothing (two small classes).
-keep class dev.zyriel.voicejournal.data.JournalEntry { *; }
-keep class dev.zyriel.voicejournal.data.Converters { *; }

# Strip Android log calls from release; the JNI-side __android_log_print
# is unaffected (native code, not touched by R8).
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
