import Combine
import Foundation
import SwiftUI

@MainActor
final class GameViewModel: ObservableObject {
    @Published private(set) var profile: PlayerProfileState
    @Published private(set) var snapshot: GameSnapshot
    @Published var showSkinWardrobe: Bool = false
    @Published var bonusClaimed: Bool = false

    private let storage: PlayerStorage
    private let audioHaptics: SoundHapticsService?

    private var simClock: Float = 0
    private var stageRevision: Int = 0
    private var nextCalloutId: Int = 1
    private var nextDebrisId: Int = 1
    private var fxRng = SeededGenerator(seed: 42)
    private var lastScaleZone: ScaleZone = .sync
    private var jumpStartZ: Float = -1
    private var jumpEndZ: Float = -1
    private var maxComboReached: Int = 0

    init(
        storage: PlayerStorage = PlayerStorage(),
        enableAudioHaptics: Bool = true
    ) {
        let loaded = storage.load()
        self.storage = storage
        self.profile = loaded
        self.maxComboReached = loaded.bestCombo
        self.audioHaptics = enableAudioHaptics ? SoundHapticsService() : nil
        self.audioHaptics?.soundEnabled = loaded.soundEnabled
        self.audioHaptics?.hapticsEnabled = loaded.hapticsEnabled
        self.snapshot = GameSnapshot()
        prepareStage(loaded.currentStage, enterRunning: false)
    }

    func prepareStage(_ stage: Int, enterRunning: Bool) {
        let generated = StageGenerator.generate(stageNumber: stage)
        stageRevision += 1
        let minS = UpgradeType.minPlayerScale
        let maxS = UpgradeType.maxScaleForLevel(profile.titanMassLevel)
        let initSlider: Float = 0.28
        let initScale = minS + (maxS - minS) * initSlider
        lastScaleZone = classifyZone(initScale)
        jumpStartZ = -1
        jumpEndZ = -1
        bonusClaimed = false

        snapshot = GameSnapshot(
            stageRevision: stageRevision,
            phase: enterRunning ? .running : .home,
            stageNumber: generated.stageNumber,
            isBossStage: generated.isBossStage,
            sliderValue: initSlider,
            currentScale: initScale,
            targetScale: initScale,
            minScale: minS,
            maxScale: maxS,
            scaleZone: lastScaleZone,
            playerZ: 0,
            playerY: 0,
            trackLength: generated.trackLength,
            progress: 0,
            runCyclePhase: 0,
            hasShield: false,
            invulnerableTimer: 0,
            comboCount: 0,
            overdriveMeter: 0,
            overdriveTimer: 0,
            momentumScore: 36 + Float(profile.titanMassLevel - 1) * 6,
            stageCoins: 0,
            earnedMultiplier: 1.0,
            finalStageReward: 0,
            cameraShake: 0,
            homeOrbitAngle: snapshot.homeOrbitAngle,
            simClock: simClock,
            activeSkin: SkinCatalog.byId(profile.selectedSkinId),
            obstacles: generated.obstacles,
            collectibles: generated.collectibles,
            multiplierWalls: generated.multiplierWalls,
            debris: [],
            callouts: [],
            boss: generated.boss
        )
        refreshUpcomingHints()
    }

    func startRun() {
        if snapshot.phase == .home || snapshot.phase == .gameOver || snapshot.phase == .stageClear {
            prepareStage(profile.currentStage, enterRunning: true)
        }
    }

    func returnHome() {
        prepareStage(profile.currentStage, enterRunning: false)
    }

    func setSliderNormalized(_ value: Float) {
        let clamped = min(max(value, 0), 1)
        snapshot.sliderValue = clamped
        snapshot.targetScale = computeTargetScale(clamped)
        if snapshot.phase == .home {
            snapshot.currentScale = snapshot.targetScale
            snapshot.scaleZone = classifyZone(snapshot.currentScale)
        }
    }

    func adjustSliderByDelta(_ delta: Float) {
        setSliderNormalized(snapshot.sliderValue + delta)
    }

