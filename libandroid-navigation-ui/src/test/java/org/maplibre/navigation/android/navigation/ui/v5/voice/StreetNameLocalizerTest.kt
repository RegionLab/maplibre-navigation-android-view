package org.maplibre.navigation.android.navigation.ui.v5.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class StreetNameLocalizerTest {

    private fun localize(text: String, language: String) = StreetNameLocalizer.localize(text, language)

    // region Russian

    @Test
    fun russian_streetTypeTranslatedWithCase() {
        // Received from GraphHopper with language=ru
        assertEquals(
            "В 200 метрах Поверните направо на улицу Тимирязев",
            localize("В 200 метрах Поверните направо на Тимирязев көшесі", "ru")
        )
        // Received from Valhalla with language=ru-RU
        assertEquals("Поверните направо на проспект Абай.", localize("Поверните направо на Абай даңғылы.", "ru"))
    }

    @Test
    fun russian_kazakhLettersTransliterated() {
        assertEquals("на улицу Байсейитова", localize("на Бәйсейітова көшесі", "ru"))
        assertEquals("на улицу Каныш Сатбаев", localize("на Қаныш Сәтбаев көшесі", "ru"))
        assertEquals("на улицу Аксаков", localize("на Ақсақов көшесі", "ru"))
    }

    @Test
    fun russian_otherTypes() {
        assertEquals("на микрорайон Самал", localize("на Самал шағын ауданы", "ru"))
        assertEquals("на дорогу Райымбек батыр", localize("на Райымбек батыр жолы", "ru"))
        assertEquals("на площадь Республика", localize("на Республика алаңы", "ru"))
        assertEquals("на набережную Есил", localize("на Есіл жағалауы", "ru"))
    }

    // endregion

    // region English

    @Test
    fun english_streetTypeAndLatinName() {
        // Received from Valhalla with language=en-US
        assertEquals("Turn right onto Abay Avenue.", localize("Turn right onto Абай даңғылы.", "en"))
        assertEquals("Turn left onto Timiryazev Street", localize("Turn left onto Тимирязев көшесі", "en"))
        assertEquals("Continue on Kanysh Satbayev Street", localize("Continue on Қаныш Сәтбаев көшесі", "en"))
    }

    @Test
    fun english_yeAfterVowelsAndAtWordStart() {
        assertEquals("Yesil Embankment", localize("Есіл жағалауы", "en"))
        assertEquals("Turn right onto Rayymbek batyr Road", localize("Turn right onto Райымбек батыр жолы", "en"))
    }

    @Test
    fun english_namesWithoutStreetTypeTransliterated() {
        assertEquals("Turn right towards Medeu", localize("Turn right towards Медеу", "en"))
    }

    // endregion

    @Test
    fun uzbek_latinWithUzbekStreetType() {
        assertEquals("Timiryazev ko‘chasi", localize("Тимирязев көшесі", "uz"))
        assertEquals("Abay shoh ko‘chasi", localize("Абай даңғылы", "uz"))
    }

    @Test
    fun otherLanguagesAndPlainText_unchanged() {
        assertEquals("Тимирязев көшесіне оңға бұрылыңыз", localize("Тимирязев көшесіне оңға бұрылыңыз", "kk"))
        assertEquals("Turn right onto Main Street", localize("Turn right onto Main Street", "en"))
        assertEquals("Поверните направо на улицу Ленина", localize("Поверните направо на улицу Ленина", "ru"))
    }
}
