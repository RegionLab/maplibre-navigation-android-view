package org.maplibre.navigation.android.navigation.ui.v5.voice

/**
 * Rewrites Kazakh voice instructions of GraphHopper into natural Kazakh, e.g.
 * "200 метр жерде оңға бұрылыңыз onto Политехникалық көшесі" →
 * "200 метрден кейін Политехникалық көшесіне оңға бұрылыңыз".
 *
 * GraphHopper's Kazakh translation leaves some templates empty (it falls back to English "onto",
 * "exit the roundabout") and translates others word by word. Instructions are recognized by
 * GraphHopper's templates and rebuilt with Kazakh word order and case endings. Text that isn't
 * recognized is returned with only the English "onto" replaced, so it never gets worse.
 *
 * Rewriting is deterministic on purpose: a voice instruction must never change its meaning.
 */
object KazakhVoiceInstructions {

    private const val THEN = ", одан соң "
    private const val THEN_NATURAL = ", содан кейін "

    private val distancePrefix = Regex("""^(\d+(?:[.,]\d+)?) (метр|км) жерде (.+)$""")
    private val ontoSuffix = Regex("""^(.+?) onto (.+)$""")
    private val roundaboutExit = Regex("""^(\d+)-ші бұрылыстан бұрылыңыз$""")
    private val roundaboutExitOnto = Regex("""^Айналымда (\d+)-ден (.+)-ге$""")
    private val roundaboutExitNowOnto = Regex("""^exit the roundabout onto (.+)$""")
    private val continueOnto = Regex("""^(.+) бойынша жалғастырыңыз$""")

    /** GraphHopper action → natural action, without and with a street to turn into. */
    private class Action(val alone: String, val withStreet: (street: String) -> String)

    private val actions: Map<String, Action> = mapOf(
        "оңға бұрылыңыз" to turn("оңға"),
        "солға бұрылыңыз" to turn("солға"),
        "аздап оңға бұрылыңыз" to turn("сәл оңға"),
        "аздап солға бұрылыңыз" to turn("сәл солға"),
        "қаттырақ оңға бұрылыңыз" to turn("күрт оңға"),
        "қаттырақ солға бұрылыңыз" to turn("күрт солға"),
        "оң жаққа шығыңыз" to keep("оң"),
        "сол жаққа шығыңыз" to keep("сол"),
        "артқа бұрылыңыз" to Action("кері бұрылыңыз") { street -> "кері бұрылып, ${dative(street)} шығыңыз" },
        "жалғастырыңыз" to Action("тура жүріңіз") { street -> "${street} бойымен тура жүріңіз" },
        "айналма жолға өтіңіз" to Action("айналма жолға кіріңіз") { street -> "айналма жолға кіріңіз, $street" },
        "exit the roundabout" to Action("айналма жолдан шығыңыз") { street -> "айналма жолдан ${dative(street)} шығыңыз" },
        "мәреге жеттіңіз" to Action("межелі жерге жеттіңіз") { "межелі жерге жеттіңіз" },
    )

    private fun turn(direction: String) =
        Action("$direction бұрылыңыз") { street -> "${dative(street)} $direction бұрылыңыз" }

    private fun keep(side: String) =
        Action("$side жақпен жүріңіз") { street -> "${dative(street)} қарай $side жақпен жүріңіз" }

    /**
     * @return natural Kazakh, or [text] with only "onto" replaced if it isn't recognized
     */
    @JvmStatic
    fun rewrite(text: String): String {
        val parts = text.trim().split(THEN)
        val rewritten = parts.map { rewritePart(it.trim()) }
        if (rewritten.any { it == null }) {
            return text.replace(Regex("""\s+onto\s+"""), ", ")
        }
        val sentence = rewritten.filterNotNull().joinToString(THEN_NATURAL)
        return sentence.replaceFirstChar { it.uppercaseChar() }
    }

    private fun rewritePart(part: String): String? {
        distancePrefix.matchEntire(part)?.let { match ->
            val (amount, unit, rest) = match.destructured
            val action = rewriteAction(rest) ?: return null
            val distance = if (unit == "км") "$amount километрден кейін" else "$amount метрден кейін"
            return "$distance $action"
        }
        return rewriteAction(part)
    }

    private fun rewriteAction(part: String): String? {
        roundaboutExit.matchEntire(part)?.let { match ->
            return "айналма жолдан ${match.groupValues[1]}-ші шығысқа шығыңыз"
        }
        roundaboutExitOnto.matchEntire(part)?.let { match ->
            val (exit, street) = match.destructured
            return "айналма жолдан $exit-ші шығыспен ${dative(street)} шығыңыз"
        }
        roundaboutExitNowOnto.matchEntire(part)?.let { match ->
            return "айналма жолдан ${dative(match.groupValues[1])} шығыңыз"
        }
        continueOnto.matchEntire(part)?.let { match ->
            return "${match.groupValues[1]} бойымен тура жүріңіз"
        }
        ontoSuffix.matchEntire(part)?.let { match ->
            val (action, street) = match.destructured
            return actions[action.lowercase()]?.withStreet?.invoke(street.trim())
        }
        return actions[part.lowercase()]?.alone
    }

    /**
     * Street name in the dative case ("to the street"), e.g. "Абай даңғылы" → "Абай даңғылына".
     * Names that aren't Kazakh (e.g. "улица Абая") are kept as they are behind a pause, declining
     * them would mix languages.
     */
    internal fun dative(street: String): String {
        val name = street.trim()
        val lastWord = name.substringAfterLast(' ')
        if (!looksKazakh(name) || lastWord.isEmpty() || !lastWord.last().isLetter()) {
            return "$name бағытына"
        }
        val front = isFrontVowelWord(lastWord)
        val last = lastWord.last().lowercaseChar()
        val ending = when {
            // Third person possessive ("көшесі", "даңғылы", "жолы"), the usual street names.
            // Other words ending in ы/і aren't possessive ("Алматы" → "Алматыға").
            isPossessive(lastWord) -> if (front) "не" else "на"
            last in VOICELESS_CONSONANTS -> if (front) "ке" else "қа"
            else -> if (front) "ге" else "ға"
        }
        return name + ending
    }

    private fun isPossessive(word: String): Boolean {
        val lower = word.lowercase()
        return lower in POSSESSIVE_STREET_TYPES || lower.endsWith("сі") || lower.endsWith("сы")
    }

    private fun looksKazakh(name: String): Boolean {
        val lower = name.lowercase()
        if (RUSSIAN_STREET_WORDS.any { lower.startsWith(it) || lower.contains(" $it") }) {
            return false
        }
        // Cyrillic only, Latin names are kept as they are
        return lower.none { it in 'a'..'z' }
    }

    private fun isFrontVowelWord(word: String): Boolean {
        val lastVowel = word.lowercase().lastOrNull { it in FRONT_VOWELS || it in BACK_VOWELS }
        return lastVowel != null && lastVowel in FRONT_VOWELS
    }

    // "у" and "и" are neutral, the vowel before them decides ("Медеу" → "Медеуге")
    private const val FRONT_VOWELS = "әеіөүэ"
    private const val BACK_VOWELS = "аоұыяё"
    private const val VOICELESS_CONSONANTS = "кқпстфхцчшщ"
    private val POSSESSIVE_STREET_TYPES = setOf(
        "көшесі", "даңғылы", "жолы", "ауданы", "алаңы", "тұйығы", "бульвары", "өткелі", "шоссесі", "жағалауы",
    )
    private val RUSSIAN_STREET_WORDS = listOf("улица", "ул.", "проспект", "пр.", "переулок", "бульвар", "шоссе", "микрорайон")
}