    func onBossTapBoost() {
        guard snapshot.phase == .bossClash, var boss = snapshot.boss, !boss.bossDefeated else { return }
        boss.clashProgress = min(100, boss.clashProgress + 6.8)
        snapshot.boss = boss
        snapshot.cameraShake = min(0.8, snapshot.cameraShake + 0.22)
        spawnBurst(x: 0, y: 2.0, z: snapshot.playerZ + 3.2, count: 5, color: RgbColor(r: 1.0, g: 0.78, b: 0.15), speed: 8)
        emit(.bossClashHit)
    }

    func buyUpgrade(_ type: UpgradeType) {
        let currentLevel: Int
        switch type {
        case .titanMass: currentLevel = profile.titanMassLevel
        case .agileFlux: currentLevel = profile.agileFluxLevel
        case .goldBounty: currentLevel = profile.goldBountyLevel
        }
        let cost = type.costForLevel(currentLevel)
        guard profile.coins >= cost else { return }

        profile.coins -= cost
        switch type {
        case .titanMass: profile.titanMassLevel += 1
        case .agileFlux: profile.agileFluxLevel += 1
        case .goldBounty: profile.goldBountyLevel += 1
        }
        storage.save(profile)
        snapshot.maxScale = UpgradeType.maxScaleForLevel(profile.titanMassLevel)
        setSliderNormalized(snapshot.sliderValue)
        emit(.perfectSync)
    }

    func selectOrUnlockSkin(_ skin: HeroSkin) {
        if profile.unlockedSkinIds.contains(skin.id) {
            profile.selectedSkinId = skin.id
            storage.save(profile)
            snapshot.activeSkin = skin
            emit(.scaleZoneTick)
        } else if profile.coins >= skin.coinCost {
            profile.coins -= skin.coinCost
            profile.unlockedSkinIds.insert(skin.id)
            profile.selectedSkinId = skin.id
            storage.save(profile)
            snapshot.activeSkin = skin
            emit(.perfectSync)
        } else {
            // Live 3D preview while browsing wardrobe
            snapshot.activeSkin = skin
        }
    }

    func restoreEquippedSkinPreview() {
        snapshot.activeSkin = SkinCatalog.byId(profile.selectedSkinId)
    }

    func claimDoubleBonus() {
        guard snapshot.phase == .stageClear, !bonusClaimed else { return }
        bonusClaimed = true
        profile.coins += snapshot.finalStageReward
        storage.save(profile)
        emit(.coinPickup)
    }

    func toggleLanguage() {
        profile.language = (profile.language == .vi) ? .en : .vi
        storage.save(profile)
    }

    func toggleSound() {
        profile.soundEnabled.toggle()
        audioHaptics?.soundEnabled = profile.soundEnabled
        storage.save(profile)
    }

    func toggleHaptics() {
        profile.hapticsEnabled.toggle()
        audioHaptics?.hapticsEnabled = profile.hapticsEnabled
        storage.save(profile)
    }

    func step(rawDt: Float) {
        let dt = min(max(rawDt, 0.001), 0.05)
        simClock += dt
        snapshot.simClock = simClock

        let target = computeTargetScale(snapshot.sliderValue)
        snapshot.targetScale = target
        let morphRate = UpgradeType.morphResponsiveness(profile.agileFluxLevel)
        let blend = 1.0 - exp(-morphRate * dt)
        snapshot.currentScale += (target - snapshot.currentScale) * blend

        let newZone = classifyZone(snapshot.currentScale)
        if newZone != lastScaleZone && (snapshot.phase == .running || snapshot.phase == .goalGauntlet) {
            lastScaleZone = newZone
            emit(.scaleZoneTick)
        } else {
            lastScaleZone = newZone
        }
        snapshot.scaleZone = newZone

        snapshot.cameraShake = max(0, snapshot.cameraShake - dt * 2.2)
        snapshot.invulnerableTimer = max(0, snapshot.invulnerableTimer - dt)
        updateDebris(dt: dt)
        updateCallouts(dt: dt)

        switch snapshot.phase {
        case .home:
            snapshot.homeOrbitAngle += dt * 0.65
            snapshot.runCyclePhase += dt * 4.2
        case .running:
            stepRunning(dt: dt)
        case .goalGauntlet:
            stepGoalGauntlet(dt: dt)
        case .bossClash:
            stepBossClash(dt: dt)
        case .stageClear, .gameOver:
            if var boss = snapshot.boss, boss.bossDefeated, boss.bossFlyOffsetZ < 65 {
                boss.bossFlyOffsetZ += dt * 42
                let t = min(max(boss.bossFlyOffsetZ / 65.0, 0), 1)
                boss.bossFlyHeight = sin(t * .pi) * 11.0
                snapshot.boss = boss
            }
        }
    }

