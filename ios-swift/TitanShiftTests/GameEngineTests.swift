import XCTest
@testable import TitanShift

@MainActor
final class GameEngineTests: XCTestCase {

    func testStageGeneratorProducesMultiplierStagesAndBossEveryFifthStage() {
        let stage1 = StageGenerator.generate(stageNumber: 1)
        XCTAssertFalse(stage1.isBossStage)
        XCTAssertNil(stage1.boss)
        XCTAssertEqual(stage1.multiplierWalls.count, 8)
        XCTAssertFalse(stage1.obstacles.isEmpty)

        let stage5 = StageGenerator.generate(stageNumber: 5)
        XCTAssertTrue(stage5.isBossStage)
        XCTAssertNotNil(stage5.boss)
        XCTAssertTrue(stage5.multiplierWalls.isEmpty)
    }

    func testSliderTransitionsBetweenMicroSyncAndTitanZones() {
        let defaults = UserDefaults(suiteName: "TitanShiftUnitTests")!
        defaults.removePersistentDomain(forName: "TitanShiftUnitTests")
        let vm = GameViewModel(storage: PlayerStorage(defaults: defaults), enableAudioHaptics: false)
        vm.startRun()

        vm.setSliderNormalized(0.0)
        for _ in 0..<20 { vm.step(rawDt: 0.02) }
        XCTAssertEqual(vm.snapshot.scaleZone, .micro)
        XCTAssertLessThan(vm.snapshot.currentScale, 0.55)

        vm.setSliderNormalized(1.0)
        for _ in 0..<25 { vm.step(rawDt: 0.02) }
        XCTAssertEqual(vm.snapshot.scaleZone, .titan)
        XCTAssertGreaterThan(vm.snapshot.currentScale, 2.50)
    }

    func testAdaptiveRunnerClearsStageOne() {
        let defaults = UserDefaults(suiteName: "TitanShiftClearTests")!
        defaults.removePersistentDomain(forName: "TitanShiftClearTests")
        let vm = GameViewModel(storage: PlayerStorage(defaults: defaults), enableAudioHaptics: false)
        vm.startRun()

        var steps = 0
        while vm.snapshot.phase != .stageClear && vm.snapshot.phase != .gameOver && steps < 2000 {
            let snap = vm.snapshot
            if snap.phase == .goalGauntlet {
                vm.setSliderNormalized(1.0)
            } else if let upcoming = snap.obstacles.first(where: { !$0.resolved && $0.zEnd >= snap.playerZ }) {
                switch upcoming.type {
                case .lowLaserGate, .fragileGlassBridge, .movingPistonGate:
                    vm.setSliderNormalized(0.05)
                case .heavyBrickWall, .abyssChasmGap, .rivalEnforcer:
                    vm.setSliderNormalized(0.95)
                case .precisionSyncArch:
                    let mid = (upcoming.minSafeScale + upcoming.maxSafeScale) * 0.5
                    let norm = (mid - snap.minScale) / (snap.maxScale - snap.minScale)
                    vm.setSliderNormalized(norm)
                }
            }
            vm.step(rawDt: 0.02)
            steps += 1
        }

        XCTAssertEqual(vm.snapshot.phase, .stageClear)
        XCTAssertGreaterThan(vm.snapshot.finalStageReward, 0)
    }
}
