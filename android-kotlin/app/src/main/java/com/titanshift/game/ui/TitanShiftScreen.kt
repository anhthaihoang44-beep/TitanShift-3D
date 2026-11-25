package com.titanshift.game.ui

import android.opengl.GLSurfaceView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.titanshift.game.audio.SoundAndHapticsManager
import com.titanshift.game.data.PlayerProfileState
import com.titanshift.game.data.PlayerRepository
import com.titanshift.game.engine.GameSimulation
import com.titanshift.game.model.AppLanguage
import com.titanshift.game.model.GamePhase
import com.titanshift.game.model.GameSnapshot
import com.titanshift.game.model.HeroSkin
import com.titanshift.game.model.ScaleZone
import com.titanshift.game.model.SkinCatalog
import com.titanshift.game.model.UpgradeType
import com.titanshift.game.render.TitanGlRenderer
import kotlin.math.roundToInt

@Composable
fun TitanShiftScreen() {
    val context = LocalContext.current
    val repository = remember { PlayerRepository(context) }
    var profile by remember { mutableStateOf(repository.load()) }
    var showSkinModal by remember { mutableStateOf(false) }
    var bonusClaimed by remember { mutableStateOf(false) }

    val audioHaptics = remember {
        SoundAndHapticsManager(context).apply {
            soundEnabled = profile.soundEnabled
            hapticsEnabled = profile.hapticsEnabled
        }
    }

    val simulation = remember {
        GameSimulation(
            initialProfile = profile,
            onEvent = { ev -> audioHaptics.trigger(ev) }
        )
    }

    DisposableEffect(Unit) {
        onDispose { audioHaptics.release() }
    }

    val snap by simulation.snapshot.collectAsState()

    // Automatically credit stage clear rewards & unlock milestone skins
    LaunchedEffect(snap.phase) {
        if (snap.phase == GamePhase.STAGE_CLEAR) {
            bonusClaimed = false
            simulation.consumePendingVictoryResult()?.let { (rewardCoins, maxCombo) ->
                val nextStage = profile.currentStage + 1
                val autoUnlocked = SkinCatalog.skins
                    .filter { it.coinCost == 0 || (it.unlockStageRequirement > 1 && profile.currentStage >= it.unlockStageRequirement && it.id == "glacier_titan") }
                    .map { it.id }
                    .toSet()

                val updated = profile.copy(
                    currentStage = nextStage,
                    coins = profile.coins + rewardCoins,
                    bestCombo = maxOf(profile.bestCombo, maxCombo),
                    unlockedSkinIds = profile.unlockedSkinIds + autoUnlocked
                )
                profile = updated
                repository.save(updated)
                simulation.syncProfile(updated)
            }
        }
    }

    fun updateProfile(transform: (PlayerProfileState) -> PlayerProfileState) {
        val next = transform(profile)
        profile = next
        audioHaptics.soundEnabled = next.soundEnabled
        audioHaptics.hapticsEnabled = next.hapticsEnabled
        repository.save(next)
        simulation.syncProfile(next)
    }

    var screenWidthPx by remember { mutableStateOf(1080f) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { screenWidthPx = it.width.coerceAtLeast(1).toFloat() }
    ) {
        // 1. Real-Time 60fps OpenGL ES 3D Viewport
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                GLSurfaceView(ctx).apply {
                    setEGLContextClientVersion(2)
                    setRenderer(TitanGlRenderer(simulation))
                    renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                }
            }
        )

        // 2. Full-screen One-Finger Drag Gesture Layer during Gameplay (ScaleMan feel!)
        if (snap.phase == GamePhase.RUNNING || snap.phase == GamePhase.GOAL_GAUNTLET || snap.phase == GamePhase.BOSS_CLASH) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(snap.phase) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            val deltaNorm = (dragAmount.x / (screenWidthPx * 0.62f))
                            simulation.adjustSliderByDelta(deltaNorm)
                        }
                    }
                    .pointerInput(snap.phase) {
                        if (snap.phase == GamePhase.BOSS_CLASH) {
                            detectTapGestures {
                                simulation.onBossTapBoost()
                            }
                        }
                    }
            )
        }

        // 3. Top Header Bar (Coins, Stage Badge, Settings)
        TopStatusHeader(
            profile = profile,
            snap = snap,
            onToggleLang = {
                updateProfile {
                    it.copy(language = if (it.language == AppLanguage.VI) AppLanguage.EN else AppLanguage.VI)
                }
            },
            onToggleSound = { updateProfile { it.copy(soundEnabled = !it.soundEnabled) } },
            onToggleHaptics = { updateProfile { it.copy(hapticsEnabled = !it.hapticsEnabled) } }
        )

        // 4. Floating Callouts & Upcoming Obstacle Hint
        GameplayFloatingHud(snap = snap, lang = profile.language)

        // 5. Phase-Specific Overlays
        when (snap.phase) {
            GamePhase.HOME -> {
                HomeOverlay(
                    profile = profile,
                    snap = snap,
                    onSliderPreview = { simulation.setSliderNormalized(it) },
                    onStartRun = { simulation.startRun() },
                    onOpenSkins = { showSkinModal = true },
                    onBuyUpgrade = { type ->
                        val lvl = when (type) {
                            UpgradeType.TITAN_MASS -> profile.titanMassLevel
                            UpgradeType.AGILE_FLUX -> profile.agileFluxLevel
                            UpgradeType.GOLD_BOUNTY -> profile.goldBountyLevel
                        }
                        val cost = type.costForLevel(lvl)
                        if (profile.coins >= cost) {
                            updateProfile { p ->
                                when (type) {
                                    UpgradeType.TITAN_MASS -> p.copy(coins = p.coins - cost, titanMassLevel = p.titanMassLevel + 1)
                                    UpgradeType.AGILE_FLUX -> p.copy(coins = p.coins - cost, agileFluxLevel = p.agileFluxLevel + 1)
                                    UpgradeType.GOLD_BOUNTY -> p.copy(coins = p.coins - cost, goldBountyLevel = p.goldBountyLevel + 1)
                                }
                            }
                        }
                    }
                )
            }

            GamePhase.RUNNING, GamePhase.GOAL_GAUNTLET, GamePhase.BOSS_CLASH -> {
                BottomScaleSliderDock(
                    snap = snap,
                    lang = profile.language,
                    onSliderSet = { simulation.setSliderNormalized(it) },
                    onBossTap = { simulation.onBossTapBoost() },
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }

            GamePhase.STAGE_CLEAR -> {
                StageClearSheet(
                    snap = snap,
                    lang = profile.language,
                    bonusClaimed = bonusClaimed,
                    onClaimBonus = {
                        if (!bonusClaimed) {
                            bonusClaimed = true
                            updateProfile { it.copy(coins = it.coins + snap.finalStageReward) }
                        }
                    },
                    onNextStage = { simulation.startRun() },
                    onHome = { simulation.returnHome() }
                )
            }

            GamePhase.GAME_OVER -> {
                GameOverSheet(
                    snap = snap,
                    lang = profile.language,
                    onRetry = { simulation.startRun() },
                    onHome = { simulation.returnHome() }
                )
            }
        }

        // 6. Skin Shop Modal
        AnimatedVisibility(
            visible = showSkinModal,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            SkinWardrobeModal(
                profile = profile,
                onClose = {
                    simulation.previewSkin(profile.selectedSkinId)
                    showSkinModal = false
                },
                onSelectOrBuy = { skin ->
                    if (profile.unlockedSkinIds.contains(skin.id)) {
                        updateProfile { it.copy(selectedSkinId = skin.id) }
                    } else if (profile.coins >= skin.coinCost) {
                        updateProfile {
                            it.copy(
                                coins = it.coins - skin.coinCost,
                                unlockedSkinIds = it.unlockedSkinIds + skin.id,
                                selectedSkinId = skin.id
                            )
                        }
                    } else {
                        simulation.previewSkin(skin.id)
                    }
                }
            )
        }
    }
}

