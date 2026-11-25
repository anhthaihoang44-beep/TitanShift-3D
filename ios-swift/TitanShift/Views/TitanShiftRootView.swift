import SwiftUI

struct TitanShiftRootView: View {
    @StateObject private var viewModel = GameViewModel()
    @State private var lastDragX: CGFloat? = nil

    var body: some View {
        GeometryReader { geo in
            let screenWidth = max(1, geo.size.width)
            let snap = viewModel.snapshot
            let lang = viewModel.profile.language

            ZStack {
                // 1. Live 60fps SceneKit 3D Viewport
                TitanSceneView(viewModel: viewModel)
                    .ignoresSafeArea()

                // 2. Full-screen One-Finger Drag Gesture during Gameplay
                if snap.phase == .running || snap.phase == .goalGauntlet || snap.phase == .bossClash {
                    Color.clear
                        .contentShape(Rectangle())
                        .gesture(
                            DragGesture(minimumDistance: 0)
                                .onChanged { value in
                                    if let prev = lastDragX {
                                        let dx = value.location.x - prev
                                        let deltaNorm = Float(dx / (screenWidth * 0.62))
                                        viewModel.adjustSliderByDelta(deltaNorm)
                                    }
                                    lastDragX = value.location.x
                                }
                                .onEnded { value in
                                    if abs(value.translation.width) < 6 && abs(value.translation.height) < 6 && snap.phase == .bossClash {
                                        viewModel.onBossTapBoost()
                                    }
                                    lastDragX = nil
                                }
                        )
                }

                // 3. Phase-Specific Overlays
                switch snap.phase {
                case .home:
                    HomeOverlayView(viewModel: viewModel)
                case .running, .goalGauntlet, .bossClash:
                    VStack {
                        Spacer()
                        BottomMorphSliderDock(viewModel: viewModel)
                    }
                case .stageClear:
                    StageClearModalView(viewModel: viewModel)
                case .gameOver:
                    GameOverModalView(viewModel: viewModel)
                }

                // 4. Top Header HUD & Callouts (rendered above overlays so buttons stay clickable)
                VStack {
                    TopBarHUD(viewModel: viewModel)
                    FloatingCalloutOverlay(snap: snap, lang: lang)
                        .allowsHitTesting(false)
                    Spacer()
                }

                // 5. 3D Skin Wardrobe Sheet
                if viewModel.showSkinWardrobe {
                    SkinWardrobeOverlay(viewModel: viewModel)
                        .transition(.opacity)
                }
            }
        }
        .preferredColorScheme(.dark)
    }
}

private struct TopBarHUD: View {
    @ObservedObject var viewModel: GameViewModel

