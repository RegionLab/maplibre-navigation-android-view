package org.maplibre.navigation.android.tts.sherpa

import android.os.Looper
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class SherpaVoiceStoreTest {

    private val context = RuntimeEnvironment.getApplication()
    private val voice = SherpaVoice.piper("vits-piper-kk_KZ-test", "kk", 1000)

    @Test
    fun voiceFor_matchesLanguageCode() {
        val store = SherpaVoiceStore(context)

        assertEquals("ru", store.voiceFor("ru")?.language)
        assertEquals("kk", store.voiceFor("kk-KZ")?.language)
        assertEquals("en", store.voiceFor("en_US")?.language)
        // No Uzbek voice, Android TTS speaks Uzbek
        assertNull(store.voiceFor("uz"))
    }

    @Test
    fun download_installsVoice() {
        val store = storeServing(voiceArchive())
        assertFalse(store.isInstalled(voice))

        val result = download(store)

        val installed = result.installed!!
        assertTrue(installed.model.name.endsWith(".onnx"))
        assertTrue(installed.tokens.isFile)
        assertTrue(installed.espeakDataDir.isDirectory)
        assertNotNull(store.installedVoice("kk"))
    }

    @Test
    fun download_httpError_installsNothing() {
        val store = storeServing(ByteArray(0), code = 404)

        val result = download(store)

        assertTrue(result.error is IOException)
        assertFalse(store.isInstalled(voice))
    }

    @Test
    fun download_archiveWithoutVoice_installsNothing() {
        val store = storeServing(tarBz2(mapOf("readme.txt" to "no voice")))

        val result = download(store)

        assertNotNull(result.error)
        assertFalse(store.isInstalled(voice))
    }

    @Test
    fun extract_rejectsEntriesOutsideTargetFolder() {
        val target = File(context.cacheDir, "extract-test").apply { deleteRecursively(); mkdirs() }

        val error = runCatching {
            VoiceArchiveExtractor.extractTarBz2(tarBz2(mapOf("../evil.txt" to "x")).inputStream(), target)
        }.exceptionOrNull()

        assertTrue(error is IOException)
        assertFalse(File(context.cacheDir, "evil.txt").exists())
    }

    private class Result(val installed: InstalledVoice?, val error: Throwable?)

    private fun download(store: SherpaVoiceStore): Result {
        var installed: InstalledVoice? = null
        var error: Throwable? = null
        store.download(voice, object : SherpaVoiceStore.DownloadListener {
            override fun onInstalled(voice: InstalledVoice) {
                installed = voice
            }

            override fun onError(voice: SherpaVoice, error: Throwable) {
                failure = error
            }
        })
        val deadline = System.currentTimeMillis() + 10_000
        while (installed == null && failure == null && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        error = failure
        return Result(installed, error)
    }

    private var failure: Throwable? = null

    private fun storeServing(body: ByteArray, code: Int = 200): SherpaVoiceStore {
        File(context.filesDir, "sherpa-voices").deleteRecursively()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("test")
                .body(body.toResponseBody("application/x-bzip2".toMediaType()))
                .build()
        }.build()
        return SherpaVoiceStore(context, listOf(voice), client)
    }

    private fun voiceArchive() = tarBz2(
        mapOf(
            "${voice.id}/tokens.txt" to "a 1",
            "${voice.id}/kk_KZ-test.onnx" to "model",
            "${voice.id}/espeak-ng-data/phontab" to "data",
        )
    )

    private fun tarBz2(files: Map<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        TarArchiveOutputStream(BZip2CompressorOutputStream(bytes)).use { tar ->
            files.forEach { (name, content) ->
                val data = content.toByteArray()
                tar.putArchiveEntry(TarArchiveEntry(name).apply { size = data.size.toLong() })
                tar.write(data)
                tar.closeArchiveEntry()
            }
        }
        return bytes.toByteArray()
    }
}
