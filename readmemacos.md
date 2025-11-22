# macOS Secure Notification Server Guide

Tài liệu này dành cho dev macOS đang triển khai phần server của dự án **Android to Mac Notification Bridge**. Tổng hợp từ `readme.md` và cấu trúc UI/logic trong `ContentView.swift`.

## 1. Tổng quan hệ thống macOS
- **Ngôn ngữ & Frameworks**: Swift 5.9+, SwiftUI, Network.framework, CryptoKit, UserNotifications.
- **Chức năng chính**:
  - Khởi chạy HTTP server (port 8080 mặc định) và publish Bonjour `_securenotif._tcp`.
  - Sinh API key + AES-256 key, lưu Keychain, hiển thị qua QR.
  - Nhận yêu cầu `/notify`, xác thực API key, giải mã payload, đẩy macOS notification.
  - UI giám sát server trạng thái, thiết bị kết nối, thống kê.

## 2. Luồng hoạt động chi tiết
### 2.1 Lifecycle UI (`ContentView` + `ServerViewModel`)
1. Khi UI xuất hiện (`onAppear`) → gọi `requestNotificationPermission()` để đảm bảo `UNUserNotificationCenter` đã được cấp quyền.
2. `ServerViewModel` phơi bày binding:
   - `status`, `statusMessage`, `isServerRunning`, `serverURL`.
   - `connectedDevices`: danh sách Android clients (package, IP, lastSeen, counters).
   - `qrImage`: QR chứa JSON cấu hình (API key, encryption key, server URL).
3. Người dùng có thể `Start/Stop Server` và `Regenerate Keys` ngay trên UI.

### 2.2 Khởi tạo server
1. `SecureNotificationServer` sinh ngẫu nhiên API key (UUID) + AES-256 key (`SymmetricKey(size:.bits256)`).
2. Keys lưu vào Keychain thông qua `KeychainHelper`. QR hiển thị bằng `QRCodeGenerator` (CoreImage → `NSImage`).
3. `NWListener` khởi tạo với `NWParameters.tcp` trên port 8080, publish service `_securenotif._tcp` để Android discovery.
4. Listener nhận connection, đọc HTTP POST `/notify`.
5. `NetworkUtils` phân tích request, trả lỗi chuẩn HTTP khi gặp vấn đề (401, 400, 500).

### 2.3 Xử lý thông báo đến
1. Nhận JSON đã mã hóa: `{ "nonce": "...", "ciphertext": "...", "tag": "..." }`.
2. Kiểm tra header `Authorization` (Bearer API_KEY). Nếu sai → 401, log lại.
3. `CryptoKit` tạo `AES.GCM.SealedBox` từ nonce/cipher/tag, dùng key trong Keychain để giải mã.
4. Parse plaintext JSON thành `NotificationPayload` (packageName, title, text, category, channelId, timestamp, ...).
5. `NotificationDispatcher` dựng `UNMutableNotificationContent` và gửi qua `UNUserNotificationCenter`.
6. Cập nhật `notificationsCount`, `connectedDevices`, `statusMessage`, log event trong UI.

## 3. Biểu đồ
### 3.1 Biểu đồ luồng dữ liệu (server perspective)
```
Android Device ──HTTP POST (AES-GCM payload)──▶ SecureNotificationServer
                                             │
                                             ▼
                                    Authorization check
                                             │
                                             ▼
                                     AES-256-GCM decrypt
                                             │
                                             ▼
                                 NotificationPayload (JSON)
                                             │
                                             ▼
                                NotificationDispatcher → macOS Notification Center
                                             │
                                             ▼
                                   UI update (ServerViewModel)
```

### 3.2 Sơ đồ luồng điều khiển server (Flowchart)
```
[Start Server]
   │
   ▼
Generate API & AES key → Save Keychain
   │
   ▼
Start NWListener + Bonjour service
   │
   ▼
Incoming connection?
   │No
   └─▶ Wait
   │Yes
   ▼
Validate Authorization header
   │Pass     │Fail
   ▼        ▼
Parse encrypted JSON  Respond 401
   │
Decrypt AES-GCM
   │Success   │Fail
   ▼          ▼
Build Notification Dispatch error
   │          │
Send via UNUserNotificationCenter
   │
Update UI (counts, devices, status)
   │
 [Loop]
```

