import Foundation
import SwiftUI
import UIKit

enum AppLanguage: String, Codable, CaseIterable {
    case vi = "VI"
    case en = "EN"
}

enum ScaleZone: String, Equatable {
    case micro
    case sync
    case titan
}

enum CrestStyle: String, Codable, Equatable {
    case fin
    case horns
    case visor
    case halo
    case crown
    case sovereign
}

struct RgbColor: Codable, Equatable {
    let r: Float
    let g: Float
    let b: Float

    var uiColor: UIColor {
        UIColor(
            red: CGFloat(min(max(r, 0), 1)),
            green: CGFloat(min(max(g, 0), 1)),
            blue: CGFloat(min(max(b, 0), 1)),
            alpha: 1.0
        )
    }

    var swiftUIColor: Color {
        Color(
            red: Double(min(max(r, 0), 1)),
            green: Double(min(max(g, 0), 1)),
            blue: Double(min(max(b, 0), 1))
        )
    }
}

struct HeroSkin: Identifiable, Equatable {
    let id: String
    let nameVi: String
    let nameEn: String
    let descVi: String
    let descEn: String
    let primaryArmor: RgbColor
    let secondaryTrim: RgbColor
    let coreGlow: RgbColor
    let crestStyle: CrestStyle
    let coinCost: Int
    let unlockStageRequirement: Int

    func displayName(_ lang: AppLanguage) -> String {
        lang == .vi ? nameVi : nameEn
    }

    func displayDesc(_ lang: AppLanguage) -> String {
        lang == .vi ? descVi : descEn
    }
}

enum SkinCatalog {
    static let skins: [HeroSkin] = [
        HeroSkin(
            id: "vanguard_pulse",
            nameVi: "Chiến Binh Xung Điện",
            nameEn: "Vanguard Pulse",
            descVi: "Giáp Titan tiêu chuẩn với lõi năng lượng Cyan.",
            descEn: "Standard bio-suit with electric cyan morph core.",
            primaryArmor: RgbColor(r: 0.18, g: 0.42, b: 0.98),
            secondaryTrim: RgbColor(r: 0.88, g: 0.94, b: 1.0),
            coreGlow: RgbColor(r: 0.0, g: 0.92, b: 1.0),
            crestStyle: .fin,
            coinCost: 0,
            unlockStageRequirement: 1
        ),
        HeroSkin(
            id: "solar_striker",
            nameVi: "Hỏa Thần Mặt Trời",
            nameEn: "Solar Striker",
            descVi: "Sừng chiến giáp đỏ rực và hào quang vàng nóng chảy.",
            descEn: "Twin battle horns forged in crimson and molten gold.",
            primaryArmor: RgbColor(r: 0.92, g: 0.18, b: 0.22),
            secondaryTrim: RgbColor(r: 1.0, g: 0.76, b: 0.12),
            coreGlow: RgbColor(r: 1.0, g: 0.62, b: 0.05),
            crestStyle: .horns,
            coinCost: 250,
            unlockStageRequirement: 1
        ),
        HeroSkin(
            id: "venom_flux",
            nameVi: "Bóng Ma Neon",
            nameEn: "Venom Flux",
            descVi: "Giáp đen Obsidian kết hợp kính chiến thuật xanh lục.",
            descEn: "Obsidian stealth chassis with cyber-lime visor.",
            primaryArmor: RgbColor(r: 0.13, g: 0.16, b: 0.22),
            secondaryTrim: RgbColor(r: 0.22, g: 0.98, b: 0.46),
            coreGlow: RgbColor(r: 0.35, g: 1.0, b: 0.25),
            crestStyle: .visor,
            coinCost: 550,
            unlockStageRequirement: 2
        ),
        HeroSkin(
            id: "astral_phantom",
            nameVi: "Ảo Ảnh Tinh Vân",
            nameEn: "Astral Phantom",
            descVi: "Vòng hào quang không gian tím biếc và giáp bạc ánh sao.",
            descEn: "Floating astral halo over ultraviolet starlight armor.",
            primaryArmor: RgbColor(r: 0.52, g: 0.20, b: 0.92),
            secondaryTrim: RgbColor(r: 0.92, g: 0.90, b: 1.0),
            coreGlow: RgbColor(r: 0.88, g: 0.35, b: 1.0),
            crestStyle: .halo,
            coinCost: 1000,
            unlockStageRequirement: 3
        ),
        HeroSkin(
            id: "glacier_titan",
            nameVi: "Khổng Lồ Băng Giá",
            nameEn: "Glacier Titan",
            descVi: "Vương miện băng vĩnh cửu mở khóa từ Boss Màn 5 hoặc Xu.",
            descEn: "Frost crystal crown unlocked via Stage 5 Boss or Coins.",
            primaryArmor: RgbColor(r: 0.76, g: 0.92, b: 1.0),
            secondaryTrim: RgbColor(r: 0.12, g: 0.62, b: 0.95),
            coreGlow: RgbColor(r: 0.35, g: 0.96, b: 1.0),
            crestStyle: .crown,
            coinCost: 1500,
            unlockStageRequirement: 5
        ),
        HeroSkin(
            id: "omega_sovereign",
            nameVi: "Đế Vương Omega",
            nameEn: "Omega Sovereign",
            descVi: "Giáp tối thượng đen-vàng hồng ngọc dành cho bậc thầy biến hình.",
            descEn: "Ultimate black, gold, and magenta sovereign armor.",
            primaryArmor: RgbColor(r: 0.10, g: 0.08, b: 0.14),
            secondaryTrim: RgbColor(r: 1.0, g: 0.80, b: 0.18),
            coreGlow: RgbColor(r: 1.0, g: 0.15, b: 0.68),
            crestStyle: .sovereign,
            coinCost: 2400,
            unlockStageRequirement: 10
        )
    ]