@Composable
private fun TopStatusHeader(
    profile: PlayerProfileState,
    snap: GameSnapshot,
    onToggleLang: () -> Unit,
    onToggleSound: () -> Unit,
    onToggleHaptics: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Coin Vault Pill
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xCC0D1630))
                    .border(1.dp, Color(0x66FFD54F), RoundedCornerShape(50))
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFFFCA28))
                )
                Spacer(Modifier.width(8.dp))
                val displayedCoins = if (snap.phase == GamePhase.RUNNING || snap.phase == GamePhase.GOAL_GAUNTLET) {
                    profile.coins + snap.stageCoins
                } else {
                    profile.coins
                }
                Text(
                    text = "$displayedCoins",
                    color = Color(0xFFFFE082),
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 16.sp
                )
            }

            // Stage Indicator
            val stageLabel = if (profile.language == AppLanguage.VI) {
                if (snap.isBossStage) "⚔ MÀN ${snap.stageNumber} • BOSS" else "MÀN ${snap.stageNumber}"
            } else {
                if (snap.isBossStage) "⚔ STAGE ${snap.stageNumber} • BOSS" else "STAGE ${snap.stageNumber}"
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (snap.isBossStage) Color(0xDD880E4F) else Color(0xCC102048))
                    .border(1.dp, Color(0x6600E5FF), RoundedCornerShape(50))
                    .padding(horizontal = 16.dp, vertical = 7.dp)
            ) {
                Text(
                    text = stageLabel,
                    color = Color.White,
                    fontWeight = FontWeight.Black,
                    fontSize = 14.sp
                )
            }

            // Quick toggles
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xBB101B38))
                        .clickable { onToggleLang() }
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = profile.language.name,
                        color = Color(0xFF80D8FF),
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
                IconButton(
                    onClick = onToggleSound,
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Color(0xBB101B38))
                ) {
                    Icon(
                        imageVector = if (profile.soundEnabled) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                        contentDescription = "Sound",
                        tint = if (profile.soundEnabled) Color(0xFF00E5FF) else Color.Gray,
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(
                    onClick = onToggleHaptics,
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Color(0xBB101B38))
                ) {
                    Icon(
                        imageVector = Icons.Default.Vibration,
                        contentDescription = "Haptics",
                        tint = if (profile.hapticsEnabled) Color(0xFF69F0AE) else Color.Gray,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // Track Progress + Overdrive Bar during active run
        if (snap.phase == GamePhase.RUNNING || snap.phase == GamePhase.GOAL_GAUNTLET) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { snap.progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(50)),
                color = if (snap.isBossStage) Color(0xFFFF4081) else Color(0xFF00E5FF),
                trackColor = Color(0x55FFFFFF)
            )

            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Overdrive Fever Gauge
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Bolt,
                        contentDescription = null,
                        tint = Color(0xFFFFD600),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    val odProgress = if (snap.isOverdriveActive) (snap.overdriveTimer / 4.6f) else snap.overdriveMeter
                    LinearProgressIndicator(
                        progress = { odProgress.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .width(120.dp)
                            .height(6.dp)
                            .clip(RoundedCornerShape(50)),
                        color = if (snap.isOverdriveActive) Color(0xFFFFEA00) else Color(0xFFFF9100),
                        trackColor = Color(0x44FFFFFF)
                    )
                    if (snap.isOverdriveActive) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "OVERDRIVE!",
                            color = Color(0xFFFFEA00),
                            fontWeight = FontWeight.Black,
                            fontSize = 12.sp
                        )
                    }
                }

                if (snap.hasShield) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Color(0xAA004D60))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Icon(Icons.Default.Shield, contentDescription = null, tint = Color(0xFF00E5FF), modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = if (profile.language == AppLanguage.VI) "KHIÊN" else "SHIELD",
                            color = Color(0xFF84FFFF),
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                    }
                }

                if (snap.comboCount >= 2) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "COMBO x${snap.comboCount}",
                        color = Color(0xFF69F0AE),
                        fontWeight = FontWeight.Black,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun GameplayFloatingHud(snap: GameSnapshot, lang: AppLanguage) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(top = 92.dp, start = 20.dp, end = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val hint = if (lang == AppLanguage.VI) snap.upcomingHintVi else snap.upcomingHintEn
        if (snap.phase == GamePhase.RUNNING && hint.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xCC091328))
                    .border(1.dp, Color(0x6600E5FF), RoundedCornerShape(50))
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Text(
                    text = hint,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // Animated score/action callouts
        snap.callouts.lastOrNull()?.let { c ->
            val riseDp = (-c.age * 24f).roundToInt()
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.offset { IntOffset(0, riseDp) }
            ) {
                Text(
                    text = c.text,
                    color = Color(c.colorArgb),
                    fontWeight = FontWeight.Black,
                    fontSize = 22.sp,
                    textAlign = TextAlign.Center
                )
                if (c.subText.isNotEmpty()) {
                    Text(
                        text = c.subText,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeOverlay(
    profile: PlayerProfileState,
    snap: GameSnapshot,
    onSliderPreview: (Float) -> Unit,
    onStartRun: () -> Unit,
    onOpenSkins: () -> Unit,
    onBuyUpgrade: (UpgradeType) -> Unit
) {
    val lang = profile.language
    Box(modifier = Modifier.fillMaxSize()) {
        // Title Banner near top
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 58.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "TITANSHIFT 3D",
                color = Color.White,
                fontWeight = FontWeight.Black,
                fontSize = 30.sp,
                letterSpacing = 1.5.sp
            )
            Text(
                text = if (lang == AppLanguage.VI)
                    "Biến Hình Khổng Lồ & Tí Hon • Vượt Mọi Chướng Ngại"
                else
                    "Real-Time Size Morph Runner",
                color = Color(0xFF80D8FF),
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp
            )
        }

        // Bottom Controls: Live Scale Preview Slider + 3 Upgrades + Skins + Play
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Interactive Preview Slider on Home Screen!
            ScaleBarControl(
                sliderValue = snap.sliderValue,
                currentScale = snap.currentScale,
                zone = snap.scaleZone,
                lang = lang,
                sweetSpotCenter = null,
                onValueChange = onSliderPreview
            )

            Spacer(Modifier.height(12.dp))

            // 3 Upgrade Cards
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                UpgradeCard(
                    type = UpgradeType.TITAN_MASS,
                    level = profile.titanMassLevel,
                    coins = profile.coins,
                    lang = lang,
                    modifier = Modifier.weight(1f),
                    onClick = { onBuyUpgrade(UpgradeType.TITAN_MASS) }
                )
                UpgradeCard(
                    type = UpgradeType.AGILE_FLUX,
                    level = profile.agileFluxLevel,
                    coins = profile.coins,
                    lang = lang,
                    modifier = Modifier.weight(1f),
                    onClick = { onBuyUpgrade(UpgradeType.AGILE_FLUX) }
                )
                UpgradeCard(
                    type = UpgradeType.GOLD_BOUNTY,
                    level = profile.goldBountyLevel,
                    coins = profile.coins,
                    lang = lang,
                    modifier = Modifier.weight(1f),
                    onClick = { onBuyUpgrade(UpgradeType.GOLD_BOUNTY) }
                )
            }

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onOpenSkins,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xDD1E2B58)),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.height(58.dp)
                ) {
                    Icon(Icons.Default.Palette, contentDescription = null, tint = Color(0xFF80D8FF))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (lang == AppLanguage.VI) "TRANG PHỤC" else "SKINS",
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }

                Button(
                    onClick = onStartRun,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00C853)),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(58.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (lang == AppLanguage.VI) "BẮT ĐẦU CHẠY" else "START RUN",
                        color = Color.White,
                        fontWeight = FontWeight.Black,
                        fontSize = 18.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun UpgradeCard(
    type: UpgradeType,
    level: Int,
    coins: Int,
    lang: AppLanguage,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val cost = type.costForLevel(level)
    val canAfford = coins >= cost
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (canAfford) Color(0xDD13224A) else Color(0xAA0D152C))
            .border(
                width = 1.dp,
                color = if (canAfford) Color(0x9900E5FF) else Color(0x33FFFFFF),
                shape = RoundedCornerShape(16.dp)
            )
            .clickable(enabled = canAfford) { onClick() }
            .padding(vertical = 10.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Lv.$level",
            color = Color(0xFF00E5FF),
            fontWeight = FontWeight.Black,
            fontSize = 12.sp
        )
        Text(
            text = type.title(lang),
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
        Text(
            text = type.subtitle(level, lang),
            color = Color(0xFFB0BEC5),
            fontSize = 10.sp,
            maxLines = 1
        )
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(if (canAfford) Color(0xFFFFB300) else Color(0x44FFFFFF))
                .padding(horizontal = 10.dp, vertical = 3.dp)
        ) {
            Text(
                text = if (lang == AppLanguage.VI) "$cost Xu" else "$cost Coins",
                color = if (canAfford) Color(0xFF1A1200) else Color.LightGray,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun BottomScaleSliderDock(
    snap: GameSnapshot,
    lang: AppLanguage,
    onSliderSet: (Float) -> Unit,
    onBossTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Boss Clash Tug-of-War Meter + Tap Button
        if (snap.phase == GamePhase.BOSS_CLASH && snap.boss != null) {
            val boss = snap.boss
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xDD1A0B28))
                    .border(1.5.dp, Color(0xFFFFB300), RoundedCornerShape(18.dp))
                    .padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (lang == AppLanguage.VI)
                        "⚔ ĐẤU LỰC BOSS (${"%.1f".format(boss.timeRemaining)}s) — CHẠM LIÊN TỤC!"
                    else
                        "⚔ BOSS CLASH (${"%.1f".format(boss.timeRemaining)}s) — TAP RAPIDLY!",
                    color = Color(0xFFFFD54F),
                    fontWeight = FontWeight.Black,
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { (boss.clashProgress / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(12.dp)
                        .clip(RoundedCornerShape(50)),
                    color = Color(0xFFFFAB00),
                    trackColor = Color(0xFFFF1744)
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onBossTap,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6D00)),
                    shape = RoundedCornerShape(50)
                ) {
                    Text(
                        text = if (lang == AppLanguage.VI) "⚡ TUNG CÚ ĐẤM TITAN!" else "⚡ TITAN STRIKE!",
                        color = Color.White,
                        fontWeight = FontWeight.Black
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        ScaleBarControl(
            sliderValue = snap.sliderValue,
            currentScale = snap.currentScale,
            zone = snap.scaleZone,
            lang = lang,
            sweetSpotCenter = if (snap.phase == GamePhase.BOSS_CLASH) snap.boss?.sweetSpotSliderCenter else null,
            onValueChange = onSliderSet
        )
    }
}

@Composable
private fun ScaleBarControl(
    sliderValue: Float,
    currentScale: Float,
    zone: ScaleZone,
    lang: AppLanguage,
    sweetSpotCenter: Float?,
    onValueChange: (Float) -> Unit
) {
    var barWidthPx by remember { mutableStateOf(800f) }

    val zoneLabel = when (zone) {
        ScaleZone.MICRO -> if (lang == AppLanguage.VI) "TÍ HON • NHANH" else "MICRO • AGILE"
        ScaleZone.SYNC -> if (lang == AppLanguage.VI) "CÂN BẰNG • CHUẨN" else "SYNC • BALANCED"
        ScaleZone.TITAN -> if (lang == AppLanguage.VI) "TITAN • PHÁ HỦY" else "TITAN • SMASH"
    }
    val zoneColor = when (zone) {
        ScaleZone.MICRO -> Color(0xFF00E5FF)
        ScaleZone.SYNC -> Color(0xFF00E676)
        ScaleZone.TITAN -> Color(0xFFFF6D00)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xCC0A1226))
            .border(1.5.dp, zoneColor.copy(alpha = 0.7f), RoundedCornerShape(24.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (lang == AppLanguage.VI) "KÉO ĐỂ ĐỔI CỠ" else "SLIDE TO MORPH",
                color = Color(0xFFB0BEC5),
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "${"%.2f".format(java.util.Locale.US, currentScale)}x  •  $zoneLabel",
                color = zoneColor,
                fontWeight = FontWeight.Black,
                fontSize = 13.sp,
                maxLines = 1,
                softWrap = false
            )
        }

        Spacer(Modifier.height(8.dp))

        // Custom zero-latency interactive slider track
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .onSizeChanged { barWidthPx = it.width.coerceAtLeast(1).toFloat() }
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        onValueChange((offset.x / barWidthPx).coerceIn(0f, 1f))
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        onValueChange((change.position.x / barWidthPx).coerceIn(0f, 1f))
                    }
                },
            contentAlignment = Alignment.CenterStart
        ) {
            // 3-zone gradient track
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(14.dp)
                    .clip(RoundedCornerShape(50))
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                Color(0xFF00E5FF),
                                Color(0xFF00E676),
                                Color(0xFFFFAB00),
                                Color(0xFFFF3D00)
                            )
                        )
                    )
            )

            // Boss Sweet Spot Highlight if active
            if (sweetSpotCenter != null) {
                val leftFrac = (sweetSpotCenter - 0.16f).coerceIn(0f, 0.84f)
                Box(
                    modifier = Modifier
                        .offset { IntOffset((leftFrac * barWidthPx).roundToInt(), 0) }
                        .width(68.dp)
                        .height(22.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0x99FFD600))
                        .border(2.dp, Color.White, RoundedCornerShape(8.dp))
                )
            }

            // Glowing Slider Thumb
            val thumbOffsetPx = (sliderValue.coerceIn(0f, 1f) * (barWidthPx - 32.dp.value * 2.5f))
                .coerceAtLeast(0f)
            Box(
                modifier = Modifier
                    .offset { IntOffset(((sliderValue * (barWidthPx - 72f)).roundToInt()).coerceAtLeast(0), 0) }
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .border(3.dp, zoneColor, CircleShape)
            )
        }
    }
}