    private func stepRunning(dt: Float) {
        if snapshot.overdriveTimer > 0 {
            snapshot.overdriveTimer = max(0, snapshot.overdriveTimer - dt)
        }

        let scaleSpeedFactor = min(max(1.28 - (snapshot.currentScale - 0.35) * 0.14, 0.84), 1.30)
        let overdriveBoost: Float = snapshot.overdriveTimer > 0 ? 1.28 : 1.0
        let upgradeSpeed = UpgradeType.speedBonusFactor(profile.agileFluxLevel)
        let forwardSpeed: Float = 12.0 * scaleSpeedFactor * overdriveBoost * upgradeSpeed

        snapshot.playerZ += forwardSpeed * dt
        snapshot.progress = min(max(snapshot.playerZ / max(1, snapshot.trackLength), 0), 1)

        let strideFreq = min(max(forwardSpeed / max(0.55, snapshot.currentScale), 5.5), 20.0)
        snapshot.runCyclePhase += strideFreq * dt

        if jumpEndZ > jumpStartZ && snapshot.playerZ >= jumpStartZ && snapshot.playerZ <= jumpEndZ {
            let t = min(max((snapshot.playerZ - jumpStartZ) / (jumpEndZ - jumpStartZ), 0), 1)
            snapshot.playerY = sin(t * .pi) * 2.9
        } else {
            snapshot.playerY = 0
        }

        for i in snapshot.obstacles.indices {
            if snapshot.obstacles[i].type == .rivalEnforcer && !snapshot.obstacles[i].shatteredOrSmashed {
                snapshot.obstacles[i].lateralX = sin(simClock * 2.8 + snapshot.obstacles[i].phaseOffset) * 1.15
            }
        }

        checkCollectibles()
        checkObstacles()
        refreshUpcomingHints()

        if snapshot.phase == .running && snapshot.playerZ >= snapshot.trackLength {
            snapshot.playerY = 0
            if snapshot.isBossStage && snapshot.boss != nil {
                snapshot.phase = .bossClash
                pushCallout(
                    vi: "ĐẤU TRÙM TITAN!",
                    en: "BOSS CLASH!",
                    sub: profile.language == .vi ? "Giữ thanh trượt vùng Vàng & Chạm!" : "Hold Gold Zone & Tap!",
                    color: Color(red: 1.0, green: 0.7, blue: 0.0)
                )
            } else {
                snapshot.phase = .goalGauntlet
                pushCallout(
                    vi: "PHÁ ĐẢO NHÂN THƯỞNG!",
                    en: "GOAL SURGE!",
                    sub: profile.language == .vi ? "Phóng to tối đa để đâm xuyên tường!" : "Grow Giant to smash walls!",
                    color: Color(red: 0.0, green: 0.9, blue: 1.0)
                )
            }
        }
    }

    private func checkCollectibles() {
        let reachHeight = snapshot.currentScale * 2.15 + snapshot.playerY + 0.55
        for i in snapshot.collectibles.indices {
            if snapshot.collectibles[i].collected { continue }
            let orb = snapshot.collectibles[i]
            if abs(snapshot.playerZ - orb.z) <= 1.65 && orb.y <= reachHeight + 0.45 {
                snapshot.collectibles[i].collected = true
                if orb.isShieldCrystal {
                    snapshot.hasShield = true
                    pushCallout(vi: "KHIÊN NĂNG LƯỢNG!", en: "SHIELD ACTIVE!", color: .cyan)
                    emit(.shieldGain)
                } else {
                    snapshot.stageCoins += 5
                    snapshot.momentumScore += 1.6
                    emit(.coinPickup)
                }
            }
        }
    }