    static func byId(_ id: String) -> HeroSkin {
        skins.first(where: { $0.id == id }) ?? skins[0]
    }
}

enum UpgradeType: String, CaseIterable, Identifiable {
    case titanMass
    case agileFlux
    case goldBounty

    var id: String { rawValue }

    static let minPlayerScale: Float = 0.35

    func costForLevel(_ currentLevel: Int) -> Int {
        let base: Double
        switch self {
        case .titanMass: base = 90
        case .agileFlux: base = 80
        case .goldBounty: base = 100
        }
        let expPower = Double(max(0, currentLevel - 1))
        return Int((base * pow(1.42, expPower)).rounded())
    }

    func title(_ lang: AppLanguage) -> String {
        switch self {
        case .titanMass: return lang == .vi ? "Sức Nặng Titan" : "Titan Mass"
        case .agileFlux: return lang == .vi ? "Tốc Độ Biến Hình" : "Agile Flux"
        case .goldBounty: return lang == .vi ? "Thưởng Vàng" : "Gold Bounty"
        }
    }

    func subtitle(level: Int, lang: AppLanguage) -> String {
        switch self {
        case .titanMass:
            let maxS = Self.maxScaleForLevel(level)
            let fmt = lang == .vi ? "Cỡ tối đa %.2fx" : "Max Scale %.2fx"
            return String(format: fmt, locale: Locale(identifier: "en_US_POSIX"), maxS)
        case .agileFlux:
            let pct = Int(((Self.speedBonusFactor(level) - 1.0) * 100.0).rounded())
            return lang == .vi ? "Tốc độ +\(pct)%" : "Speed +\(pct)%"
        case .goldBounty:
            let pct = Int(((Self.coinBonusFactor(level) - 1.0) * 100.0).rounded())
            return lang == .vi ? "Xu +\(pct)%" : "Coins +\(pct)%"
        }
    }

    static func maxScaleForLevel(_ level: Int) -> Float {
        min(3.65, 2.85 + Float(max(0, level - 1)) * 0.08)
    }

    static func speedBonusFactor(_ level: Int) -> Float {
        min(1.25, 1.0 + Float(max(0, level - 1)) * 0.025)
    }

    static func morphResponsiveness(_ level: Int) -> Float {
        min(26.0, 13.5 + Float(max(0, level - 1)) * 1.15)
    }

    static func coinBonusFactor(_ level: Int) -> Float {
        1.0 + Float(max(0, level - 1)) * 0.15
    }
}

