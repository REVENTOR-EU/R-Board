package app.reventor.dictation

import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper

/**
 * Offline dictation for the keyboard's voice key, two interaction styles:
 *
 *  - Layout shortcut key (sends press/release): push-to-talk — press-and-hold
 *    records, release transcribes and commits.
 *  - Toolbar mic (fires only a tap code event): tap-to-toggle — first tap starts
 *    recording, second tap stops and commits.
 *
 * The tap event arrives for both styles, so [onVoiceKeyTap] must consume the
 * gesture a press/release already handled instead of starting a second recording.
 * All slow work runs on a single executor (which also serializes engine access).
 *
 * Recognition runs on the Parakeet TDT 0.6B v3 model via sherpa-onnx; it
 * auto-detects between its 25 European languages and produces punctuated,
 * capitalized text natively.
 */
class DictationController(private val ime: InputMethodService) {

    companion object {
        private const val TAG = "ReventorDictation"
        private const val SAMPLE_RATE = 16_000
        private const val MAX_SAMPLES = SAMPLE_RATE * 30
    }

    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    private var recorder: AudioRecord? = null
    private var readerThread: Thread? = null

    @Volatile
    private var recording = false
    private val chunks = ArrayList<FloatArray>()
    private var totalSamples = 0

    // recording waveform inside the suggestion strip (covers the suggestions area)
    private var waveform: WaveformBarView? = null
    @Volatile private var themeBackground = 0xFF16191E.toInt()
    @Volatile private var themeLine = 0xFF3D5AFE.toInt()

    // gesture bookkeeping between press / release / tap events
    @Volatile
    private var gestureHandled = false // press started a dictation gesture (PTT)

    // timestamp of the last press/release belonging to a PTT gesture — the tap
    // event for that gesture may arrive BEFORE or AFTER the release (device-dependent)
    @Volatile
    private var lastGestureAt = 0L

    @Volatile
    private var pendingRelease = false

    @Volatile
    private var sessionActive = false

    /** Theme colors for the recording waveform (line color over the strip background). */
    fun setColors(line: Int, background: Int) {
        themeLine = line
        themeBackground = background
    }

    private fun showWaveform() {
        main.post {
            val content = ime.window?.findViewById(android.R.id.content) as? android.view.ViewGroup
            val stripId = ime.resources.getIdentifier("suggestions_strip", "id", ime.packageName)
            val strip = if (content != null && stripId != 0) content.findViewById<View>(stripId) as? android.view.ViewGroup else null
            val existing = waveform
            if (existing != null) {
                if (existing.parent === strip && strip != null) {
                    Log.d(TAG, "waveform: reusing existing view")
                    return@post
                }
                // strip was recreated — drop the stale view and anchor fresh
                Log.d(TAG, "waveform: stale anchor — re-anchoring")
                (existing.parent as? android.view.ViewGroup)?.removeView(existing)
                waveform = null
            }
            if (strip == null || strip.width == 0) {
                Log.w(TAG, "waveform: suggestion strip not ready (id=$stripId, strip=${strip != null}, width=${strip?.width}) — retrying")
                main.postDelayed({ showWaveform() }, 100)
                return@post
            }
            Log.d(TAG, "waveform anchored to suggestion strip ${strip.width}x${strip.height}")
            val bar = WaveformBarView(ime, themeBackground, themeLine)
            // explicit size: the strip is wrap_content, MATCH_PARENT would measure 0
            strip.addView(bar, android.widget.LinearLayout.LayoutParams(strip.width, strip.height))
            // the waveform takes over the strip: hide suggestions/pills while recording
            for (i in 0 until strip.childCount - 1) strip.getChildAt(i).visibility = View.GONE
            waveform = bar
        }
    }

    private fun hideWaveform() {
        main.post {
            waveform?.let { bar ->
                (bar.parent as? android.view.ViewGroup)?.let { strip ->
                    strip.removeView(bar)
                    for (i in 0 until strip.childCount) strip.getChildAt(i).visibility = View.VISIBLE
                }
            }
            waveform = null
        }
    }

