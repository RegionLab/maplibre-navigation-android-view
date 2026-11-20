package org.maplibre.navigation.android.navigation.ui.v5.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.maplibre.navigation.core.models.BannerInstructions
import org.maplibre.navigation.core.models.BannerText
import org.maplibre.navigation.core.models.DirectionsRoute
import org.maplibre.navigation.core.models.LegStep
import org.maplibre.navigation.core.models.ManeuverModifier
import org.maplibre.navigation.core.models.RouteLeg
import org.maplibre.navigation.core.models.StepManeuver
import org.maplibre.navigation.core.models.VoiceInstructions
import org.maplibre.spatialk.geojson.Position

class KazakhInstructionGeneratorTest {

    private fun step(
        type: StepManeuver.Type,
        modifier: ManeuverModifier.Type? = null,
        name: String? = null,
        exit: Int? = null,
        voice: List<VoiceInstructions>? = null,
        banners: List<BannerInstructions>? = null,
    ) = LegStep(
        geometry = "", distance = 0.0, duration = 0.0, name = name,
        maneuver = StepManeuver(
            location = Position(76.9, 43.2), bearingBefore = 0.0, bearingAfter = 0.0,
            type = type, modifier = modifier, exit = exit,
        ),
        voiceInstructions = voice, bannerInstructions = banners,
    )

    private fun voice(text: String, distance: Double) =
        VoiceInstructions(distanceAlongGeometry = distance, announcement = text, ssmlAnnouncement = "<speak>$text</speak>")

    private fun banner(text: String) = BannerInstructions(distanceAlongGeometry = 100.0, primary = BannerText(text = text))

    private fun generate(vararg steps: LegStep): List<LegStep> {
        val route = DirectionsRoute(
            geometry = "", distance = 0.0, duration = 0.0,
            legs = listOf(RouteLeg(distance = 0.0, duration = 0.0, steps = steps.toList())),
        )
        return KazakhInstructionGenerator.apply(route).legs.single().steps
    }

    private fun announcements(step: LegStep) = step.voiceInstructions!!.map { it.announcement }

    @Test
    fun turnOntoStreet_withEnglishDistance() {
        // Mapbox announcements for language=kk, received on the device
        val steps = generate(
            step(
                StepManeuver.Type.DEPART, name = "Қаныш Сәтбаев көшесі",
                voice = listOf(voice("In 400 meters, Turn left onto Әуезов көшесі.", 420.0), voice("Turn left onto Әуезов көшесі.", 40.0)),
            ),
            step(StepManeuver.Type.TURN, ManeuverModifier.Type.LEFT, name = "Әуезов көшесі"),
        )

        assertEquals(
            listOf("400 метрден кейін Әуезов көшесіне солға бұрылыңыз", "Әуезов көшесіне солға бұрылыңыз"),
            announcements(steps[0])
        )
        assertNull(steps[0].voiceInstructions!![0].ssmlAnnouncement)
    }

    @Test
    fun valhallaRoute() {
        // Valhalla announcements for language=kk, received from valhalla1.openstreetmap.de
        val steps = generate(
            step(
                StepManeuver.Type.DEPART, name = "Қаныш Сәтбаев көшесі",
                voice = listOf(
                    voice("Drive east on Қаныш Сәтбаев көшесі.", 1090.0),
                    voice("In 300 meters, Turn left onto Желтоқсан көшесі.", 262.0),
                ),
            ),
            step(
                StepManeuver.Type.TURN, ManeuverModifier.Type.LEFT, name = "Желтоқсан көшесі",
                voice = listOf(voice("Continue for 400 meters.", 416.0)),
            ),
            step(
                StepManeuver.Type.END_OF_ROAD, ManeuverModifier.Type.LEFT, name = "Бәйсейітова көшесі",
                voice = listOf(voice("You have arrived at your destination.", 47.0)),
                banners = listOf(banner("You have arrived at your destination.")),
            ),
            step(StepManeuver.Type.ARRIVE, name = "Бәйсейітова көшесі"),
        )

        assertEquals(
            listOf("Қаныш Сәтбаев көшесі бойымен жүріңіз", "300 метрден кейін Желтоқсан көшесіне солға бұрылыңыз"),
            announcements(steps[0])
        )
        assertEquals(listOf("400 метр тура жүріңіз"), announcements(steps[1]))
        assertEquals(listOf("Межелі жерге жеттіңіз"), announcements(steps[2]))
        assertEquals("Межелі жер", steps[2].bannerInstructions!![0].primary.text)
    }

