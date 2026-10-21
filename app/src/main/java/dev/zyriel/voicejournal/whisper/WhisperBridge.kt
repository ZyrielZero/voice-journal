package dev.zyriel.voicejournal.whisper

import android.util.Log

/**
 * JNI surface to whisper.cpp. whisper and ggml are linked statically into
 * the JNI library, which ships in two builds (see cpp/CMakeLists.txt):
 * libwhisperjni.so for cores with dotprod and fp16, and
 * libwhisperjni_compat.so for baseline armv8-a. [CpuFeatures] picks one
 * from /proc/cpuinfo before anything is loaded, because the optimized
 * library would SIGILL on an older core.
 *
 * Hardware-verified (Pixel 10 Pro XL, v0.4.x): transcription runs on-device;
 * see the committed benchmark JSON. Those numbers predate the dotprod build.
 */
object WhisperBridge {

    /** Which library was loaded, for the bench JSON and bug reports. */
    val loadedLibrary: String

    init {
        val wanted = CpuFeatures.libraryFor(CpuFeatures.readCpuinfo())
        loadedLibrary = try {
            System.loadLibrary(wanted)
            wanted
        } catch (e: UnsatisfiedLinkError) {
            // A missing or unloadable optimized build must not take
            // transcription down with it; the baseline build runs anywhere.
            if (wanted == CpuFeatures.COMPAT_LIB) throw e
            Log.w("WhisperBridge", "loading $wanted failed, falling back", e)
            System.loadLibrary(CpuFeatures.COMPAT_LIB)
            CpuFeatures.COMPAT_LIB
        }
    }

    external fun initContext(modelPath: String): Long
    external fun fullTranscribe(ctxPtr: Long, samples: FloatArray, nThreads: Int, vadModelPath: String?): String
    external fun freeContext(ctxPtr: Long)
}
