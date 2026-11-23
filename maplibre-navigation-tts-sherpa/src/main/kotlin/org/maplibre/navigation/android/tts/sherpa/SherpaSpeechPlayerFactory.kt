package org.maplibre.navigation.android.tts.sherpa

import android.content.Context
import org.maplibre.navigation.android.navigation.ui.v5.voice.NavigationSpeechPlayer
import org.maplibre.navigation.android.navigation.ui.v5.voice.SpeechPlayer
import org.maplibre.navigation.android.navigation.ui.v5.voice.SpeechPlayerFactory
import org.maplibre.navigation.android.navigation.ui.v5.voice.SpeechPlayerProvider

/**
 * Speaks voice instructions with the installed sherpa-onnx voice of their language, set it with
 * `NavigationView.setSpeechPlayerFactory`. Languages without a downloaded voice, and voices that
 * fail to load, use the Android text-to-speech.
 */
class SherpaSpeechPlayerFactory @JvmOverloads constructor(
    context: Context,
    private val voiceStore: SherpaVoiceStore,
    private val speed: Float = SherpaSpeechPlayer.DEFAULT_SPEED,
) : SpeechPlayerFactory {

    private val appContext = context.applicationContext

    override fun create(language: String): SpeechPlayer {
        val androidTextToSpeech = {
            NavigationSpeechPlayer(SpeechPlayerProvider(appContext, language, true))
        }
        val voice = voiceStore.installedVoice(language) ?: return androidTextToSpeech()
        return SherpaSpeechPlayer(appContext, voice, speed, fallback = androidTextToSpeech)
    }
}