    /** Key-down on the layout voice key: start push-to-talk. */
    fun onPressStart() {
        Log.d(TAG, "onPressStart: engine available=${ParakeetEngine.available()}")
        gestureHandled = false
        pendingRelease = false
        if (!ParakeetEngine.available()) return // no native lib (wrong ABI) → legacy voice IME
        gestureHandled = true
        lastGestureAt = android.os.SystemClock.uptimeMillis()
        if (!hasMicPermission()) {
            launchPermissionActivity()
            return
        }
        executor.execute { prepareAndStartRecording() }
    }

    /** Key-up on the layout voice key: stop and transcribe. */
    fun onPressEnd() {
        Log.d(TAG, "onPressEnd: gestureHandled=$gestureHandled sessionActive=$sessionActive")
        if (!gestureHandled) return
        gestureHandled = false
        lastGestureAt = android.os.SystemClock.uptimeMillis()
        pendingRelease = true
        if (sessionActive) finalizeOnExecutor()
    }

    /**
     * Tap-complete event for KeyCode.VOICE_INPUT (fires for toolbar mic AND layout key).
     * Returns true when dictation handled this gesture, so the legacy
     * switch-to-external-voice-IME path must be skipped.
     */
    fun onVoiceKeyTap(): Boolean {
        Log.d(TAG, "onVoiceKeyTap: gestureHandled=$gestureHandled sessionActive=$sessionActive recording=$recording")
        // this tap belongs to a press/release gesture (it can arrive before OR after
        // the release) — consume it so the legacy path stays out, and leave the
        // release event something to finalize
        if (gestureHandled || android.os.SystemClock.uptimeMillis() - lastGestureAt < 1500) {
            return true
        }
        if (!ParakeetEngine.available()) return false
        if (!hasMicPermission()) {
            launchPermissionActivity()
            return true
        }
        if (sessionActive || recording) {
            // second tap of toggle mode: stop and commit
            finalizeOnExecutor()
            return true
        }
        // first tap of toggle mode: start; keeps recording until the next tap
        executor.execute { prepareAndStartRecording() }
        return true
    }