    private func checkObstacles() {
        let overdrive = snapshot.overdriveTimer > 0
        for i in snapshot.obstacles.indices {
            let obs = snapshot.obstacles[i]
            if obs.resolved { continue }

            switch obs.type {
            case .fragileGlassBridge:
                if snapshot.playerZ >= obs.zStart && snapshot.playerZ <= obs.zEnd {
                    if !overdrive && snapshot.currentScale > obs.maxSafeScale && snapshot.invulnerableTimer <= 0 {
                        snapshot.obstacles[i].resolved = true
                        snapshot.obstacles[i].shatteredOrSmashed = true
                        spawnBurst(x: 0, y: 0.1, z: snapshot.playerZ + 1, count: 16, color: RgbColor(r: 0.55, g: 0.92, b: 1.0), speed: 11)
                        emit(.glassShatter)
                        handleObstacleFailure(
                            vi: "Quá nặng! Cầu kính đã vỡ tan!",
                            en: "Too heavy! You shattered the glass bridge!"
                        )
                        return
                    }
                } else if snapshot.playerZ > obs.zEnd {
                    snapshot.obstacles[i].resolved = true
                    rewardCleanClear(vi: "BƯỚC NHẸ TÊNH!", en: "FEATHER STEP!", bonusCoins: 12, momentumGain: 14, overdriveGain: 0.24, color: .cyan)
                    emit(.glassSafe)
                }

            case .abyssChasmGap:
                if snapshot.playerZ >= obs.zStart - 0.6 {
                    if overdrive || snapshot.currentScale >= obs.minSafeScale {
                        snapshot.obstacles[i].resolved = true
                        jumpStartZ = obs.zStart - 0.8
                        jumpEndZ = obs.zEnd + 1.2
                        rewardCleanClear(vi: "BƯỚC NHẢY KHỔNG LỒ!", en: "GIANT LEAP!", bonusCoins: 15, momentumGain: 16, overdriveGain: 0.25, color: .orange)
                        emit(.giantLeap)
                    } else if snapshot.invulnerableTimer <= 0 {
                        snapshot.obstacles[i].resolved = true
                        handleObstacleFailure(
                            vi: "Quá nhỏ! Không đủ sải chân nhảy qua vực!",
                            en: "Too small to leap across the chasm!"
                        )
                        return
                    } else {
                        snapshot.obstacles[i].resolved = true
                        jumpStartZ = obs.zStart
                        jumpEndZ = obs.zEnd + 1.0
                    }
                }

            case .lowLaserGate:
                if snapshot.playerZ >= obs.zStart {
                    snapshot.obstacles[i].resolved = true
                    snapshot.obstacles[i].shatteredOrSmashed = overdrive
                    if overdrive || snapshot.currentScale <= obs.maxSafeScale {
                        rewardCleanClear(vi: "LUỒN LÁCH SIÊU TỐC!", en: "SLIP UNDER!", bonusCoins: 10, momentumGain: 13, overdriveGain: 0.24, color: .mint)
                        emit(.slipUnder)
                    } else if snapshot.invulnerableTimer <= 0 {
                        handleObstacleFailure(
                            vi: "Đụng trúng cổng Laser tầm thấp! Hãy thu nhỏ lại!",
                            en: "Hit the low plasma gate! Shrink down!"
                        )
                        return
                    }
                }

            case .movingPistonGate:
                if snapshot.playerZ >= obs.zStart {
                    let openFactor = 0.5 + 0.5 * sin(simClock * 3.1 + obs.phaseOffset)
                    let dynamicMaxSafe: Float = openFactor > 0.65 ? 1.90 : obs.maxSafeScale
                    snapshot.obstacles[i].resolved = true
                    snapshot.obstacles[i].shatteredOrSmashed = overdrive
                    if overdrive || snapshot.currentScale <= dynamicMaxSafe {
                        rewardCleanClear(vi: "LÁCH PISTON CHUẨN!", en: "TIMED DODGE!", bonusCoins: 14, momentumGain: 15, overdriveGain: 0.26, color: .green)
                        emit(.slipUnder)
                    } else if snapshot.invulnerableTimer <= 0 {
                        handleObstacleFailure(
                            vi: "Bị cổng Piston ép trúng! Hãy thu nhỏ kịp lúc!",
                            en: "Crushed by the moving piston gate!"
                        )
                        return
                    }
                }

            case .heavyBrickWall:
                if snapshot.playerZ >= obs.zStart - 0.5 {
                    if overdrive || snapshot.currentScale >= obs.minSafeScale {
                        snapshot.obstacles[i].resolved = true
                        snapshot.obstacles[i].shatteredOrSmashed = true
                        snapshot.cameraShake = 0.65
                        spawnBurst(x: 0, y: 1.8, z: obs.zStart + 1.0, count: 16, color: RgbColor(r: 0.95, g: 0.42, b: 0.18), speed: 15)
                        rewardCleanClear(vi: "HỦY DIỆT TƯỜNG CHẮN!", en: "TITAN SMASH!", bonusCoins: 16, momentumGain: 18, overdriveGain: 0.28, color: .orange)
                        emit(.wallSmash)
                    } else if snapshot.invulnerableTimer <= 0 {
                        snapshot.obstacles[i].resolved = true
                        handleObstacleFailure(
                            vi: "Chưa đủ to để phá tường gạch! Hãy phóng đại Titan!",
                            en: "Too small to smash the heavy brick wall!"
                        )
                        return
                    } else {
                        snapshot.obstacles[i].resolved = true
                        snapshot.obstacles[i].shatteredOrSmashed = true
                    }
                }

            case .precisionSyncArch:
                if snapshot.playerZ >= obs.zStart {
                    snapshot.obstacles[i].resolved = true
                    let matched = snapshot.currentScale >= obs.minSafeScale && snapshot.currentScale <= obs.maxSafeScale
                    if overdrive || matched {
                        spawnBurst(x: 0, y: 1.6, z: obs.zStart, count: 10, color: RgbColor(r: 0.25, g: 1.0, b: 0.55), speed: 9)
                        rewardCleanClear(vi: "ĐỒNG BỘ HOÀN HẢO!", en: "PERFECT SYNC!", bonusCoins: 22, momentumGain: 20, overdriveGain: 0.34, color: .green)
                        emit(.perfectSync)
                    } else if snapshot.invulnerableTimer <= 0 {
                        handleObstacleFailure(
                            vi: String(format: "Sai kích cỡ cổng Đồng Bộ (%.1fx - %.1fx)!", locale: Locale(identifier: "en_US_POSIX"), obs.minSafeScale, obs.maxSafeScale),
                            en: String(format: "Missed Sync Arch size window (%.1fx - %.1fx)!", locale: Locale(identifier: "en_US_POSIX"), obs.minSafeScale, obs.maxSafeScale)
                        )
                        return
                    }
                }

            case .rivalEnforcer:
                if snapshot.playerZ >= obs.zStart - 0.4 {
                    if overdrive || snapshot.currentScale >= obs.minSafeScale {
                        snapshot.obstacles[i].resolved = true
                        snapshot.obstacles[i].shatteredOrSmashed = true
                        snapshot.cameraShake = 0.55
                        spawnBurst(x: obs.lateralX, y: 1.5, z: obs.zStart + 0.5, count: 14, color: RgbColor(r: 0.95, g: 0.22, b: 0.42), speed: 14)
                        rewardCleanClear(vi: "HẤT TUNG ĐỐI THỦ!", en: "RIVAL CRUSHED!", bonusCoins: 20, momentumGain: 19, overdriveGain: 0.30, color: .pink)
                        emit(.rivalCrush)
                    } else if snapshot.invulnerableTimer <= 0 {
                        snapshot.obstacles[i].resolved = true
                        handleObstacleFailure(
                            vi: "Bị Vệ Binh hạng nặng húc văng! Hãy hóa Khổng Lồ!",
                            en: "Knocked out by Armored Enforcer! Grow Giant!"
                        )
                        return
                    } else {
                        snapshot.obstacles[i].resolved = true
                        snapshot.obstacles[i].shatteredOrSmashed = true
                    }
                }
            }
        }
    }

