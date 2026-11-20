package org.maplibre.navigation.android.tts.sherpa

/**
 * A downloadable sherpa-onnx voice: a `.tar.bz2` archive with one folder containing a VITS
 * `*.onnx` model, `tokens.txt` and `espeak-ng-data`, like the
 * [sherpa-onnx TTS models](https://github.com/k2-fsa/sherpa-onnx/releases/tag/tts-models).
 *
 * @param id unique name, also the folder the voice is installed to
 * @param language language code the voice speaks, e.g. "ru", matched against the language of the
 * voice instructions
 * @param archiveUrl where to download the voice from, e.g. your own CDN in production
 * @param sizeBytes approximate download size, e.g. to ask the user before downloading
 * @param lengthScale speech tempo, larger is slower (1.0 is the speed the voice was trained with)
 * @param noiseScale intonation variation, smaller sounds flatter
 * @param noiseScaleW variation of sound durations, smaller gives a steadier rhythm
 */
data class SherpaVoice @JvmOverloads constructor(
    val id: String,
    val language: String,
    val archiveUrl: String,
    val sizeBytes: Long,
    val lengthScale: Float = DEFAULT_LENGTH_SCALE,
    val noiseScale: Float = DEFAULT_NOISE_SCALE,
    val noiseScaleW: Float = DEFAULT_NOISE_SCALE_W,
) {
    companion object {

        // Piper defaults
        const val DEFAULT_LENGTH_SCALE = 1.0f
        const val DEFAULT_NOISE_SCALE = 0.667f
        const val DEFAULT_NOISE_SCALE_W = 0.8f

        private const val SHERPA_TTS_MODELS_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/"
        private const val MB = 1024L * 1024L

        // int8 quantized Piper voices, 2–4 times smaller than the original models

        /** Russian, female, ~20 MB. */
        @JvmField
        val RUSSIAN_IRINA = piper("vits-piper-ru_RU-irina-medium-int8", "ru", 20 * MB)

        /** Russian, male, ~20 MB. */
        @JvmField
        val RUSSIAN_DMITRI = piper("vits-piper-ru_RU-dmitri-medium-int8", "ru", 20 * MB)

        /**
         * Kazakh, high quality, ~34 MB. Synthesized slower than spoken on phones, instructions
         * start 5–7 s late, so it isn't the default.
         */
        @JvmField
        val KAZAKH_ISSAI = piper("vits-piper-kk_KZ-issai-high-int8", "kk", 34 * MB)

        /**
         * Kazakh, ~13 MB. Simpler sound than [KAZAKH_ISSAI], but synthesized about 10 times
         * faster: instructions start after ~0.5 s instead of 5–7 s on a current phone.
         */
        @JvmField
        val KAZAKH_RAYA = piper("vits-piper-kk_KZ-raya-x_low-int8", "kk", 13 * MB)

        /** English (US), ~20 MB. */
        @JvmField
        val ENGLISH_AMY = piper("vits-piper-en_US-amy-low-int8", "en", 20 * MB)

        /** One voice per language. There is no Uzbek voice yet, it falls back to Android TTS. */
        @JvmField
        val DEFAULTS: List<SherpaVoice> = listOf(RUSSIAN_IRINA, KAZAKH_RAYA, ENGLISH_AMY)

        @JvmStatic
        fun piper(id: String, language: String, sizeBytes: Long) =
            SherpaVoice(id, language, "$SHERPA_TTS_MODELS_URL$id.tar.bz2", sizeBytes)
    }
}

/**
 * Files of an installed voice, passed to sherpa-onnx.
 */
data class InstalledVoice(
    val voice: SherpaVoice,
    val model: java.io.File,
    val tokens: java.io.File,
    val espeakDataDir: java.io.File,
)