@Composable
private fun StageClearSheet(
    snap: GameSnapshot,
    lang: AppLanguage,
    bonusClaimed: Boolean,
    onClaimBonus: () -> Unit,
    onNextStage: () -> Unit,
    onHome: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x99040918)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .padding(24.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(Color(0xF2101D42))
                .border(2.dp, Color(0xFF00E676), RoundedCornerShape(28.dp))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = if (lang == AppLanguage.VI) "HOÀN THÀNH MÀN ${snap.stageNumber}!" else "STAGE ${snap.stageNumber} CLEAR!",
                color = Color(0xFF69F0AE),
                fontWeight = FontWeight.Black,
                fontSize = 26.sp,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (lang == AppLanguage.VI)
                    "Hệ số phá đảo: x${"%.1f".format(snap.earnedMultiplier)}"
                else
                    "Breakthrough Multiplier: x${"%.1f".format(snap.earnedMultiplier)}",
                color = Color(0xFF80D8FF),
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
            Spacer(Modifier.height(14.dp))
            val totalEarned = if (bonusClaimed) snap.finalStageReward * 2 else snap.finalStageReward
            Text(
                text = if (lang == AppLanguage.VI) "+$totalEarned XU" else "+$totalEarned COINS",
                color = Color(0xFFFFD54F),
                fontWeight = FontWeight.Black,
                fontSize = 34.sp
            )
            Spacer(Modifier.height(18.dp))

            if (!bonusClaimed) {
                Button(
                    onClick = onClaimBonus,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFAB00)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                ) {
                    Text(
                        text = if (lang == AppLanguage.VI) "🎁 NHẬN THƯỞNG x2 MIỄN PHÍ!" else "🎁 CLAIM FREE x2 BONUS!",
                        color = Color(0xFF1A1200),
                        fontWeight = FontWeight.Black
                    )
                }
                Spacer(Modifier.height(10.dp))
            }

            Button(
                onClick = onNextStage,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00C853)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
            ) {
                Text(
                    text = if (lang == AppLanguage.VI) "TIẾP TỤC MÀN KẾ ▶" else "NEXT STAGE ▶",
                    color = Color.White,
                    fontWeight = FontWeight.Black,
                    fontSize = 17.sp
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (lang == AppLanguage.VI) "Về Trang Chủ / Nâng Cấp" else "Back to Home / Upgrades",
                color = Color(0xFFB0BEC5),
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clickable { onHome() }
                    .padding(8.dp)
            )
        }
    }
}

