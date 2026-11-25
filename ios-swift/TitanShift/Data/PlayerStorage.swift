import Foundation

struct PlayerProfileState: Codable, Equatable {
    var currentStage: Int = 1
    var coins: Int = 150
    var bestCombo: Int = 0
    var selectedSkinId: String = SkinCatalog.skins[0].id
    var unlockedSkinIds: Set<String> = [SkinCatalog.skins[0].id]
    var titanMassLevel: Int = 1
    var agileFluxLevel: Int = 1
    var goldBountyLevel: Int = 1
    var soundEnabled: Bool = true
    var hapticsEnabled: Bool = true
    var language: AppLanguage = .en
}

final class PlayerStorage {
    private let defaults: UserDefaults
    private let storageKey = "titan_shift_ios_profile_v1"

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    func load() -> PlayerProfileState {
        guard let data = defaults.data(forKey: storageKey),
              var decoded = try? JSONDecoder().decode(PlayerProfileState.self, from: data) else {
            return PlayerProfileState()
        }
        decoded.currentStage = max(1, decoded.currentStage)
        decoded.coins = max(0, decoded.coins)
        decoded.unlockedSkinIds.insert(SkinCatalog.skins[0].id)
        return decoded
    }

    func save(_ state: PlayerProfileState) {
        if let data = try? JSONEncoder().encode(state) {
            defaults.set(data, forKey: storageKey)
        }
    }
}
