package org.maplibre.navigation.android.navigation.ui.v5.voice

/**
 * Makes Kazakh street names in voice instructions speakable by Russian, English and Uzbek voices.
 *
 * Routing engines return street names as written in OpenStreetMap, in Kazakhstan mostly Kazakh
 * ("Тимирязев көшесі"), also when instructions are requested in another language. Other voices
 * can't read Kazakh letters. The street type is translated and the name transliterated:
 * - ru: "на Тимирязев көшесі" → "на улицу Тимирязев", "Бәйсейітова" → "Байсеитова"
 * - en: "onto Абай даңғылы" → "onto Abay Avenue"
 * - uz: "Тимирязев көшесі" → "Timiryazev ko‘chasi"
 */
object StreetNameLocalizer {

    private class StreetType(val kazakh: String, val russian: String, val russianAccusative: String,
                             val english: String, val uzbek: String)

    // Longer types first, "шағын ауданы" before "ауданы", "тас жолы" before "жолы"
    private val streetTypes = listOf(
        StreetType("шағын ауданы", "микрорайон", "микрорайон", "Microdistrict", "mavzesi"),
        StreetType("тас жолы", "шоссе", "шоссе", "Highway", "shossesi"),
        StreetType("көшесі", "улица", "улицу", "Street", "ko‘chasi"),
        StreetType("даңғылы", "проспект", "проспект", "Avenue", "shoh ko‘chasi"),
        StreetType("ауданы", "район", "район", "District", "tumani"),
        StreetType("алаңы", "площадь", "площадь", "Square", "maydoni"),
        StreetType("жолы", "дорога", "дорогу", "Road", "yo‘li"),
        StreetType("тұйығы", "тупик", "тупик", "Lane", "berk ko‘chasi"),
        StreetType("бульвары", "бульвар", "бульвар", "Boulevard", "xiyoboni"),
        StreetType("өткелі", "проезд", "проезд", "Passage", "o‘tish yo‘li"),
        StreetType("жағалауы", "набережная", "набережную", "Embankment", "sohili"),
    )

    private const val KAZAKH_LETTERS = "әғқңөұүһіӘҒҚҢӨҰҮҺІ"

    /**
     * Russian instruction words that can stand right before a street name. English and Uzbek
     * instructions are Latin, street names are the Cyrillic words before the street type.
     */
    private val russianInstructionWords = setOf(
        "на", "в", "по", "через", "к", "и", "затем", "поверните", "направо", "налево", "держитесь",
        "правее", "левее", "прямо", "продолжайте", "развернитесь", "съезд", "выезд", "метрах", "километрах",
    )

    /**
     * @param language language of the instruction, e.g. "ru"; other languages are returned unchanged
     */
    @JvmStatic
    fun localize(text: String, language: String): String {
        val target = language.substringBefore('-').substringBefore('_').lowercase()
        if (target !in setOf("ru", "en", "uz") || text.none { it in KAZAKH_LETTERS || it.isCyrillic() }) {
            return text
        }
        var result = translateStreetTypes(text, target)
        // Remaining Kazakh words, e.g. place names without a street type
        result = when (target) {
            "ru" -> result.split(' ').joinToString(" ") { word ->
                if (word.any { it in KAZAKH_LETTERS }) kazakhToRussian(word) else word
            }
            else -> result.split(' ').joinToString(" ") { word ->
                if (word.any { it.isCyrillic() }) cyrillicToLatin(word) else word
            }
        }
        return result
    }

    private fun translateStreetTypes(text: String, target: String): String {
        var result = text
        for (type in streetTypes) {
            val pattern = Regex("""(?<![\p{L}])((?:[\p{L}\d’'‘-]+ ){1,4})${Regex.escape(type.kazakh)}(?![\p{L}])""")
            result = pattern.replace(result) { match ->
                val words = match.groupValues[1].trim().split(' ')
                // The street name are the Cyrillic words right before the type, not the instruction
                val nameWords = words.takeLastWhile { word ->
                    word.any { it.isCyrillic() || it.isDigit() } && word.lowercase() !in russianInstructionWords
                }
                val prefix = words.dropLast(nameWords.size).joinToString(" ")
                if (nameWords.isEmpty()) {
                    return@replace match.value
                }
                val name = nameWords.joinToString(" ")
                val street = when (target) {
                    "ru" -> {
                        val accusative = prefix.split(' ').lastOrNull()?.lowercase() in setOf("на", "в")
                        val typeName = if (accusative) type.russianAccusative else type.russian
                        "$typeName ${kazakhToRussian(name)}"
                    }
                    "en" -> "${cyrillicToLatin(name)} ${type.english}"
                    else -> "${cyrillicToLatin(name)} ${type.uzbek}"
                }
                listOf(prefix, street).filter { it.isNotEmpty() }.joinToString(" ")
            }
        }
        return result
    }

    /** Kazakh letters → closest Russian letters, readable by Russian voices. */
    internal fun kazakhToRussian(text: String): String = buildString {
        text.forEach { char ->
            append(
                when (char) {
                    'ә' -> 'а'; 'Ә' -> 'А'
                    'ғ' -> 'г'; 'Ғ' -> 'Г'
                    'қ' -> 'к'; 'Қ' -> 'К'
                    'ң' -> 'н'; 'Ң' -> 'Н'
                    'ө' -> 'о'; 'Ө' -> 'О'
                    'ұ', 'ү' -> 'у'; 'Ұ', 'Ү' -> 'У'
                    'һ' -> 'х'; 'Һ' -> 'Х'
                    'і' -> 'и'; 'І' -> 'И'
                    else -> char
                }
            )
        }
    }

    /** Kazakh and Russian Cyrillic → Latin as it is pronounced, readable by English voices. */
    internal fun cyrillicToLatin(text: String): String = buildString {
        text.forEachIndexed { index, char ->
            val previous = text.getOrNull(index - 1)?.lowercaseChar()
            // "е" at the start of a word and after a vowel is "ye", e.g. "Сәтбаев" → "Satbayev"
            val isYe = char.lowercaseChar() == 'е' && (previous == null || !previous.isLetter() || previous in CYRILLIC_VOWELS)
            val latin = if (isYe) "ye" else LATIN[char.lowercaseChar()] ?: char.toString()
            append(if (char.isUpperCase() && latin.isNotEmpty()) latin.replaceFirstChar { it.uppercaseChar() } else latin)
        }
    }

    private const val CYRILLIC_VOWELS = "аәеёиоөуұүыіэюяьъ"

    private fun Char.isCyrillic() = this in 'Ѐ'..'ӿ'

    private val LATIN = mapOf(
        'а' to "a", 'ә' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'ғ' to "g", 'д' to "d", 'е' to "e",
        'ё' to "yo", 'ж' to "zh", 'з' to "z", 'и' to "i", 'й' to "y", 'к' to "k", 'қ' to "k", 'л' to "l",
        'м' to "m", 'н' to "n", 'ң' to "ng", 'о' to "o", 'ө' to "o", 'п' to "p", 'р' to "r", 'с' to "s",
        'т' to "t", 'у' to "u", 'ұ' to "u", 'ү' to "u", 'ф' to "f", 'х' to "kh", 'һ' to "h", 'ц' to "ts",
        'ч' to "ch", 'ш' to "sh", 'щ' to "shch", 'ъ' to "", 'ы' to "y", 'і' to "i", 'ь' to "", 'э' to "e",
        'ю' to "yu", 'я' to "ya",
    )
}