    var body: some View {
        let profile = viewModel.profile
        let snap = viewModel.snapshot
        let lang = profile.language

        VStack(spacing: 8) {
            HStack {
                // Coin Pill
                HStack(spacing: 6) {
                    Circle()
                        .fill(Color.yellow)
                        .frame(width: 14, height: 14)
                    let displayCoins = (snap.phase == .running || snap.phase == .goalGauntlet)
                        ? (profile.coins + snap.stageCoins)
                        : profile.coins
                    Text("\(displayCoins)")
                        .font(.system(size: 16, weight: .heavy, design: .rounded))
                        .foregroundColor(Color(red: 1.0, green: 0.88, blue: 0.50))
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 7)
                .background(Color(red: 0.05, green: 0.09, blue: 0.19).opacity(0.85))
                .clipShape(Capsule())
                .overlay(Capsule().stroke(Color.yellow.opacity(0.4), lineWidth: 1))

                Spacer()

                // Stage Badge
                let stageText: String = {
                    if lang == .vi {
                        return snap.isBossStage ? "⚔ MÀN \(snap.stageNumber) • BOSS" : "MÀN \(snap.stageNumber)"
                    } else {
                        return snap.isBossStage ? "⚔ STAGE \(snap.stageNumber) • BOSS" : "STAGE \(snap.stageNumber)"
                    }
                }()

                Text(stageText)
                    .font(.system(size: 14, weight: .black, design: .rounded))
                    .foregroundColor(.white)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 7)
                    .background((snap.isBossStage ? Color.pink.opacity(0.75) : Color(red: 0.06, green: 0.13, blue: 0.28).opacity(0.85)))
                    .clipShape(Capsule())
                    .overlay(Capsule().stroke(Color.cyan.opacity(0.45), lineWidth: 1))

                Spacer()

                // Language + Sound + Haptics
                HStack(spacing: 6) {
                    Button(action: { viewModel.toggleLanguage() }) {
                        Text(lang.rawValue)
                            .font(.system(size: 12, weight: .bold))
                            .foregroundColor(.cyan)
                            .padding(.horizontal, 8)
                            .padding(.vertical, 6)
                            .background(Color.black.opacity(0.55))
                            .clipShape(RoundedRectangle(cornerRadius: 10))
                    }

                    Button(action: { viewModel.toggleSound() }) {
                        Image(systemName: profile.soundEnabled ? "speaker.wave.2.fill" : "speaker.slash.fill")
                            .font(.system(size: 14, weight: .bold))
                            .foregroundColor(profile.soundEnabled ? .cyan : .gray)
                            .frame(width: 32, height: 32)
                            .background(Color.black.opacity(0.55))
                            .clipShape(Circle())
                    }

                    Button(action: { viewModel.toggleHaptics() }) {
                        Image(systemName: "iphone.radiowaves.left.and.right")
                            .font(.system(size: 14, weight: .bold))
                            .foregroundColor(profile.hapticsEnabled ? .green : .gray)
                            .frame(width: 32, height: 32)
                            .background(Color.black.opacity(0.55))
                            .clipShape(Circle())
                    }
                }
            }

            if snap.phase == .running || snap.phase == .goalGauntlet {
                ProgressView(value: Double(snap.progress))
                    .tint(snap.isBossStage ? .pink : .cyan)

                HStack {
                    Image(systemName: "bolt.fill")
                        .foregroundColor(.yellow)
                        .font(.system(size: 13))
                    let odVal = snap.isOverdriveActive ? (snap.overdriveTimer / 4.6) : snap.overdriveMeter
                    ProgressView(value: Double(min(max(odVal, 0), 1)))
                        .tint(snap.isOverdriveActive ? .yellow : .orange)
                        .frame(width: 115)

                    if snap.isOverdriveActive {
                        Text("OVERDRIVE!")
                            .font(.system(size: 11, weight: .black))
                            .foregroundColor(.yellow)
                    }

                    Spacer()

                    if snap.hasShield {
                        Label(lang == .vi ? "KHIÊN" : "SHIELD", systemImage: "shield.fill")
                            .font(.system(size: 11, weight: .bold))
                            .foregroundColor(.cyan)
                            .padding(.horizontal, 8)
                            .padding(.vertical, 3)
                            .background(Color.cyan.opacity(0.18))
                            .clipShape(Capsule())
                    }

                    if snap.comboCount >= 2 {
                        Text("COMBO x\(snap.comboCount)")
                            .font(.system(size: 13, weight: .black, design: .rounded))
                            .foregroundColor(.green)
                    }
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 8)
    }
}

private struct FloatingCalloutOverlay: View {
    let snap: GameSnapshot
    let lang: AppLanguage

    var body: some View {
        VStack(spacing: 8) {
            let hint = lang == .vi ? snap.upcomingHintVi : snap.upcomingHintEn
            if snap.phase == .running && !hint.isEmpty {
                Text(hint)
                    .font(.system(size: 13, weight: .bold, design: .rounded))
                    .foregroundColor(.white)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 6)
                    .background(Color(red: 0.04, green: 0.08, blue: 0.18).opacity(0.85))
                    .clipShape(Capsule())
                    .overlay(Capsule().stroke(Color.cyan.opacity(0.45), lineWidth: 1))
            }

            if let callout = snap.callouts.last {
                VStack(spacing: 2) {
                    Text(callout.text)
                        .font(.system(size: 22, weight: .black, design: .rounded))
                        .foregroundColor(callout.color)
                        .shadow(color: .black.opacity(0.7), radius: 4)
                    if !callout.subText.isEmpty {
                        Text(callout.subText)
                            .font(.system(size: 13, weight: .bold))
                            .foregroundColor(.white)
                    }
                }
                .offset(y: CGFloat(-callout.age * 24))
            }
        }
        .padding(.top, 8)
    }
}

private struct HomeOverlayView: View {
    @ObservedObject var viewModel: GameViewModel

