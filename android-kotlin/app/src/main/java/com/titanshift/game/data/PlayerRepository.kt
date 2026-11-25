package com.titanshift.game.data

import android.content.Context
import android.content.SharedPreferences
import com.titanshift.game.model.AppLanguage
import com.titanshift.game.model.SkinCatalog

data class PlayerProfileState(
    val currentStage: Int = 1,
    val coins: Int = 150,
    val bestCombo: Int = 0,
    val selectedSkinId: String = SkinCatalog.skins.first().id,
    val unlockedSkinIds: Set<String> = setOf(SkinCatalog.skins.first().id),
    val titanMassLevel: Int = 1,
    val agileFluxLevel: Int = 1,
    val goldBountyLevel: Int = 1,
    val soundEnabled: Boolean = true,
    val hapticsEnabled: Boolean = true,
    val language: AppLanguage = AppLanguage.EN
)

class PlayerRepository(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("titan_shift_save_v1", Context.MODE_PRIVATE)

    fun load(): PlayerProfileState {
        val defaultSkin = SkinCatalog.skins.first().id
        val unlockedRaw = prefs.getStringSet(KEY_UNLOCKED_SKINS, setOf(defaultSkin)) ?: setOf(defaultSkin)
        val langName = prefs.getString(KEY_LANGUAGE, AppLanguage.EN.name) ?: AppLanguage.EN.name
        val language = runCatching { AppLanguage.valueOf(langName) }.getOrDefault(AppLanguage.EN)

        return PlayerProfileState(
            currentStage = prefs.getInt(KEY_STAGE, 1).coerceAtLeast(1),
            coins = prefs.getInt(KEY_COINS, 150).coerceAtLeast(0),
            bestCombo = prefs.getInt(KEY_BEST_COMBO, 0).coerceAtLeast(0),
            selectedSkinId = prefs.getString(KEY_SELECTED_SKIN, defaultSkin) ?: defaultSkin,
            unlockedSkinIds = unlockedRaw + defaultSkin,
            titanMassLevel = prefs.getInt(KEY_TITAN_MASS, 1).coerceAtLeast(1),
            agileFluxLevel = prefs.getInt(KEY_AGILE_FLUX, 1).coerceAtLeast(1),
            goldBountyLevel = prefs.getInt(KEY_GOLD_BOUNTY, 1).coerceAtLeast(1),
            soundEnabled = prefs.getBoolean(KEY_SOUND, true),
            hapticsEnabled = prefs.getBoolean(KEY_HAPTICS, true),
            language = language
        )
    }

    fun save(state: PlayerProfileState) {
        prefs.edit()
            .putInt(KEY_STAGE, state.currentStage)
            .putInt(KEY_COINS, state.coins)
            .putInt(KEY_BEST_COMBO, state.bestCombo)
            .putString(KEY_SELECTED_SKIN, state.selectedSkinId)
            .putStringSet(KEY_UNLOCKED_SKINS, state.unlockedSkinIds)
            .putInt(KEY_TITAN_MASS, state.titanMassLevel)
            .putInt(KEY_AGILE_FLUX, state.agileFluxLevel)
            .putInt(KEY_GOLD_BOUNTY, state.goldBountyLevel)
            .putBoolean(KEY_SOUND, state.soundEnabled)
            .putBoolean(KEY_HAPTICS, state.hapticsEnabled)
            .putString(KEY_LANGUAGE, state.language.name)
            .apply()
    }

    companion object {
        private const val KEY_STAGE = "stage"
        private const val KEY_COINS = "coins"
        private const val KEY_BEST_COMBO = "best_combo"
        private const val KEY_SELECTED_SKIN = "selected_skin"
        private const val KEY_UNLOCKED_SKINS = "unlocked_skins"
        private const val KEY_TITAN_MASS = "lvl_titan_mass"
        private const val KEY_AGILE_FLUX = "lvl_agile_flux"
        private const val KEY_GOLD_BOUNTY = "lvl_gold_bounty"
        private const val KEY_SOUND = "sound_enabled"
        private const val KEY_HAPTICS = "haptics_enabled"
        private const val KEY_LANGUAGE = "app_language_v2"
    }
}
