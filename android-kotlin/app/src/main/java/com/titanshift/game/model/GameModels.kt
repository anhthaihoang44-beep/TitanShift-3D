package com.titanshift.game.model

import kotlin.math.pow
import kotlin.math.roundToInt

enum class AppLanguage {
    VI, EN
}

enum class ScaleZone {
    MICRO,   // 0.35x .. 0.85x (Fast, agile, light, fits under low gates & crosses fragile glass)
    SYNC,    // 0.85x .. 1.68x (Balanced stride & precision sync gates)
    TITAN    // 1.68x .. 3.40x (Heavy giant, smashes walls/rivals & leaps chasms)
}

enum class CrestStyle {
    FIN,
    HORNS,
    VISOR,
    HALO,
    CROWN,
    SOVEREIGN
}

data class RgbColor(val r: Float, val g: Float, val b: Float) {
    fun toArgbInt(alpha: Float = 1f): Int {
        val a = (alpha.coerceIn(0f, 1f) * 255f).roundToInt()
        val ri = (r.coerceIn(0f, 1f) * 255f).roundToInt()
        val gi = (g.coerceIn(0f, 1f) * 255f).roundToInt()
        val bi = (b.coerceIn(0f, 1f) * 255f).roundToInt()
        return (a shl 24) or (ri shl 16) or (gi shl 8) or bi
    }
}

data class HeroSkin(
    val id: String,
    val nameVi: String,
    val nameEn: String,
    val descVi: String,
    val descEn: String,
    val primaryArmor: RgbColor,
    val secondaryTrim: RgbColor,
    val coreGlow: RgbColor,
    val crestStyle: CrestStyle,
    val coinCost: Int,
    val unlockStageRequirement: Int = 1
) {
    fun displayName(lang: AppLanguage): String = if (lang == AppLanguage.VI) nameVi else nameEn
    fun displayDesc(lang: AppLanguage): String = if (lang == AppLanguage.VI) descVi else descEn
}

object SkinCatalog {
    val skins: List<HeroSkin> = listOf(
        HeroSkin(
            id = "vanguard_pulse",
            nameVi = "Chiến Binh Xung Điện",
            nameEn = "Vanguard Pulse",
            descVi = "Giáp Titan tiêu chuẩn với lõi năng lượng Cyan.",
            descEn = "Standard bio-suit with electric cyan morph core.",
            primaryArmor = RgbColor(0.18f, 0.42f, 0.98f),
            secondaryTrim = RgbColor(0.88f, 0.94f, 1.0f),
            coreGlow = RgbColor(0.0f, 0.92f, 1.0f),
            crestStyle = CrestStyle.FIN,
            coinCost = 0,
            unlockStageRequirement = 1
        ),
        HeroSkin(
            id = "solar_striker",
            nameVi = "Hỏa Thần Mặt Trời",
            nameEn = "Solar Striker",
            descVi = "Sừng chiến giáp đỏ rực và hào quang vàng nóng chảy.",
            descEn = "Twin battle horns forged in crimson and molten gold.",
            primaryArmor = RgbColor(0.92f, 0.18f, 0.22f),
            secondaryTrim = RgbColor(1.0f, 0.76f, 0.12f),
            coreGlow = RgbColor(1.0f, 0.62f, 0.05f),
            crestStyle = CrestStyle.HORNS,
            coinCost = 250,
            unlockStageRequirement = 1
        ),
        HeroSkin(
            id = "venom_flux",
            nameVi = "Bóng Ma Neon",
            nameEn = "Venom Flux",
            descVi = "Giáp đen Obsidian kết hợp kính chiến thuật xanh lục.",
            descEn = "Obsidian stealth chassis with cyber-lime visor.",
            primaryArmor = RgbColor(0.13f, 0.16f, 0.22f),
            secondaryTrim = RgbColor(0.22f, 0.98f, 0.46f),
            coreGlow = RgbColor(0.35f, 1.0f, 0.25f),
            crestStyle = CrestStyle.VISOR,
            coinCost = 550,
            unlockStageRequirement = 2
        ),
        HeroSkin(
            id = "astral_phantom",
            nameVi = "Ảo Ảnh Tinh Vân",
            nameEn = "Astral Phantom",
            descVi = "Vòng hào quang không gian tím biếc và giáp bạc ánh sao.",
            descEn = "Floating astral halo over ultraviolet starlight armor.",
            primaryArmor = RgbColor(0.52f, 0.20f, 0.92f),
            secondaryTrim = RgbColor(0.92f, 0.90f, 1.0f),
            coreGlow = RgbColor(0.88f, 0.35f, 1.0f),
            crestStyle = CrestStyle.HALO,
            coinCost = 1000,
            unlockStageRequirement = 3
        ),
        HeroSkin(
            id = "glacier_titan",
            nameVi = "Khổng Lồ Băng Giá",
            nameEn = "Glacier Titan",
            descVi = "Vương miện băng vĩnh cửu mở khóa từ Boss Màn 5 hoặc Xu.",
            descEn = "Frost crystal crown unlocked via Stage 5 Boss or Coins.",
            primaryArmor = RgbColor(0.76f, 0.92f, 1.0f),
            secondaryTrim = RgbColor(0.12f, 0.62f, 0.95f),
            coreGlow = RgbColor(0.35f, 0.96f, 1.0f),
            crestStyle = CrestStyle.CROWN,
            coinCost = 1500,
            unlockStageRequirement = 5
        ),
        HeroSkin(
            id = "omega_sovereign",
            nameVi = "Đế Vương Omega",
            nameEn = "Omega Sovereign",
            descVi = "Giáp tối thượng đen-vàng hồng ngọc dành cho bậc thầy biến hình.",
            descEn = "Ultimate black, gold, and magenta sovereign armor.",
            primaryArmor = RgbColor(0.10f, 0.08f, 0.14f),
            secondaryTrim = RgbColor(1.0f, 0.80f, 0.18f),
            coreGlow = RgbColor(1.0f, 0.15f, 0.68f),
            crestStyle = CrestStyle.SOVEREIGN,
            coinCost = 2400,
            unlockStageRequirement = 10
        )
    )