enum ObstacleType: String, Equatable {
    case lowLaserGate
    case heavyBrickWall
    case fragileGlassBridge
    case abyssChasmGap
    case precisionSyncArch
    case rivalEnforcer
    case movingPistonGate
}

struct ObstacleInstance: Identifiable, Equatable {
    let id: Int
    let type: ObstacleType
    let zStart: Float
    let zLength: Float
    let minSafeScale: Float
    let maxSafeScale: Float
    let phaseOffset: Float
    var resolved: Bool = false
    var shatteredOrSmashed: Bool = false
    var lateralX: Float = 0

    var zEnd: Float { zStart + zLength }
}

struct CollectibleOrb: Identifiable, Equatable {
    let id: Int
    let z: Float
    let x: Float
    let y: Float
    let isShieldCrystal: Bool
    var collected: Bool = false
}

struct MultiplierWall: Identifiable, Equatable {
    var id: Int { index }
    let index: Int
    let z: Float
    let multiplier: Float
    let requiredMomentum: Float
    let color: RgbColor
    var broken: Bool = false
}

struct DebrisPiece: Identifiable, Equatable {
    let id: Int
    var x: Float
    var y: Float
    var z: Float
    var vx: Float
    var vy: Float
    var vz: Float
    var rotX: Float
    var rotY: Float
    let size: Float
    let color: RgbColor
    var life: Float
}

struct FloatingCallout: Identifiable, Equatable {
    let id: Int
    let text: String
    let subText: String
    let color: Color
    var age: Float = 0
    let duration: Float = 1.05
}

struct BossEncounter: Equatable {
    var maxPower: Float = 100
    var clashProgress: Float = 46
    var timeRemaining: Float = 5.0
    var sweetSpotSliderCenter: Float = 0.78
    let bossScale: Float
    let bossZ: Float
    var bossDefeated: Bool = false
    var bossFlyOffsetZ: Float = 0
    var bossFlyHeight: Float = 0
}

enum GamePhase: String, Equatable {
    case home
    case running
    case goalGauntlet
    case bossClash
    case stageClear
    case gameOver
}

enum GameEvent {
    case coinPickup
    case shieldGain
    case scaleZoneTick
    case slipUnder
    case wallSmash
    case glassSafe
    case glassShatter
    case giantLeap
    case perfectSync
    case rivalCrush
    case shieldBlock
    case overdriveActivated
    case bossClashHit
    case multiplierBreak
    case stageVictory
    case playerDefeat
}

struct GameSnapshot: Equatable {
    var stageRevision: Int = 0
    var phase: GamePhase = .home
    var stageNumber: Int = 1
    var isBossStage: Bool = false
    var sliderValue: Float = 0.28
    var currentScale: Float = 1.0
    var targetScale: Float = 1.0
    var minScale: Float = UpgradeType.minPlayerScale
    var maxScale: Float = 2.85
    var scaleZone: ScaleZone = .sync
    var playerZ: Float = 0
    var playerY: Float = 0
    var trackLength: Float = 220
    var progress: Float = 0
    var runCyclePhase: Float = 0
    var hasShield: Bool = false
    var invulnerableTimer: Float = 0
    var comboCount: Int = 0
    var overdriveMeter: Float = 0
    var overdriveTimer: Float = 0
    var momentumScore: Float = 36
    var stageCoins: Int = 0
    var earnedMultiplier: Float = 1.0
    var finalStageReward: Int = 0
    var cameraShake: Float = 0
    var homeOrbitAngle: Float = 0
    var simClock: Float = 0
    var activeSkin: HeroSkin = SkinCatalog.skins[0]
    var obstacles: [ObstacleInstance] = []
    var collectibles: [CollectibleOrb] = []
    var multiplierWalls: [MultiplierWall] = []
    var debris: [DebrisPiece] = []
    var callouts: [FloatingCallout] = []
    var boss: BossEncounter? = nil
    var upcomingHintVi: String = ""
    var upcomingHintEn: String = ""
    var failReasonVi: String = ""
    var failReasonEn: String = ""

    var isOverdriveActive: Bool { overdriveTimer > 0 }
}
