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
 * RAM budget: the loaded recognizer holds ~1.5-2 GB — far too much for an IME process
 * to keep around. So the recognizer is BUILT while the user is still holding the voice
 * key (init reads the whole 652 MB encoder, seconds), the clip is decoded on release,
 * and the recognizer is destroyed right after ([releaseAfterUse]).
 *
 * The model auto-detects between its 25 European languages; no language is forced
 * (consistent with the Whistle path, where the language hint proved to be weak
 * conditioning only).
 */
object ParakeetEngine {
    private const val TAG = "ReventorDictation"
    private val lock = Any()

    private var recognizer: OfflineRecognizer? = null

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
    fun releaseAfterUse() = synchronized(lock) {
        val rec = recognizer
        recognizer = null
        try {
            rec?.release()
        } catch (t: Throwable) {
            Log.w(TAG, "parakeet release failed", t)
        }
    }
}
