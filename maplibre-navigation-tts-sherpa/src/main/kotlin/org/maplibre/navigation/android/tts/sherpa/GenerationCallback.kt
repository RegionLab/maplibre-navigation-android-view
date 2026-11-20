package org.maplibre.navigation.android.tts.sherpa

/**
 * Receives generated audio from sherpa-onnx.
 *
 * sherpa-onnx calls `invoke(float[]): Integer` from native code by name and signature. A Kotlin
 * lambda compiles to a synthetic class without that method and crashes the app with
 * `NoSuchMethodError` inside `generateWithCallback`, so this has to be a real class.
 * The method is kept by consumer-rules.pro.
 *
 * @param onSamples writes the samples, returns false to stop the generation
 */
internal class GenerationCallback(
    private val onSamples: (FloatArray) -> Boolean,
) : (FloatArray) -> Int {

    override fun invoke(samples: FloatArray): Int = if (onSamples(samples)) CONTINUE else STOP

    private companion object {
        const val CONTINUE = 1
        const val STOP = 0
    }
}
