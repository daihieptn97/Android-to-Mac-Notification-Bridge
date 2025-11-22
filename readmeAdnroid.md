# Android Notification Bridge Flow Guide

Tài liệu này dành cho đội kỹ thuật Android của dự án **Android to Mac Notification Bridge**. Nội dung tổng hợp từ `readme.md` và các thành phần quan trọng trong `MainActivity.java` cùng các mô-đun liên quan.

## **Permissions**
- **`android.permission.INTERNET`**: Cho phép ứng dụng truy cập Internet để gửi thông báo tới macOS server.
- **`android.permission.ACCESS_NETWORK_STATE`**: Kiểm tra trạng thái kết nối mạng trước khi gửi.
- **`android.permission.ACCESS_WIFI_STATE`**: Truy cập trạng thái Wi‑Fi (dùng cho discovery/diagnostics).
- **`android.permission.CHANGE_WIFI_STATE`**: Thay đổi trạng thái Wi‑Fi nếu cần (được khai báo trong manifest).
- **`android.permission.NEARBY_WIFI_DEVICES`** (với flag `neverForLocation`): Dùng cho khám phá thiết bị Wi‑Fi gần (không dùng vị trí).
- **`android.permission.CAMERA`**: Cần để quét QR code trong `SetupActivity`.
- **`android.permission.POST_NOTIFICATIONS`**: Yêu cầu trên Android 13+ để hiển thị/đẩy thông báo.
- **Service binding permission**: `android.permission.BIND_NOTIFICATION_LISTENER_SERVICE` — cần cho `NotificationBridgeService` (ứng dụng phải được cấp quyền Notification Access trong Settings để `NotificationListenerService` hoạt động).

Lưu ý: ngoài các permission khai báo trong `AndroidManifest.xml`, người dùng cần bật quyền "Notification access" cho app trong cài đặt hệ thống để `NotificationBridgeService` có thể nhận sự kiện thông báo.

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
Android Notifications
   ↓ onNotificationPosted
NotificationBridgeService
   ├─> Build NotificationPayload (from StatusBarNotification)
   ├─> Read keys / config from ConfigRepository
   ↓
EncryptionHelper
   └─> Encrypt(payload) -> EncryptedPayload { nonce, ciphertext, tag }
   ↓
NotificationSender
   └─> POST /notify (Authorization: Bearer <apiKey>)
   ↓
Mac Server (SecureNotificationServer)
```

### 3.2 Sơ đồ luồng (gửi test notification)
```
[Start]
  ↓
Check: has valid config? (apiKey + encryptionKey)
  ├─ No -> Update status = ERROR, show Toast -> [End]
  └─ Yes -> Continue
         ↓
Check: serverUrl cached?
  ├─ No -> Start discovery (async) -> wait for resolve -> if resolved continue, else ERROR
  └─ Yes -> Continue
         ↓
Build test payload (NotificationPayload)
  ↓
Encrypt payload via EncryptionHelper (AES-256-GCM)
  ├─ On encrypt error -> Update status = ERROR, show Toast -> [End]
  └─ On success -> continue
         ↓
NotificationSender.send(url, apiKey, encryptedPayload)
  ↓
HTTP response:
  ├─ 2xx -> onSuccess: increment sentCount, update status = CONNECTED, show success Toast
  └─ non-2xx / network error -> onError: update status = ERROR, show failure Toast
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

## **Tệp nguồn & Mối quan hệ**

Dưới đây là bảng tóm tắt các tệp trong package `com.hieptran.android_to_mac_notification_bridge`, mục đích chính và mối liên hệ chính giữa chúng (dành cho lập trình viên Android):