## 4. Kỹ thuật mã hóa & các kỹ thuật khác
- **AES-256-GCM** với `CryptoKit.AES.GCM`.
- **Nonce 12-byte**: sinh ngẫu nhiên, kiểm tra độ dài trước khi decrypt.
- **Key storage**: Keychain (không serialize ra file). Khi nhấn "Regenerate Keys" → xóa key cũ, sinh lại, cập nhật QR.
- **Authentication**: Bearer API key, có thể mở rộng thêm whitelist IP nếu cần.
- **Networking**: `Network.framework` với `NWListener`, `NWConnection`, `DispatchQueue` chuyên dụng.
- **QR Generation**: `CoreImage.CIFilter.qrCodeGenerator`, output `NSImage` render trong SwiftUI.
- **State Management**: `ObservableObject` + `@Published` trong `ServerViewModel`, UI cập nhật realtime.

## 5. Bảo mật & xử lý sự cố
| Rủi ro | Biện pháp |
|--------|-----------|
| API key lộ | Không hard-code; chỉ hiển thị QR khi cần. Có nút "Regenerate Keys" để rotate ngay. |
| Replay / tamper | GCM authentication tag phát hiện chỉnh sửa; nonce duy nhất ngăn trùng lặp. Có thể mở rộng lưu nonce gần nhất để chống replay nâng cao. |
| Unauthorized device | Authorization header phải khớp API key + server chỉ lắng nghe LAN. Có thể bổ sung danh sách package/IP được phép. |
| Key mất đồng bộ | Khi regen key, Android cần quét QR mới. UI hiển thị rõ server URL & status để nhắc người dùng. |
| Notification permission bị thu hồi | `requestNotificationPermission()` chạy khi UI mở. Nếu bị từ chối, status message phản ánh lỗi. |

## 6. Theo dõi & vận hành
- **Status section** trong `ContentView` hiển thị màu đỏ/xanh theo `isServerRunning`, kèm `statusMessage` giải thích.
- **Server URL**: hiển thị dạng monospaced + cho phép copy nhanh.
- **Connected devices**: danh sách `DeviceState` với `packageName`, `ipAddress`, thời điểm `lastSeen`, số thông báo đã xử lý.
- **QR section**: preview QR để Android quét lại khi cần.
- **Control section**:
  - `Start/Stop Server`: quản lý `NWListener` lifecycle.
  - `Regenerate Keys`: gọi lại pipeline sinh key + cập nhật QR + broadcast tới UI.

## 7. Checklist cho dev
- [ ] Kiểm tra log khi server không chạy: xem `SecureNotificationServer` errors (port busy?).
- [ ] Đảm bảo quyền Notification đã cấp trong System Settings → Notifications.
- [ ] Nếu Android không tìm thấy server: xác nhận Bonjour quảng bá (mDNS service `_securenotif._tcp`).
- [ ] Sau mỗi lần rotate key, kiểm thử lại quá trình quét QR và gửi test notification từ Android.

Tài liệu này cung cấp góc nhìn hệ thống cho macOS, giúp dev hiểu rõ luồng dữ liệu, cơ chế bảo mật và cách vận hành server trong LAN. Áp dụng quy trình này để mở rộng (ví dụ logging nâng cao, metrics) mà vẫn đảm bảo an toàn thông tin.

## 8. Tệp nguồn & Mối quan hệ (trong `Macos/android_to_mac_notification_bridge`)

