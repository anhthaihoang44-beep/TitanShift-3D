package com.titanshift.game

import com.titanshift.game.data.PlayerProfileState
import com.titanshift.game.engine.GameSimulation
import com.titanshift.game.engine.StageGenerator
import com.titanshift.game.model.GamePhase
import com.titanshift.game.model.ObstacleType
import com.titanshift.game.model.ScaleZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameSimulationTest {

    @Test
    fun `stage generator creates standard multiplier stages and boss every 5th stage`() {
        val stage1 = StageGenerator.generate(1)
        assertFalse(stage1.isBossStage)
        assertNull(stage1.boss)
        assertEquals(8, stage1.multiplierWalls.size)
        assertTrue(stage1.obstacles.isNotEmpty())

        val stage5 = StageGenerator.generate(5)
        assertTrue(stage5.isBossStage)
        assertNotNull(stage5.boss)
        assertTrue(stage5.multiplierWalls.isEmpty())
    }

    @Test
    fun `slider smoothly morphs hero across MICRO SYNC and TITAN zones`() {
        val sim = GameSimulation(PlayerProfileState())
        sim.startRun()

        // Move slider to 0.0 -> Micro zone
        sim.setSliderNormalized(0f)
        repeat(20) { sim.step(0.02f) }
        assertEquals(ScaleZone.MICRO, sim.snapshot.value.scaleZone)
        assertTrue(sim.snapshot.value.currentScale < 0.55f)

        // Move slider to 1.0 -> Titan zone
        sim.setSliderNormalized(1f)
        repeat(25) { sim.step(0.02f) }
        assertEquals(ScaleZone.TITAN, sim.snapshot.value.scaleZone)
        assertTrue(sim.snapshot.value.currentScale > 2.5f)
    }

    @Test
    fun `adaptive player clears stage 1 obstacles and reaches victory`() {
        val sim = GameSimulation(PlayerProfileState(currentStage = 1))
        sim.startRun()

        var safetySteps = 0
        while (sim.snapshot.value.phase != GamePhase.STAGE_CLEAR &&
            sim.snapshot.value.phase != GamePhase.GAME_OVER &&
            safetySteps < 2000
        ) {
            val snap = sim.snapshot.value
            if (snap.phase == GamePhase.GOAL_GAUNTLET) {
                sim.setSliderNormalized(1.0f)
            } else {
                val upcoming = snap.obstacles.firstOrNull { !it.resolved && it.zEnd >= snap.playerZ }
                when (upcoming?.type) {
                    ObstacleType.LOW_LASER_GATE,
                    ObstacleType.FRAGILE_GLASS_BRIDGE,
                    ObstacleType.MOVING_PISTON_GATE -> sim.setSliderNormalized(0.05f)

                    ObstacleType.HEAVY_BRICK_WALL,
                    ObstacleType.ABYSS_CHASM_GAP,
                    ObstacleType.RIVAL_ENFORCER -> sim.setSliderNormalized(0.95f)

                    ObstacleType.PRECISION_SYNC_ARCH -> {
                        val mid = (upcoming.minSafeScale + upcoming.maxSafeScale) * 0.5f
                        val norm = (mid - snap.minScale) / (snap.maxScale - snap.minScale)
                        sim.setSliderNormalized(norm)
                    }

                    null -> sim.setSliderNormalized(0.95f)
                }
            }
            sim.step(0.02f)
            safetySteps++
        }

        assertEquals(GamePhase.STAGE_CLEAR, sim.snapshot.value.phase)
        assertTrue(sim.snapshot.value.finalStageReward > 0)
        assertTrue(sim.snapshot.value.earnedMultiplier >= 1.5f)
    }

    @Test
    fun `hitting heavy brick wall while tiny causes wipeout when unshielded`() {
        val sim = GameSimulation(PlayerProfileState(currentStage = 1))
        sim.startRun()

        // Keep player tiny (0.35x): passes first Low Laser Gate, then hits second Heavy Brick Wall!
        sim.setSliderNormalized(0f)
        var steps = 0
        while (sim.snapshot.value.phase == GamePhase.RUNNING && steps < 800) {
            sim.step(0.02f)
            steps++
        }

        assertEquals(GamePhase.GAME_OVER, sim.snapshot.value.phase)
    }
}
