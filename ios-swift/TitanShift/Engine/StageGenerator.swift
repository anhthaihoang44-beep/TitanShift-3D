import Foundation

struct SeededGenerator {
    private var state: UInt64

    init(seed: UInt64) {
        self.state = seed == 0 ? 0x9E3779B97F4A7C15 : seed
    }

    mutating func nextUInt64() -> UInt64 {
        state &+= 0x9E3779B97F4A7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58476D1CE4E5B9
        z = (z ^ (z >> 27)) &* 0x94D049BB133111EB
        return z ^ (z >> 31)
    }

    mutating func nextFloat() -> Float {
        Float(nextUInt64() >> 40) / Float(1 << 24)
    }

    mutating func nextInt(upperBound: Int) -> Int {
        guard upperBound > 1 else { return 0 }
        return Int(nextUInt64() % UInt64(upperBound))
    }
}

struct GeneratedStage {
    let stageNumber: Int
    let isBossStage: Bool
    let trackLength: Float
    let obstacles: [ObstacleInstance]
    let collectibles: [CollectibleOrb]
    let multiplierWalls: [MultiplierWall]
    let boss: BossEncounter?
}

enum StageGenerator {
    static func generate(stageNumber: Int) -> GeneratedStage {
        let stage = max(1, stageNumber)
        let isBossStage = (stage % 5 == 0)
        var rng = SeededGenerator(seed: UInt64(stage) * 7919 + 104729)

        let segmentCount = 7 + min(7, stage / 2)
        let spacing = min(max(34.0 - Float(stage) * 0.3, 26.0), 34.0)

        var obstacles: [ObstacleInstance] = []
        var collectibles: [CollectibleOrb] = []

        var currentZ: Float = 34.0
        var nextObsId = 1
        var nextOrbId = 1
        var prevType: ObstacleType? = nil
        let pool = buildObstaclePool(stage: stage)

        for index in 0..<segmentCount {
            let chosenType: ObstacleType
            if stage == 1 {
                switch index {
                case 0: chosenType = .lowLaserGate
                case 1: chosenType = .heavyBrickWall
                case 2: chosenType = .fragileGlassBridge
                case 3: chosenType = .heavyBrickWall
                case 4: chosenType = .lowLaserGate
                case 5: chosenType = .precisionSyncArch
                default:
                    let candidates = pool.filter { $0 != prevType }
                    chosenType = candidates[rng.nextInt(upperBound: candidates.count)]
                }
            } else {
                let candidates = pool.filter { $0 != prevType }
                chosenType = candidates[rng.nextInt(upperBound: candidates.count)]
            }
            prevType = chosenType

            let obs = createObstacle(
                id: nextObsId,
                type: chosenType,
                zStart: currentZ,
                stage: stage,
                rng: &rng
            )
            nextObsId += 1
            obstacles.append(obs)

            let orbs = buildGuidedCoins(
                startId: nextOrbId,
                obstacle: obs,
                includeShield: index == segmentCount / 2
            )
            nextOrbId += orbs.count
            collectibles.append(contentsOf: orbs)

            currentZ = obs.zEnd + spacing
        }

        let finishZ = currentZ + 12.0
        let multiplierWalls = isBossStage ? [] : buildMultiplierGauntlet(finishZ: finishZ)
        let boss: BossEncounter? = isBossStage ? BossEncounter(
            maxPower: 100,
            clashProgress: 46,
            timeRemaining: 5.0,
            sweetSpotSliderCenter: 0.78,
            bossScale: min(3.8, 3.0 + Float(stage / 5) * 0.15),
            bossZ: finishZ + 18.0
        ) : nil

        return GeneratedStage(
            stageNumber: stage,
            isBossStage: isBossStage,
            trackLength: finishZ,
            obstacles: obstacles,
            collectibles: collectibles,
            multiplierWalls: multiplierWalls,
            boss: boss
        )
    }

    private static func buildObstaclePool(stage: Int) -> [ObstacleType] {
        var pool: [ObstacleType] = [
            .lowLaserGate,
            .heavyBrickWall,
            .fragileGlassBridge,
            .precisionSyncArch
        ]
        if stage >= 2 {
            pool.append(.abyssChasmGap)
            pool.append(.rivalEnforcer)
        }
        if stage >= 3 {
            pool.append(.movingPistonGate)
        }
        return pool
    }

