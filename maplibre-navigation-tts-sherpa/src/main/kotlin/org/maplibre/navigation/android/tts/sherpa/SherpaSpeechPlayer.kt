package org.maplibre.navigation.android.tts.sherpa

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import org.maplibre.navigation.android.navigation.ui.v5.voice.SpeechAnnouncement
import org.maplibre.navigation.android.navigation.ui.v5.voice.SpeechPlayer
import timber.log.Timber
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Speaks voice instructions offline with a sherpa-onnx voice.
 *
 * The model is loaded and speech is synthesized on a background thread, audio is streamed while
 * it is generated. Instructions are spoken one after another, going off route, muting and
 * [onDestroy] drop the pending ones. If the voice can't be loaded, [fallback] speaks instead.
 *
 * @param speed speech rate, 1.0 is normal
 * @param fallback creates the player used when the voice fails, e.g. the Android text-to-speech
 */
class SherpaSpeechPlayer @JvmOverloads constructor(
    context: Context,
    private val voice: InstalledVoice,
    private val speed: Float = DEFAULT_SPEED,
    private val numThreads: Int = DEFAULT_NUM_THREADS,
    fallback: (() -> SpeechPlayer)? = null,
) : SpeechPlayer {

    private val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)
    private val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "sherpa-tts") }
    private val fallbackFactory = fallback
    private var createdFallback: SpeechPlayer? = null

    /** Created on first use, the Android text-to-speech binds a service when created. */
    private val fallbackPlayer: SpeechPlayer?
        @Synchronized get() = createdFallback
            ?: fallbackFactory?.invoke()?.also {
                it.isMuted = muted
                createdFallback = it
            }

    /** Increased to drop queued and running announcements. */
    private val generation = AtomicInteger()

    @Volatile
    private var muted = false

    @Volatile
    private var tts: OfflineTts? = null

    @Volatile
    private var isVoiceBroken = false

    // Written on the executor thread, paused from any thread to stop speaking right away
    @Volatile
    private var audioTrack: AudioTrack? = null
    private var focusRequest: AudioFocusRequest? = null

    init {
        executor.execute { loadVoice() }
    }

    override fun play(speechAnnouncement: SpeechAnnouncement) {
        if (muted) {
            return
        }
        if (isVoiceBroken) {
            fallbackPlayer?.play(speechAnnouncement)
            return
        }
        val text = speechAnnouncement.announcement()?.takeIf { it.isNotBlank() } ?: return
        val playGeneration = generation.get()
        executor.execute {
            if (playGeneration != generation.get()) {
                return@execute
            }
            val engine = tts
            if (engine == null) {
                fallbackPlayer?.play(speechAnnouncement)
                return@execute
            }
            if (!speak(engine, text, playGeneration)) {
                // The voice can't speak this text (e.g. unsupported characters), don't stay silent
                Timber.w("sherpa-onnx voice %s produced no audio, using the fallback", voice.voice.id)
                isVoiceBroken = true
                fallbackPlayer?.play(speechAnnouncement)
            }
        }
    }

    override fun isMuted(): Boolean = muted

    override fun setMuted(isMuted: Boolean) {
        muted = isMuted
        createdFallback?.isMuted = isMuted
        if (isMuted) {
            stopSpeaking()
        }
    }

    override fun onOffRoute() {
        stopSpeaking()
        createdFallback?.onOffRoute()
    }

    override fun onDestroy() {
        stopSpeaking()
        executor.execute {
            audioTrack?.release()
            audioTrack = null
            tts?.release()
            tts = null
        }
        executor.shutdown()
        createdFallback?.onDestroy()
    }

    private fun stopSpeaking() {
        generation.incrementAndGet()
        // Stops the track right away, the synthesis notices the new generation and ends.
        // The track may be released concurrently, that's fine.
        audioTrack?.let { track -> runCatching { track.pause(); track.flush() } }
    }

    private fun loadVoice() {
        try {
            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = voice.model.absolutePath,
                        tokens = voice.tokens.absolutePath,
                        dataDir = voice.espeakDataDir.absolutePath,
                        noiseScale = voice.voice.noiseScale,
                        noiseScaleW = voice.voice.noiseScaleW,
                        lengthScale = voice.voice.lengthScale,
                    ),
                    numThreads = numThreads,
                    debug = false,
                ),
            )
            tts = OfflineTts(assetManager = null, config = config)
        } catch (error: Throwable) {
            // UnsatisfiedLinkError on unsupported ABIs, errors from broken model files
            Timber.e(error, "Loading sherpa-onnx voice %s failed, using the fallback", voice.voice.id)
            isVoiceBroken = true
        }
    }

    /**
     * @return false if the voice produced no audio
     */
    private fun speak(engine: OfflineTts, text: String, playGeneration: Int): Boolean {
        val sampleRate = engine.sampleRate()
        // A new track per announcement: a reused track keeps its playback position after flush()
        val track = createAudioTrack(sampleRate)
        audioTrack = track
        requestAudioFocus()
        var writtenFrames = 0
        try {
            val callback = GenerationCallback { samples ->
                if (playGeneration != generation.get()) {
                    return@GenerationCallback false
                }
                var offset = 0
                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    // Fill the buffer before starting, an empty buffer underruns and clicks.
                    // Blocking writes before play() would wait forever.
                    offset = track.write(samples, 0, samples.size, AudioTrack.WRITE_NON_BLOCKING).coerceAtLeast(0)
                    track.play()
                }
                if (offset < samples.size) {
                    track.write(samples, offset, samples.size - offset, AudioTrack.WRITE_BLOCKING)
                }
                writtenFrames += samples.size
                true
            }
            // The speed argument overrides the length scale of the model config, apply both here
            val effectiveSpeed = speed / voice.voice.lengthScale
            engine.generateWithCallback(text = text, sid = 0, speed = effectiveSpeed, callback = callback)
            waitUntilPlayed(track, writtenFrames, sampleRate, playGeneration)
        } catch (error: Exception) {
            Timber.e(error, "Speaking with sherpa-onnx failed")
        } finally {
            runCatching { track.stop() }
            track.release()
            if (audioTrack === track) {
                audioTrack = null
            }
            abandonAudioFocus()
        }
        // A stopped announcement isn't a failure
        return writtenFrames > 0 || playGeneration != generation.get()
    }

    /**
     * @return true if everything was played, false if interrupted or timed out
     */
    private fun waitUntilPlayed(track: AudioTrack, frames: Int, sampleRate: Int, playGeneration: Int): Boolean {
        val timeoutMs = frames * 1000L / sampleRate + PLAYBACK_TIMEOUT_MARGIN_MS
        val start = System.nanoTime()
        while (track.playbackHeadPosition < frames) {
            if (playGeneration != generation.get() ||
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) >= timeoutMs
            ) {
                return false
            }
            Thread.sleep(PLAYBACK_POLL_MS)
        }
        return true
    }

    private fun createAudioTrack(sampleRate: Int): AudioTrack {
        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT
        )
        // Half a second of audio bridges the pauses while the next sentence is generated
        val bufferSize = maxOf(minBufferSize, sampleRate * BYTES_PER_FLOAT_SAMPLE / 2)
        return AudioTrack.Builder()
            .setAudioAttributes(audioAttributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private fun requestAudioFocus() {
        val manager = audioManager ?: return
        // Lowers music instead of stopping it, like other navigation apps
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(audioAttributes)
                .build()
            focusRequest = request
            manager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            manager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        }
    }

    private fun abandonAudioFocus() {
        val manager = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let(manager::abandonAudioFocusRequest)
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            manager.abandonAudioFocus(null)
        }
    }

    companion object {
        const val DEFAULT_SPEED = 1.0f
        /** Up to 4 cores, synthesis has to be faster than speaking to not lag behind. */
        @JvmField
        val DEFAULT_NUM_THREADS = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
        private const val PLAYBACK_POLL_MS = 20L
        private const val BYTES_PER_FLOAT_SAMPLE = 4
        private const val PLAYBACK_TIMEOUT_MARGIN_MS = 1_000L

        private val audioAttributes: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }
}
