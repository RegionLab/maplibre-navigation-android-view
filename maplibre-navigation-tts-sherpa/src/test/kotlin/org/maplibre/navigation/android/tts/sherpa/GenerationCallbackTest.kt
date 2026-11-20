package org.maplibre.navigation.android.tts.sherpa

import org.junit.Assert.assertEquals
import org.junit.Test

class GenerationCallbackTest {

    @Test
    fun hasTheMethodSherpaOnnxCallsFromNativeCode() {
        // JNI looks up invoke with the signature ([F)Ljava/lang/Integer; a lambda doesn't have it
        val method = GenerationCallback::class.java.getMethod("invoke", FloatArray::class.java)

        assertEquals(Integer::class.java, method.returnType)
    }

    @Test
    fun returnsWhetherToContinue() {
        assertEquals(1, GenerationCallback { true }.invoke(FloatArray(1)))
        assertEquals(0, GenerationCallback { false }.invoke(FloatArray(1)))
    }
}
