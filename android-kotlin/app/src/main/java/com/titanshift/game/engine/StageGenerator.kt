package com.titanshift.game.engine

import com.titanshift.game.model.BossEncounter
import com.titanshift.game.model.CollectibleOrb
import com.titanshift.game.model.MultiplierWall
import com.titanshift.game.model.ObstacleInstance
import com.titanshift.game.model.ObstacleType
import com.titanshift.game.model.RgbColor
import kotlin.random.Random

data class GeneratedStage(
    val stageNumber: Int,
    val isBossStage: Boolean,
    val trackLength: Float,
    val obstacles: List<ObstacleInstance>,
    val collectibles: List<CollectibleOrb>,
    val multiplierWalls: List<MultiplierWall>,
    val boss: BossEncounter?
)

object StageGenerator {

    fun generate(stageNumber: Int): GeneratedStage {
        val stage = stageNumber.coerceAtLeast(1)
        val isBossStage = (stage % 5 == 0)
        val rng = Random(stage * 7919L + 104729L)

        val segmentCount = (7 + (stage / 2).coerceAtMost(7))
        val spacing = (34f - (stage * 0.3f)).coerceIn(26f, 34f)

        val obstacles = mutableListOf<ObstacleInstance>()
        val collectibles = mutableListOf<CollectibleOrb>()

        var currentZ = 34f
        var nextObsId = 1
        var nextOrbId = 1
        var prevType: ObstacleType? = null

        val unlockedPool = buildObstaclePool(stage)

        for (index in 0 until segmentCount) {
            val chosenType = when {
                // Curated onboarding on Stage 1 so the player immediately feels shrink -> smash -> glass -> sync!
                stage == 1 && index == 0 -> ObstacleType.LOW_LASER_GATE
                stage == 1 && index == 1 -> ObstacleType.HEAVY_BRICK_WALL
                stage == 1 && index == 2 -> ObstacleType.FRAGILE_GLASS_BRIDGE
                stage == 1 && index == 3 -> ObstacleType.HEAVY_BRICK_WALL
                stage == 1 && index == 4 -> ObstacleType.LOW_LASER_GATE
                stage == 1 && index == 5 -> ObstacleType.PRECISION_SYNC_ARCH
                else -> {
                    val candidates = unlockedPool.filter { it != prevType }
                    candidates[rng.nextInt(candidates.size)]
                }
            }
            prevType = chosenType

            val obs = createObstacle(
                id = nextObsId++,
                type = chosenType,
                zStart = currentZ,
                stage = stage,
                rng = rng
            )
            obstacles += obs

            // Place scale-guided coin trail leading into & across each obstacle
            val leadCoins = buildGuidedCoins(
                startId = nextOrbId,
                obstacle = obs,
                includeShield = (index == segmentCount / 2)
            )
            collectibles += leadCoins
            nextOrbId += leadCoins.size

            currentZ = obs.zEnd + spacing
        }

        val finishZ = currentZ + 12f
        val multiplierWalls = if (!isBossStage) {
            buildMultiplierGauntlet(finishZ)
        } else {
            emptyList()
        }

        val boss = if (isBossStage) {
            BossEncounter(
                maxPower = 100f,
                clashProgress = 46f,
                timeRemaining = 5.0f,
                sweetSpotSliderCenter = 0.78f,
                bossScale = (3.0f + (stage / 5) * 0.15f).coerceAtMost(3.8f),
                bossZ = finishZ + 18f
            )
        } else {
            null
        }

        return GeneratedStage(
            stageNumber = stage,
            isBossStage = isBossStage,
            trackLength = finishZ,
            obstacles = obstacles,
            collectibles = collectibles,
            multiplierWalls = multiplierWalls,
            boss = boss
        )
    }

    private fun buildObstaclePool(stage: Int): List<ObstacleType> = buildList {
        add(ObstacleType.LOW_LASER_GATE)
        add(ObstacleType.HEAVY_BRICK_WALL)
        add(ObstacleType.FRAGILE_GLASS_BRIDGE)
        add(ObstacleType.PRECISION_SYNC_ARCH)
        if (stage >= 2) {
            add(ObstacleType.ABYSS_CHASM_GAP)
            add(ObstacleType.RIVAL_ENFORCER)
        }
        if (stage >= 3) {
            add(ObstacleType.MOVING_PISTON_GATE)
        }
    }