    @Test
    fun kilometersAndThen() {
        val steps = generate(
            step(StepManeuver.Type.DEPART, voice = listOf(voice("In 1.5 kilometers, Turn right, then Turn left.", 1500.0))),
            step(StepManeuver.Type.TURN, ManeuverModifier.Type.RIGHT),
            step(StepManeuver.Type.TURN, ManeuverModifier.Type.SLIGHT_LEFT, name = "Абай даңғылы"),
        )

        assertEquals(
            listOf("1.5 километрден кейін оңға бұрылыңыз, содан кейін Абай даңғылына сәл солға бұрылыңыз"),
            announcements(steps[0])
        )
    }

    @Test
    fun announcementWithoutEnglishDistance_getsDistanceToManeuver() {
        val steps = generate(
            step(StepManeuver.Type.DEPART, voice = listOf(voice("Make a sharp right.", 2040.0))),
            step(StepManeuver.Type.END_OF_ROAD, ManeuverModifier.Type.SHARP_RIGHT),
        )

        assertEquals(listOf("2 километрден кейін күрт оңға бұрылыңыз"), announcements(steps[0]))
    }

    @Test
    fun roundaboutUturnForkArrival() {
        val steps = generate(
            step(StepManeuver.Type.DEPART, voice = listOf(voice("Enter the roundabout", 30.0))),
            step(StepManeuver.Type.ROUNDABOUT, ManeuverModifier.Type.RIGHT, name = "Сейфуллин даңғылы", exit = 2,
                voice = listOf(voice("Make a U-turn", 30.0))),
            step(StepManeuver.Type.CONTINUE, ManeuverModifier.Type.UTURN, voice = listOf(voice("Keep right", 30.0))),
            step(StepManeuver.Type.FORK, ManeuverModifier.Type.SLIGHT_RIGHT, name = "Райымбек батыр жолы",
                voice = listOf(voice("In 200 meters, You will arrive", 200.0), voice("You have arrived", 0.0))),
            step(StepManeuver.Type.ARRIVE),
        )

        assertEquals(listOf("Айналма жолдан 2-ші шығыспен Сейфуллин даңғылына шығыңыз"), announcements(steps[0]))
        assertEquals(listOf("Кері бұрылыңыз"), announcements(steps[1]))
        assertEquals(listOf("Райымбек батыр жолына қарай оң жақпен жүріңіз"), announcements(steps[2]))
        assertEquals(listOf("200 метрден кейін межелі жерге жетесіз", "Межелі жерге жеттіңіз"), announcements(steps[3]))
    }

    @Test
    fun banners_streetKeptEnglishManeuverReplaced() {
        val steps = generate(
            step(StepManeuver.Type.DEPART, banners = listOf(banner("Әуезов көшесі"))),
            step(StepManeuver.Type.TURN, ManeuverModifier.Type.LEFT, name = "Әуезов көшесі", banners = listOf(banner("Turn left"))),
            step(StepManeuver.Type.TURN, ManeuverModifier.Type.LEFT),
        )

        assertEquals("Әуезов көшесі", steps[0].bannerInstructions!![0].primary.text)
        assertEquals("Солға бұрылыңыз", steps[1].bannerInstructions!![0].primary.text)
        assertEquals("Солға бұрылыңыз", steps[1].bannerInstructions!![0].primary.components!!.single().text)
        assertEquals("Әуезов көшесіне солға бұрылыңыз", steps[1].maneuver.instruction)
    }
}