    var body: some View {
        let profile = viewModel.profile
        let snap = viewModel.snapshot
        let lang = profile.language

        VStack {
            VStack(spacing: 4) {
                Text("TITANSHIFT 3D")
                    .font(.system(size: 30, weight: .black, design: .rounded))
                    .foregroundColor(.white)
                    .tracking(1.5)
                Text(lang == .vi ? "Biến Hình Khổng Lồ & Tí Hon • Vượt Mọi Chướng Ngại" : "Real-Time Size Morph Runner")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundColor(.cyan)
            }
            .padding(.top, 58)

            Spacer()

            VStack(spacing: 12) {
                ScaleSliderBarView(
                    sliderValue: snap.sliderValue,
                    currentScale: snap.currentScale,
                    zone: snap.scaleZone,
                    lang: lang,
                    sweetSpotCenter: nil,
                    onChange: { viewModel.setSliderNormalized($0) }
                )

                // 3 Upgrade Cards
                HStack(spacing: 8) {
                    ForEach(UpgradeType.allCases) { type in
                        let lvl: Int = {
                            switch type {
                            case .titanMass: return profile.titanMassLevel
                            case .agileFlux: return profile.agileFluxLevel
                            case .goldBounty: return profile.goldBountyLevel
                            }
                        }()
                        let cost = type.costForLevel(lvl)
                        let canAfford = profile.coins >= cost

                        Button(action: { viewModel.buyUpgrade(type) }) {
                            VStack(spacing: 4) {
                                Text("Lv.\(lvl)")
                                    .font(.system(size: 12, weight: .black))
                                    .foregroundColor(.cyan)
                                Text(type.title(lang))
                                    .font(.system(size: 12, weight: .bold))
                                    .foregroundColor(.white)
                                    .lineLimit(1)
                                Text(type.subtitle(level: lvl, lang: lang))
                                    .font(.system(size: 10))
                                    .foregroundColor(.gray)
                                    .lineLimit(1)
                                Text(lang == .vi ? "\(cost) Xu" : "\(cost) Coins")
                                    .font(.system(size: 11, weight: .heavy))
                                    .foregroundColor(canAfford ? .black : .gray)
                                    .padding(.horizontal, 10)
                                    .padding(.vertical, 3)
                                    .background(canAfford ? Color.yellow : Color.white.opacity(0.15))
                                    .clipShape(Capsule())
                            }
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 10)
                            .padding(.horizontal, 6)
                            .background(Color(red: 0.07, green: 0.13, blue: 0.28).opacity(0.88))
                            .clipShape(RoundedRectangle(cornerRadius: 16))
                            .overlay(
                                RoundedRectangle(cornerRadius: 16)
                                    .stroke(canAfford ? Color.cyan.opacity(0.6) : Color.white.opacity(0.15), lineWidth: 1)
                            )
                        }
                        .disabled(!canAfford)
                    }
                }

                // Skins + Start Run Buttons
                HStack(spacing: 10) {
                    Button(action: { viewModel.showSkinWardrobe = true }) {
                        HStack(spacing: 6) {
                            Image(systemName: "paintpalette.fill")
                                .foregroundColor(.cyan)
                            Text(lang == .vi ? "TRANG PHỤC" : "SKINS")
                                .font(.system(size: 15, weight: .bold))
                                .foregroundColor(.white)
                        }
                        .frame(height: 56)
                        .padding(.horizontal, 18)
                        .background(Color(red: 0.12, green: 0.18, blue: 0.36).opacity(0.92))
                        .clipShape(RoundedRectangle(cornerRadius: 18))
                    }

                    Button(action: { viewModel.startRun() }) {
                        HStack(spacing: 8) {
                            Image(systemName: "play.fill")
                            Text(lang == .vi ? "BẮT ĐẦU CHẠY" : "START RUN")
                                .font(.system(size: 18, weight: .black, design: .rounded))
                        }
                        .foregroundColor(.white)
                        .frame(maxWidth: .infinity)
                        .frame(height: 56)
                        .background(Color.green)
                        .clipShape(RoundedRectangle(cornerRadius: 18))
                    }
                }
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 16)
        }
    }
}

private struct BottomMorphSliderDock: View {
    @ObservedObject var viewModel: GameViewModel

