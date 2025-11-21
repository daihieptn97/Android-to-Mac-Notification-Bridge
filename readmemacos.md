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