    private fun prepareAndStartRecording() {
        if (!ModelManager.parakeetComplete(ime)) {
            if (ModelManager.startDownload(ime)) {
                toast("Downloading dictation engine (670 MB)…")
            } else {
                toast("Dictation engine is downloading… ${ModelManager.parakeetProgress.coerceAtLeast(0)}%")
            }
            return
        }
        startRecorder()
        // build the recognizer while the user is still speaking — init reads
        // 652 MB and takes seconds; the same executor then finalizes after it
        executor.execute { ParakeetEngine.prepare(ModelManager.parakeetModelDir(ime)) }
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(ime, android.Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun launchPermissionActivity() {
        val intent = Intent(ime, DictationPermissionActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ime.startActivity(intent)
    }

    private fun startRecorder() {
        if (recording) return
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT,
                maxOf(minBuf, SAMPLE_RATE * 4)
            )
        } catch (t: Throwable) {
            Log.w(TAG, "AudioRecord unavailable", t)
            return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return
        }
        synchronized(chunks) {
            chunks.clear()
            totalSamples = 0
        }
        recording = true
        sessionActive = true
        recorder = rec
        rec.startRecording()
        readerThread = Thread {
            val tmp = FloatArray(SAMPLE_RATE / 25) // 40 ms — amplitude granularity for the waveform
            while (recording && totalSamples < MAX_SAMPLES) {
                val n = rec.read(tmp, 0, tmp.size, AudioRecord.READ_BLOCKING)
                if (n > 0) {
                    synchronized(chunks) {
                        chunks.add(tmp.copyOf(n))
                        totalSamples += n
                    }
                    var sum = 0.0
                    for (i in 0 until n) {
                        val v = tmp[i].toDouble()
                        sum += v * v
                    }
                    val rms = kotlin.math.sqrt(sum / n)
                    waveform?.pushAmplitude(rms.toFloat()) // thread-safe (postInvalidateOnAnimation)
                }
            }
            // hit the 30 s cap with nobody pressing stop (user walked away) — auto-finalize
            if (totalSamples >= MAX_SAMPLES && sessionActive) {
                finalizeOnExecutor()
            }
        }.also { it.start() }
        showWaveform()
        if (pendingRelease) finalizeOnExecutor()
    }

    private fun finalizeOnExecutor() {
        sessionActive = false
        hideWaveform()
        executor.execute {
            val raw = stopRecorder() ?: return@execute
            // front-end: cut the key-press transient, trim silence, normalize level
            val pcm = cleanAudio(raw) ?: run {
                main.post { toast("Nothing heard") }
                return@execute
            }
            val text = ParakeetEngine.transcribe(pcm).trim()
            // the recognizer holds ~1.5-2 GB — always freed after one clip
            ParakeetEngine.releaseAfterUse()
            if (text.isEmpty()) {
                main.post { toast("Nothing heard") }
                return@execute
            }
            // the model already punctuates and capitalizes; this only guarantees a
            // uniform sentence ending and a trailing space, so consecutive recordings
            // read as separate sentences
            var out = text
            if (out[0].isLowerCase()) out = out[0].uppercaseChar() + out.substring(1)
            if (out.last() !in ".!?…") out += "."
            out += " "
            main.post {
                ime.currentInputConnection?.commitText(out, 1)
            }
        }
    }

    /**
     * Prepares raw mic PCM for the engine: drops the first 150 ms (the key-press /
     * finger transient would survive energy-based trimming and is often decoded as a
     * word), trims leading and trailing silence, and raises quiet clips to a sane
     * level. Returns null when no speech energy remains.
     */
    private fun cleanAudio(raw: FloatArray): FloatArray? {
        val transientEnd = minOf(raw.size, SAMPLE_RATE * 3 / 20) // 150 ms
        val pcm = if (transientEnd < raw.size) raw.copyOfRange(transientEnd, raw.size) else return null
        val frame = SAMPLE_RATE / 50 // 20 ms
        val frames = pcm.size / frame
        if (frames < 3) return null
        val rms = FloatArray(frames) { f ->
            var sum = 0.0
            val off = f * frame
            for (i in off until off + frame) {
                val v = pcm[i].toDouble()
                sum += v * v
            }
            kotlin.math.sqrt(sum / frame).toFloat()
        }
        var peakRms = 0f
        for (r in rms) if (r > peakRms) peakRms = r
        val threshold = maxOf(0.0025f, peakRms * 0.08f)
        var first = -1
        var last = -1
        for (i in 0 until frames) {
            if (rms[i] > threshold) {
                if (first < 0) first = i
                last = i
            }
        }
        if (first < 0) return null
        val pad = 6 // 120 ms margin on both sides
        val from = maxOf(0, (first - pad) * frame)
        val to = minOf(pcm.size, (last + 1 + pad) * frame)
        if (to - from < SAMPLE_RATE / 10) return null // < 100 ms of speech
        val trimmed = pcm.copyOfRange(from, to)
        var peak = 0f
        for (v in trimmed) if (kotlin.math.abs(v) > peak) peak = kotlin.math.abs(v)
        if (peak > 0.0001f && peak < 0.35f) {
            val gain = minOf(0.85f / peak, 8f) // cap the gain so the noise floor is not amplified into speech
            for (i in trimmed.indices) trimmed[i] *= gain
        }
        return trimmed
    }

    private fun stopRecorder(): FloatArray? {
        recording = false
        readerThread?.let { t ->
            try {
                t.join(1500)
            } catch (_: InterruptedException) {
            }
        }
        readerThread = null
        val rec = recorder
        recorder = null
        if (rec == null) return null
        try {
            rec.stop()
        } catch (_: Throwable) {
        }
        rec.release()
        val pcm = synchronized(chunks) {
            val all = FloatArray(totalSamples)
            var off = 0
            for (c in chunks) {
                System.arraycopy(c, 0, all, off, c.size)
                off += c.size
            }
            chunks.clear()
            all
        }
        return if (pcm.isEmpty()) null else pcm
    }

    private fun toast(message: String) {
        main.post {
            try {
                Toast.makeText(ime, message, Toast.LENGTH_SHORT).show()
            } catch (_: Throwable) {
            }
        }
    }
}