@Composable
private fun GameOverSheet(
    snap: GameSnapshot,
    lang: AppLanguage,
    onRetry: () -> Unit,
    onHome: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xAA080816)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .padding(24.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(Color(0xF21B1026))
                .border(2.dp, Color(0xFFFF5252), RoundedCornerShape(28.dp))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = if (lang == AppLanguage.VI) "THẤT BẠI!" else "WIPEOUT!",
                color = Color(0xFFFF5252),
                fontWeight = FontWeight.Black,
                fontSize = 28.sp
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = if (lang == AppLanguage.VI) snap.failReasonVi else snap.failReasonEn,
                color = Color.White,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (lang == AppLanguage.VI)
                    "Đã tiến được ${(snap.progress * 100).roundToInt()}% chặng đường"
                else
                    "Reached ${(snap.progress * 100).roundToInt()}% of track",
                color = Color(0xFFB0BEC5),
                fontSize = 13.sp
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5252)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, tint = Color.White)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (lang == AppLanguage.VI) "CHƠI LẠI NGAY" else "TRY AGAIN",
                    color = Color.White,
                    fontWeight = FontWeight.Black,
                    fontSize = 17.sp
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = if (lang == AppLanguage.VI) "Về Trang Chủ Nâng Cấp" else "Back to Upgrades",
                color = Color(0xFF80D8FF),
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clickable { onHome() }
                    .padding(8.dp)
            )
        }
    }
}

