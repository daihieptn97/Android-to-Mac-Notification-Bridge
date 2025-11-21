# Android Notification Bridge Flow Guide

Tài liệu này dành cho đội kỹ thuật Android của dự án **Android to Mac Notification Bridge**. Nội dung tổng hợp từ `readme.md` và các thành phần quan trọng trong `MainActivity.java` cùng các mô-đun liên quan.

## 1. Tổng quan hệ thống Android
- **Mục tiêu**: Lắng nghe thông báo hệ thống, mã hóa và gửi tới server macOS qua LAN.
- **Thành phần chính**:
  - `NotificationBridgeService`: bắt sự kiện `NotificationListenerService`.
  - `ConfigRepository`: lưu API Key, AES key, server URL, thống kê gửi.
  - `ServiceDiscoveryManager`: tìm Mac server thông qua NSD `_securenotif._tcp`.
  - `NotificationSender`: gửi HTTP POST `/notify` với OkHttp.
  - `MainActivity`: điều khiển UI và orchestrate discovery / test send.

## 2. Luồng hoạt động chi tiết
### 2.1 Setup & Cấu hình
1. Người dùng mở **SetupActivity**, quét QR từ macOS để lấy `api_key` và `encryption_key`.
2. Khoá được lưu trong `EncryptedSharedPreferences` (AES-256).
3. Ứng dụng yêu cầu quyền Notification Listener và kiểm tra trạng thái trong `MainActivity`.
4. Nếu thiếu cấu hình → `ConfigRepository.hasValidConfig()` trả `false`, UI hiển thị lỗi và không cho gửi test.

### 2.2 Khởi động ứng dụng (`MainActivity.onCreate`)
1. Khởi tạo `ConfigRepository`, `ServiceDiscoveryManager`, `NotificationSender`.
2. Ánh xạ UI (`TextView`, `MaterialButton`).
3. Đăng ký listener SharedPreferences để cập nhật UI realtime.
4. Gọi `renderState()` để đọc `BridgeState` hiện tại.

### 2.3 Vòng đời
- `onStart`: đăng ký listener, kiểm tra quyền Notification Access. Nếu thiếu → cập nhật trạng thái `BridgeState.Status.ERROR` với thông điệp tương ứng.
- `onStop`: hủy listener để tránh memory leak.
- `onDestroy`: dừng discovery nếu đang chạy.

### 2.4 Tự động khám phá server (`maybeStartDiscovery`)
1. Kiểm tra `ConfigRepository.hasValidConfig()`.
2. Nếu chưa có `serverUrl` và discovery chưa chạy → gọi `ServiceDiscoveryManager.startDiscovery()`.
3. Listener cập nhật trạng thái:
   - `onDiscoveryStarted`: `BridgeState.Status.DISCOVERING`.
   - `onServiceResolved`: lưu `serverUrl`, chuyển sang `CONNECTED`.
   - `onDiscoveryError`: cập nhật `ERROR` + thông điệp lỗi.

### 2.5 Gửi thông báo thử (`sendTestNotification`)
1. Validate cấu hình (API key, encryption key, server URL).
2. Nếu thiếu → cập nhật trạng thái `ERROR`, show Toast và (nếu cần) kích hoạt discovery.
3. Gọi `buildTestPayload()` tạo JSON mẫu.
4. Mã hóa payload bằng `EncryptionHelper.encrypt()` (AES-256-GCM).
5. `NotificationSender.send()` gửi POST với header `Authorization: Bearer <apiKey>`.
6. Callback:
   - `onSuccess`: tăng `sentCount`, cập nhật trạng thái `CONNECTED`, show Toast thành công.
   - `onError`: ghi log, cập nhật `ERROR`, hiển thị Toast thất bại.

## 3. Biểu đồ
### 3.1 Biểu đồ luồng dữ liệu (DFD cấp cao)
```
Android Notifications ─┐
                       │ 1. onNotificationPosted
NotificationBridgeSvc ─┼────▶ Payload JSON
                       │
ConfigRepository ◀─────┤ (API Key, AES Key)
                       │
EncryptionHelper ─────▶ EncryptedPayload
                       │
NotificationSender ───▶ HTTP POST /notify ──▶ Mac Server
```

### 3.2 Sơ đồ luồng (Flowchart gửi test)
```
[Start]
   │
   ▼
Has valid config?
   │Yes                    │No
   ▼                       ▼
Server URL cached?     Update status ERROR
   │Yes       │No         Show Toast
   ▼          ▼           [End]
Encrypt payload   Start discovery
   │               │
   ▼               ▼
Encryption OK?   (async) wait
   │Yes   │No
   ▼      ▼
Send HTTP   Update status ERROR
   │
   ▼
Send success?
   │Yes        │No
   ▼           ▼
Inc sentCount  Update status ERROR
Update status  Show Toast failed
Show Toast OK
   │
  [End]
```

## 4. Kỹ thuật mã hóa & bảo mật
- **Thuật toán**: AES-256-GCM (CryptoKit/Javax Crypto).
- **Key storage**: `EncryptedSharedPreferences` với MasterKey (AndroidX Security).
- **Nonce**: 12-byte random mỗi payload, đảm bảo tính duy nhất → chống replay.
- **Authentication**: Header `Authorization` chứa API Key.
- **Data integrity**: GCM tag 128-bit, phát hiện chỉnh sửa.
- **Handling key errors**: `IllegalArgumentException` trong `EncryptionHelper` được catch và hiển thị rõ ràng.

## 5. Kỹ thuật bổ trợ khác
- **Networking**: OkHttp với timeout 3s, fire-and-forget để tiết kiệm pin.
- **Service Discovery**: NsdManager để resolve `_securenotif._tcp`.
- **Concurrency**: Thread / Handler (trong code hiện tại dùng callback background).
- **UI feedback**: `BridgeState` + SharedPreferences listener để cập nhật `TextView`.
- **Logging**: tiền tố `hehe123` giúp trace logcat theo luồng.

## 6. Bảo mật & xử lý sự cố
| Vấn đề | Biện pháp |
|--------|-----------|
| API Key rò rỉ | Chỉ lưu trong `EncryptedSharedPreferences`, không log plaintext. |
| Thiếu quyền Notif | `NotificationManagerCompat.getEnabledListenerPackages` kiểm tra, cập nhật trạng thái yêu cầu cấp quyền. |
| Server không tìm thấy | Auto-discovery + thông báo lỗi để user biết cần bật server. |
| Encryption lỗi | Bắt exception, hiển thị lý do cụ thể để dev xử lý key sai định dạng. |
| Replay/Forgery | GCM tag + nonce duy nhất, server xác thực API Key và decrypt mới nhận. |

## 7. Tham khảo nhanh
- **UI entry**: `MainActivity`, `SetupActivity`.
- **Service**: `NotificationBridgeService` (không hiển thị nhưng bắt buộc bật).
- **Crypto**: `EncryptionHelper` (`com.hieptran.android_to_mac_notification_bridge.crypto`).
- **Network**: `NotificationSender`.
- **Discovery**: `ServiceDiscoveryManager`.

Tài liệu này hỗ trợ dev hiểu rõ cách dòng dữ liệu đi qua app Android, các điểm cần chú ý khi mở rộng hoặc debug. Đảm bảo tuân thủ quy trình bảo mật khi thao tác với API key và encryption key.
