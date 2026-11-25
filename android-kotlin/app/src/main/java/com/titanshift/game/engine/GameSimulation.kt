package com.titanshift.game.engine

import com.titanshift.game.data.PlayerProfileState
import com.titanshift.game.model.AppLanguage
import com.titanshift.game.model.BossEncounter
import com.titanshift.game.model.CollectibleOrb
import com.titanshift.game.model.DebrisPiece
import com.titanshift.game.model.FloatingCallout
import com.titanshift.game.model.GameEvent
import com.titanshift.game.model.GamePhase
import com.titanshift.game.model.GameSnapshot
import com.titanshift.game.model.MultiplierWall
import com.titanshift.game.model.ObstacleInstance
import com.titanshift.game.model.ObstacleType
import com.titanshift.game.model.RgbColor
import com.titanshift.game.model.ScaleZone
import com.titanshift.game.model.SkinCatalog
import com.titanshift.game.model.UpgradeType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

class GameSimulation(
    initialProfile: PlayerProfileState = PlayerProfileState(),
    private val onEvent: (GameEvent) -> Unit = {}
) {
    private var profile: PlayerProfileState = initialProfile
    private var simTime: Float = 0f
    private var nextCalloutId: Int = 1
    private val fxRng = Random(42L)

    private var phase: GamePhase = GamePhase.HOME
    private var stageNumber: Int = initialProfile.currentStage
    private var isBossStage: Boolean = false
    private var trackLength: Float = 220f

    private var sliderValue: Float = 0.28f
    private var currentScale: Float = 1.0f
    private var lastScaleZone: ScaleZone = ScaleZone.SYNC

    private var playerZ: Float = 0f
    private var playerY: Float = 0f
    private var jumpStartZ: Float = -1f
    private var jumpEndZ: Float = -1f
    private var runCyclePhase: Float = 0f

    private var hasShield: Boolean = false
    private var invulnerableTimer: Float = 0f
    private var comboCount: Int = 0
    private var maxComboReached: Int = initialProfile.bestCombo
    private var overdriveMeter: Float = 0f
    private var overdriveTimer: Float = 0f
    private var momentumScore: Float = 32f

    private var stageCoins: Int = 0
    private var earnedMultiplier: Float = 1.0f
    private var finalStageReward: Int = 0
    private var rewardCommitted: Boolean = false

    private var cameraShake: Float = 0f
    private var homeOrbitAngle: Float = 0f
    private var finishStopTimer: Float = 0f

    private var failReasonVi: String = ""
    private var failReasonEn: String = ""

    private val obstacles = mutableListOf<ObstacleInstance>()
    private val collectibles = mutableListOf<CollectibleOrb>()
    private val multiplierWalls = mutableListOf<MultiplierWall>()
    private val debris = mutableListOf<DebrisPiece>()
    private val callouts = mutableListOf<FloatingCallout>()
    private var boss: BossEncounter? = null

    private val _snapshot = MutableStateFlow(buildSnapshot())
    val snapshot: StateFlow<GameSnapshot> = _snapshot.asStateFlow()

    init {
        prepareStage(initialProfile.currentStage, enterRunning = false)
    }

    @Synchronized
    fun syncProfile(newProfile: PlayerProfileState) {
        val stageChanged = newProfile.currentStage != stageNumber && phase == GamePhase.HOME
        profile = newProfile
        if (stageChanged) {
            prepareStage(newProfile.currentStage, enterRunning = false)
        } else {
            publishSnapshot()
        }
    }

    @Synchronized
    fun previewSkin(skinId: String) {
        profile = profile.copy(selectedSkinId = skinId)
        publishSnapshot()
    }

    @Synchronized
    fun prepareStage(stage: Int, enterRunning: Boolean) {
        val generated = StageGenerator.generate(stage)
        stageNumber = generated.stageNumber
        isBossStage = generated.isBossStage
        trackLength = generated.trackLength

        obstacles.clear()
        obstacles.addAll(generated.obstacles)
        collectibles.clear()
        collectibles.addAll(generated.collectibles)
        multiplierWalls.clear()
        multiplierWalls.addAll(generated.multiplierWalls)
        debris.clear()
        callouts.clear()
        boss = generated.boss

        sliderValue = 0.28f
        currentScale = computeTargetScale(sliderValue)
        lastScaleZone = classifyZone(currentScale)

        playerZ = 0f
        playerY = 0f
        jumpStartZ = -1f
        jumpEndZ = -1f
        runCyclePhase = 0f
        hasShield = false
        invulnerableTimer = 0f
        comboCount = 0
        overdriveMeter = 0f
        overdriveTimer = 0f
        momentumScore = 36f + (profile.titanMassLevel - 1) * 6f
        stageCoins = 0
        earnedMultiplier = 1.0f
        finalStageReward = 0
        rewardCommitted = false
        cameraShake = 0f
        finishStopTimer = 0f
        failReasonVi = ""
        failReasonEn = ""

        phase = if (enterRunning) GamePhase.RUNNING else GamePhase.HOME
        publishSnapshot()
    }

    @Synchronized
    fun startRun() {
        if (phase == GamePhase.HOME || phase == GamePhase.GAME_OVER || phase == GamePhase.STAGE_CLEAR) {
            prepareStage(profile.currentStage, enterRunning = true)
        }
    }

    @Synchronized
    fun returnHome() {
        prepareStage(profile.currentStage, enterRunning = false)
    }

    @Synchronized
    fun setSliderNormalized(normalized: Float) {
        sliderValue = normalized.coerceIn(0f, 1f)
        if (phase == GamePhase.HOME) {
            currentScale = computeTargetScale(sliderValue)
        }
    }

    @Synchronized
    fun adjustSliderByDelta(deltaNormalized: Float) {
        setSliderNormalized(sliderValue + deltaNormalized)
    }

    @Synchronized
    fun onBossTapBoost() {
        val currentBoss = boss ?: return
        if (phase != GamePhase.BOSS_CLASH || currentBoss.bossDefeated) return
        val newProgress = (currentBoss.clashProgress + 6.8f).coerceAtMost(100f)
        boss = currentBoss.copy(clashProgress = newProgress)
        cameraShake = (cameraShake + 0.22f).coerceAtMost(0.8f)
        spawnBurst(
            x = 0f,
            y = 2.0f,
            z = playerZ + 3.2f,
            count = 5,
            color = RgbColor(1f, 0.78f, 0.15f),
            speed = 8f
        )
        onEvent(GameEvent.BOSS_CLASH_HIT)
    }

    @Synchronized
    fun consumePendingVictoryResult(): Pair<Int, Int>? {
        if (phase != GamePhase.STAGE_CLEAR || rewardCommitted) return null
        rewardCommitted = true
        return Pair(finalStageReward, maxComboReached)
    }

    @Synchronized
    fun step(rawDt: Float) {
        val dt = rawDt.coerceIn(0.001f, 0.05f)
        simTime += dt

        // Smooth scale interpolation
        val targetScale = computeTargetScale(sliderValue)
        val morphRate = UpgradeType.morphResponsiveness(profile.agileFluxLevel)
        val blend = 1f - exp(-morphRate * dt)
        currentScale += (targetScale - currentScale) * blend

        val newZone = classifyZone(currentScale)
        if (newZone != lastScaleZone && (phase == GamePhase.RUNNING || phase == GamePhase.GOAL_GAUNTLET)) {
            lastScaleZone = newZone
            onEvent(GameEvent.SCALE_ZONE_TICK)
        } else {
            lastScaleZone = newZone
        }

        // Decay camera shake, callouts, and 3D debris
        cameraShake = (cameraShake - dt * 2.2f).coerceAtLeast(0f)
        invulnerableTimer = (invulnerableTimer - dt).coerceAtLeast(0f)
        updateDebris(dt)
        updateCallouts(dt)

        when (phase) {
            GamePhase.HOME -> {
                homeOrbitAngle += dt * 0.65f
                runCyclePhase += dt * 4.2f
            }

            GamePhase.RUNNING -> stepRunning(dt)
            GamePhase.GOAL_GAUNTLET -> stepGoalGauntlet(dt)
            GamePhase.BOSS_CLASH -> stepBossClash(dt)
            GamePhase.STAGE_CLEAR, GamePhase.GAME_OVER -> {
                // Keep subtle idle animation & flying boss/debris settling
                boss?.let { b ->
                    if (b.bossDefeated && b.bossFlyOffsetZ < 65f) {
                        boss = b.copy(
                            bossFlyOffsetZ = b.bossFlyOffsetZ + dt * 42f,
                            bossFlyHeight = sin((b.bossFlyOffsetZ / 65f).coerceIn(0f, 1f) * PI.toFloat()) * 11f
                        )
                    }
                }
            }
        }

        publishSnapshot()
    }

    private fun stepRunning(dt: Float) {
        if (overdriveTimer > 0f) {
            overdriveTimer = (overdriveTimer - dt).coerceAtLeast(0f)
        }

        // Micro runs faster & lighter; Titan runs slightly heavier with longer strides
        val scaleSpeedFactor = (1.28f - (currentScale - 0.35f) * 0.14f).coerceIn(0.84f, 1.30f)
        val overdriveBoost = if (overdriveTimer > 0f) 1.28f else 1.0f
        val upgradeSpeed = UpgradeType.speedBonusFactor(profile.agileFluxLevel)
        val forwardSpeed = 12.0f * scaleSpeedFactor * overdriveBoost * upgradeSpeed

        playerZ += forwardSpeed * dt
        val strideFreq = (forwardSpeed / currentScale.coerceAtLeast(0.55f)).coerceIn(5.5f, 20f)
        runCyclePhase += strideFreq * dt

        // Parabolic jump arc when crossing chasm gap
        if (jumpEndZ > jumpStartZ && playerZ in jumpStartZ..jumpEndZ) {
            val t = ((playerZ - jumpStartZ) / (jumpEndZ - jumpStartZ)).coerceIn(0f, 1f)
            playerY = sin(t * PI.toFloat()) * 2.9f
        } else {
            playerY = 0f
        }

        // Update patrolling obstacles
        for (i in obstacles.indices) {
            val obs = obstacles[i]
            if (obs.type == ObstacleType.RIVAL_ENFORCER && !obs.shatteredOrSmashed) {
                val latX = sin(simTime * 2.8f + obs.phaseOffset) * 1.15f
                obstacles[i] = obs.copy(lateralX = latX)
            }
        }

        checkCollectibles()
        checkObstacles()

        if (phase == GamePhase.RUNNING && playerZ >= trackLength) {
            playerY = 0f
            if (isBossStage && boss != null) {
                phase = GamePhase.BOSS_CLASH
                pushCallout(
                    vi = "ĐẤU TRÙM TITAN!",
                    en = "BOSS CLASH!",
                    sub = if (profile.language == AppLanguage.VI) "Giữ thanh trượt vùng Vàng & Chạm liên tục!" else "Hold Sweet Spot & Tap!",
                    colorArgb = 0xFFFFB300.toInt()
                )
            } else {
                phase = GamePhase.GOAL_GAUNTLET
                pushCallout(
                    vi = "PHÁ ĐẢO NHÂN THƯỞNG!",
                    en = "GOAL SURGE!",
                    sub = if (profile.language == AppLanguage.VI) "Phóng to tối đa để đâm xuyên tường!" else "Grow Giant to smash walls!",
                    colorArgb = 0xFF00E5FF.toInt()
                )
            }
        }
    }

    private fun checkCollectibles() {
        val reachHeight = currentScale * 2.15f + playerY + 0.55f
        for (i in collectibles.indices) {
            val orb = collectibles[i]
            if (orb.collected) continue
            if (abs(playerZ - orb.z) <= 1.65f && orb.y <= reachHeight + 0.45f) {
                collectibles[i] = orb.copy(collected = true)
                if (orb.isShieldCrystal) {
                    hasShield = true
                    pushCallout(
                        vi = "KHIÊN NĂNG LƯỢNG!",
                        en = "SHIELD ACTIVE!",
                        colorArgb = 0xFF00E5FF.toInt()
                    )
                    onEvent(GameEvent.SHIELD_GAIN)
                } else {
                    stageCoins += 5
                    momentumScore += 1.6f
                    onEvent(GameEvent.COIN_PICKUP)
                }
            }
        }
    }

    private fun checkObstacles() {
        val overdrive = overdriveTimer > 0f

        for (i in obstacles.indices) {
            val obs = obstacles[i]
            if (obs.resolved) continue

            when (obs.type) {
                ObstacleType.FRAGILE_GLASS_BRIDGE -> {
                    if (playerZ >= obs.zStart && playerZ <= obs.zEnd) {
                        if (!overdrive && currentScale > obs.maxSafeScale && invulnerableTimer <= 0f) {
                            obstacles[i] = obs.copy(resolved = true, shatteredOrSmashed = true)
                            spawnBurst(
                                x = 0f,
                                y = 0.1f,
                                z = playerZ + 1.0f,
                                count = 16,
                                color = RgbColor(0.55f, 0.92f, 1.0f),
                                speed = 11f
                            )
                            onEvent(GameEvent.GLASS_SHATTER)
                            handleObstacleFailure(
                                vi = "Quá nặng! Cầu kính đã vỡ tan!",
                                en = "Too heavy! You shattered the glass bridge!"
                            )
                            return
                        }
                    } else if (playerZ > obs.zEnd) {
                        obstacles[i] = obs.copy(resolved = true)
                        rewardCleanClear(
                            vi = "BƯỚC NHẸ TÊNH!",
                            en = "FEATHER STEP!",
                            bonusCoins = 12,
                            momentumGain = 14f,
                            overdriveGain = 0.24f,
                            colorArgb = 0xFF40C4FF.toInt()
                        )
                        onEvent(GameEvent.GLASS_SAFE)
                    }
                }

                ObstacleType.ABYSS_CHASM_GAP -> {
                    if (playerZ >= obs.zStart - 0.6f) {
                        if (overdrive || currentScale >= obs.minSafeScale) {
                            obstacles[i] = obs.copy(resolved = true)
                            jumpStartZ = obs.zStart - 0.8f
                            jumpEndZ = obs.zEnd + 1.2f
                            rewardCleanClear(
                                vi = "BƯỚC NHẢY KHỔNG LỒ!",
                                en = "GIANT LEAP!",
                                bonusCoins = 15,
                                momentumGain = 16f,
                                overdriveGain = 0.25f,
                                colorArgb = 0xFFFFAB00.toInt()
                            )
                            onEvent(GameEvent.GIANT_LEAP)
                        } else if (invulnerableTimer <= 0f) {
                            obstacles[i] = obs.copy(resolved = true)
                            handleObstacleFailure(
                                vi = "Quá nhỏ! Không đủ sải chân nhảy qua vực!",
                                en = "Too small to leap across the chasm!"
                            )
                            return
                        } else {
                            obstacles[i] = obs.copy(resolved = true)
                            jumpStartZ = obs.zStart
                            jumpEndZ = obs.zEnd + 1.0f
                        }
                    }
                }

                ObstacleType.LOW_LASER_GATE -> {
                    if (playerZ >= obs.zStart) {
                        obstacles[i] = obs.copy(resolved = true, shatteredOrSmashed = overdrive)
                        if (overdrive || currentScale <= obs.maxSafeScale) {
                            rewardCleanClear(
                                vi = "LUỒN LÁCH SIÊU TỐC!",
                                en = "SLIP UNDER!",
                                bonusCoins = 10,
                                momentumGain = 13f,
                                overdriveGain = 0.24f,
                                colorArgb = 0xFF00F5D4.toInt()
                            )
                            onEvent(GameEvent.SLIP_UNDER)
                        } else if (invulnerableTimer <= 0f) {
                            handleObstacleFailure(
                                vi = "Đụng trúng cổng Laser tầm thấp! Hãy thu nhỏ lại!",
                                en = "Hit the low plasma gate! Shrink down!"
                            )
                            return
                        }
                    }
                }

                ObstacleType.MOVING_PISTON_GATE -> {
                    if (playerZ >= obs.zStart) {
                        val openFactor = 0.5f + 0.5f * sin(simTime * 3.1f + obs.phaseOffset)
                        val dynamicMaxSafe = if (openFactor > 0.65f) 1.90f else obs.maxSafeScale
                        obstacles[i] = obs.copy(resolved = true, shatteredOrSmashed = overdrive)
                        if (overdrive || currentScale <= dynamicMaxSafe) {
                            rewardCleanClear(
                                vi = "LÁCH PISTON CHUẨN!",
                                en = "TIMED DODGE!",
                                bonusCoins = 14,
                                momentumGain = 15f,
                                overdriveGain = 0.26f,
                                colorArgb = 0xFF00E676.toInt()
                            )
                            onEvent(GameEvent.SLIP_UNDER)
                        } else if (invulnerableTimer <= 0f) {
                            handleObstacleFailure(
                                vi = "Bị cổng Piston ép trúng! Hãy thu nhỏ kịp lúc!",
                                en = "Crushed by the moving piston gate!"
                            )
                            return
                        }
                    }
                }

                ObstacleType.HEAVY_BRICK_WALL -> {
                    if (playerZ >= obs.zStart - 0.5f) {
                        if (overdrive || currentScale >= obs.minSafeScale) {
                            obstacles[i] = obs.copy(resolved = true, shatteredOrSmashed = true)
                            cameraShake = 0.65f
                            spawnBurst(
                                x = 0f,
                                y = 1.8f,
                                z = obs.zStart + 1.0f,
                                count = 16,
                                color = RgbColor(0.95f, 0.42f, 0.18f),
                                speed = 15f
                            )
                            rewardCleanClear(
                                vi = "HỦY DIỆT TƯỜNG CHẮN!",
                                en = "TITAN SMASH!",
                                bonusCoins = 16,
                                momentumGain = 18f,
                                overdriveGain = 0.28f,
                                colorArgb = 0xFFFF6D00.toInt()
                            )
                            onEvent(GameEvent.WALL_SMASH)
                        } else if (invulnerableTimer <= 0f) {
                            obstacles[i] = obs.copy(resolved = true)
                            handleObstacleFailure(
                                vi = "Chưa đủ to để phá tường gạch! Hãy phóng đại Titan!",
                                en = "Too small to smash the heavy brick wall!"
                            )
                            return
                        } else {
                            obstacles[i] = obs.copy(resolved = true, shatteredOrSmashed = true)
                        }
                    }
                }

                ObstacleType.PRECISION_SYNC_ARCH -> {
                    if (playerZ >= obs.zStart) {
                        obstacles[i] = obs.copy(resolved = true)
                        val matched = currentScale in obs.minSafeScale..obs.maxSafeScale
                        if (overdrive || matched) {
                            spawnBurst(
                                x = 0f,
                                y = 1.6f,
                                z = obs.zStart,
                                count = 10,
                                color = RgbColor(0.25f, 1.0f, 0.55f),
                                speed = 9f
                            )
                            rewardCleanClear(
                                vi = "ĐỒNG BỘ HOÀN HẢO!",
                                en = "PERFECT SYNC!",
                                bonusCoins = 22,
                                momentumGain = 20f,
                                overdriveGain = 0.34f,
                                colorArgb = 0xFF00E676.toInt()
                            )
                            onEvent(GameEvent.PERFECT_SYNC)
                        } else if (invulnerableTimer <= 0f) {
                            handleObstacleFailure(
                                vi = "Sai kích cỡ cổng Đồng Bộ (%.1fx - %.1fx)!".format(obs.minSafeScale, obs.maxSafeScale),
                                en = "Missed Sync Arch size window (%.1fx - %.1fx)!".format(obs.minSafeScale, obs.maxSafeScale)
                            )
                            return
                        }
                    }
                }

                ObstacleType.RIVAL_ENFORCER -> {
                    if (playerZ >= obs.zStart - 0.4f) {
                        if (overdrive || currentScale >= obs.minSafeScale) {
                            obstacles[i] = obs.copy(resolved = true, shatteredOrSmashed = true)
                            cameraShake = 0.55f
                            spawnBurst(
                                x = obs.lateralX,
                                y = 1.5f,
                                z = obs.zStart + 0.5f,
                                count = 14,
                                color = RgbColor(0.95f, 0.22f, 0.42f),
                                speed = 14f
                            )
                            rewardCleanClear(
                                vi = "HẤT TUNG ĐỐI THỦ!",
                                en = "RIVAL CRUSHED!",
                                bonusCoins = 20,
                                momentumGain = 19f,
                                overdriveGain = 0.30f,
                                colorArgb = 0xFFFF1744.toInt()
                            )
                            onEvent(GameEvent.RIVAL_CRUSH)
                        } else if (invulnerableTimer <= 0f) {
                            obstacles[i] = obs.copy(resolved = true)
                            handleObstacleFailure(
                                vi = "Bị Vệ Binh hạng nặng húc văng! Hãy hóa Khổng Lồ!",
                                en = "Knocked out by Armored Enforcer! Grow Giant!"
                            )
                            return
                        } else {
                            obstacles[i] = obs.copy(resolved = true, shatteredOrSmashed = true)
                        }
                    }
                }
            }
        }
    }

    private fun rewardCleanClear(
        vi: String,
        en: String,
        bonusCoins: Int,
        momentumGain: Float,
        overdriveGain: Float,
        colorArgb: Int
    ) {
        comboCount += 1
        if (comboCount > maxComboReached) {
            maxComboReached = comboCount
        }
        val comboBonus = (comboCount - 1).coerceAtMost(8) * 2
        val earned = bonusCoins + comboBonus
        stageCoins += earned
        momentumScore += momentumGain + comboCount * 1.5f

        if (overdriveTimer <= 0f) {
            overdriveMeter = (overdriveMeter + overdriveGain).coerceAtMost(1f)
            if (overdriveMeter >= 1f) {
                overdriveMeter = 0f
                overdriveTimer = 4.6f
                cameraShake = 0.5f
                pushCallout(
                    vi = "SIÊU BÙNG NỔ OVERDRIVE!",
                    en = "HYPER OVERDRIVE!",
                    sub = if (profile.language == AppLanguage.VI) "4.5s BẤT TỬ & HỦY DIỆT" else "4.5s INVINCIBLE SMASH",
                    colorArgb = 0xFFFFD600.toInt()
                )
                onEvent(GameEvent.OVERDRIVE_ACTIVATED)
                return
            }
        }

        pushCallout(
            vi = vi,
            en = en,
            sub = if (profile.language == AppLanguage.VI) "+$earned Xu  •  Combo x$comboCount" else "+$earned Coins  •  Combo x$comboCount",
            colorArgb = colorArgb
        )
    }

    private fun handleObstacleFailure(vi: String, en: String) {
        if (hasShield) {
            hasShield = false
            invulnerableTimer = 1.15f
            comboCount = 0
            cameraShake = 0.55f
            spawnBurst(
                x = 0f,
                y = 1.3f,
                z = playerZ,
                count = 12,
                color = RgbColor(0.0f, 0.88f, 1.0f),
                speed = 11f
            )
            pushCallout(
                vi = "KHIÊN ĐỠ ĐÒN!",
                en = "SHIELD SAVED YOU!",
                colorArgb = 0xFF00E5FF.toInt()
            )
            onEvent(GameEvent.SHIELD_BLOCK)
            return
        }

        failReasonVi = vi
        failReasonEn = en
        comboCount = 0
        cameraShake = 0.85f
        spawnBurst(
            x = 0f,
            y = 1.2f,
            z = playerZ,
            count = 18,
            color = RgbColor(1.0f, 0.25f, 0.30f),
            speed = 12f
        )
        phase = GamePhase.GAME_OVER
        onEvent(GameEvent.PLAYER_DEFEAT)
    }

    private fun stepGoalGauntlet(dt: Float) {
        val dashSpeed = 24f
        playerZ += dashSpeed * dt
        runCyclePhase += 14f * dt

        // Being in Titan zone during Goal Gauntlet adds extra breakthrough force!
        val liveScaleForce = (currentScale / 2.2f).coerceIn(0.65f, 1.45f)
        val effectivePower = momentumScore * liveScaleForce

        for (i in multiplierWalls.indices) {
            val wall = multiplierWalls[i]
            if (wall.broken) continue
            if (playerZ >= wall.z - 0.6f) {
                if (effectivePower >= wall.requiredMomentum || wall.index == 0) {
                    multiplierWalls[i] = wall.copy(broken = true)
                    earnedMultiplier = wall.multiplier
                    cameraShake = 0.55f
                    spawnBurst(
                        x = 0f,
                        y = 2.0f,
                        z = wall.z,
                        count = 16,
                        color = wall.color,
                        speed = 16f
                    )
                    pushCallout(
                        vi = "PHÁ MỐC x%.1f!".format(wall.multiplier),
                        en = "SMASHED x%.1f!".format(wall.multiplier),
                        colorArgb = wall.color.toArgbInt()
                    )
                    onEvent(GameEvent.MULTIPLIER_BREAK)

                    if (wall.index == multiplierWalls.lastIndex) {
                        completeStageVictory()
                        return
                    }
                } else {
                    finishStopTimer += dt
                    completeStageVictory()
                    return
                }
            }
        }

        val lastWallZ = multiplierWalls.lastOrNull()?.z ?: (trackLength + 20f)
        if (playerZ > lastWallZ + 6f) {
            completeStageVictory()
        }
    }

    private fun stepBossClash(dt: Float) {
        val currentBoss = boss ?: run {
            completeStageVictory()
            return
        }

        // Move player into arena confrontation spot
        val arenaPlayerZ = currentBoss.bossZ - 6.5f
        if (playerZ < arenaPlayerZ) {
            playerZ = (playerZ + 22f * dt).coerceAtMost(arenaPlayerZ)
            runCyclePhase += 11f * dt
            return
        }

        val sweetCenter = (0.68f + 0.24f * sin(simTime * 2.4f)).coerceIn(0.35f, 0.95f)
        val distFromSweet = abs(sliderValue - sweetCenter)
        val inSweetSpot = distFromSweet <= 0.17f

        val titanBonus = (profile.titanMassLevel - 1) * 1.6f
        val deltaProgress = if (inSweetSpot) {
            (16.5f + titanBonus) * dt
        } else {
            -7.5f * dt
        }

        val updatedProgress = (currentBoss.clashProgress + deltaProgress).coerceIn(0f, 100f)
        val updatedTime = (currentBoss.timeRemaining - dt).coerceAtLeast(0f)

        boss = currentBoss.copy(
            clashProgress = updatedProgress,
            timeRemaining = updatedTime,
            sweetSpotSliderCenter = sweetCenter
        )

        if (updatedProgress >= 100f) {
            earnedMultiplier = 10.0f
            boss = boss?.copy(bossDefeated = true)
            cameraShake = 1.0f
            spawnBurst(
                x = 0f,
                y = 2.6f,
                z = currentBoss.bossZ,
                count = 28,
                color = RgbColor(1.0f, 0.72f, 0.12f),
                speed = 20f
            )
            pushCallout(
                vi = "HẠ GỤC TRÙM KHỔNG LỒ! x10!",
                en = "BOSS OBLITERATED! x10!",
                colorArgb = 0xFFFFD600.toInt()
            )
            completeStageVictory()
        } else if (updatedTime <= 0f) {
            if (updatedProgress >= 42f) {
                earnedMultiplier = (3.0f + (updatedProgress / 100f) * 5.0f)
                boss = boss?.copy(bossDefeated = true)
                cameraShake = 0.8f
                spawnBurst(
                    x = 0f,
                    y = 2.4f,
                    z = currentBoss.bossZ,
                    count = 22,
                    color = RgbColor(0.2f, 0.95f, 1.0f),
                    speed = 16f
                )
                completeStageVictory()
            } else {
                failReasonVi = "Trùm Mecha áp đảo sức mạnh! Hãy giữ thanh trượt ở vùng Vàng & chạm nhanh!"
                failReasonEn = "Overpowered by the Boss! Keep slider in the Gold Zone & tap rapidly!"
                phase = GamePhase.GAME_OVER
                onEvent(GameEvent.PLAYER_DEFEAT)
            }
        }
    }

    private fun completeStageVictory() {
        val baseStageBonus = 35 + stageNumber * 8
        val rawCoins = stageCoins + baseStageBonus
        val goldFactor = UpgradeType.coinBonusFactor(profile.goldBountyLevel)
        finalStageReward = (rawCoins * earnedMultiplier * goldFactor).roundToInt().coerceAtLeast(25)
        phase = GamePhase.STAGE_CLEAR
        onEvent(GameEvent.STAGE_VICTORY)
    }

    private fun spawnBurst(
        x: Float,
        y: Float,
        z: Float,
        count: Int,
        color: RgbColor,
        speed: Float
    ) {
        repeat(count) {
            val angle = fxRng.nextFloat() * (2f * PI.toFloat())
            val horiz = (0.3f + fxRng.nextFloat() * 0.7f) * speed
            debris += DebrisPiece(
                x = x + (fxRng.nextFloat() - 0.5f) * 1.4f,
                y = y + fxRng.nextFloat() * 1.1f,
                z = z + (fxRng.nextFloat() - 0.5f) * 1.0f,
                vx = cos(angle) * horiz,
                vy = (0.45f + fxRng.nextFloat() * 0.75f) * speed,
                vz = sin(angle) * horiz + 6f,
                rotX = fxRng.nextFloat() * 360f,
                rotY = fxRng.nextFloat() * 360f,
                size = 0.22f + fxRng.nextFloat() * 0.28f,
                color = color,
                life = 0.85f + fxRng.nextFloat() * 0.45f
            )
        }
        if (debris.size > 90) {
            debris.subList(0, debris.size - 90).clear()
        }
    }

    private fun updateDebris(dt: Float) {
        val iter = debris.listIterator()
        while (iter.hasNext()) {
            val d = iter.next()
            val nextLife = d.life - dt
            if (nextLife <= 0f) {
                iter.remove()
            } else {
                var ny = d.y + d.vy * dt
                var nvy = d.vy - 26f * dt
                if (ny < 0.12f) {
                    ny = 0.12f
                    nvy = abs(nvy) * 0.42f
                }
                iter.set(
                    d.copy(
                        x = d.x + d.vx * dt,
                        y = ny,
                        z = d.z + d.vz * dt,
                        vy = nvy,
                        rotX = d.rotX + 240f * dt,
                        rotY = d.rotY + 190f * dt,
                        life = nextLife
                    )
                )
            }
        }
    }

    private fun updateCallouts(dt: Float) {
        val iter = callouts.listIterator()
        while (iter.hasNext()) {
            val c = iter.next()
            val nextAge = c.age + dt
            if (nextAge >= c.duration) {
                iter.remove()
            } else {
                iter.set(c.copy(age = nextAge))
            }
        }
    }

    private fun pushCallout(
        vi: String,
        en: String,
        sub: String = "",
        colorArgb: Int
    ) {
        val text = if (profile.language == AppLanguage.VI) vi else en
        callouts += FloatingCallout(
            id = nextCalloutId++,
            text = text,
            subText = sub,
            colorArgb = colorArgb
        )
        if (callouts.size > 3) {
            callouts.removeAt(0)
        }
    }

    private fun computeTargetScale(slider: Float): Float {
        val minS = UpgradeType.MIN_PLAYER_SCALE
        val maxS = UpgradeType.maxScaleForLevel(profile.titanMassLevel)
        return minS + (maxS - minS) * slider.coerceIn(0f, 1f)
    }

    private fun classifyZone(scale: Float): ScaleZone = when {
        scale < 0.88f -> ScaleZone.MICRO
        scale < 1.68f -> ScaleZone.SYNC
        else -> ScaleZone.TITAN
    }

    private fun computeUpcomingHints(): Pair<String, String> {
        val next = obstacles.firstOrNull { !it.resolved && it.zEnd >= playerZ && it.zStart - playerZ <= 30f }
            ?: return Pair("", "")
        return when (next.type) {
            ObstacleType.LOW_LASER_GATE -> Pair("⬇ THU NHỎ (< 0.88x) để chui dưới Laser!", "⬇ SHRINK (< 0.88x) under Laser!")
            ObstacleType.HEAVY_BRICK_WALL -> Pair("⬆ HÓA TITAN (> 1.7x) đâm vỡ tường gạch!", "⬆ GROW TITAN (> 1.7x) to smash wall!")
            ObstacleType.FRAGILE_GLASS_BRIDGE -> Pair("⬇ GIỮ NHẸ (< 1.2x) qua cầu kính!", "⬇ STAY LIGHT (< 1.2x) on glass bridge!")
            ObstacleType.ABYSS_CHASM_GAP -> Pair("⬆ HÓA KHỔNG LỒ (> 1.8x) nhảy qua vực!", "⬆ GROW GIANT (> 1.8x) to leap chasm!")
            ObstacleType.PRECISION_SYNC_ARCH -> Pair(
                "🎯 CĂN CỠ %.1fx – %.1fx qua Cổng Đồng Bộ!".format(next.minSafeScale, next.maxSafeScale),
                "🎯 MATCH %.1fx – %.1fx for Sync Arch!".format(next.minSafeScale, next.maxSafeScale)
            )
            ObstacleType.RIVAL_ENFORCER -> Pair("⬆ HÓA TITAN (> 1.8x) hất tung Vệ Binh!", "⬆ GROW TITAN (> 1.8x) to crush Enforcer!")
            ObstacleType.MOVING_PISTON_GATE -> Pair("⚡ Canh Piston nâng lên hoặc THU NHỎ!", "⚡ Time the Piston or SHRINK tiny!")
        }
    }

    private fun publishSnapshot() {
        _snapshot.value = buildSnapshot()
    }

    private fun buildSnapshot(): GameSnapshot {
        val maxS = UpgradeType.maxScaleForLevel(profile.titanMassLevel)
        val (hintVi, hintEn) = computeUpcomingHints()
        return GameSnapshot(
            phase = phase,
            stageNumber = stageNumber,
            isBossStage = isBossStage,
            sliderValue = sliderValue,
            currentScale = currentScale,
            targetScale = computeTargetScale(sliderValue),
            minScale = UpgradeType.MIN_PLAYER_SCALE,
            maxScale = maxS,
            scaleZone = classifyZone(currentScale),
            playerZ = playerZ,
            playerY = playerY,
            trackLength = trackLength,
            progress = (playerZ / trackLength.coerceAtLeast(1f)).coerceIn(0f, 1f),
            runCyclePhase = runCyclePhase,
            hasShield = hasShield,
            invulnerableTimer = invulnerableTimer,
            comboCount = comboCount,
            overdriveMeter = overdriveMeter,
            overdriveTimer = overdriveTimer,
            momentumScore = momentumScore,
            stageCoins = stageCoins,
            earnedMultiplier = earnedMultiplier,
            finalStageReward = finalStageReward,
            totalCoins = profile.coins,
            cameraShake = cameraShake,
            homeOrbitAngle = homeOrbitAngle,
            activeSkin = SkinCatalog.byId(profile.selectedSkinId),
            obstacles = obstacles.toList(),
            collectibles = collectibles.toList(),
            multiplierWalls = multiplierWalls.toList(),
            debris = debris.toList(),
            callouts = callouts.toList(),
            boss = boss,
            upcomingHintVi = hintVi,
            upcomingHintEn = hintEn,
            failReasonVi = failReasonVi,
            failReasonEn = failReasonEn
        )
    }
}