    private func rewardCleanClear(
        vi: String,
        en: String,
        bonusCoins: Int,
        momentumGain: Float,
        overdriveGain: Float,
        color: Color
    ) {
        snapshot.comboCount += 1
        maxComboReached = max(maxComboReached, snapshot.comboCount)
        let comboBonus = min(8, snapshot.comboCount - 1) * 2
        let earned = bonusCoins + comboBonus
        snapshot.stageCoins += earned
        snapshot.momentumScore += momentumGain + Float(snapshot.comboCount) * 1.5

        if snapshot.overdriveTimer <= 0 {
            snapshot.overdriveMeter = min(1.0, snapshot.overdriveMeter + overdriveGain)
            if snapshot.overdriveMeter >= 1.0 {
                snapshot.overdriveMeter = 0
                snapshot.overdriveTimer = 4.6
                snapshot.cameraShake = 0.5
                pushCallout(vi: "SIÊU BÙNG NỔ OVERDRIVE!", en: "HYPER OVERDRIVE!", sub: profile.language == .vi ? "4.5s BẤT TỬ & HỦY DIỆT" : "4.5s INVINCIBLE SMASH", color: .yellow)
                emit(.overdriveActivated)
                return
            }
        }

        pushCallout(vi: vi, en: en, sub: profile.language == .vi ? "+\(earned) Xu  •  Combo x\(snapshot.comboCount)" : "+\(earned) Coins  •  Combo x\(snapshot.comboCount)", color: color)
    }