| Tệp | Mục đích (Ngắn) | Mối liên hệ / Vai trò trong luồng |
|---|---|---|
| `ConfigRepository.java` | Lưu cấu hình (API key, khóa AES base64), `serverUrl`, `sentCount` và `BridgeState` (dùng `EncryptedSharedPreferences` nếu có). | Nơi mọi thành phần đọc/ghi cấu hình và trạng thái. |
| `NotificationBridgeService.java` | `NotificationListenerService` nhận thông báo hệ thống, chuyển thành payload, mã hóa và gửi. | Chạy nền, dùng `ServiceDiscoveryManager`, `EncryptionHelper`, `NotificationSender` và cập nhật `ConfigRepository`. |
| `NotificationPayload.java` | Trích xuất các trường cần thiết từ `StatusBarNotification` và chuyển thành JSON. | Được `NotificationBridgeService` và `MainActivity` (test) sử dụng để tạo payload. |
| `EncryptedPayload.java` | Model đơn giản chứa `nonce`, `ciphertext`, `tag` (Base64) và xuất JSON gửi lên server. | Kết quả của `EncryptionHelper.encrypt()`; được `NotificationSender` gửi đi. |
| `EncryptionHelper.java` | AES‑256‑GCM encryptor: nhận key base64, sinh nonce 12 byte, trả `EncryptedPayload`. | Được gọi bởi cả service và UI khi cần mã hóa payload. |
| `ServiceDiscoveryManager.java` | Dùng `NsdManager` để khám phá `_securenotif._tcp` trên LAN và resolve thành `http://host:port/notify`. | Thông báo URL cho UI hoặc service qua listener; `ConfigRepository` lưu URL. |
| `NotificationSender.java` | Lớp mạng dùng OkHttp để POST JSON (`EncryptedPayload`) đến server với header `Authorization: Bearer <apiKey>`. | Thực hiện gửi trên executor nền và báo lỗi/thành công bằng callback. |
| `NotificationUtils.java` | Utility nhỏ để bỏ qua thông báo nội bộ hoặc `ongoing` (tránh vòng lặp). | Được `NotificationBridgeService` dùng để lọc thông báo không cần gửi. |
| `MainActivity.java` | UI hiển thị `BridgeState`, `serverUrl`, `sentCount` và trạng thái cấu hình; điều khiển discovery và gửi test notification. | Tương tác với `ConfigRepository`, `ServiceDiscoveryManager`, `EncryptionHelper` (để test) và `NotificationSender`. |
| `SetupActivity.java` | Quét QR (Camera + ML Kit) để nhận `api_key` và `encryption_key`, validate và lưu vào `ConfigRepository`. | Sau lưu, yêu cầu rebind `NotificationListenerService` để áp dụng cấu hình mới. |

Luồng tổng quát (high-level):
- `SetupActivity` → lưu `api_key` & `encryption_key` vào `ConfigRepository` (qua QR).
- `MainActivity` ↔ `ConfigRepository` (đọc trạng thái, kích hoạt discovery, gửi payload test).
- Khi có thông báo: `NotificationBridgeService` → chuyển `StatusBarNotification` → `NotificationPayload` → `EncryptionHelper` → `EncryptedPayload` → `NotificationSender` → Server.
- `ServiceDiscoveryManager` → resolve URL → listener cập nhật `ConfigRepository` (UI hoặc service nhận URL và lưu lại).

Khi sửa đổi chức năng, hãy nhớ phân tách rõ trách nhiệm: lưu/trạng thái (`ConfigRepository`), khám phá (`ServiceDiscoveryManager`), mã hóa (`EncryptionHelper`/models), mạng (`NotificationSender`) và giao diện/quyền (`MainActivity`/`SetupActivity`).

## **Cơ chế truyền nhận & Mã hóa (chi tiết kỹ thuật)**

Phần này mô tả cụ thể luồng dữ liệu từ một thông báo hệ thống tới khi nó được mã hóa và gửi lên macOS server, dựa trên `NotificationPayload.java`, `EncryptionHelper.java`, `EncryptedPayload.java` và `NotificationSender.java`.

- **Xây dựng payload (theo `NotificationPayload`)**:
   - Khi `NotificationBridgeService` nhận `StatusBarNotification`, nó gọi `NotificationPayload.from(sbn)`.
   - Các trường được trích xuất và đưa vào JSON: `packageName`, `title`, `text`, `category`, `channelId`, `ongoing`, `timestamp`.
   - `NotificationPayload.toJson()` trả về JSON chuẩn dùng làm plaintext để mã hóa.