    fun byId(id: String): HeroSkin = skins.firstOrNull { it.id == id } ?: skins.first()
}

enum class UpgradeType {
    TITAN_MASS,   // Boosts max scale & smash momentum
    AGILE_FLUX,   // Boosts run speed & morph responsiveness
    GOLD_BOUNTY;  // Multiplies coin earnings

    fun costForLevel(currentLevel: Int): Int {
        val base = when (this) {
            TITAN_MASS -> 90.0
            AGILE_FLUX -> 80.0
            GOLD_BOUNTY -> 100.0
        }
        return (base * 1.42.pow((currentLevel - 1).coerceAtLeast(0))).roundToInt()
    }

    fun title(lang: AppLanguage): String = when (this) {
        TITAN_MASS -> if (lang == AppLanguage.VI) "Sức Nặng Titan" else "Titan Mass"
        AGILE_FLUX -> if (lang == AppLanguage.VI) "Tốc Độ Biến Hình" else "Agile Flux"
        GOLD_BOUNTY -> if (lang == AppLanguage.VI) "Thưởng Vàng" else "Gold Bounty"
    }

    fun subtitle(level: Int, lang: AppLanguage): String = when (this) {
        TITAN_MASS -> {
            val maxS = maxScaleForLevel(level)
            if (lang == AppLanguage.VI) "Cỡ tối đa %.2fx".format(maxS) else "Max Scale %.2fx".format(maxS)
        }
        AGILE_FLUX -> {
            val pct = (speedBonusFactor(level) * 100f - 100f).roundToInt()
            if (lang == AppLanguage.VI) "Tốc độ +$pct%" else "Speed +$pct%"
        }
        GOLD_BOUNTY -> {
            val pct = (coinBonusFactor(level) * 100f - 100f).roundToInt()
            if (lang == AppLanguage.VI) "Xu +$pct%" else "Coins +$pct%"
        }
    }

    companion object {
        const val MIN_PLAYER_SCALE = 0.35f

        fun maxScaleForLevel(level: Int): Float =
            (2.85f + (level - 1).coerceAtLeast(0) * 0.08f).coerceAtMost(3.65f)

        fun speedBonusFactor(level: Int): Float =
            (1.0f + (level - 1).coerceAtLeast(0) * 0.025f).coerceAtMost(1.25f)

        fun morphResponsiveness(level: Int): Float =
            (13.5f + (level - 1).coerceAtLeast(0) * 1.15f).coerceAtMost(26.0f)

        fun coinBonusFactor(level: Int): Float =
            1.0f + (level - 1).coerceAtLeast(0) * 0.15f
    }
}

enum class ObstacleType {
    LOW_LASER_GATE,       // Shrink < 0.88x to slip underneath
    HEAVY_BRICK_WALL,     // Grow >= 1.68x to smash through
    FRAGILE_GLASS_BRIDGE, // Stay <= 1.22x so glass doesn't shatter
    ABYSS_CHASM_GAP,      // Grow >= 1.78x to giant-leap across gap
    PRECISION_SYNC_ARCH,  // Match [minTargetScale..maxTargetScale]
    RIVAL_ENFORCER,       // Moving armored guard; grow >= 1.80x to crush
    MOVING_PISTON_GATE    // Vertically oscillating beam; pass when high or shrink tiny
}