Dưới đây liệt kê các tệp nguồn chính nằm trong `Macos/android_to_mac_notification_bridge/android_to_mac_notification_bridge/` cùng mô tả ngắn và mối quan hệ giữa chúng. Mục đích là giúp dev nhanh nắm được trách nhiệm từng file và luồng phụ thuộc nội bộ.
| Tệp | Chức năng chính | Phụ thuộc / Ghi chú |
|---|---|---|
| `android_to_mac_notification_bridgeApp.swift` | Entry point (SwiftUI App). Tạo và inject `ServerViewModel` vào `ContentView`. | Khởi tạo `ServerViewModel`.
| `BridgeConfig.swift` | Model chứa cấu hình server (API key, AES key, port, server URL). Serialize/deserialize JSON cho QR. | Dùng bởi `QRCodeGenerator`.
| `SecureNotificationServer.swift` | Lõi server: sinh/rotate keys, lưu/đọc Keychain, khởi tạo `NWListener`, publish Bonjour, nhận kết nối, parse HTTP và forward payload để giải mã và dispatch. | Phụ thuộc: `NetworkUtils`, `KeychainHelper`, `NotificationDispatcher`, `NotificationPayload`.
| `NetworkUtils.swift` | Tiện ích đọc/ghi HTTP trên `NWConnection` (parse headers, đọc body, tạo responses). | Được `SecureNotificationServer` sử dụng để tách phần networking.
| `KeychainHelper.swift` | Wrapper truy cập Keychain (lưu API key, AES symmetric key). | `SecureNotificationServer` và `ServerViewModel` dùng để lưu/đọc key.
| `QRCodeGenerator.swift` | Sinh `NSImage` QR từ `BridgeConfig` (JSON). | Dùng bởi `ServerViewModel` để tạo `qrImage` cho UI.
| `NotificationPayload.swift` | `Codable` model mô tả payload từ Android (`title`, `content`, `packageName`, `type`, `timestamp`, ...). | Decode plaintext JSON sau khi giải mã AES-GCM.
| `NotificationDispatcher.swift` | Xây dựng `UNMutableNotificationContent` từ `NotificationPayload` và gửi tới `UNUserNotificationCenter`. | Có thể mapping `type`→icon/sound.
| `ServerViewModel.swift` | `ObservableObject` chứa trạng thái UI (`isServerRunning`, `status`, `serverURL`, `connectedDevices`, `notificationsCount`, `qrImage`) và hành động (`startServer`, `stopServer`, `regenerateKeys`, `requestNotificationPermission`). | Phối hợp với `SecureNotificationServer`, `KeychainHelper`, `QRCodeGenerator`.
| `ContentView.swift` | Giao diện chính (SwiftUI) dùng `ServerViewModel` làm nguồn dữ liệu. Hiển thị QR, trạng thái, danh sách thiết bị, điều khiển. | Quan sát `ServerViewModel`.
| `Assets.xcassets` | Tập tài nguyên (icons, images) dùng bởi UI và notifications. | Tài nguyên UI.
| `android_to_mac_notification_bridge.entitlements` | Entitlements (quyền cần thiết: notifications, network, ...). | Cấu hình project.

### Mối quan hệ tóm tắt

| Từ | Tới | Mô tả |
|---|---|---|
| `ContentView` | `ServerViewModel` | `ContentView` quan sát (`@ObservedObject`) để cập nhật UI realtime.
| `ServerViewModel` | `SecureNotificationServer` | `ServerViewModel` điều khiển lifecycle (start/stop) và nhận callback/trạng thái.
| `SecureNotificationServer` | `NetworkUtils` | Sử dụng `NetworkUtils` để parse HTTP và xử lý `NWConnection`.
| `SecureNotificationServer` | `KeychainHelper` | Lưu/đọc API key và AES key.
| `SecureNotificationServer` | `NotificationPayload` → `NotificationDispatcher` | Sau khi giải mã, decode sang `NotificationPayload`, rồi truyền cho `NotificationDispatcher` để gửi notification.
| `ServerViewModel` | `QRCodeGenerator` | Sinh `qrImage` từ `BridgeConfig` để hiển thị trong UI.

Ví dụ luồng (khi nhận HTTP POST `/notify`):
1. `SecureNotificationServer` nhận `NWConnection` → dùng `NetworkUtils` đọc body.
2. Kiểm header `Authorization` với API key từ `KeychainHelper`.
3. Giải mã AES-GCM (CryptoKit) → plaintext JSON.
4. Decode vào `NotificationPayload` → gọi `NotificationDispatcher`.
5. `NotificationDispatcher` gửi `UNNotification` → `ServerViewModel` cập nhật counters → `ContentView` hiển thị.

### Gợi ý refactor / kiểm thử
- Tách giao diện (protocol) cho `SecureNotificationServer` để dễ mock `ServerViewModel` khi viết unit tests.
- Viết unit tests cho `NetworkUtils` (parse HTTP) và `NotificationPayload` (encode/decode).
- Đưa logging ra service riêng (`Logger`) để giữ `SecureNotificationServer` gọn.