    private func handleObstacleFailure(vi: String, en: String) {
        if snapshot.hasShield {
            snapshot.hasShield = false
            snapshot.invulnerableTimer = 1.15
            snapshot.comboCount = 0
            snapshot.cameraShake = 0.55
            spawnBurst(x: 0, y: 1.3, z: snapshot.playerZ, count: 12, color: RgbColor(r: 0, g: 0.88, b: 1), speed: 11)
            pushCallout(vi: "KHIÊN ĐỠ ĐÒN!", en: "SHIELD SAVED YOU!", color: .cyan)
            emit(.shieldBlock)
            return
        }

        snapshot.failReasonVi = vi
        snapshot.failReasonEn = en
        snapshot.comboCount = 0
        snapshot.cameraShake = 0.85
        spawnBurst(x: 0, y: 1.2, z: snapshot.playerZ, count: 18, color: RgbColor(r: 1.0, g: 0.25, b: 0.30), speed: 12)
        snapshot.phase = .gameOver
        emit(.playerDefeat)
    }

    private func stepGoalGauntlet(dt: Float) {
        snapshot.playerZ += 24.0 * dt
        snapshot.runCyclePhase += 14.0 * dt

        let liveScaleForce = min(max(snapshot.currentScale / 2.2, 0.65), 1.45)
        let effectivePower = snapshot.momentumScore * liveScaleForce

        for i in snapshot.multiplierWalls.indices {
            let wall = snapshot.multiplierWalls[i]
            if wall.broken { continue }
            if snapshot.playerZ >= wall.z - 0.6 {
                if effectivePower >= wall.requiredMomentum || wall.index == 0 {
                    snapshot.multiplierWalls[i].broken = true
                    snapshot.earnedMultiplier = wall.multiplier
                    snapshot.cameraShake = 0.55
                    spawnBurst(x: 0, y: 2.0, z: wall.z, count: 16, color: wall.color, speed: 16)
                    pushCallout(
                        vi: String(format: "PHÁ MỐC x%.1f!", locale: Locale(identifier: "en_US_POSIX"), wall.multiplier),
                        en: String(format: "SMASHED x%.1f!", locale: Locale(identifier: "en_US_POSIX"), wall.multiplier),
                        color: wall.color.swiftUIColor
                    )
                    emit(.multiplierBreak)

                    if wall.index == snapshot.multiplierWalls.count - 1 {
                        completeStageVictory()
                        return
                    }
                } else {
                    completeStageVictory()
                    return
                }
            }
        }

        let lastZ = snapshot.multiplierWalls.last?.z ?? (snapshot.trackLength + 20)
        if snapshot.playerZ > lastZ + 6 {
            completeStageVictory()
        }
    }