    var body: some View {
        let snap = viewModel.snapshot
        let lang = viewModel.profile.language

        VStack(spacing: 10) {
            if snap.phase == .bossClash, let boss = snap.boss {
                VStack(spacing: 8) {
                    Text(
                        lang == .vi
                            ? String(format: "⚔ ĐẤU LỰC BOSS (%.1fs) — CHẠM LIÊN TỤC!", locale: Locale(identifier: "en_US_POSIX"), boss.timeRemaining)
                            : String(format: "⚔ BOSS CLASH (%.1fs) — TAP RAPIDLY!", locale: Locale(identifier: "en_US_POSIX"), boss.timeRemaining)
                    )
                    .font(.system(size: 13, weight: .black))
                    .foregroundColor(.yellow)

                    ProgressView(value: Double(min(max(boss.clashProgress / 100.0, 0), 1)))
                        .tint(.orange)

                    Button(action: { viewModel.onBossTapBoost() }) {
                        Text(lang == .vi ? "⚡ TUNG CÚ ĐẤM TITAN!" : "⚡ TITAN STRIKE!")
                            .font(.system(size: 15, weight: .black))
                            .foregroundColor(.white)
                            .padding(.horizontal, 20)
                            .padding(.vertical, 8)
                            .background(Color.orange)
                            .clipShape(Capsule())
                    }
                }
                .padding(12)
                .background(Color(red: 0.12, green: 0.05, blue: 0.18).opacity(0.9))
                .clipShape(RoundedRectangle(cornerRadius: 18))
                .overlay(RoundedRectangle(cornerRadius: 18).stroke(Color.yellow, lineWidth: 1.5))
            }

            ScaleSliderBarView(
                sliderValue: snap.sliderValue,
                currentScale: snap.currentScale,
                zone: snap.scaleZone,
                lang: lang,
                sweetSpotCenter: snap.phase == .bossClash ? snap.boss?.sweetSpotSliderCenter : nil,
                onChange: { viewModel.setSliderNormalized($0) }
            )
        }
        .padding(.horizontal, 18)
        .padding(.bottom, 20)
    }
}

private struct ScaleSliderBarView: View {
    let sliderValue: Float
    let currentScale: Float
    let zone: ScaleZone
    let lang: AppLanguage
    let sweetSpotCenter: Float?
    let onChange: (Float) -> Void

    private var zoneColor: Color {
        switch zone {
        case .micro: return .cyan
        case .sync: return .green
        case .titan: return .orange
        }
    }

    private var zoneLabel: String {
        switch zone {
        case .micro: return lang == .vi ? "TÍ HON • NHANH" : "MICRO • AGILE"
        case .sync: return lang == .vi ? "CÂN BẰNG • CHUẨN" : "SYNC • BALANCED"
        case .titan: return lang == .vi ? "TITAN • PHÁ HỦY" : "TITAN • SMASH"
        }
    }

    var body: some View {
        VStack(spacing: 8) {
            HStack {
                Text(lang == .vi ? "KÉO ĐỂ ĐỔI CỠ" : "SLIDE TO MORPH")
                    .font(.system(size: 11, weight: .bold))
                    .foregroundColor(.gray)
                    .lineLimit(1)
                Spacer(minLength: 8)
                Text(String(format: "%.2fx  •  %@", locale: Locale(identifier: "en_US_POSIX"), currentScale, zoneLabel))
                    .font(.system(size: 13, weight: .black, design: .rounded))
                    .foregroundColor(zoneColor)
                    .lineLimit(1)
                    .minimumScaleFactor(0.75)
            }

            GeometryReader { geo in
                let width = max(1, geo.size.width)
                ZStack(alignment: .leading) {
                    Capsule()
                        .fill(
                            LinearGradient(
                                colors: [.cyan, .green, .orange, .red],
                                startPoint: .leading,
                                endPoint: .trailing
                            )
                        )
                        .frame(height: 14)

                    if let sweet = sweetSpotCenter {
                        let sweetWidth: CGFloat = 68
                        let sweetX = CGFloat(min(max(sweet, 0.16), 0.84)) * width - sweetWidth * 0.5
                        RoundedRectangle(cornerRadius: 8)
                            .fill(Color.yellow.opacity(0.55))
                            .frame(width: sweetWidth, height: 22)
                            .overlay(RoundedRectangle(cornerRadius: 8).stroke(Color.white, lineWidth: 2))
                            .offset(x: max(0, sweetX))
                    }

                    let thumbSize: CGFloat = 30
                    let thumbX = CGFloat(min(max(sliderValue, 0), 1)) * max(0, width - thumbSize)
                    Circle()
                        .fill(Color.white)
                        .frame(width: thumbSize, height: thumbSize)
                        .overlay(Circle().stroke(zoneColor, lineWidth: 3))
                        .offset(x: thumbX)
                }
                .contentShape(Rectangle())
                .gesture(
                    DragGesture(minimumDistance: 0)
                        .onChanged { value in
                            let norm = Float(value.location.x / width)
                            onChange(min(max(norm, 0), 1))
                        }
                )
            }
            .frame(height: 34)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .background(Color(red: 0.04, green: 0.07, blue: 0.16).opacity(0.86))
        .clipShape(RoundedRectangle(cornerRadius: 22))
        .overlay(RoundedRectangle(cornerRadius: 22).stroke(zoneColor.opacity(0.7), lineWidth: 1.5))
    }
}

private struct StageClearModalView: View {
    @ObservedObject var viewModel: GameViewModel

