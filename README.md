<div align="center">

# Atheer SDK — أثير SDK

**Android NFC Contactless Payment SDK for Yemeni E-Wallets**

[![API](https://img.shields.io/badge/API-24%2B-brightgreen)](https://android-arsenal.com/api?level=24)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9-blueviolet)](https://kotlinlang.org)
[![License](https://img.shields.io/badge/License-Proprietary-red)](#license)
[![Version](https://img.shields.io/badge/Version-1.0.0-blue)](https://github.com/ahmedaliahmed775/atheer-sdk/packages)

</div>

---

## Table of Contents

- [Overview](#overview)
- [Architecture](#architecture)
- [Security Model](#security-model)
  - [Zero-Trust Dynamic Key Derivation](#zero-trust-dynamic-key-derivation)
  - [Storage Security](#storage-security)
  - [Network Security](#network-security)
  - [API Key Security Best Practices](#api-key-security-best-practices)
- [Requirements](#requirements)
- [Installation](#installation)
- [Quick Start](#quick-start)
- [AtheerWalletAdapter — Simplified Integration](#atheerwalletadapter--simplified-integration)
- [API Reference](#api-reference)
  - [Initialization](#1-initialization)
  - [Device Enrollment](#2-device-enrollment)
  - [Readiness Check](#3-readiness-check)
  - [Prepare Payment (Customer Side)](#4-prepare-payment-customer-side)
  - [Process Payment (Merchant Side)](#5-process-payment-merchant-side)
  - [Session State](#6-session-state)
  - [Error Handling](#7-error-handling)
- [Data Models](#data-models)
- [Permissions](#permissions)
- [ProGuard / R8](#proguard--r8)
- [Sandbox vs Production](#sandbox-vs-production)
- [License](#license)

---

## Overview

**Atheer SDK** is an Android library that enables secure, contactless NFC payments for Yemeni e-wallets (such as جوالي, الكريمي, and أم فلوس). It turns any NFC-enabled Android device into a fully functional payment instrument or SoftPOS terminal using **Host Card Emulation (HCE)**.

The SDK implements a **Zero-Trust Dynamic Key Derivation** protocol where every payment transaction is protected by a unique, one-time cryptogram derived from a backend-issued device seed and a monotonic counter — making replay attacks and token theft practically impossible.

### Core Capabilities

| Feature | Description |
|---|---|
| 🪙 **HCE Payments** | Device acts as an NFC card — no physical card required |
| 👆 **Biometric Auth** | Fingerprint-protected payment sessions |
| 🔑 **HMAC-SHA256 Cryptogram** | Per-transaction unique signature bound to device + counter |
| 📡 **Private APN** | Zero-rating cellular tunnel for Atheer transactions |
| 🛡️ **Root Detection** | Blocks or warns on rooted/jailbroken devices |
| ⏱️ **Relay Attack Protection** | RTT-based proximity check (< 50ms) |
| 💾 **Encrypted Local DB** | SQLCipher-encrypted offline transaction log |
| 📌 **Certificate Pinning** | MITM protection in production mode |

---

## Architecture

```
com.atheer.sdk
│
├── AtheerSdk                    ← Main singleton — orchestrates payment flows
├── AtheerSdkBuilder             ← Optional builder for early config validation
├── AtheerSdkConfig              ← DSL configuration (includes certificatePins)
├── SessionState                 ← Enum: IDLE / ARMED / EXPIRED
├── AtheerPaymentSettingsActivity← Activity to set app as default payment app
│
├── repository/
│   ├── AtheerRepository         ← Data access interface (network + DB + keystore)
│   └── AtheerRepositoryImpl     ← Concrete implementation (internal)
│
├── hce/
│   └── AtheerApduService        ← HostApduService — emulates the NFC card
│
├── nfc/
│   ├── AtheerNfcReader          ← NFC reader for merchant SoftPOS
│   └── AtheerFeedbackUtils      ← Haptic + audio feedback on successful tap
│
├── security/
│   ├── AtheerKeystoreManager    ← Key derivation, counter, seed storage (internal)
│   └── AtheerPaymentSession     ← Thread-safe armed-session manager (internal)
│
├── network/
│   └── AtheerNetworkRouter      ← HTTP client with dynamic cert pinning + APN fallback (internal)
│
├── database/
│   ├── AtheerDatabase           ← Room + SQLCipher encrypted DB (with explicit migrations)
│   ├── TransactionDao           ← DAO for transaction CRUD
│   └── TransactionEntity        ← Room entity for stored transactions
│
└── model/
    ├── AtheerError              ← Sealed error hierarchy
    ├── AtheerReadinessReport    ← Device capability snapshot
    ├── AtheerTransaction        ← Completed payment record
    ├── ChargeRequest            ← Payment request payload
    ├── ChargeResponse           ← Payment response from backend
    ├── EnrollResponse           ← Device enrollment response
    ├── LoginRequest/Response    ← Auth models
    ├── SignupRequest/Response   ← Registration models
    ├── BalanceResponse          ← Wallet balance
    └── HistoryResponse          ← Transaction history

com.atheer.wallet.adapter
└── AtheerWalletAdapter          ← Simplified facade for wallet integrations (Singleton)
```

### Payment Flow

```
Customer Device (HCE)                          Merchant Device (SoftPOS)
─────────────────────────────────────────────────────────────────────────
1. enrollDevice()         ← Backend issues deviceSeed
2. preparePayment()       ← Biometric auth → derives LUK → generates Cryptogram
                             AtheerPaymentSession.arm(payload, signature)
                             SessionState = ARMED (60s window)

                             ──── NFC Tap ────►

                                                3. AtheerNfcReader detects tag
                                                   RTT check < 50ms (anti-relay)
                                                   Reads: DeviceID|Counter|Timestamp|Signature

                                                4. parseNfcDataToRequest()
                                                   Timestamp age check < 120s

                                                5. charge(request, accessToken)
                                                   POST /api/v1/payments/process

                                                6. Transaction saved locally (SQLCipher)
```

---

## Security Model

### Zero-Trust Dynamic Key Derivation

The SDK implements a **Zero-Trust Dynamic Key Derivation** architecture where no long-lived static key is ever used directly for payment authorization. Every transaction uses a freshly derived one-time key that is mathematically bound to the device identity and a monotonic counter.

```
Backend:  deviceSeed  = HMAC-SHA256(MASTER_SEED, deviceId)
                               ↓  (transmitted to SDK securely during enrollment)
SDK:      LUK         = HMAC-SHA256(deviceSeed, counter)
                               ↓  (derived locally, never leaves the device)
SDK:      cryptogram  = HMAC-SHA256(LUK, "phoneNumber|counter|timestamp")
                               ↓  (transmitted to merchant via NFC)
Backend:  Verification = recompute cryptogram using stored deviceSeed + received counter
```

#### Why this is secure:
| Threat | Mitigation |
|---|---|
| **Replay attacks** | Monotonic counter — each counter value is valid exactly once; Backend rejects any counter ≤ last accepted |
| **Token theft** | LUK is derived on-the-fly; it is never stored and changes with every transaction |
| **MITM attacks** | Certificate Pinning pins the server's public key; any unauthorized CA is rejected |
| **Compromised device** | `deviceSeed` is stored in `EncryptedSharedPreferences` backed by Android Keystore hardware |
| **Transaction staleness** | NFC payload timestamp validated — payloads older than 120 seconds are rejected |
| **Relay attacks** | 60-second session window + RTT proximity check (< 50ms) on the merchant terminal |

#### Session Lifecycle:
```
preparePayment() called
        │
        ▼ Biometric Success
  SessionState = ARMED
  AtheerPaymentSession.arm(payload, cryptogram)
        │
        ├─── NFC Tap within 60s ──► SessionState = IDLE (session consumed)
        │
        └─── 60s elapsed ─────────► SessionState = EXPIRED (session discarded)
```

### Storage Security

| Data | Storage Mechanism |
|---|---|
| `deviceSeed` | `EncryptedSharedPreferences` (AES-256-GCM via Android Keystore) |
| Monotonic counter | `EncryptedSharedPreferences` (AES-256-GCM via Android Keystore) |
| Master Seed (fallback) | Android Keystore (hardware-backed TEE/StrongBox AES-256) |
| DB passphrase | `EncryptedSharedPreferences` (AES-256-GCM via Android Keystore) |
| Transaction log | SQLCipher-encrypted Room database (AES-256 CBC) |

### Network Security

- **Production**: TLS 1.2 / 1.3 enforced. Certificate Pinning with dynamically configured `sha256` public key hashes (primary + backup). Configured via `AtheerSdkConfig.certificatePins`.
- **Sandbox**: Cleartext HTTP allowed for local development (localhost / emulator).
- **Private APN**: Optional dedicated cellular tunnel bound to Atheer's zero-rated network, with fallback to public internet.

### API Key Security Best Practices

> ⚠️ Your `apiKey` and `merchantId` are sensitive credentials. Follow these practices to avoid leaking them:

1. **Never hardcode secrets** in Kotlin/Java source files. Use `BuildConfig` or a secrets manager instead:
   ```kotlin
   // In build.gradle.kts:
   buildConfigField("String", "ATHEER_API_KEY", "\"${System.getenv("ATHEER_API_KEY")}\"")
   ```

2. **Use environment variables** in CI/CD pipelines rather than committing secrets to version control.

3. **Configure Certificate Pinning** in production — without it, a compromised CA could perform a MITM attack:
   ```kotlin
   AtheerSdk.init {
       isSandbox        = false
       certificatePins  = listOf(
           "sha256/<primary-cert-hash>=",   // openssl s_client -connect api.atheer.com:443 ...
           "sha256/<backup-cert-hash>="     // Always include a backup to avoid outage on rotation
       )
   }
   ```

4. **Enable `blockRootedDevices = true`** in production to prevent the SDK from running on compromised devices.

5. **Rotate `deviceSeed`** by calling `enrollDevice(forceRenew = true)` if you suspect the seed is compromised.

---

## Requirements

| Requirement | Minimum |
|---|---|
| Android API | 24 (Android 7.0 Nougat) |
| Compile SDK | 34 |
| NFC hardware | Required |
| HCE support | Required (`android.hardware.nfc.hce`) |
| Biometric sensor | Required for customer payment |
| Kotlin | 1.9+ |
| Java | 17 (source/target compatibility) |

---

## Installation

### GitHub Packages

Add the GitHub Packages repository to your project-level `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/ahmedaliahmed775/atheer-sdk")
            credentials {
                username = providers.gradleProperty("gpr.user").orElse(System.getenv("GITHUB_ACTOR")).get()
                password = providers.gradleProperty("gpr.key").orElse(System.getenv("GITHUB_TOKEN")).get()
            }
        }
    }
}
```

Add the dependency to your module-level `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.github.ahmedaliahmed775:atheer-sdk:1.0.0")
}
```

---

## Quick Start

### 1. Initialize the SDK (Application class)

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()

        AtheerSdk.init {
            context            = applicationContext
            merchantId         = "YOUR_MERCHANT_ID"
            apiKey             = "YOUR_API_KEY"
            phoneNumber        = "71XXXXXXX"   // User's phone number — acts as device identity
            isSandbox          = false          // true for development
            blockRootedDevices = true           // throw SecurityException on rooted devices
            certificatePins    = listOf(        // Required in production
                "sha256/<primary-cert-hash>=",
                "sha256/<backup-cert-hash>="
            )
        }
    }
}
```

### 2. Enroll the device (once per install)

```kotlin
val sdk = AtheerSdk.getInstance()

lifecycleScope.launch {
    sdk.enrollDevice()
        .onSuccess { /* proceed to payment UI */ }
        .onFailure { error -> showError(error.message) }
}
```

### 3. Check device readiness

```kotlin
val report = sdk.checkReadiness()

if (report.isReadyForPayment) {
    showPayButton()
} else {
    if (!report.isNfcEnabled)         promptEnableNfc()
    if (!report.isBiometricAvailable) promptSetupBiometric()
    if (!report.isDeviceEnrolled) {
        lifecycleScope.launch { sdk.enrollDevice() }
    }
}
```

### 4. Customer — prepare payment (arm session)

```kotlin
lifecycleScope.launch {
    sdk.preparePayment(activity = this@PaymentActivity)
        .onSuccess { /* session armed — show "tap to pay" UI */ }
        .onFailure { error -> showError(error.message) }
}
```

### 5. Merchant — process incoming NFC tap

```kotlin
// In your merchant Activity — enable NFC reader mode
val nfcAdapter = NfcAdapter.getDefaultAdapter(this)

nfcAdapter.enableReaderMode(
    this,
    AtheerNfcReader(
        context         = this,
        merchantId      = "YOUR_MERCHANT_ID",
        receiverAccount = "71XXXXXXX",        // merchant's registered phone/POS
        amount          = 5000L,               // amount in YER
        currency        = "YER",
        transactionType = "P2M",
        transactionCallback = { chargeRequest ->
            lifecycleScope.launch {
                val result = sdk.charge(chargeRequest, accessToken = userAccessToken)
                result.onSuccess  { response -> showSuccess(response.transactionId) }
                result.onFailure  { error    -> showError(error.message) }
            }
        },
        errorCallback = { exception -> showError(exception.message) }
    ),
    NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
    null
)
```

### 6. Observe session state

```kotlin
lifecycleScope.launch {
    sdk.sessionState.collect { state ->
        when (state) {
            SessionState.ARMED   -> startCountdown(60)
            SessionState.EXPIRED -> showMessage("انتهت صلاحية الجلسة")
            SessionState.IDLE    -> resetPaymentUI()
        }
    }
}
```

---

## AtheerWalletAdapter — Simplified Integration

`AtheerWalletAdapter` is a high-level facade that hides all SDK complexity behind a clean 4-step API.
It is the **recommended integration path** for wallet app developers.

### Step 1 — Initialize (once in `Application.onCreate`)

```kotlin
AtheerWalletAdapter.initialize(
    context            = applicationContext,
    merchantId         = "YOUR_MERCHANT_ID",
    apiKey             = "YOUR_API_KEY",
    phoneNumber        = "71XXXXXXX",
    isSandbox          = false,
    blockRootedDevices = true,
    certificatePins    = listOf(
        "sha256/<primary-cert-hash>=",
        "sha256/<backup-cert-hash>="
    )
)
```

### Step 2 — Enroll (once after first install)

```kotlin
lifecycleScope.launch {
    AtheerWalletAdapter.enroll()
        .onSuccess { showPaymentScreen() }
        .onFailure { showError(it.message) }
}
```

### Step 3 — Prepare (before each payment tap)

```kotlin
lifecycleScope.launch {
    AtheerWalletAdapter.preparePayment(activity = this@MainActivity)
        .onSuccess { showTapToPayUI() }
        .onFailure { showError(it.message) }
}
```

### Step 4 — Process (merchant side, after NFC tap)

```kotlin
lifecycleScope.launch {
    AtheerWalletAdapter.processPayment(
        rawNfcData      = receivedNfcPayload,
        amount          = 5000.0,
        receiverAccount = "71XXXXXXX",
        transactionType = "P2M",
        accessToken     = userAccessToken
    )
    .onSuccess  { response -> showSuccess(response.transactionId) }
    .onFailure  { error    -> showError(error.message) }
}
```

### Observe session state via Adapter

```kotlin
lifecycleScope.launch {
    AtheerWalletAdapter.sessionState.collect { state ->
        when (state) {
            SessionState.ARMED   -> startCountdown(60)
            SessionState.EXPIRED -> showExpiredMessage()
            SessionState.IDLE    -> resetPaymentUI()
        }
    }
}
```

### Check readiness via Adapter

```kotlin
val report = AtheerWalletAdapter.checkReadiness()
if (!report.isReadyForPayment) {
    if (!report.isNfcEnabled)         promptEnableNfc()
    if (!report.isBiometricAvailable) promptSetupBiometric()
}
```

---

## API Reference

### 1. Initialization

#### `AtheerSdk.init(block: AtheerSdkConfig.() -> Unit)`

Initializes the singleton. Must be called before `getInstance()`. Throws `SecurityException` if `blockRootedDevices = true` and the device is rooted.

| Property | Type | Default | Description |
|---|---|---|---|
| `context` | `Context` | — | **Required.** Application context |
| `merchantId` | `String` | `""` | **Required.** Merchant identifier issued by Atheer |
| `apiKey` | `String` | `""` | **Required.** API key for request authentication |
| `phoneNumber` | `String` | `""` | **Required.** User's phone number — serves as device identity |
| `isSandbox` | `Boolean` | `true` | `true` = connect to local emulator; `false` = production |
| `enableApnFallback` | `Boolean` | `false` | Enable fallback to public internet if private APN is unavailable |
| `blockRootedDevices` | `Boolean` | `false` | Throw `SecurityException` on rooted devices instead of just warning |
| `certificatePins` | `List<String>` | `emptyList()` | SHA-256 public key hashes for Certificate Pinning. **Required in production.** Ignored in sandbox. |

#### `AtheerSdkBuilder`

Alternative builder with early validation:

```kotlin
val config = AtheerSdkBuilder().apply {
    context    = applicationContext
    merchantId = "MERCHANT_001"
    apiKey     = "api-key"
    phoneNumber= "71XXXXXXX"
    isSandbox  = true
}.build()  // throws IllegalStateException if any required field is blank
```

---

### 2. Device Enrollment

```kotlin
suspend fun enrollDevice(forceRenew: Boolean = false): Result<Unit>
```

Registers the device with the Atheer backend and securely stores the issued `deviceSeed`. If the device is already enrolled and `forceRenew = false`, the call returns immediately without a network request.

**Endpoint**: `POST /api/v1/devices/enroll`  
**Body**: `{ "deviceId": "<phoneNumber>" }`

```kotlin
lifecycleScope.launch {
    sdk.enrollDevice()
        .onSuccess { /* proceed to payment UI */ }
        .onFailure { error -> showError(error.message) }
}
```

---

### 3. Readiness Check

```kotlin
fun checkReadiness(): AtheerReadinessReport
```

Returns a snapshot of all hardware and enrollment prerequisites. Use this before presenting the payment UI.

```kotlin
data class AtheerReadinessReport(
    val isNfcSupported: Boolean,       // Device has NFC hardware
    val isNfcEnabled: Boolean,         // NFC is turned on in Settings
    val isHceSupported: Boolean,       // Device supports Host Card Emulation
    val isBiometricAvailable: Boolean, // Strong biometric (fingerprint) is set up
    val isDeviceEnrolled: Boolean,     // deviceSeed has been received and stored
    val isDeviceRooted: Boolean        // RootBeer detection result
) {
    // true when ALL requirements are met: NFC on, HCE supported,
    // biometric available, device enrolled, and device not rooted
    val isReadyForPayment: Boolean
}
```

---

### 4. Prepare Payment (Customer Side)

```kotlin
suspend fun preparePayment(activity: FragmentActivity): Result<Unit>
```

Triggers the biometric prompt. On success:
1. Increments the monotonic counter.
2. Derives a one-time LUK via `HMAC-SHA256(deviceSeed, counter)`.
3. Creates a cryptogram: `HMAC-SHA256(LUK, "phoneNumber|counter|timestamp")`.
4. Arms the payment session for 60 seconds.
5. Updates `sessionState` to `SessionState.ARMED`.

**Rate Limiting**: A minimum of **5 seconds** must elapse between consecutive payment attempts.

```kotlin
lifecycleScope.launch {
    sdk.preparePayment(activity = this@PaymentActivity)
        .onSuccess { showTapToPayUI() }
        .onFailure { error -> showError(error.message) }
}
```

---

### 5. Process Payment (Merchant Side)

#### `parseNfcDataToRequest`

```kotlin
fun parseNfcDataToRequest(
    rawNfcData: String,        // Format: "DeviceID|Counter|Timestamp|Signature"
    amount: Double,
    receiverAccount: String,
    transactionType: String    // "P2M" or "P2P"
): ChargeRequest
```

Parses the raw NFC payload received from the customer's device. Validates:
- Payload has exactly 4 pipe-delimited fields.
- `timestamp` age is ≤ 120 seconds.
- `timestamp` is not from the future (tolerance: 10 seconds).

Throws `IllegalArgumentException` on validation failure.

#### `charge`

```kotlin
suspend fun charge(request: ChargeRequest, accessToken: String): Result<ChargeResponse>
```

Sends the payment request to the backend. On success, automatically persists the transaction to the local encrypted database. Returns `Result.failure` if no internet connection is available.

**Endpoint**: `POST /api/v1/payments/process`

---

### 6. Session State

```kotlin
val sessionState: StateFlow<SessionState>
```

A `StateFlow` you can collect to observe the payment session lifecycle:

| Value | Meaning |
|---|---|
| `IDLE` | No active session |
| `ARMED` | Biometric succeeded — ready for NFC tap (60s window) |
| `EXPIRED` | 60-second window elapsed without NFC tap |

The session transitions to `IDLE` immediately after a successful NFC tap consumes it.

---

### 7. Error Handling

All network errors are mapped to `AtheerError` — a sealed class hierarchy:

```kotlin
sealed class AtheerError(val errorCode: String, override val message: String) : Exception(message) {
    class InsufficientFunds(message: String)    // ERR_FUNDS   — HTTP 400 / status "REJECTED"
    class InvalidVoucher(message: String)       // ERR_VOUCHER — HTTP 400 / status "EXPIRED"
    class AuthenticationFailed(message: String) // ERR_AUTH    — HTTP 401 / 403
    class ProviderTimeout(message: String)      // ERR_TIMEOUT — HTTP 504
    class NetworkError(message: String)         // ERR_NETWORK — No internet / HTTP 503
    class UnknownError(message: String)         // ERR_UNKNOWN — Unexpected response
}
```

Usage:

```kotlin
val result = sdk.charge(request, accessToken)
result.onFailure { error ->
    when (error) {
        is AtheerError.InsufficientFunds    -> showMessage("رصيد غير كافٍ")
        is AtheerError.AuthenticationFailed -> refreshTokenAndRetry()
        is AtheerError.ProviderTimeout      -> showMessage("انتهت مهلة الاستجابة، حاول مجدداً")
        is AtheerError.NetworkError         -> showMessage("لا يوجد اتصال بالإنترنت")
        else                                -> showMessage(error.message ?: "خطأ غير معروف")
    }
}
```

---

## Data Models

### `ChargeRequest`

```kotlin
data class ChargeRequest(
    val amount: Long,                          // Amount in the smallest currency unit
    val currency: String = "YER",              // ISO 4217 currency code
    val merchantId: String,                    // Merchant identifier
    val receiverAccount: String,               // Merchant phone / POS number
    val transactionRef: String,                // Unique reference (UUID) generated on merchant device
    val transactionType: String,               // "P2M" (Person-to-Merchant) or "P2P"
    val deviceId: String,                      // Customer's phone number (from NFC payload)
    val counter: Long,                         // Monotonic counter (from NFC payload)
    val timestamp: Long,                       // Unix millis (from NFC payload)
    val authMethod: String = "BIOMETRIC_CRYPTOGRAM",
    val signature: String,                     // HMAC-SHA256 cryptogram
    val description: String? = null
)
```

### `ChargeResponse`

```kotlin
data class ChargeResponse(
    val transactionId: String,   // Atheer-assigned transaction ID
    val status: String,          // ACCEPTED | PENDING | REJECTED | REVERSED | EXPIRED
    val message: String? = null, // Human-readable result message
    val balance: Double? = null, // Remaining wallet balance (جوالي support)
    val issuerRef: String? = null,// External issuer reference (bank/wallet)
    val trxDate: String? = null  // Server-side transaction timestamp
)
```

### `AtheerTransaction`

```kotlin
data class AtheerTransaction(
    val transactionId: String,
    val amount: Long,
    val currency: String,
    val receiverAccount: String,
    val transactionType: String,
    val timestamp: Long,
    val deviceId: String,
    val counter: Long,
    val authMethod: String,
    val signature: String
)
```

---

## Permissions

The SDK declares the following permissions in its `AndroidManifest.xml` (automatically merged):

```xml
<uses-permission android:name="android.permission.NFC" />
<uses-feature android:name="android.hardware.nfc.hce" android:required="true" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.CHANGE_NETWORK_STATE" />
<uses-permission android:name="android.permission.BIOMETRIC" />
<uses-permission android:name="android.permission.VIBRATE" />
```

The `android.hardware.nfc.hce` feature is marked **required**, meaning your app will only be visible on devices that support HCE on Google Play.

### HCE Service & AID Registration

The SDK registers `AtheerApduService` as a payment HCE service under the following AIDs:

| AID | Network |
|---|---|
| `A000000003101001` | Atheer (proprietary) |
| `A0000000031010` | Visa |
| `A0000000041010` | Mastercard |

The service requires the device to be unlocked (`android:requireDeviceUnlock="true"`).

### Setting as Default Payment App

To ensure the HCE service is selected during NFC taps, guide the user to the settings screen:

```kotlin
val intent = Intent(this, AtheerPaymentSettingsActivity::class.java)
startActivity(intent)
```

This activity lets the user set Atheer as the default NFC payment application.

---

## ProGuard / R8

The SDK ships a `consumer-rules.pro` file that is automatically applied to consuming apps. No manual ProGuard rules are required.

---

## Sandbox vs Production

| Setting | Sandbox (`isSandbox = true`) | Production (`isSandbox = false`) |
|---|---|---|
| API Base URL | `http://10.0.2.2:4000` (Android Emulator) | `http://206.189.137.59:4000` |
| Certificate Pinning | **Disabled** | **Enabled** (TLS 1.2 / 1.3) |
| HTTP Cleartext | Allowed | Blocked |
| Root Detection | Warning only | Configurable (block or warn) |

> ⚠️ **Before releasing to production**, update the `sha256` certificate pin values in `AtheerNetworkRouter` to match your server's actual public key hash:
> ```bash
> openssl s_client -connect api.atheer.com:443 \
>   | openssl x509 -pubkey -noout \
>   | openssl pkey -pubin -outform der \
>   | openssl dgst -sha256 -binary \
>   | base64
> ```

---

## Dependencies

| Library | Version | Purpose |
|---|---|---|
| `kotlinx-coroutines-android` | 1.8.1 | Async operations |
| `androidx.security:security-crypto` | 1.1.0-alpha06 | EncryptedSharedPreferences |
| `androidx.biometric:biometric` | 1.1.0 | Fingerprint authentication |
| `androidx.room:room-runtime` | 2.6.1 | Local database ORM |
| `net.zetetic:android-database-sqlcipher` | 4.5.4 | SQLCipher encryption |
| `com.squareup.okhttp3:okhttp` | 4.12.0 | HTTP client |
| `com.google.code.gson:gson` | 2.10.1 | JSON serialization |
| `com.scottyab:rootbeer-lib` | 0.1.0 | Root detection |
| `com.google.android.play:integrity` | 1.3.0 | Play Integrity API |

---

## License

Copyright © Atheer. All rights reserved.

See [NOTICE.txt](NOTICE.txt) for third-party attributions.

---

<div align="center">

Built with ❤️ for the Yemeni fintech ecosystem · أُنشئ لدعم منظومة المدفوعات الرقمية اليمنية

</div>
