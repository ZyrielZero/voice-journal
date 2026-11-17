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
 * Hardware-verified (Pixel 11 Pro XL, v1.1.0, dotprod library):
 * transcription runs on-device; see the committed benchmark JSON.
 */
object WhisperBridge {

    private const val TAG = "WhisperBridge"

    /** Which library was loaded, for the bench JSON and bug reports. */
    val loadedLibrary: String

    /** Guarded by [probeLock]: no probe is needed (any more) in this process. */
    private var fastProbed: Boolean
    private val probeLock = Any()

    init {
        val avoidFast = FastLibMarker.shouldAvoidFast()
        if (avoidFast) {
            Log.i(TAG, "${CpuFeatures.OPTIMIZED_LIB} did not survive ${FastLibMarker.MAX_STRIKES} " +
                "transcriptions in a row on this build; loading ${CpuFeatures.COMPAT_LIB}")
        }
        val wanted = CpuFeatures.libraryFor(CpuFeatures.readCpuinfo(), avoidFast)
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
        // Already proven on this build: no probe, no marker, no window.
        fastProbed = loadedLibrary != CpuFeatures.OPTIMIZED_LIB || FastLibMarker.isVerified()
    }

    /**
     * [fullTranscribe] with the strike marker around the first call on the
     * optimized library. A SIGILL kills the process inside the call, so the
     * marker outlives it; [FastLibMarker] turns repeated leftovers into a
     * fallback to the compat build. Once one call has returned on this
     * build, and on the compat build always, calls go straight through.
     */
    fun transcribe(
        ctxPtr: Long,
        samples: FloatArray,
        nThreads: Int,
        vadModelPath: String?,
        suppressNst: Boolean,
        temperatureInc: Float,
    ): ByteArray? {
        synchronized(probeLock) {
            if (!fastProbed) {
                FastLibMarker.beginProbe()
                try {
                    return fullTranscribe(ctxPtr, samples, nThreads, vadModelPath, suppressNst, temperatureInc)
                } finally {
                    // Reached on return or a JVM exception: either way the
                    // native code ran without killing the process.
                    FastLibMarker.endProbe()
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