    private func stepBossClash(dt: Float) {
        guard var boss = snapshot.boss else {
            completeStageVictory()
            return
        }

        let arenaPlayerZ = boss.bossZ - 6.5
        if snapshot.playerZ < arenaPlayerZ {
            snapshot.playerZ = min(arenaPlayerZ, snapshot.playerZ + 22.0 * dt)
            snapshot.runCyclePhase += 11.0 * dt
            return
        }

        let sweetCenter = min(max(0.68 + 0.24 * sin(simClock * 2.4), 0.35), 0.95)
        let inSweetSpot = abs(snapshot.sliderValue - sweetCenter) <= 0.17
        let titanBonus = Float(profile.titanMassLevel - 1) * 1.6
        let delta: Float = inSweetSpot ? (16.5 + titanBonus) * dt : -7.5 * dt

        boss.clashProgress = min(max(boss.clashProgress + delta, 0), 100)
        boss.timeRemaining = max(0, boss.timeRemaining - dt)
        boss.sweetSpotSliderCenter = sweetCenter
        snapshot.boss = boss

        if boss.clashProgress >= 100 {
            snapshot.earnedMultiplier = 10.0
            boss.bossDefeated = true
            snapshot.boss = boss
            snapshot.cameraShake = 1.0
            spawnBurst(x: 0, y: 2.6, z: boss.bossZ, count: 28, color: RgbColor(r: 1, g: 0.72, b: 0.12), speed: 20)
            pushCallout(vi: "HẠ GỤC TRÙM KHỔNG LỒ! x10!", en: "BOSS OBLITERATED! x10!", color: .yellow)
            completeStageVictory()
        } else if boss.timeRemaining <= 0 {
            if boss.clashProgress >= 42 {
                snapshot.earnedMultiplier = 3.0 + (boss.clashProgress / 100.0) * 5.0
                boss.bossDefeated = true
                snapshot.boss = boss
                snapshot.cameraShake = 0.8
                spawnBurst(x: 0, y: 2.4, z: boss.bossZ, count: 22, color: RgbColor(r: 0.2, g: 0.95, b: 1.0), speed: 16)
                completeStageVictory()
            } else {
                snapshot.failReasonVi = "Trùm Mecha áp đảo sức mạnh! Hãy giữ thanh trượt ở vùng Vàng & chạm nhanh!"
                snapshot.failReasonEn = "Overpowered by the Boss! Keep slider in the Gold Zone & tap rapidly!"
                snapshot.phase = .gameOver
                emit(.playerDefeat)
            }
        }
    }

    private func completeStageVictory() {
        let baseStageBonus = 35 + snapshot.stageNumber * 8
        let rawCoins = Float(snapshot.stageCoins + baseStageBonus)
        let goldFactor = UpgradeType.coinBonusFactor(profile.goldBountyLevel)
        let reward = max(25, Int((rawCoins * snapshot.earnedMultiplier * goldFactor).rounded()))
        snapshot.finalStageReward = reward
        snapshot.phase = .stageClear

        // Persist progression immediately
        profile.coins += reward
        profile.bestCombo = max(profile.bestCombo, maxComboReached)
        if profile.currentStage >= 5 {
            profile.unlockedSkinIds.insert("glacier_titan")
        }
        profile.currentStage += 1
        storage.save(profile)
        emit(.stageVictory)
    }

    private func spawnBurst(x: Float, y: Float, z: Float, count: Int, color: RgbColor, speed: Float) {
        for _ in 0..<count {
            let angle = fxRng.nextFloat() * 2.0 * .pi
            let horiz = (0.3 + fxRng.nextFloat() * 0.7) * speed
            snapshot.debris.append(
                DebrisPiece(
                    id: nextDebrisId,
                    x: x + (fxRng.nextFloat() - 0.5) * 1.4,
                    y: y + fxRng.nextFloat() * 1.1,
                    z: z + (fxRng.nextFloat() - 0.5) * 1.0,
                    vx: cos(angle) * horiz,
                    vy: (0.45 + fxRng.nextFloat() * 0.75) * speed,
                    vz: sin(angle) * horiz + 6.0,
                    rotX: fxRng.nextFloat() * 360,
                    rotY: fxRng.nextFloat() * 360,
                    size: 0.22 + fxRng.nextFloat() * 0.28,
                    color: color,
                    life: 0.85 + fxRng.nextFloat() * 0.45
                )
            )
            nextDebrisId += 1
        }
        if snapshot.debris.count > 90 {
            snapshot.debris.removeFirst(snapshot.debris.count - 90)
        }
    }