    private fun createObstacle(
        id: Int,
        type: ObstacleType,
        zStart: Float,
        stage: Int,
        rng: Random
    ): ObstacleInstance = when (type) {
        ObstacleType.LOW_LASER_GATE -> ObstacleInstance(
            id = id,
            type = type,
            zStart = zStart,
            zLength = 3.2f,
            minSafeScale = 0f,
            maxSafeScale = 0.88f
        )

        ObstacleType.HEAVY_BRICK_WALL -> ObstacleInstance(
            id = id,
            type = type,
            zStart = zStart,
            zLength = 3.4f,
            minSafeScale = 1.68f,
            maxSafeScale = 10f
        )

        ObstacleType.FRAGILE_GLASS_BRIDGE -> ObstacleInstance(
            id = id,
            type = type,
            zStart = zStart,
            zLength = 10.5f,
            minSafeScale = 0f,
            maxSafeScale = 1.22f
        )

        ObstacleType.ABYSS_CHASM_GAP -> ObstacleInstance(
            id = id,
            type = type,
            zStart = zStart,
            zLength = 7.2f,
            minSafeScale = 1.78f,
            maxSafeScale = 10f
        )

        ObstacleType.PRECISION_SYNC_ARCH -> {
            val center = 1.15f + rng.nextFloat() * 0.35f
            val halfWindow = (0.34f - stage * 0.01f).coerceIn(0.22f, 0.34f)
            ObstacleInstance(
                id = id,
                type = type,
                zStart = zStart,
                zLength = 2.8f,
                minSafeScale = (center - halfWindow).coerceAtLeast(0.75f),
                maxSafeScale = center + halfWindow
            )
        }

        ObstacleType.RIVAL_ENFORCER -> ObstacleInstance(
            id = id,
            type = type,
            zStart = zStart,
            zLength = 3.2f,
            minSafeScale = 1.80f,
            maxSafeScale = 10f,
            phaseOffset = rng.nextFloat() * 6.28f
        )

        ObstacleType.MOVING_PISTON_GATE -> ObstacleInstance(
            id = id,
            type = type,
            zStart = zStart,
            zLength = 3.2f,
            minSafeScale = 0f,
            maxSafeScale = 0.85f,
            phaseOffset = rng.nextFloat() * 6.28f
        )
    }

    private fun buildGuidedCoins(
        startId: Int,
        obstacle: ObstacleInstance,
        includeShield: Boolean
    ): List<CollectibleOrb> {
        val list = mutableListOf<CollectibleOrb>()
        var id = startId

        // Coin height naturally hints at the ideal scale!
        val idealHeight = when (obstacle.type) {
            ObstacleType.LOW_LASER_GATE,
            ObstacleType.FRAGILE_GLASS_BRIDGE,
            ObstacleType.MOVING_PISTON_GATE -> 0.55f

            ObstacleType.PRECISION_SYNC_ARCH -> 1.45f

            ObstacleType.HEAVY_BRICK_WALL,
            ObstacleType.ABYSS_CHASM_GAP,
            ObstacleType.RIVAL_ENFORCER -> 2.45f
        }

        for (step in 0 until 3) {
            val z = obstacle.zStart - 10.5f + step * 3.4f
            list += CollectibleOrb(
                id = id++,
                z = z,
                x = 0f,
                y = idealHeight,
                isShieldCrystal = (includeShield && step == 1)
            )
        }
        return list
    }

    private fun buildMultiplierGauntlet(finishZ: Float): List<MultiplierWall> {
        val multipliers = listOf(1.5f, 2.0f, 3.0f, 4.0f, 5.0f, 6.5f, 8.0f, 10.0f)
        val palette = listOf(
            RgbColor(0.0f, 0.88f, 1.0f),
            RgbColor(0.12f, 0.95f, 0.62f),
            RgbColor(0.45f, 0.98f, 0.25f),
            RgbColor(1.0f, 0.86f, 0.15f),
            RgbColor(1.0f, 0.58f, 0.12f),
            RgbColor(1.0f, 0.28f, 0.35f),
            RgbColor(0.88f, 0.24f, 0.98f),
            RgbColor(1.0f, 0.82f, 0.22f)
        )
        return multipliers.mapIndexed { idx, mult ->
            MultiplierWall(
                index = idx,
                z = finishZ + 12f + idx * 9.5f,
                multiplier = mult,
                requiredMomentum = 18f + idx * 16f,
                color = palette[idx % palette.size]
            )
        }
    }
}
