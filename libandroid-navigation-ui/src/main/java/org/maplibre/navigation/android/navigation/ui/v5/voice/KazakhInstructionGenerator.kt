package org.maplibre.navigation.android.navigation.ui.v5.voice

import org.maplibre.navigation.core.models.BannerComponents
import org.maplibre.navigation.core.models.BannerInstructions
import org.maplibre.navigation.core.models.BannerText
import org.maplibre.navigation.core.models.DirectionsRoute
import org.maplibre.navigation.core.models.LegStep
import org.maplibre.navigation.core.models.ManeuverModifier
import org.maplibre.navigation.core.models.RouteLeg
import org.maplibre.navigation.core.models.StepManeuver
import org.maplibre.navigation.core.models.VoiceInstructions
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Builds Kazakh voice and banner instructions from the maneuvers of a route, for routing engines
 * without Kazakh, e.g. Mapbox answers `language=kk` in English.
 *
 * The maneuver (type, direction, roundabout exit), the street and the distance are taken from the
 * route, the English text only tells whether an announcement has a distance and a "then" part.
 */
internal object KazakhInstructionGenerator {

    private val englishDistance = Regex("""^In (\d+(?:[.,]\d+)?) (meters?|kilometers?)""", RegexOption.IGNORE_CASE)
    private val englishThen = Regex(""",? then """, RegexOption.IGNORE_CASE)
    private val englishContinueFor = Regex("""^Continue (?:on .+ )?for (\d+(?:[.,]\d+)?) (meters?|kilometers?)""", RegexOption.IGNORE_CASE)
    private val englishDepart = Regex("""^(Drive|Head|Walk|Bike) """, RegexOption.IGNORE_CASE)

    /**
     * Announcements without an English distance ("Continue for 2 kilometers", "Turn left") get
     * the distance to the maneuver if they are farther than this.
     */
    private const val IMMEDIATE_DISTANCE_METERS = 150.0

    @JvmStatic
    fun apply(route: DirectionsRoute): DirectionsRoute =
        route.copy(legs = route.legs.map(::applyToLeg), voiceLanguage = "kk")

    private fun applyToLeg(leg: RouteLeg): RouteLeg {
        val steps = leg.steps
        return leg.copy(steps = steps.mapIndexed { index, step ->
            // Instructions of a step announce the maneuver at its end, the start of the next step
            val next = steps.getOrNull(index + 1)
            val afterNext = steps.getOrNull(index + 2)
            step.copy(
                maneuver = step.maneuver.copy(instruction = capitalize(action(step))),
                voiceInstructions = if (next == null) step.voiceInstructions else step.voiceInstructions?.map { voice ->
                    voice.withAnnouncement(voiceText(voice, step, next, afterNext))
                },
                bannerInstructions = if (next == null) step.bannerInstructions else step.bannerInstructions?.map { banner ->
                    banner.inKazakh(next)
                },
            )
        })
    }

    private fun voiceText(voice: VoiceInstructions, step: LegStep, next: LegStep, afterNext: LegStep?): String {
        val english = voice.announcement.orEmpty()
        // "Drive east on Абай даңғылы.", the first announcement describes the departure
        if (step.maneuver.type == StepManeuver.Type.DEPART && englishDepart.containsMatchIn(english)) {
            val depart = action(step)
            return capitalize(if (englishThen.containsMatchIn(english)) "$depart, содан кейін ${action(next)}" else depart)
        }
        // "Continue for 400 meters." between two maneuvers
        englishContinueFor.find(english)?.let { match ->
            val amount = match.groupValues[1].replace(',', '.')
            val unit = if (match.groupValues[2].startsWith("k", ignoreCase = true)) "километр" else "метр"
            return capitalize("$amount $unit тура жүріңіз")
        }
        val distance = englishDistance.find(english)?.let { match ->
            val amount = match.groupValues[1].replace(',', '.')
            if (match.groupValues[2].startsWith("k", ignoreCase = true)) "$amount километрден кейін" else "$amount метрден кейін"
        } ?: voice.distanceAlongGeometry.takeIf { it >= IMMEDIATE_DISTANCE_METERS }?.let(::distancePhrase)

        val nextAction = if (next.maneuver.type == StepManeuver.Type.ARRIVE && distance != null) {
            "межелі жерге жетесіз"
        } else {
            action(next)
        }
        val text = buildString {
            distance?.let { append(it).append(' ') }
            append(nextAction)
            if (afterNext != null && englishThen.containsMatchIn(english)) {
                append(", содан кейін ").append(action(afterNext))
            }
        }
        return capitalize(text)
    }

    private fun distancePhrase(meters: Double): String = when {
        meters >= 1000 -> {
            val km = (meters / 100).roundToInt() / 10.0
            val text = if (km % 1.0 == 0.0) km.toInt().toString() else String.format(Locale.ROOT, "%.1f", km)
            "$text километрден кейін"
        }
        meters >= 100 -> "${(meters / 100).roundToInt() * 100} метрден кейін"
        else -> "${(meters / 50).roundToInt().coerceAtLeast(1) * 50} метрден кейін"
    }