    private func updateDebris(dt: Float) {
        var idx = snapshot.debris.count - 1
        while idx >= 0 {
            snapshot.debris[idx].life -= dt
            if snapshot.debris[idx].life <= 0 {
                snapshot.debris.remove(at: idx)
            } else {
                var ny = snapshot.debris[idx].y + snapshot.debris[idx].vy * dt
                var nvy = snapshot.debris[idx].vy - 26.0 * dt
                if ny < 0.12 {
                    ny = 0.12
                    nvy = abs(nvy) * 0.42
                }
                snapshot.debris[idx].x += snapshot.debris[idx].vx * dt
                snapshot.debris[idx].y = ny
                snapshot.debris[idx].z += snapshot.debris[idx].vz * dt
                snapshot.debris[idx].vy = nvy
                snapshot.debris[idx].rotX += 240 * dt
                snapshot.debris[idx].rotY += 190 * dt
            }
            idx -= 1
        }
    }

    private func updateCallouts(dt: Float) {
        var idx = snapshot.callouts.count - 1
        while idx >= 0 {
            snapshot.callouts[idx].age += dt
            if snapshot.callouts[idx].age >= snapshot.callouts[idx].duration {
                snapshot.callouts.remove(at: idx)
            }
            idx -= 1
        }
    }

    private func pushCallout(vi: String, en: String, sub: String = "", color: Color) {
        let txt = profile.language == .vi ? vi : en
        snapshot.callouts.append(
            FloatingCallout(id: nextCalloutId, text: txt, subText: sub, color: color)
        )
        nextCalloutId += 1
        if snapshot.callouts.count > 3 {
            snapshot.callouts.removeFirst()
        }
    }

    private func refreshUpcomingHints() {
        guard let next = snapshot.obstacles.first(where: { !$0.resolved && $0.zEnd >= snapshot.playerZ && ($0.zStart - snapshot.playerZ) <= 30 }) else {
            snapshot.upcomingHintVi = ""
            snapshot.upcomingHintEn = ""
            return
        }
        switch next.type {
        case .lowLaserGate:
            snapshot.upcomingHintVi = "⬇ THU NHỎ (< 0.88x) để chui dưới Laser!"
            snapshot.upcomingHintEn = "⬇ SHRINK (< 0.88x) under Laser!"
        case .heavyBrickWall:
            snapshot.upcomingHintVi = "⬆ HÓA TITAN (> 1.7x) đâm vỡ tường gạch!"
            snapshot.upcomingHintEn = "⬆ GROW TITAN (> 1.7x) to smash wall!"
        case .fragileGlassBridge:
            snapshot.upcomingHintVi = "⬇ GIỮ NHẸ (< 1.2x) qua cầu kính!"
            snapshot.upcomingHintEn = "⬇ STAY LIGHT (< 1.2x) on glass bridge!"
        case .abyssChasmGap:
            snapshot.upcomingHintVi = "⬆ HÓA KHỔNG LỒ (> 1.8x) nhảy qua vực!"
            snapshot.upcomingHintEn = "⬆ GROW GIANT (> 1.8x) to leap chasm!"
        case .precisionSyncArch:
            snapshot.upcomingHintVi = String(format: "🎯 CĂN CỠ %.1fx – %.1fx qua Cổng Đồng Bộ!", locale: Locale(identifier: "en_US_POSIX"), next.minSafeScale, next.maxSafeScale)
            snapshot.upcomingHintEn = String(format: "🎯 MATCH %.1fx – %.1fx for Sync Arch!", locale: Locale(identifier: "en_US_POSIX"), next.minSafeScale, next.maxSafeScale)
        case .rivalEnforcer:
            snapshot.upcomingHintVi = "⬆ HÓA TITAN (> 1.8x) hất tung Vệ Binh!"
            snapshot.upcomingHintEn = "⬆ GROW TITAN (> 1.8x) to crush Enforcer!"
        case .movingPistonGate:
            snapshot.upcomingHintVi = "⚡ Canh Piston nâng lên hoặc THU NHỎ!"
            snapshot.upcomingHintEn = "⚡ Time the Piston or SHRINK tiny!"
        }
    }

    private func computeTargetScale(_ slider: Float) -> Float {
        let minS = UpgradeType.minPlayerScale
        let maxS = UpgradeType.maxScaleForLevel(profile.titanMassLevel)
        return minS + (maxS - minS) * min(max(slider, 0), 1)
    }

    private func classifyZone(_ scale: Float) -> ScaleZone {
        if scale < 0.88 { return .micro }
        if scale < 1.68 { return .sync }
        return .titan
    }

    private func emit(_ event: GameEvent) {
        audioHaptics?.trigger(event)
    }
}
