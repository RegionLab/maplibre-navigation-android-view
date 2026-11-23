package org.maplibre.navigation.android.tts.sherpa

import android.content.Context
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Downloads and stores sherpa-onnx voices in the app's files folder. Nothing is downloaded
 * automatically: call [download] when the app decides, e.g. behind a feature flag, after the user
 * enabled voice guidance or when a language is selected.
 *
 * @param voices voices available for download, one per language is used
 */
class SherpaVoiceStore @JvmOverloads constructor(
    context: Context,
    private val voices: List<SherpaVoice> = SherpaVoice.DEFAULTS,
    private val httpClient: OkHttpClient = OkHttpClient(),
) {

    private val appContext = context.applicationContext
    private val rootDir = File(appContext.filesDir, VOICES_DIR)
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "sherpa-voice-download").apply { priority = Thread.MIN_PRIORITY }
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * The voice for [language] (e.g. "ru", "kk-KZ"), installed or not, or null if there is none.
     */
    fun voiceFor(language: String): SherpaVoice? {
        val code = language.substringBefore('-').substringBefore('_').lowercase()
        return voices.firstOrNull { it.language.equals(code, ignoreCase = true) }
    }

    /**
     * The installed voice for [language], or null if it isn't downloaded.
     */
    fun installedVoice(language: String): InstalledVoice? = voiceFor(language)?.let(::installed)

    fun installed(voice: SherpaVoice): InstalledVoice? =
        VoiceArchiveExtractor.findVoiceFiles(voice, File(rootDir, voice.id))

    fun isInstalled(voice: SherpaVoice): Boolean = installed(voice) != null

    /**
     * Downloads and installs [voice] in the background. Callbacks are called on the main thread.
     *
     * @param wifiOnly don't download over metered networks, the listener gets an error instead
     */
    @JvmOverloads
    fun download(voice: SherpaVoice, listener: DownloadListener, wifiOnly: Boolean = false): Cancellable {
        val cancelled = AtomicBoolean(false)
        if (wifiOnly && isMeteredNetwork()) {
            mainHandler.post { listener.onError(voice, IOException("Not downloading over a metered network")) }
            return Cancellable { cancelled.set(true) }
        }
        executor.execute {
            if (cancelled.get()) {
                return@execute
            }
            val result = runCatching { downloadAndInstall(voice, listener, cancelled) }
            mainHandler.post {
                if (cancelled.get()) {
                    return@post
                }
                result.fold(
                    onSuccess = { listener.onInstalled(it) },
                    onFailure = { listener.onError(voice, it) },
                )
            }
        }
        return Cancellable { cancelled.set(true) }
    }

    /**
     * Removes an installed voice, e.g. to free space or when the language isn't used anymore.
     */
    fun delete(voice: SherpaVoice) {
        executor.execute { File(rootDir, voice.id).deleteRecursively() }
    }

    private fun downloadAndInstall(
        voice: SherpaVoice,
        listener: DownloadListener,
        cancelled: AtomicBoolean,
    ): InstalledVoice {
        installed(voice)?.let { return it }
        val tempDir = File(rootDir, ".download-${voice.id}").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            val request = Request.Builder().url(voice.archiveUrl).build()
            httpClient.newCall(request).execute().use { response ->
                val body = response.body
                if (!response.isSuccessful || body == null) {
                    throw IOException("Voice download failed with HTTP ${response.code}")
                }
                val total = body.contentLength().takeIf { it > 0 } ?: voice.sizeBytes
                val progressInput = ProgressInputStream(body.byteStream(), cancelled) { read ->
                    mainHandler.post { listener.onProgress(voice, read, total) }
                }
                VoiceArchiveExtractor.extractTarBz2(progressInput, tempDir)
            }
            if (cancelled.get()) {
                throw IOException("Voice download cancelled")
            }
            checkNotNull(VoiceArchiveExtractor.findVoiceFiles(voice, tempDir)) {
                "The archive of ${voice.id} contains no voice"
            }
            // Atomic install: a half downloaded voice is never used
            val voiceDir = File(rootDir, voice.id)
            voiceDir.deleteRecursively()
            if (!tempDir.renameTo(voiceDir)) {
                throw IOException("Could not install ${voice.id}")
            }
            return checkNotNull(installed(voice))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun isMeteredNetwork(): Boolean {
        val connectivity = appContext.getSystemService(ConnectivityManager::class.java) ?: return false
        return connectivity.isActiveNetworkMetered
    }

    interface DownloadListener {
        fun onProgress(voice: SherpaVoice, downloadedBytes: Long, totalBytes: Long) {}
        fun onInstalled(voice: InstalledVoice)
        fun onError(voice: SherpaVoice, error: Throwable)
    }

    fun interface Cancellable {
        fun cancel()
    }

    /** Reports the downloaded bytes about every 256 KB and stops reading when cancelled. */
    private class ProgressInputStream(
        input: InputStream,
        private val cancelled: AtomicBoolean,
        private val onProgress: (Long) -> Unit,
    ) : FilterInputStream(input) {
        private var read = 0L
        private var reported = 0L

        override fun read(): Int = super.read().also { if (it >= 0) count(1) }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            super.read(buffer, offset, length).also { if (it > 0) count(it) }

        private fun count(bytes: Int) {
            if (cancelled.get()) {
                throw IOException("Voice download cancelled")
            }
            read += bytes
            if (read - reported >= PROGRESS_STEP_BYTES) {
                reported = read
                onProgress(read)
            }
        }
    }

    private companion object {
        const val VOICES_DIR = "sherpa-voices"
        const val PROGRESS_STEP_BYTES = 256 * 1024L
    }
}
