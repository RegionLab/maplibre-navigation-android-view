package org.maplibre.navigation.android.navigation.ui.v5.voice

/**
 * Creates the [SpeechPlayer] for a navigation session, e.g. to use an own text-to-speech engine
 * instead of the Android [android.speech.tts.TextToSpeech] used by default.
 *
 * Set it with `NavigationView.setSpeechPlayerFactory`. A player is created when navigation starts
 * and released with [SpeechPlayer.onDestroy] when it stops.
 */
fun interface SpeechPlayerFactory {

    /**
     * @param language language of the voice instructions, e.g. "ru" or "kk"
     */
    fun create(language: String): SpeechPlayer
}
