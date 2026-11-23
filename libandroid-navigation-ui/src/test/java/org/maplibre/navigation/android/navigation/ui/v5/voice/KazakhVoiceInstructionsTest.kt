package org.maplibre.navigation.android.navigation.ui.v5.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class KazakhVoiceInstructionsTest {

    private fun rewrite(text: String) = KazakhVoiceInstructions.rewrite(text)

    // region Instructions received from GraphHopper on the device

    @Test
    fun turnOntoStreet() {
        assertEquals(
            "Байтұрсынұлы көшесіне солға бұрылыңыз",
            rewrite("солға бұрылыңыз onto Байтұрсынұлы көшесі")
        )
    }

    @Test
    fun turnInDistanceOntoStreet() {
        assertEquals(
            "200 метрден кейін Политехникалық көшесіне оңға бұрылыңыз",
            rewrite("200 метр жерде оңға бұрылыңыз onto Политехникалық көшесі")
        )
    }

    @Test
    fun turnThenKeep() {
        assertEquals(
            "Тимирязев көшесіне оңға бұрылыңыз, содан кейін Тимирязев көшесіне қарай оң жақпен жүріңіз",
            rewrite("оңға бұрылыңыз onto Тимирязев көшесі, одан соң оң жаққа шығыңыз onto Тимирязев көшесі")
        )
    }

    // endregion

    @Test
    fun turnsWithoutStreet() {
        assertEquals("Оңға бұрылыңыз", rewrite("оңға бұрылыңыз"))
        assertEquals("Сәл солға бұрылыңыз", rewrite("аздап солға бұрылыңыз"))
        assertEquals("Күрт оңға бұрылыңыз", rewrite("Қаттырақ оңға бұрылыңыз"))
        assertEquals("Кері бұрылыңыз", rewrite("артқа бұрылыңыз"))
        assertEquals("Тура жүріңіз", rewrite("Жалғастырыңыз"))
        assertEquals("Сол жақпен жүріңіз", rewrite("сол жаққа шығыңыз"))
    }

    @Test
    fun distances() {
        assertEquals("1 километрден кейін оңға бұрылыңыз", rewrite("1 км жерде оңға бұрылыңыз"))
        assertEquals("1.5 километрден кейін солға бұрылыңыз", rewrite("1.5 км жерде солға бұрылыңыз"))
        assertEquals("50 метрден кейін кері бұрылыңыз", rewrite("50 метр жерде артқа бұрылыңыз"))
    }

    @Test
    fun continueAlongStreet() {
        assertEquals("Абай даңғылы бойымен тура жүріңіз", rewrite("Абай даңғылы бойынша жалғастырыңыз"))
    }

    @Test
    fun roundabouts() {
        assertEquals("Айналма жолға кіріңіз", rewrite("айналма жолға өтіңіз"))
        assertEquals("Айналма жолдан 2-ші шығысқа шығыңыз", rewrite("2-ші бұрылыстан бұрылыңыз"))
        assertEquals(
            "Айналма жолдан 3-ші шығыспен Сейфуллин даңғылына шығыңыз",
            rewrite("Айналымда 3-ден Сейфуллин даңғылы-ге")
        )
        assertEquals(
            "Айналма жолдан Абай даңғылына шығыңыз",
            rewrite("exit the roundabout onto Абай даңғылы")
        )
    }

    @Test
    fun arrival() {
        assertEquals("Межелі жерге жеттіңіз", rewrite("мәреге жеттіңіз"))
    }

    @Test
    fun unknownText_onlyReplacesOnto() {
        assertEquals("бір нәрсе, Абай даңғылы", rewrite("бір нәрсе onto Абай даңғылы"))
        assertEquals("мүлдем белгісіз сөйлем", rewrite("мүлдем белгісіз сөйлем"))
    }

    @Test
    fun unknownPartOfSentence_keepsWholeSentence() {
        // Never rewrite half of an instruction
        assertEquals(
            "оңға бұрылыңыз, Абай даңғылы, одан соң белгісіз әрекет",
            rewrite("оңға бұрылыңыз onto Абай даңғылы, одан соң белгісіз әрекет")
        )
    }

    // region Dative case of street names

    @Test
    fun dative_possessiveStreetTypes() {
        assertEquals("Абай даңғылына", KazakhVoiceInstructions.dative("Абай даңғылы"))
        assertEquals("Тимирязев көшесіне", KazakhVoiceInstructions.dative("Тимирязев көшесі"))
        assertEquals("Райымбек батыр жолына", KazakhVoiceInstructions.dative("Райымбек батыр жолы"))
        assertEquals("Самал шағын ауданына", KazakhVoiceInstructions.dative("Самал шағын ауданы"))
    }

    @Test
    fun dative_otherWordEndings() {
        assertEquals("Медеуге", KazakhVoiceInstructions.dative("Медеу"))
        // Ends in ы but isn't possessive
        assertEquals("Алматыға", KazakhVoiceInstructions.dative("Алматы"))
        assertEquals("Арасанға", KazakhVoiceInstructions.dative("Арасан"))
        assertEquals("Көкбазарға", KazakhVoiceInstructions.dative("Көкбазар"))
        assertEquals("Достық алаңына", KazakhVoiceInstructions.dative("Достық алаңы"))
        assertEquals("Республика алаңына", KazakhVoiceInstructions.dative("Республика алаңы"))
        assertEquals("Тастаққа", KazakhVoiceInstructions.dative("Тастақ"))
        assertEquals("Шұғылаға", KazakhVoiceInstructions.dative("Шұғыла"))
        assertEquals("Ақсайға", KazakhVoiceInstructions.dative("Ақсай"))
    }

    @Test
    fun dative_nonKazakhNamesAreNotDeclined() {
        assertEquals("улица Абая бағытына", KazakhVoiceInstructions.dative("улица Абая"))
        assertEquals("Dostyk Avenue бағытына", KazakhVoiceInstructions.dative("Dostyk Avenue"))
    }

    // endregion
}
