package app.reventor.dictation

import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import java.io.File

/**
 * Optional high-accuracy engine: NVIDIA Parakeet TDT 0.6B v3 (int8, CC-BY-4.0) run by
 * sherpa-onnx. Model files (~670 MB) are downloaded on demand (see [ModelManager]).
 *
 * RAM budget: the loaded recognizer holds ~1.5-2 GB. It stays loaded so repeated
 * dictations skip the ~2 s model load, and is released when the system runs low on
 * memory (forwarded from LatinIME's onTrimMemory) — the 5-minute idle timer only
 * arms on memory-constrained devices.
 *
 * The model auto-detects between its 25 European languages; no language is forced
 * (consistent with the Whistle path, where the language hint proved to be weak
 * conditioning only).
 */
object ParakeetEngine {
    private const val TAG = "ReventorDictation"
    private val lock = Any()

    private var recognizer: OfflineRecognizer? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val idleRelease = Runnable { releaseAfterUse() }

    /** False when the sherpa-onnx JNI libs are missing (non-arm64 build). */
    fun available(): Boolean = try {
        System.loadLibrary("sherpa-onnx-jni")
        true
    } catch (_: Throwable) {
        false
    }

    fun ready(): Boolean = synchronized(lock) { recognizer != null }

    /** Builds the recognizer. Takes seconds on first use; call while recording. */
    fun prepare(modelDir: File): Boolean = synchronized(lock) {
        if (recognizer != null) return true
        try {
            val started = android.os.SystemClock.elapsedRealtime()
            recognizer = OfflineRecognizer(
                assetManager = null,
                config = OfflineRecognizerConfig(
                    modelConfig = OfflineModelConfig(
                        transducer = OfflineTransducerModelConfig(
                            encoder = File(modelDir, "encoder.int8.onnx").absolutePath,
                            decoder = File(modelDir, "decoder.int8.onnx").absolutePath,
                            joiner = File(modelDir, "joiner.int8.onnx").absolutePath,
                        ),
                        tokens = File(modelDir, "tokens.txt").absolutePath,
                        numThreads = 4,
                        modelType = "nemo_transducer",
                        provider = "cpu",
                    ),
                ),
            )
            Log.i(TAG, "parakeet recognizer ready in ${android.os.SystemClock.elapsedRealtime() - started} ms")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "parakeet init failed", t)
            recognizer = null
            false
        }
    }

    /** Keeps the loaded recognizer alive: cancels any pending idle release. */
    fun keepAlive(): Unit = synchronized(lock) {
        if (recognizer != null) handler.removeCallbacks(idleRelease)
    }

    /**
     * Arms (or disarms) the idle release. A delayMs of 0 means "keep the model
     * loaded indefinitely" — memory-pressure trim still frees it.
     */
    fun scheduleIdleRelease(delayMs: Long): Unit = synchronized(lock) {
        handler.removeCallbacks(idleRelease)
        if (recognizer != null && delayMs > 0) {
            handler.postDelayed(idleRelease, delayMs)
        }
    }

    /** Decodes one clip (16 kHz mono); empty string on failure or unloaded engine. */
    fun transcribe(pcm: FloatArray): String = synchronized(lock) {
        val rec = recognizer ?: return ""
        try {
            val stream = rec.createStream()
            stream.acceptWaveform(pcm, sampleRate = 16000)
            rec.decode(stream)
            val text = rec.getResult(stream).text
            stream.release()
            text
        } catch (t: Throwable) {
            Log.w(TAG, "parakeet transcribe failed", t)
            ""
        }
    }

    /** Frees the ~1.5-2 GB the recognizer holds; the next prepare() rebuilds it. */
    fun releaseAfterUse(): Unit = synchronized(lock) {
        handler.removeCallbacks(idleRelease)
        val rec = recognizer
        recognizer = null
        try {
            rec?.release()
            if (rec != null) Log.i(TAG, "parakeet recognizer released")
        } catch (t: Throwable) {
            Log.w(TAG, "parakeet release failed", t)
        }
    }
}