    var body: some View {
        let snap = viewModel.snapshot
        let lang = viewModel.profile.language
        let shownReward = viewModel.bonusClaimed ? snap.finalStageReward * 2 : snap.finalStageReward

        ZStack {
            Color.black.opacity(0.62).ignoresSafeArea()

            VStack(spacing: 14) {
                Text(lang == .vi ? "HOÀN THÀNH MÀN \(snap.stageNumber)!" : "STAGE \(snap.stageNumber) CLEAR!")
                    .font(.system(size: 26, weight: .black, design: .rounded))
                    .foregroundColor(.green)

                Text(
                    lang == .vi
                        ? String(format: "Hệ số phá đảo: x%.1f", locale: Locale(identifier: "en_US_POSIX"), snap.earnedMultiplier)
                        : String(format: "Breakthrough Multiplier: x%.1f", locale: Locale(identifier: "en_US_POSIX"), snap.earnedMultiplier)
                )
                .font(.system(size: 16, weight: .bold))
                .foregroundColor(.cyan)

                Text(lang == .vi ? "+\(shownReward) XU" : "+\(shownReward) COINS")
                    .font(.system(size: 34, weight: .black, design: .rounded))
                    .foregroundColor(.yellow)

                if !viewModel.bonusClaimed {
                    Button(action: { viewModel.claimDoubleBonus() }) {
                        Text(lang == .vi ? "🎁 NHẬN THƯỞNG x2 MIỄN PHÍ!" : "🎁 CLAIM FREE x2 BONUS!")
                            .font(.system(size: 15, weight: .black))
                            .foregroundColor(.black)
                            .frame(maxWidth: .infinity)
                            .frame(height: 50)
                            .background(Color.yellow)
                            .clipShape(RoundedRectangle(cornerRadius: 16))
                    }
                }

                Button(action: { viewModel.startRun() }) {
                    Text(lang == .vi ? "TIẾP TỤC MÀN KẾ ▶" : "NEXT STAGE ▶")
                        .font(.system(size: 17, weight: .black))
                        .foregroundColor(.white)
                        .frame(maxWidth: .infinity)
                        .frame(height: 54)
                        .background(Color.green)
                        .clipShape(RoundedRectangle(cornerRadius: 16))
                }

                Button(action: { viewModel.returnHome() }) {
                    Text(lang == .vi ? "Về Trang Chủ / Nâng Cấp" : "Back to Home / Upgrades")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundColor(.gray)
                }
            }
            .padding(24)
            .background(Color(red: 0.06, green: 0.11, blue: 0.25))
            .clipShape(RoundedRectangle(cornerRadius: 28))
            .overlay(RoundedRectangle(cornerRadius: 28).stroke(Color.green, lineWidth: 2))
            .padding(24)
        }
    }
}

private struct GameOverModalView: View {
    @ObservedObject var viewModel: GameViewModel

    var body: some View {
        let snap = viewModel.snapshot
        let lang = viewModel.profile.language

        ZStack {
            Color.black.opacity(0.68).ignoresSafeArea()

            VStack(spacing: 14) {
                Text(lang == .vi ? "THẤT BẠI!" : "WIPEOUT!")
                    .font(.system(size: 28, weight: .black, design: .rounded))
                    .foregroundColor(.red)

                Text(lang == .vi ? snap.failReasonVi : snap.failReasonEn)
                    .font(.system(size: 15, weight: .medium))
                    .foregroundColor(.white)
                    .multilineTextAlignment(.center)

                Text(
                    lang == .vi
                        ? "Đã tiến được \(Int((snap.progress * 100).rounded()))% chặng đường"
                        : "Reached \(Int((snap.progress * 100).rounded()))% of track"
                )
                .font(.system(size: 13))
                .foregroundColor(.gray)

                Button(action: { viewModel.startRun() }) {
                    Label(lang == .vi ? "CHƠI LẠI NGAY" : "TRY AGAIN", systemImage: "arrow.clockwise")
                        .font(.system(size: 17, weight: .black))
                        .foregroundColor(.white)
                        .frame(maxWidth: .infinity)
                        .frame(height: 54)
                        .background(Color.red)
                        .clipShape(RoundedRectangle(cornerRadius: 16))
                }

                Button(action: { viewModel.returnHome() }) {
                    Text(lang == .vi ? "Về Trang Chủ Nâng Cấp" : "Back to Upgrades")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundColor(.cyan)
                }
            }
            .padding(24)
            .background(Color(red: 0.11, green: 0.06, blue: 0.16))
            .clipShape(RoundedRectangle(cornerRadius: 28))
            .overlay(RoundedRectangle(cornerRadius: 28).stroke(Color.red, lineWidth: 2))
            .padding(24)
        }
    }
}

private struct SkinWardrobeOverlay: View {
    @ObservedObject var viewModel: GameViewModel
    private let columns = [GridItem(.flexible()), GridItem(.flexible())]

