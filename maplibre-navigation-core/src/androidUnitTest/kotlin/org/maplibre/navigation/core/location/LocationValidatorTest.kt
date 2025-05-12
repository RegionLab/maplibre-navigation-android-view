package org.maplibre.navigation.core.location

import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class LocationValidatorTest {

    @Test
    fun isValidUpdate_trueOnFirstUpdate() {
        val location = buildLocationWithAccuracy(10f)
        val accuracyThreshold = 100
        val validator = LocationValidator(accuracyThreshold)

        val isValid = validator.isValidUpdate(location)

        assertTrue(isValid)
    }

    @Test
    fun isValidUpdate_trueWhenUnder100MeterAccuracyThreshold() {
        val location = buildLocationWithAccuracy(90f)
        val validator = buildValidatorWithUpdate()

        val isValid = validator.isValidUpdate(location)

        assertTrue(isValid)
    }

    @Test
    fun isValidUpdate_falseWhenOver100MeterAccuracyThreshold() {
        val location = buildLocationWithAccuracy(110f)
        val validator = buildValidatorWithUpdate()

        val isValid = validator.isValidUpdate(location)

        assertFalse(isValid)
    }

    private fun buildValidatorWithUpdate(): LocationValidator {
        val location = buildLocationWithAccuracy(10f)
        val accuracyThreshold = 100
        val validator = LocationValidator(accuracyThreshold)
        validator.isValidUpdate(location)
        return validator
    }

    private fun buildLocationWithAccuracy(accuracyValue: Float): Location {
        val location = mockk<Location> {
            every { accuracyMeters } returns accuracyValue
        }
        return location
    }
}