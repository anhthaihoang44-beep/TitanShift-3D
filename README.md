# TitanShift 3D

Game runner 3D lấy cảm hứng từ thể loại "đổi kích thước để vượt chướng ngại" (giống ScaleMan), nhưng **toàn bộ code, tên gọi, mô hình 3D, cơ chế chi tiết, âm thanh và giao diện đều được viết mới**. Không có asset hay code nào lấy từ file APK gốc.

## Điểm khác so với ScaleMan
| ScaleMan | TitanShift 3D |
|---|---|
| Người que, thanh trượt đổi chiều cao | Chiến binh giáp robot, 3 vùng kích thước: **Tí hon / Cân bằng / Titan** (mỗi vùng có tốc độ riêng) |
| Chướng ngại cơ bản | 7 loại: cổng laser thấp, tường gạch, cầu kính dễ vỡ, vực sâu (nhảy khổng lồ), cổng đồng bộ (phải đúng khoảng kích thước), vệ binh tuần tra, cổng piston di chuyển |
| — | Combo, thanh **Overdrive** (4.6 giây bất tử), khiên năng lượng |
| Đích nhân thưởng | Chuỗi 8 tường nhân x1.5 → x10, phá được bao nhiêu tùy theo “động lượng” tích lũy |
| Boss | Mỗi màn thứ 5: đấu lực với Mecha, giữ thanh trượt trong vùng vàng di động + chạm liên tục |
| Skin | 6 bộ giáp với mũ khác nhau (vây, sừng, kính, hào quang, vương miện, Omega) + 3 nâng cấp |

Có song ngữ Việt/Anh, âm thanh tự tổng hợp (không dùng file âm thanh), rung phản hồi, lưu tiến trình.

## Android (Kotlin) — `android-kotlin/`
- Kotlin + Jetpack Compose (giao diện) + **OpenGL ES 2.0** tự viết (3D) + AudioTrack + Vibrator.
- Build: `cd android-kotlin && gradlew.bat assembleDebug` (JDK 17, Android SDK 35).
- Test: `gradlew.bat testDebugUnitTest`.
- APK đã build sẵn: `TitanShift-debug.apk` (ở thư mục gốc).

## iOS (Swift) — `ios-swift/`
- SwiftUI (giao diện) + **SceneKit** (3D) + AVAudioPlayer (WAV tự tạo) + UIFeedbackGenerator. iOS 16+.
- Cần máy **Mac có Xcode 15+**:
  - Mở `TitanShift.xcodeproj` → chọn Team ký ứng dụng → Run.
  - Hoặc dùng `brew install xcodegen && xcodegen` (theo `project.yml`) để tạo project kèm target test `TitanShiftTests`.

## Cấu trúc (giống nhau trên cả 2 nền tảng)
```
Models      – kiểu dữ liệu, skin, nâng cấp, chướng ngại
Data        – lưu tiến trình (SharedPreferences / UserDefaults)
Engine      – StageGenerator (sinh màn theo seed) + mô phỏng game
Audio       – âm thanh tổng hợp + rung
Render/Scene3D – dựng 3D
UI/Views    – HUD, trang chủ, cửa hàng skin, màn thắng/thua
```