data class ObstacleInstance(
    val id: Int,
    val type: ObstacleType,
    val zStart: Float,
    val zLength: Float,
    val minSafeScale: Float = 0f,
    val maxSafeScale: Float = 10f,
    val phaseOffset: Float = 0f,
    val resolved: Boolean = false,
    val shatteredOrSmashed: Boolean = false,
    val lateralX: Float = 0f
) {
    val zEnd: Float get() = zStart + zLength
}

data class CollectibleOrb(
    val id: Int,
    val z: Float,
    val x: Float,
    val y: Float,
    val isShieldCrystal: Boolean = false,
    val collected: Boolean = false
)

data class MultiplierWall(
    val index: Int,
    val z: Float,
    val multiplier: Float,
    val requiredMomentum: Float,
    val color: RgbColor,
    val broken: Boolean = false
)

data class DebrisPiece(
    val x: Float,
    val y: Float,
    val z: Float,
    val vx: Float,
    val vy: Float,
    val vz: Float,
    val rotX: Float,
    val rotY: Float,
    val size: Float,
    val color: RgbColor,
    val life: Float
)

data class FloatingCallout(
    val id: Int,
    val text: String,
    val subText: String = "",
    val colorArgb: Int,
    val age: Float = 0f,
    val duration: Float = 1.05f
)

data class BossEncounter(
    val maxPower: Float = 100f,
    val clashProgress: Float = 50f, // 0 = defeated, 100 = boss launched!
    val timeRemaining: Float = 4.5f,
    val sweetSpotSliderCenter: Float = 0.78f,
    val bossScale: Float = 3.1f,
    val bossZ: Float = 0f,
    val bossDefeated: Boolean = false,
    val bossFlyOffsetZ: Float = 0f,
    val bossFlyHeight: Float = 0f
)

enum class GamePhase {
    HOME,
    RUNNING,
    GOAL_GAUNTLET,
    BOSS_CLASH,
    STAGE_CLEAR,
    GAME_OVER
}

enum class GameEvent {
    COIN_PICKUP,
    SHIELD_GAIN,
    SCALE_ZONE_TICK,
    SLIP_UNDER,
    WALL_SMASH,
    GLASS_SAFE,
    GLASS_SHATTER,
    GIANT_LEAP,
    PERFECT_SYNC,
    RIVAL_CRUSH,
    SHIELD_BLOCK,
    OVERDRIVE_ACTIVATED,
    BOSS_CLASH_HIT,
    MULTIPLIER_BREAK,
    STAGE_VICTORY,
    PLAYER_DEFEAT
}

data class GameSnapshot(
    val phase: GamePhase = GamePhase.HOME,
    val stageNumber: Int = 1,
    val isBossStage: Boolean = false,
    val sliderValue: Float = 0.30f, // 0f..1f
    val currentScale: Float = 1.0f,
    val targetScale: Float = 1.0f,
    val minScale: Float = UpgradeType.MIN_PLAYER_SCALE,
    val maxScale: Float = 2.85f,
    val scaleZone: ScaleZone = ScaleZone.SYNC,
    val playerZ: Float = 0f,
    val playerY: Float = 0f,
    val trackLength: Float = 220f,
    val progress: Float = 0f,
    val runCyclePhase: Float = 0f,
    val hasShield: Boolean = false,
    val invulnerableTimer: Float = 0f,
    val comboCount: Int = 0,
    val overdriveMeter: Float = 0f, // 0f..1f
    val overdriveTimer: Float = 0f, // > 0f means active!
    val momentumScore: Float = 0f,
    val stageCoins: Int = 0,
    val earnedMultiplier: Float = 1.0f,
    val finalStageReward: Int = 0,
    val totalCoins: Int = 0,
    val cameraShake: Float = 0f,
    val homeOrbitAngle: Float = 0f,
    val activeSkin: HeroSkin = SkinCatalog.skins.first(),
    val obstacles: List<ObstacleInstance> = emptyList(),
    val collectibles: List<CollectibleOrb> = emptyList(),
    val multiplierWalls: List<MultiplierWall> = emptyList(),
    val debris: List<DebrisPiece> = emptyList(),
    val callouts: List<FloatingCallout> = emptyList(),
    val boss: BossEncounter? = null,
    val upcomingHintVi: String = "",
    val upcomingHintEn: String = "",
    val failReasonVi: String = "",
    val failReasonEn: String = ""
) {
    val isOverdriveActive: Boolean get() = overdriveTimer > 0f
}
