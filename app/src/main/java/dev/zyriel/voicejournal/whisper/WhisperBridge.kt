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

    private const val TAG = "WhisperBridge"

    /** Which library was loaded, for the bench JSON and bug reports. */
    val loadedLibrary: String

    /** Guarded by [probeLock]: the first optimized call has returned. */
    private var fastProbed = false
    private val probeLock = Any()

    init {
        val marker = FastLibMarker.file()
        val fastCrashed = marker?.exists() == true
        if (fastCrashed) {
            Log.i(TAG, "${CpuFeatures.OPTIMIZED_LIB} crashed in an earlier transcription " +
                "(${marker?.name} present); loading ${CpuFeatures.COMPAT_LIB}")
        }
        val wanted = CpuFeatures.libraryFor(CpuFeatures.readCpuinfo(), fastCrashed)
        loadedLibrary = try {
            System.loadLibrary(wanted)
            wanted
        } catch (e: UnsatisfiedLinkError) {
            // A missing or unloadable optimized build must not take
            // transcription down with it; the baseline build runs anywhere.
            if (wanted == CpuFeatures.COMPAT_LIB) throw e
            Log.w(TAG, "loading $wanted failed, falling back", e)
            System.loadLibrary(CpuFeatures.COMPAT_LIB)
            CpuFeatures.COMPAT_LIB
        }
    }

    /**
     * [fullTranscribe] with the crash marker around the first call on the
     * optimized library. A SIGILL kills the process inside the call, so the
     * marker outlives it and the next load picks the compat build. Calls
     * after the first, and every call on the compat build, go straight
     * through.
     */
    fun transcribe(
        ctxPtr: Long,
        samples: FloatArray,
        nThreads: Int,
        vadModelPath: String?,
        suppressNst: Boolean,
        temperatureInc: Float,
    ): ByteArray? {
        if (loadedLibrary != CpuFeatures.OPTIMIZED_LIB) {
            return fullTranscribe(ctxPtr, samples, nThreads, vadModelPath, suppressNst, temperatureInc)
        }
        synchronized(probeLock) {
            if (!fastProbed) {
                val marker = FastLibMarker.file()
                runCatching { marker?.writeText(CpuFeatures.OPTIMIZED_LIB) }
                try {
                    return fullTranscribe(ctxPtr, samples, nThreads, vadModelPath, suppressNst, temperatureInc)
                } finally {
                    // Reached on return or a JVM exception: either way the
                    // native code ran without killing the process.
                    marker?.delete()
                    fastProbed = true
                }
            }
        }
        return fullTranscribe(ctxPtr, samples, nThreads, vadModelPath, suppressNst, temperatureInc)
    }

    external fun initContext(modelPath: String): Long
    /**
     * Runs whisper_full on [samples] (16 kHz mono floats). Returns the
     * transcript as UTF-8 bytes, empty for real silence, or null when
     * transcription failed (null context, or whisper_full returned
     * non-zero). [temperatureInc] <= 0 disables the temperature fallback.
     */
    external fun fullTranscribe(
        ctxPtr: Long,
        samples: FloatArray,
        nThreads: Int,
        vadModelPath: String?,
        suppressNst: Boolean,
        temperatureInc: Float,
    ): ByteArray?
    external fun freeContext(ctxPtr: Long)
}
