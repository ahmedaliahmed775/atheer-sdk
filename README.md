<div align="center">

# Atheer SDK

**A robust and secure Android library for processing payments via Host Card Emulation (HCE) and SoftPOS technology, with advanced offline-capable transaction support.**

[![Android API](https://img.shields.io/badge/API-24%2B-brightgreen.svg)](https://android-arsenal.com/api?level=24)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.23-blue.svg)](https://kotlinlang.org)
[![License](https://img.shields.io/badge/License-See%20LICENSE.txt-orange.svg)](LICENSE.txt)
[![GitHub Packages](https://img.shields.io/badge/Published-GitHub%20Packages-blue.svg)](https://github.com/ahmedaliahmed775/atheer-sdk/packages)

</div>

---

## Table of Contents

1. [Project Overview](#project-overview)
2. [Architecture](#architecture)
3. [Features](#features)
4. [Prerequisites](#prerequisites)
5. [Installation](#installation)
6. [Required Permissions](#required-permissions)
7. [Quick Start](#quick-start)
8. [Usage](#usage)
   - [Payer Side — HCE (Host Card Emulation)](#payer-side--hce-host-card-emulation)
   - [Merchant Side — SoftPOS NFC Reader](#merchant-side--softpos-nfc-reader)
   - [Direct Charge (Online)](#direct-charge-online)
   - [Error Handling](#error-handling)
9. [Payment Flow](#payment-flow)
10. [Data Models](#data-models)
11. [Security Model](#security-model)
12. [Project Structure](#project-structure)
13. [Contributing](#contributing)
14. [License](#license)

---

## Project Overview

**Atheer SDK** is an Android library written in Kotlin that provides a complete, hardware-backed payment layer for applications integrating with the **Atheer Switch Backend**. It enables two complementary payment modes:

| Mode | Description |
|------|-------------|
| **HCE (Payer)** | The payer's device emulates a contactless payment card. The SDK arms a cryptographically signed payment session and transmits it over NFC tap. |
| **SoftPOS (Merchant)** | The merchant's device acts as an NFC terminal reader. The SDK reads, validates, and submits the signed payment data to the Atheer Switch. |

The library is designed around a **Zero-Trust Dynamic Key Derivation** architecture, meaning every transaction uses a unique derived key, eliminating the need for persistent plaintext secrets on the device.

---

## Architecture

```
┌────────────────────────────────────────────────────────┐
│                      AtheerSdk (Facade)                │
│  init()  │  preparePayment()  │  charge()  │  parseNfc │
└──────────┬─────────────────────────────────────────────┘
           │
    ┌──────┴────────────────────────────────────┐
    │                                           │
┌───▼──────────────┐              ┌─────────────▼───────┐
│  Security Layer  │              │   Network Layer     │
│                  │              │                     │
│ AtheerKeystore   │              │ AtheerNetworkRouter │
│  Manager         │              │ (OkHttp / TLS 1.3)  │
│  ├─ Master Seed  │              └─────────────────────┘
│  │  (TEE/AES256) │
│  ├─ Monotonic    │              ┌─────────────────────┐
│  │  Counter      │              │    Storage Layer    │
│  └─ LUK Derivation│             │                     │
│     (HMAC-SHA256)│              │  AtheerDatabase     │
│                  │              │  (Room + SQLCipher) │
│ AtheerPayment    │              └─────────────────────┘
│  Session (60s)   │
└──────────────────┘
        │
┌───────┴───────────────────────────┐
│           NFC Layer               │
│                                   │
│  AtheerApduService (HCE / Payer)  │
│  AtheerNfcReader   (SoftPOS /     │
│                     Merchant)     │
└───────────────────────────────────┘
```

### Zero-Trust Dynamic Key Derivation

1. A **Master Seed** (AES-256) is generated once and stored inside the hardware-backed **Android Keystore** (TEE / StrongBox).
2. A **Monotonic Counter** stored in `EncryptedSharedPreferences` increments atomically on every transaction.
3. A **Limited-Use Key (LUK)** is derived per transaction: `LUK = HMAC-SHA256(MasterSeed, Counter)`.
4. The transaction payload (`DeviceID | Counter | Timestamp`) is signed with the LUK before NFC transmission.
5. The Backend verifies the signature and rejects any replayed counter value.

---

## Features

- ✅ **Host Card Emulation (HCE)** — device acts as a contactless payment card over NFC
- ✅ **SoftPOS NFC Reader** — device reads payment data from another HCE device
- ✅ **Zero-Trust Dynamic Key Derivation** — unique cryptographic key per transaction
- ✅ **Hardware-backed Key Storage** — Master Seed secured in Android Keystore (TEE/StrongBox)
- ✅ **Biometric Authentication** — BIOMETRIC_STRONG required before arming a payment session
- ✅ **Monotonic Counter** — encrypted, tamper-resistant counter prevents replay attacks
- ✅ **Relay Attack Protection** — NFC Round-Trip Time (RTT) measured; rejects responses > 50 ms
- ✅ **Root Detection** — powered by [RootBeer](https://github.com/scottyab/rootbeer); throws `SecurityException` on rooted devices
- ✅ **Google Play Integrity API** — asynchronous device integrity verification
- ✅ **60-Second Session Window** — armed payment sessions auto-expire after 60 seconds
- ✅ **Encrypted Local Storage** — Room + SQLCipher for transaction history
- ✅ **TLS 1.2 / 1.3 enforcement** — secure HTTPS-only communication
- ✅ **Structured Error Model** — sealed `AtheerError` class with typed error codes
- ✅ **Kotlin Coroutines** — fully async, non-blocking API

---

## Prerequisites

| Requirement | Detail |
|-------------|--------|
| **Android API** | Minimum API level **24** (Android 7.0 Nougat) |
| **Compile SDK** | API level **34** (Android 14) |
| **Kotlin** | 1.9.23 or higher |
| **NFC hardware** | Device must have an NFC chip |
| **HCE support** | Required on payer devices (`android.hardware.nfc.hce`) |
| **Biometric hardware** | Fingerprint sensor or equivalent (BIOMETRIC_STRONG) |
| **Google Play Services** | Required for Play Integrity API |
| **Build tool** | Gradle 8.x with KSP plugin |

---

## Installation

The SDK is published to **GitHub Packages**. Add the following to your project's Gradle files:

### 1. Configure the repository

In your root `settings.gradle.kts` (or `build.gradle.kts`):

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

> **Note:** GitHub Packages requires authentication. Create a [Personal Access Token](https://github.com/settings/tokens) with `read:packages` scope.
>
> Store credentials in your **user-level** `~/.gradle/gradle.properties` (shared across all Gradle projects, never committed):
> ```
> gpr.user=YOUR_GITHUB_USERNAME
> gpr.key=YOUR_PERSONAL_ACCESS_TOKEN
> ```
>
> Alternatively, add a project-local `gradle.properties` file and ensure it is listed in `.gitignore` so credentials are never committed to version control.

### 2. Add the dependency

In your app module's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.github.ahmedaliahmed775:atheer-sdk:1.0.0")
}
```

Replace `1.0.0` with the [latest released version](https://github.com/ahmedaliahmed775/atheer-sdk/packages).

---

## Required Permissions

The SDK declares the following permissions in its manifest (automatically merged into your app):

```xml
<!-- NFC communication -->
<uses-permission android:name="android.permission.NFC" />
<uses-feature android:name="android.hardware.nfc.hce" android:required="true" />

<!-- Network access -->
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

<!-- Biometrics -->
<uses-permission android:name="android.permission.BIOMETRIC" />

<!-- Haptic feedback -->
<uses-permission android:name="android.permission.VIBRATE" />
```

---

## Quick Start

### 1. Initialize the SDK

Call `AtheerSdk.init()` once in your `Application.onCreate()`:

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()

        try {
            AtheerSdk.init(
                context     = this,
                merchantId  = "YOUR_MERCHANT_ID",
                apiKey      = "YOUR_API_KEY",
                isSandbox   = false          // set true for testing
            )
        } catch (e: SecurityException) {
            // Thrown if root is detected or device integrity check fails
            Log.e("App", "Insecure device: ${e.message}")
        }
    }
}
```

> ⚠️ `init()` performs a **root check** synchronously and fires a **Play Integrity** request asynchronously. On rooted devices a `SecurityException` is thrown immediately.

---

## Usage

### Payer Side — HCE (Host Card Emulation)

The payer taps their phone to the merchant terminal. The SDK:
1. Prompts biometric authentication.
2. Derives a one-time LUK and signs the payload.
3. Arms a 60-second session for `AtheerApduService` to serve over NFC.

```kotlin
// Called from a FragmentActivity when the user initiates a payment
AtheerSdk.getInstance().preparePayment(
    activity  = this,
    deviceId  = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID),
    onSuccess = {
        // Session is now armed — prompt user to tap their device to the terminal
        showMessage("Hold your phone near the terminal to complete payment.")
    },
    onError = { errorMessage ->
        showError(errorMessage)
    }
)
```

The `AtheerApduService` HCE service is registered automatically via the library's manifest entry. No additional setup is required.

---

### Merchant Side — SoftPOS NFC Reader

The merchant's app reads the payer's NFC data and submits the charge:

```kotlin
// 1. Build the NFC reader
val nfcReader = AtheerNfcReader(
    context          = this,
    merchantId       = "MERCHANT_001",
    receiverAccount  = "00967700000001",   // merchant wallet / POS account
    amount           = 1500L,              // amount in smallest currency unit
    currency         = "YER",
    transactionType  = "P2M",
    transactionCallback = { chargeRequest ->
        // NFC data received — submit to the Atheer Switch
        submitCharge(chargeRequest)
    },
    errorCallback = { exception ->
        when (exception) {
            is AtheerNfcReader.RelayAttackException ->
                showError("Relay attack detected. Please try again face-to-face.")
            else ->
                showError("NFC error: ${exception.message}")
        }
    }
)

// 2. Enable NFC reader mode in onResume
override fun onResume() {
    super.onResume()
    NfcAdapter.getDefaultAdapter(this)?.enableReaderMode(
        this,
        nfcReader,
        NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
        null
    )
}

// 3. Disable in onPause
override fun onPause() {
    super.onPause()
    NfcAdapter.getDefaultAdapter(this)?.disableReaderMode(this)
}

// 4. Submit the charge
private fun submitCharge(request: ChargeRequest) {
    lifecycleScope.launch {
        val result = AtheerSdk.getInstance().charge(request, accessToken = "BEARER_TOKEN")
        result.fold(
            onSuccess = { response -> showSuccess("Transaction ID: ${response.transactionId}") },
            onFailure = { error -> handleAtheerError(error) }
        )
    }
}
```

---

### Direct Charge (Online)

For in-app payments that do not go through NFC, derive the signature using the SDK's key manager:

```kotlin
val keystoreManager = AtheerSdk.getInstance().getKeystoreManager()

// 1. Increment counter and derive a one-time LUK
val counter   = keystoreManager.incrementAndGetCounter()
val luk       = keystoreManager.deriveLUK(counter)
val timestamp = SystemClock.elapsedRealtime()
val deviceId  = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)

// 2. Build and sign the payload: DeviceID|Counter|Timestamp
val payload   = "$deviceId|$counter|$timestamp"
val signature = keystoreManager.signWithLUK(payload, luk)

// 3. Construct the request
val request = ChargeRequest(
    amount          = 5000L,
    currency        = "YER",
    merchantId      = "MERCHANT_001",
    receiverAccount = "00967700000001",
    transactionRef  = UUID.randomUUID().toString(),
    transactionType = "P2M",
    deviceId        = deviceId,
    counter         = counter,
    timestamp       = timestamp,
    authMethod      = "BIOMETRIC_CRYPTOGRAM",
    signature       = signature,
    description     = "Order #12345"
)

lifecycleScope.launch {
    val result = AtheerSdk.getInstance().charge(request, accessToken = "BEARER_TOKEN")
    result.onSuccess { response ->
        Log.i("Payment", "Success — Transaction ID: ${response.transactionId}")
    }.onFailure { error ->
        handleAtheerError(error)
    }
}
```

---

### Error Handling

The SDK uses a typed sealed class for all errors:

```kotlin
fun handleAtheerError(error: Throwable) {
    when (error) {
        is AtheerError.InsufficientFunds     -> showError("Insufficient balance.")
        is AtheerError.AuthenticationFailed  -> showError("Authentication failed. Check your API key.")
        is AtheerError.InvalidVoucher        -> showError("Invalid or expired token.")
        is AtheerError.ProviderTimeout       -> showError("Switch server timed out. Try again.")
        is AtheerError.NetworkError          -> showError("No internet connection.")
        is AtheerError.UnknownError          -> showError("An unexpected error occurred.")
        else                                 -> showError(error.message ?: "Unknown error")
    }
}
```

| Error Class | Error Code | Trigger |
|-------------|-----------|---------|
| `InsufficientFunds` | `ERR_FUNDS` | HTTP 400 with `REJECTED` status |
| `InvalidVoucher` | `ERR_VOUCHER` | HTTP 400 / `EXPIRED` status |
| `AuthenticationFailed` | `ERR_AUTH` | HTTP 401 / 403 |
| `ProviderTimeout` | `ERR_TIMEOUT` | HTTP 504 |
| `NetworkError` | `ERR_NETWORK` | IO exception / no connectivity |
| `UnknownError` | `ERR_UNKNOWN` | Any other server error |

---

## Payment Flow

### HCE Tap-to-Pay Flow

```
  Payer Device                           Merchant Device (SoftPOS)
       │                                          │
       │  1. User initiates payment               │
       │  2. Biometric authentication             │
       │  3. Counter++ → Derive LUK               │
       │  4. Sign payload: DeviceID|Counter|TS    │
       │  5. Arm 60-second session                │
       │                                          │
       │   ──────── NFC TAP ──────────────────>   │
       │   SELECT AID (A000000003101001)           │
       │   <───────────────────────────────────   │
       │   GET_PAYMENT_DATA (0x00CA)               │
       │   ────── Signed Payload ──────────────>  │
       │                                          │
       │   (RTT measured < 50 ms enforced)        │
       │                                          │
       │                                   6. Parse payload
       │                                   7. Build ChargeRequest
       │                                   8. POST /api/v1/payments/process
       │                                          │
       │                                   9. Backend verifies signature
       │                                      & monotonic counter
       │                                  10. Transaction processed
```

---

## Data Models

### `ChargeRequest`

| Field | Type | Description |
|-------|------|-------------|
| `amount` | `Long` | Amount in the smallest currency unit |
| `currency` | `String` | Currency code (default: `"YER"`) |
| `merchantId` | `String` | Merchant identifier |
| `receiverAccount` | `String` | Merchant wallet or POS account number |
| `transactionRef` | `String` | Unique transaction reference (UUID) |
| `transactionType` | `String` | `"P2M"` (Person-to-Merchant) or `"P2P"` |
| `deviceId` | `String` | Sender device identifier |
| `counter` | `Long` | Monotonic counter value |
| `timestamp` | `Long` | `SystemClock.elapsedRealtime()` value |
| `authMethod` | `String` | Default: `"BIOMETRIC_CRYPTOGRAM"` |
| `signature` | `String` | Base64-encoded HMAC-SHA256 signature |
| `description` | `String?` | Optional transaction description |

### `ChargeResponse`

| Field | Type | Description |
|-------|------|-------------|
| `transactionId` | `String` | Unique transaction ID from the Switch |
| `status` | `String` | Transaction status (e.g., `"ACCEPTED"`) |
| `message` | `String` | Human-readable result message |

### `AtheerTransaction`

Represents a full transaction object used in `processTransaction()`:

| Field | Type | Description |
|-------|------|-------------|
| `amount` | `Double` | Transaction amount |
| `currency` | `String` | Currency code |
| `receiverAccount` | `String` | Recipient account |
| `transactionType` | `String` | Transaction type |
| `deviceId` | `String` | Sender device ID |
| `counter` | `Long` | Monotonic counter |
| `timestamp` | `Long` | Transaction timestamp |
| `authMethod` | `String` | Authentication method |
| `signature` | `String` | Digital signature |

### `AtheerError`

Sealed class — see [Error Handling](#error-handling) section above.

---

## Security Model

Atheer SDK is built on a multi-layered defense strategy:

| Layer | Mechanism |
|-------|-----------|
| **Device Integrity** | Root detection via RootBeer; rejects rooted devices at init |
| **Platform Attestation** | Google Play Integrity API validates device and app authenticity |
| **Key Storage** | Master Seed (AES-256) stored in Android Keystore, optionally in StrongBox TEE |
| **Key Derivation** | `LUK = HMAC-SHA256(MasterSeed, Counter)` — unique key per transaction |
| **Replay Prevention** | Monotonic counter stored in `EncryptedSharedPreferences`; backend rejects repeated counter values |
| **Biometric Gate** | `BIOMETRIC_STRONG` authentication required before each payment session |
| **Session Expiry** | Armed payment sessions expire after **60 seconds** |
| **Relay Attack Prevention** | NFC RTT enforced < 50 ms; sessions are cancelled if exceeded |
| **Transport Security** | TLS 1.2 / 1.3 enforced via `ConnectionSpec.MODERN_TLS` |
| **Payload Integrity** | Every NFC payload is digitally signed: `DeviceID | Counter | Timestamp | Signature` |

---

## Project Structure

```
atheer-sdk/
├── atheer-sdk/
│   ├── build.gradle.kts           # Module build config & publishing setup
│   ├── consumer-rules.pro         # ProGuard rules for consumers
│   ├── proguard-rules.pro         # ProGuard rules for the library
│   └── src/main/
│       ├── AndroidManifest.xml    # Permissions, HCE service, settings activity
│       ├── java/com/atheer/sdk/
│       │   ├── AtheerSdk.kt                          # Main SDK facade (init, charge, preparePayment)
│       │   ├── AtheerPaymentSettingsActivity.kt      # HCE payment settings screen
│       │   ├── database/
│       │   │   ├── AtheerDatabase.kt                 # Room + SQLCipher database
│       │   │   ├── TransactionDao.kt                 # DAO for transaction queries
│       │   │   └── TransactionEntity.kt              # Local transaction entity
│       │   ├── hce/
│       │   │   └── AtheerApduService.kt              # HostApduService (HCE card emulation)
│       │   ├── model/
│       │   │   ├── AtheerError.kt                    # Typed error sealed class
│       │   │   ├── AtheerTransaction.kt              # Full transaction model
│       │   │   ├── BalanceResponse.kt                # Balance query response
│       │   │   ├── ChargeRequest.kt                  # Charge/payment request model
│       │   │   ├── ChargeResponse.kt                 # Charge/payment response model
│       │   │   ├── HistoryResponse.kt                # Transaction history response
│       │   │   ├── LoginRequest.kt / LoginResponse.kt
│       │   │   ├── SignupRequest.kt / SignupResponse.kt
│       │   ├── network/
│       │   │   ├── AtheerNetworkRouter.kt            # OkHttp-based network client
│       │   │   ├── AtheerCellularRouter.kt           # Cellular/APN fallback router
│       │   │   └── AtheerSyncWorker.kt               # Background sync worker (⚠️ deprecated in v1.2.0 — no-op)
│       │   ├── nfc/
│       │   │   ├── AtheerNfcReader.kt                # IsoDep NFC reader (SoftPOS / merchant)
│       │   │   └── AtheerFeedbackUtils.kt            # Haptic & audio feedback helpers
│       │   └── security/
│       │       ├── AtheerKeystoreManager.kt          # Key generation, LUK derivation, counter
│       │       └── AtheerPaymentSession.kt           # Time-limited armed payment session
│       └── res/
│           ├── values/strings.xml
│           └── xml/
│               ├── apduservice.xml                   # HCE AID registration
│               └── network_security_config.xml
├── build.gradle.kts               # Root build config
├── settings.gradle.kts            # Project/module settings
├── gradle.properties
├── LICENSE.txt
└── NOTICE.txt
```

---

## Contributing

Contributions are welcome! To extend or improve the SDK:

1. **Fork** the repository and create a feature branch.
2. Follow the existing Kotlin code style (`kotlin.code.style=official`).
3. Write or update tests under `src/test/`.
4. Ensure the build passes: `./gradlew :atheer-sdk:build`
5. Open a Pull Request with a clear description of the change.

**Areas open for contribution:**

- Additional network router backends (e.g., cellular APN)
- Extended transaction types beyond P2M and P2P
- Enhanced device attestation strategies
- Expanded error model and localization

---

## License

See [`LICENSE.txt`](LICENSE.txt) and [`NOTICE.txt`](NOTICE.txt) in the repository root for full license details.