@Composable
private fun SkinWardrobeModal(
    profile: PlayerProfileState,
    onClose: () -> Unit,
    onSelectOrBuy: (HeroSkin) -> Unit
) {
    val lang = profile.language
    Surface(
        color = Color(0xDD080F24),
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(18.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (lang == AppLanguage.VI) "KHO GIÁP CHIẾN BINH 3D" else "3D HERO ARMORY",
                    color = Color.White,
                    fontWeight = FontWeight.Black,
                    fontSize = 20.sp
                )
                Button(
                    onClick = onClose,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF263868)),
                    shape = RoundedCornerShape(50)
                ) {
                    Text(
                        text = if (lang == AppLanguage.VI) "ĐÓNG" else "CLOSE",
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxHeight()
            ) {
                items(SkinCatalog.skins) { skin ->
                    val unlocked = profile.unlockedSkinIds.contains(skin.id)
                    val equipped = profile.selectedSkinId == skin.id
                    val canBuy = profile.coins >= skin.coinCost

                    Column(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(if (equipped) Color(0xFF1A3365) else Color(0xFF101C3B))
                            .border(
                                width = if (equipped) 2.dp else 1.dp,
                                color = if (equipped) Color(0xFF00E5FF) else Color(0x33FFFFFF),
                                shape = RoundedCornerShape(20.dp)
                            )
                            .clickable { onSelectOrBuy(skin) }
                            .padding(14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Color Swatch Crest Preview
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(
                                Modifier
                                    .size(26.dp)
                                    .clip(CircleShape)
                                    .background(Color(skin.primaryArmor.toArgbInt()))
                            )
                            Box(
                                Modifier
                                    .size(26.dp)
                                    .clip(CircleShape)
                                    .background(Color(skin.coreGlow.toArgbInt()))
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = skin.displayName(lang),
                            color = Color.White,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = skin.displayDesc(lang),
                            color = Color(0xFFB0BEC5),
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center,
                            minLines = 2,
                            maxLines = 2
                        )
                        Spacer(Modifier.height(10.dp))

                        if (equipped) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF00E676), modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = if (lang == AppLanguage.VI) "ĐANG DÙNG" else "EQUIPPED",
                                    color = Color(0xFF00E676),
                                    fontWeight = FontWeight.Black,
                                    fontSize = 12.sp
                                )
                            }
                        } else if (unlocked) {
                            Text(
                                text = if (lang == AppLanguage.VI) "CHỌN MẶC" else "EQUIP",
                                color = Color(0xFF80D8FF),
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        } else {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(if (canBuy) Color(0xFFFFB300) else Color(0x44FFFFFF))
                                    .padding(horizontal = 12.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Lock,
                                    contentDescription = null,
                                    tint = if (canBuy) Color(0xFF1A1200) else Color.LightGray,
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = if (lang == AppLanguage.VI) "${skin.coinCost} Xu" else "${skin.coinCost} Coins",
                                    color = if (canBuy) Color(0xFF1A1200) else Color.LightGray,
                                    fontWeight = FontWeight.Black,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
