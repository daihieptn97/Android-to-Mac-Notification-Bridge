# Android Java Implementation Plan

## Goals
- Mirror the Android-side behavior described in `readme.md`, relying solely on Java source.
- Keep the app modular to align with the architecture layers (UI, service, business, network, data).
- Ensure future extensibility for Kotlin/Compose without blocking current Java workflows.

## Key Features to Deliver
1. **Secure setup flow**
   - Scan QR produced by macOS app via CameraX + ML Kit Barcode.
   - Parse JSON config `{ "api_key": "...", "encryption_key": "..." }`.
   - Persist values inside `EncryptedSharedPreferences`.
   - Request notification-listener permission if not granted.

2. **Service discovery**
   - Use `NsdManager` to discover `_securenotif._tcp` service.
   - Resolve host + port, cache full `http://ip:port/notify` URL.
   - Re-run discovery on demand when cached value fails.

3. **Notification bridge service**
   - Extend `NotificationListenerService`.
   - Filter out self-app notifications.
   - Transform `StatusBarNotification` into JSON payload (id, title, text, category, package, postTime).

4. **Encryption helper**
   - AES-256-GCM using `javax.crypto`.
   - Generate random 12-byte nonce per message.
   - Return Base64 strings for nonce + ciphertext + auth tag.

5. **Networking**
   - Lightweight OkHttp client with 3s timeout, no persistent pool.
   - POST to `/notify` with `Authorization: Bearer <apiKey>` header.
   - Fire-and-forget background executor with retry on lookup failure.

6. **Status UI**
   - `MainActivity` shows:
     - Server discovery state (searching / connected / error).
     - Sent notification count.
     - Button to re-run discovery, open notification access settings, go to setup.
   - `SetupActivity` hosts camera preview + QR parsing state.

## Package Structure (`com.hieptran.android_to_mac_notification_bridge`)
```
├── ui
│   ├── MainActivity.java
│   └── SetupActivity.java
├── service
│   └── NotificationBridgeService.java
├── discovery
│   └── ServiceDiscoveryManager.java
├── crypto
│   └── EncryptionHelper.java
├── network
│   └── NotificationSender.java
├── data
│   └── ConfigRepository.java
├── model
│   ├── EncryptedPayload.java
│   └── NotificationPayload.java
└── util
    └── NotificationUtils.java
```

## Step-by-Step Build Plan
1. **Baseline refactor**
   - Move `MainActivity` into `ui` package, convert layout to status dashboard.
   - Add `SetupActivity` layout + navigation.

2. **Data layer**
   - Implement `ConfigRepository` for encrypted prefs, typed getters/setters, and sentinel for missing config.

3. **Crypto + models**
   - `EncryptionHelper` accepts raw key bytes (Base64 from config) and exposes `EncryptedPayload encrypt(JSONObject)`.
   - Models handle Base64 serialization.

4. **Network layer**
   - `NotificationSender` builds OkHttp request, attaches JSON body: `{ "nonce": "...", "ciphertext": "...", "tag": "..." }` plus metadata.
   - Provide callback for success/failure.

5. **Service discovery**
   - `ServiceDiscoveryManager` wraps `NsdManager` and exposes listener interface with states.
   - Cache resolved URL inside repository.

6. **Notification bridge service**
   - Manage lifecycle, permission checks, queue events through single-thread executor to avoid overlap.
   - On missing config/discovery, trigger broadcast for UI + attempt rediscovery.

7. **UI wiring**
   - `MainActivity` observes LiveData/Flow? Since using Java, we can lean on `ViewModel` + `MutableLiveData` or simple `BroadcastReceiver` updates.
   - Setup screen guides user, buttons to open system settings and start QR scan.

8. **Permissions & manifest**
   - Add notification listener service declaration w/ intent filter.
   - Add camera permission, internet, NSD, and queries for service discovery.

9. **Testing & docs**
   - Add local unit test for `EncryptionHelper` round-trip.
   - Document manual validation steps in README section.

## Assumptions
- Min SDK 31 allows CameraX + ML Kit + EncryptedSharedPreferences without compat shims.
- We'll ship ML Kit barcode scanning via Play Services dependency; fallback instructions documented.
- Since macOS counterpart isn't in scope, provide toggle to paste config manually if QR scanning fails (optional stretch goal).

## Risks & Mitigations
- **Camera permissions**: handle runtime permission + degrade gracefully.
- **Notification access**: show CTA linking to `Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS`.
- **Service discovery reliability**: add manual override input for IP/port later; for now rely on NSD with retry/backoff.
- **Encryption key format**: assume Base64 32-byte key; validate length and log errors.

## Next Steps
- Update Gradle dependencies (CameraX, ML Kit, OkHttp, Gson, security-crypto).
- Scaffold packages + boilerplate classes.
- Flesh out business logic per steps above.