    private static func createObstacle(
        id: Int,
        type: ObstacleType,
        zStart: Float,
        stage: Int,
        rng: inout SeededGenerator
    ) -> ObstacleInstance {
        switch type {
        case .lowLaserGate:
            return ObstacleInstance(id: id, type: type, zStart: zStart, zLength: 3.2, minSafeScale: 0, maxSafeScale: 0.88, phaseOffset: 0)
        case .heavyBrickWall:
            return ObstacleInstance(id: id, type: type, zStart: zStart, zLength: 3.4, minSafeScale: 1.68, maxSafeScale: 10, phaseOffset: 0)
        case .fragileGlassBridge:
            return ObstacleInstance(id: id, type: type, zStart: zStart, zLength: 10.5, minSafeScale: 0, maxSafeScale: 1.22, phaseOffset: 0)
        case .abyssChasmGap:
            return ObstacleInstance(id: id, type: type, zStart: zStart, zLength: 7.2, minSafeScale: 1.78, maxSafeScale: 10, phaseOffset: 0)
        case .precisionSyncArch:
            let center: Float = 1.15 + rng.nextFloat() * 0.35
            let halfWindow = min(max(0.34 - Float(stage) * 0.01, 0.22), 0.34)
            return ObstacleInstance(
                id: id,
                type: type,
                zStart: zStart,
                zLength: 2.8,
                minSafeScale: max(0.75, center - halfWindow),
                maxSafeScale: center + halfWindow,
                phaseOffset: 0
            )
        case .rivalEnforcer:
            return ObstacleInstance(
                id: id,
                type: type,
                zStart: zStart,
                zLength: 3.2,
                minSafeScale: 1.80,
                maxSafeScale: 10,
                phaseOffset: rng.nextFloat() * 6.28
            )
        case .movingPistonGate:
            return ObstacleInstance(
                id: id,
                type: type,
                zStart: zStart,
                zLength: 3.2,
                minSafeScale: 0,
                maxSafeScale: 0.85,
                phaseOffset: rng.nextFloat() * 6.28
            )
        }
    }

    private static func buildGuidedCoins(
        startId: Int,
        obstacle: ObstacleInstance,
        includeShield: Bool
    ) -> [CollectibleOrb] {
        let idealHeight: Float
        switch obstacle.type {
        case .lowLaserGate, .fragileGlassBridge, .movingPistonGate:
            idealHeight = 0.55
        case .precisionSyncArch:
            idealHeight = 1.45
        case .heavyBrickWall, .abyssChasmGap, .rivalEnforcer:
            idealHeight = 2.45
        }

        var result: [CollectibleOrb] = []
        for step in 0..<3 {
            result.append(
                CollectibleOrb(
                    id: startId + step,
                    z: obstacle.zStart - 10.5 + Float(step) * 3.4,
                    x: 0,
                    y: idealHeight,
                    isShieldCrystal: includeShield && step == 1
                )
            )
        }
        return result
    }

    private static func buildMultiplierGauntlet(finishZ: Float) -> [MultiplierWall] {
        let multipliers: [Float] = [1.5, 2.0, 3.0, 4.0, 5.0, 6.5, 8.0, 10.0]
        let palette: [RgbColor] = [
            RgbColor(r: 0.0, g: 0.88, b: 1.0),
            RgbColor(r: 0.12, g: 0.95, b: 0.62),
            RgbColor(r: 0.45, g: 0.98, b: 0.25),
            RgbColor(r: 1.0, g: 0.86, b: 0.15),
            RgbColor(r: 1.0, g: 0.58, b: 0.12),
            RgbColor(r: 1.0, g: 0.28, b: 0.35),
            RgbColor(r: 0.88, g: 0.24, b: 0.98),
            RgbColor(r: 1.0, g: 0.82, b: 0.22)
        ]

        return multipliers.enumerated().map { idx, mult in
            MultiplierWall(
                index: idx,
                z: finishZ + 12.0 + Float(idx) * 9.5,
                multiplier: mult,
                requiredMomentum: 18.0 + Float(idx) * 16.0,
                color: palette[idx % palette.count]
            )
        }
    }
}