- **Mã hóa (theo `EncryptionHelper`)**:
   - Thuật toán: AES-256-GCM, transformation `AES/GCM/NoPadding`.
   - Khóa: base64 được lưu trong `ConfigRepository`; khi giải base64 ra phải có đúng 32 bytes (AES-256). `ConfigRepository.isValidKey()` kiểm tra kích thước này.
   - Nonce (IV): 12 byte random được sinh cho mỗi payload (bảo đảm không tái sử dụng nonce với cùng key).
   - Tag: GCM authentication tag 16 byte (128 bit) được tách ra từ kết quả của cipher.
   - Kết quả: `ciphertext` và `tag` (và `nonce`) được mã hóa Base64 (NO_WRAP) và đóng gói thành `EncryptedPayload`.

- **Định dạng gửi (theo `EncryptedPayload.toJson()`)**:
   - JSON gửi lên server có 3 trường: `nonce`, `ciphertext`, `tag` (tất cả Base64 strings).
   - Ví dụ:
      {
         "nonce": "Base64(...)",
         "ciphertext": "Base64(...)",
         "tag": "Base64(...)"
      }

- **Gửi lên server (theo `NotificationSender`)**:
   - URL: `ServiceDiscoveryManager` resolve service thành `http://<host>:<port>/notify` và `ConfigRepository` lưu URL này.
   - Yêu cầu HTTP: POST JSON (body = `EncryptedPayload.toJson()`), headers:
      - `Content-Type: application/json`
      - `Authorization: Bearer <apiKey>` (lấy từ `ConfigRepository`)
   - OkHttp client cấu hình timeout 3s (connect/read/write) và chạy call trên một executor nền.
   - Phản hồi: nếu HTTP trả về thành công (2xx) thì gọi `onSuccess()`, ngược lại `onError(IOException("HTTP " + code))`.

- **Luồng xử lý & cập nhật trạng thái**:
   - `NotificationBridgeService` hoặc `MainActivity` (khi gửi test) thực hiện:
      1. Kiểm tra `ConfigRepository.hasValidConfig()` (api key + encryption key hợp lệ).
      2. Lấy `serverUrl`; nếu không có, khởi động discovery.
      3. Tạo payload JSON (từ `NotificationPayload` hoặc `buildTestPayload()`), gọi `EncryptionHelper.encrypt()`.
      4. Gọi `NotificationSender.send(url, apiKey, encryptedPayload, callback)`.
      5. Callback `onSuccess()` → `ConfigRepository.incrementSentCount()` và `updateStatus(CONNECTED, ...)`.
      6. Callback `onError()` → `updateStatus(ERROR, message)`, có thể `clearServerUrl()` và bắt đầu discovery lại.

- **Xử lý lỗi & an toàn**:
   - Nếu khóa base64 không đúng (không đủ 32 bytes) `EncryptionHelper` ném `IllegalArgumentException` — caller (service/UI) phải catch để báo lỗi người dùng và cập nhật `BridgeState`.
   - Nếu `EncryptedSharedPreferences` không khả dụng, `ConfigRepository` fallback về `SharedPreferences` thường và ghi log (không ghi plaintext key ra log).
   - Không log trực tiếp khóa hoặc plaintext payload; chỉ log thông tin đủ để debug (messsage lỗi, mã HTTP, stacktrace nếu cần trong dev builds).

- **Gửi thêm thông tin thiết bị khi kết nối**:
   - Khi discovery resolve thành công URL, `MainActivity` (hoặc service) có thể gửi một payload `device info` (manufacturer, model, sdkInt, androidId, appVersion, timestamp) cũng theo chu trình mã hóa ở trên — server có thể lưu mapping device ↔ api_key.

Ghi chú ngắn: nếu bạn muốn gửi thông tin mạng (SSID, địa chỉ IP) hoặc các trường nhạy cảm hơn, cần khai báo và yêu cầu thêm permission tương ứng (ví dụ `ACCESS_FINE_LOCATION` để đọc SSID trên một số phiên bản Android) và cập nhật chính sách quyền trong UI/tài liệu.
