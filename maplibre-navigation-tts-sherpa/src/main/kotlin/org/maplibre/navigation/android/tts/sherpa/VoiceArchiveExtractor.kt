package org.maplibre.navigation.android.tts.sherpa

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Extracts `.tar.bz2` voice archives. Entries that would be written outside the target folder
 * (e.g. `../`) are rejected, the archives come from the network.
 */
internal object VoiceArchiveExtractor {

    fun extractTarBz2(input: InputStream, targetDir: File) {
        val canonicalTarget = targetDir.canonicalFile
        TarArchiveInputStream(BZip2CompressorInputStream(input.buffered())).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                val file = File(canonicalTarget, entry.name).canonicalFile
                if (!file.path.startsWith(canonicalTarget.path + File.separator)) {
                    throw IOException("Archive entry outside of the target folder: ${entry.name}")
                }
                when {
                    entry.isDirectory -> file.mkdirs()
                    entry.isFile -> {
                        file.parentFile?.mkdirs()
                        file.outputStream().use { tar.copyTo(it) }
                    }
                    // Links and special files are not needed by voices
                    else -> Unit
                }
            }
        }
    }

    /**
     * Finds the voice files in an extracted voice folder, or null if something is missing.
     */
    fun findVoiceFiles(voice: SherpaVoice, voiceDir: File): InstalledVoice? {
        val root = voiceDir.listFiles()?.singleOrNull { it.isDirectory && File(it, "tokens.txt").isFile }
            ?: voiceDir.takeIf { File(it, "tokens.txt").isFile }
            ?: return null
        val model = root.listFiles { file -> file.isFile && file.name.endsWith(".onnx") }?.firstOrNull()
            ?: return null
        val espeakData = File(root, "espeak-ng-data").takeIf { it.isDirectory } ?: return null
        return InstalledVoice(voice, model, File(root, "tokens.txt"), espeakData)
    }
}