    private fun VoiceInstructions.withAnnouncement(text: String) =
        // SSML would be read instead of the text by some speech engines
        copy(announcement = text, ssmlAnnouncement = null)

    /**
     * Banners show the street, or the maneuver if the street has no name. Only English maneuver
     * texts are replaced, street names are kept.
     */
    private fun BannerInstructions.inKazakh(next: LegStep): BannerInstructions {
        val street = streetOf(next)
        if (next.maneuver.type != StepManeuver.Type.ARRIVE && street != null && primary.text.contains(street)) {
            return this
        }
        if (primary.text.none { it in 'a'..'z' || it in 'A'..'Z' } && primary.text.isNotBlank()) {
            return this
        }
        val text = when (next.maneuver.type) {
            StepManeuver.Type.ARRIVE -> "Межелі жер"
            else -> capitalize(street ?: action(next))
        }
        return copy(primary = primary.copy(text = text, components = listOf(BannerComponents(text = text, type = BannerComponents.Type.TEXT))))
    }

    /** Kazakh instruction for the maneuver at the start of [step], into the street of [step]. */
    internal fun action(step: LegStep): String {
        val maneuver = step.maneuver
        val street = streetOf(step)
        val modifier = maneuver.modifier
        return when (maneuver.type) {
            StepManeuver.Type.ARRIVE -> "межелі жерге жеттіңіз"
            StepManeuver.Type.DEPART -> street?.let { "$it бойымен жүріңіз" } ?: "жолға шығыңыз"
            StepManeuver.Type.ROUNDABOUT, StepManeuver.Type.ROTARY, StepManeuver.Type.ROUNDABOUT_TURN -> {
                val exit = maneuver.exit
                when {
                    exit != null && street != null -> "айналма жолдан $exit-ші шығыспен ${KazakhVoiceInstructions.dative(street)} шығыңыз"
                    exit != null -> "айналма жолдан $exit-ші шығысқа шығыңыз"
                    else -> "айналма жолға кіріңіз"
                }
            }
            StepManeuver.Type.EXIT_ROUNDABOUT, StepManeuver.Type.EXIT_ROTARY ->
                street?.let { "айналма жолдан ${KazakhVoiceInstructions.dative(it)} шығыңыз" } ?: "айналма жолдан шығыңыз"
            StepManeuver.Type.FORK, StepManeuver.Type.MERGE, StepManeuver.Type.ON_RAMP, StepManeuver.Type.OFF_RAMP ->
                keep(modifier, street)
            else -> turn(modifier, street)
        }
    }

    private fun turn(modifier: ManeuverModifier.Type?, street: String?): String {
        val direction = when (modifier) {
            ManeuverModifier.Type.LEFT -> "солға"
            ManeuverModifier.Type.RIGHT -> "оңға"
            ManeuverModifier.Type.SLIGHT_LEFT -> "сәл солға"
            ManeuverModifier.Type.SLIGHT_RIGHT -> "сәл оңға"
            ManeuverModifier.Type.SHARP_LEFT -> "күрт солға"
            ManeuverModifier.Type.SHARP_RIGHT -> "күрт оңға"
            ManeuverModifier.Type.UTURN ->
                return street?.let { "кері бұрылып, ${KazakhVoiceInstructions.dative(it)} шығыңыз" } ?: "кері бұрылыңыз"
            ManeuverModifier.Type.STRAIGHT, null ->
                return street?.let { "$it бойымен тура жүріңіз" } ?: "тура жүріңіз"
        }
        return street?.let { "${KazakhVoiceInstructions.dative(it)} $direction бұрылыңыз" } ?: "$direction бұрылыңыз"
    }

    private fun keep(modifier: ManeuverModifier.Type?, street: String?): String {
        val side = when (modifier) {
            ManeuverModifier.Type.LEFT, ManeuverModifier.Type.SLIGHT_LEFT, ManeuverModifier.Type.SHARP_LEFT -> "сол"
            ManeuverModifier.Type.RIGHT, ManeuverModifier.Type.SLIGHT_RIGHT, ManeuverModifier.Type.SHARP_RIGHT -> "оң"
            ManeuverModifier.Type.UTURN, ManeuverModifier.Type.STRAIGHT, null ->
                return street?.let { "$it бойымен тура жүріңіз" } ?: "тура жүріңіз"
        }
        return street?.let { "${KazakhVoiceInstructions.dative(it)} қарай $side жақпен жүріңіз" } ?: "$side жақпен жүріңіз"
    }

    private fun streetOf(step: LegStep): String? =
        step.name?.takeIf { it.isNotBlank() } ?: step.ref?.takeIf { it.isNotBlank() }

    private fun capitalize(text: String) = text.replaceFirstChar { it.uppercaseChar() }
}
