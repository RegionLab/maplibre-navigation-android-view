# sherpa-onnx calls these classes and fields from native code by name
-keep class com.k2fsa.sherpa.onnx.** { *; }

# Called from native code by name and signature, see GenerationCallback
-keep class org.maplibre.navigation.android.tts.sherpa.GenerationCallback {
    java.lang.Integer invoke(float[]);
}