    var body: some View {
        let profile = viewModel.profile
        let lang = profile.language

        ZStack {
            Color(red: 0.03, green: 0.06, blue: 0.14).opacity(0.90)
                .ignoresSafeArea()

            VStack(spacing: 14) {
                HStack {
                    Text(lang == .vi ? "KHO GIÁP CHIẾN BINH 3D" : "3D HERO ARMORY")
                        .font(.system(size: 20, weight: .black, design: .rounded))
                        .foregroundColor(.white)
                    Spacer()
                    Button(action: {
                        viewModel.restoreEquippedSkinPreview()
                        viewModel.showSkinWardrobe = false
                    }) {
                        Text(lang == .vi ? "ĐÓNG" : "CLOSE")
                            .font(.system(size: 13, weight: .bold))
                            .foregroundColor(.white)
                            .padding(.horizontal, 16)
                            .padding(.vertical, 8)
                            .background(Color.blue.opacity(0.5))
                            .clipShape(Capsule())
                    }
                }

                ScrollView {
                    LazyVGrid(columns: columns, spacing: 12) {
                        ForEach(SkinCatalog.skins) { skin in
                            let unlocked = profile.unlockedSkinIds.contains(skin.id)
                            let equipped = profile.selectedSkinId == skin.id
                            let canBuy = profile.coins >= skin.coinCost

                            Button(action: { viewModel.selectOrUnlockSkin(skin) }) {
                                VStack(spacing: 8) {
                                    HStack(spacing: 6) {
                                        Circle().fill(skin.primaryArmor.swiftUIColor).frame(width: 24, height: 24)
                                        Circle().fill(skin.coreGlow.swiftUIColor).frame(width: 24, height: 24)
                                    }
                                    Text(skin.displayName(lang))
                                        .font(.system(size: 14, weight: .heavy))
                                        .foregroundColor(.white)
                                        .multilineTextAlignment(.center)
                                    Text(skin.displayDesc(lang))
                                        .font(.system(size: 11))
                                        .foregroundColor(.gray)
                                        .multilineTextAlignment(.center)
                                        .lineLimit(2)

                                    if equipped {
                                        Label(lang == .vi ? "ĐANG DÙNG" : "EQUIPPED", systemImage: "checkmark.circle.fill")
                                            .font(.system(size: 12, weight: .black))
                                            .foregroundColor(.green)
                                    } else if unlocked {
                                        Text(lang == .vi ? "CHỌN MẶC" : "EQUIP")
                                            .font(.system(size: 12, weight: .bold))
                                            .foregroundColor(.cyan)
                                    } else {
                                        Label(lang == .vi ? "\(skin.coinCost) Xu" : "\(skin.coinCost) Coins", systemImage: "lock.fill")
                                            .font(.system(size: 12, weight: .black))
                                            .foregroundColor(canBuy ? .black : .gray)
                                            .padding(.horizontal, 12)
                                            .padding(.vertical, 4)
                                            .background(canBuy ? Color.yellow : Color.white.opacity(0.15))
                                            .clipShape(Capsule())
                                    }
                                }
                                .padding(14)
                                .frame(maxWidth: .infinity)
                                .background(equipped ? Color.blue.opacity(0.32) : Color.white.opacity(0.06))
                                .clipShape(RoundedRectangle(cornerRadius: 20))
                                .overlay(
                                    RoundedRectangle(cornerRadius: 20)
                                        .stroke(equipped ? Color.cyan : Color.white.opacity(0.15), lineWidth: equipped ? 2 : 1)
                                )
                            }
                        }
                    }
                }
            }
            .padding(18)
        }
    }
}
