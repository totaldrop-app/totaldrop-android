# TotalDrop Android Module (`05-android`)

Standalone 3-layer Android module for **TotalDrop** (synced with upstream commit `ebe2b19`), combining the shared Rust cryptographic/protocol workspace, the phone/foldable/tablet **Material 3 Expressive** shell (`ui-m3-expressive/`), the dedicated 10-foot **Android TV / Google TV** Jetpack Compose for TV shell (`ui-android-tv/`), and three isolated per-ABI build units (`target-arm64-v8a/`, `target-armeabi-v7a/`, `target-x86_64/`).

---

## Repository Layout

```text
05-android/
├── .github/workflows/ci.yml       # Hardened multi-arch & multi-ABI CI (16 KB ELF, cargo-deny, REUSE, gitleaks)
├── Cargo.toml                     # Rust 2024 workspace (resolver = "3", rust-version = "1.99")
├── Cargo.lock                     # Pinned dependency graph shared by all Rust crates & bridge
├── rust-toolchain.toml            # Stable Rust toolchain + Android cross-compilation targets
├── deny.toml                      # Supply-chain & license policy (cargo-deny)
├── REUSE.toml                     # REUSE 3.3 machine-readable copyright & license annotations
├── tools/check-spdx.sh            # Verifies SPDX headers across all hand-written source files
├── crates/
│   ├── totaldrop-crypto/          # Suite TD0: CPace, ML-KEM-768, Ed25519, ChaCha20-Poly1305, BLAKE3
│   ├── totaldrop-proto/           # Transfer codes, strict link parser, CBOR wire format, path safety
│   ├── totaldrop-l10n/            # Compile-time ARB catalog, BCP 47 negotiation, ICU plurals & BiDi safety
│   └── totaldrop-core/            # QUIC/UDP direct + relay transport, NAT port mapping, transfer engine
├── l10n/                          # Shared app & CLI ARB translations, status.json, and test vectors
├── ui-m3-expressive/              # Phone, foldable, and tablet Material 3 Expressive shell (Flutter + Kotlin)
│   ├── android/                   # Android Gradle app (minSdk 35 / targetSdk 37), App Links, JobScheduler
│   ├── lib/                       # M3 Expressive Dart UI, 200% text-scale layouts, TalkBack semantics
│   ├── rust/                      # flutter_rust_bridge crate (`rust_lib_totaldrop`)
│   ├── rust_builder/              # Cargokit Android NDK integration
│   └── test/                      # Widget, l10n, 200% font-scale, App Link, and percent tests
├── ui-android-tv/                 # 100% native Android TV / Google TV shell (Jetpack Compose for TV)
│   ├── build.gradle.kts           # minSdk 31 / targetSdk 35, androidx.tv:tv-material, ABI splits
│   └── src/main/                  # LEANBACK_LAUNCHER manifest, MainActivity, TvReceiveScreen, TvSendScreen
├── target-arm64-v8a/              # Isolated aarch64-linux-android 16 KB page-aligned build unit
├── target-armeabi-v7a/            # Isolated armv7-linux-androideabi 32-bit ARMv7+NEON build unit
└── target-x86_64/                 # Isolated x86_64-linux-android 16 KB page-aligned build unit
```

---

## Architecture Principles

1. **Zero Cryptography or Protocol Parsing Outside Rust**: Neither Dart (`ui-m3-expressive`) nor Kotlin (`ui-android-tv`) performs key generation, hashing, signature verification, transfer-code parsing, or network socket management. All cryptographic handshakes (CPace + ML-KEM-768), chunk integrity checks, and relay-only privacy routing execute inside `crates/totaldrop-*`.
2. **Zero Telemetry, Ads, or Ambient Permissions**: No analytics SDKs, crash reporters, advertising identifiers (`AD_ID`), camera permissions, or microphone permissions are included in either Android shell.
3. **Compile-Time Verified Translations (ADR 0008 & ADR 0019)**: All ARB translation catalogs in `l10n/` are compiled directly into `totaldrop-l10n` at build time. Unreviewed drafts of safety-critical strings automatically fall back to English (`needs_english`), and every filename or text payload from a peer passes through `reveal_controls` / `shown_text` so hidden Unicode BiDi or control codepoints (`<U+202E>`) cannot spoof file extensions.

---

## Upstream `ebe2b19` Android Highlights & Security Hardening

### 1. Verified App Links & Strict Receive URI Routing (`#445`)
- Both `ui-m3-expressive` and `ui-android-tv` declare `android:autoVerify="true"` intent filters for `https://totaldrop.app/r` (verified by Android against `totaldrop.app/.well-known/assetlinks.json` and the Google Play signing certificate) alongside `totaldrop://r`.
- In `ui-m3-expressive/android/app/src/main/AndroidManifest.xml`, `flutter_deeplinking_enabled` is explicitly set to `false` so Flutter's internal router never pushes a duplicate `Home` route on top of `Receive` when opening a link.
- Web receive links carry the one-time transfer code exclusively in the URL fragment (`https://totaldrop.app/r#7-harbor-rocket`), ensuring the secret never reaches `totaldrop.app` in HTTP requests or server logs.
- Every incoming URI is parsed strictly in Rust via `totaldrop_proto::TransferCode::from_link` (`code_from_link`): percent-escapes are decoded at most once, inputs longer than 1,024 bytes or containing query strings/extra segments are rejected, empty link fragments are ignored harmlessly, and malformed non-empty codes clear the input field and surface a clear invalid-code message.

### 2. Threat Model `T19` Mitigation (Receive Link & Intent Spoofing)
- **Explicit Intent Filtering**: Because `MainActivity` is exported, another local app could send an explicit `ACTION_VIEW` intent that bypasses manifest `<intent-filter>` declarations. Both `ui-m3-expressive` (`MainActivity.isReceiveLink`) and `ui-android-tv` (`MainActivity.isSupportedReceiveLink`) re-verify the exact scheme, host, and path in Kotlin before handing the URI to Rust.
- **No Auto-Connect**: Opening a receive link *only* populates the code input box on the Receive screen. No network connection starts until the user explicitly activates **Connect**, after which CPace key agreement, the 4 verification words, and manual offer confirmation still protect the device.
- **Active-Session Isolation**: Links arriving while a connection is negotiating, while an incoming offer awaits user confirmation, while a transfer is running, or while an in-memory received text note is displayed on screen are refused (`canTakeLink == false`) so an external trigger cannot clobber active state or discard unsaved received text.
- **Custom Scheme vs. App Links Boundary**: Verified `https://totaldrop.app/r#…` links open TotalDrop directly without chooser interception on Play-signed installs, whereas `totaldrop://r/…` remains a fallback for in-browser buttons (tracked in upstream `#507` for package-scoped `intent://` hardening).

### 3. 200% Text-Scale Protection & Overflow-Free Layouts (`#322`)
- `ui-m3-expressive` verifies every screen (`Home`, `Send`, `Receive`, `Settings`, `BackgroundSetup`) across every shipped translation plus pseudo-locales (`en-XA` +40% length expansion and `ar-XB` RTL) on a compact phone viewport at Android's largest **200% font scale** (`test/screens_l10n_test.dart`).
- Side-by-side action buttons (`ButtonPair`) measure label line wraps and automatically switch from horizontal rows to vertical stacks whenever a translated label would wrap onto multiple lines or truncate with `…`.

### 4. Regional Percent Formatting, Floor Rounding & TalkBack Parity (`#483`)
- Transfer percentages follow the device's **regional format** (`Locale` / CLDR numeral system and symbol spacing, such as `50%` in `en-US`, `%50` in `tr-TR`, `50 %` in `de-DE`, and Eastern Arabic numerals in `ar-EG` / `fa-IR`) rather than the app's display language (ADR 0008).
- Completion percentages round **strictly down** (`floor`), so `995` of `1,000` bytes displays `99%` instead of prematurely reporting `100%` while data is still arriving (`test/progress_percent_test.dart` and `computeFloorPercent` in `ui-android-tv`).
- Accessibility semantics (`LinearProgressIndicator` semantics in Flutter and `stateDescription` / `ProgressBarRangeInfo` in Jetpack Compose for TV) expose the exact floor-rounded integer (`99`) so TalkBack announces the same percentage shown visually.

### 5. Full Foldable & Large Screen Android Support (`0.4.0-beta.1+15`)
- **Zero-Dependency Posture & Hinge Engine (`ui-m3-expressive/lib/ui/core/adaptive.dart`)**: Reads Jetpack WindowManager `DisplayFeature`s exposed by `FlutterActivity` (`MediaQuery.displayFeaturesOf`) to adapt seamlessly across **Compact / Narrow Cover** (`320–344 dp`), **Unfolded Flat** (`Medium >= 600 dp`, `Expanded >= 840 dp`), **Book Posture** (vertical hinge / half-opened vertical fold), and **Tabletop / Flex Posture** (horizontal half-opened fold on clamshell and book foldables).
- **Zero Hinge Overlap & RTL Parity**: In Book and Unfolded Flat layouts, panes split cleanly around the physical hinge or center crease with directional `Row` mirroring (`ar`, `fa`, `he`, `ur`). In Tabletop posture, visual status and transfer codes occupy the tilted upper display half while interactive controls sit in the lower half, automatically falling back to keyboard-safe scrolling when an IME shrinks the lower pane.
- **Uninterrupted Fold/Unfold Continuity & Multi-Window Drag & Drop**: `AndroidManifest.xml` declares `android:resizeableActivity="true"`, `android:supportsRtl="true"`, and full configuration-change handling (`orientation|keyboardHidden|keyboard|navigation|screenSize|smallestScreenSize|locale|layoutDirection|fontScale|screenLayout|density|uiMode`) so active transfers survive fold/unfold transitions untouched. In split-screen or desktop windowing, `MainActivity.kt` accepts dropped `content://` files and plain text through the same `ShareCopier` / `SharedFiles` security boundary and releases temporary `DragAndDropPermissions` immediately after copying.
- **Verified Across Form Factors**: `test/foldable_test.dart`, `DragDropTest.kt`, and `tool/check_16kb_elf.py` verify zero hinge overlap, RTL pane mirroring, `AlertDialog` hinge avoidance, live cover -> unfolded -> book -> tabletop state continuity, 200% text-scale overflow immunity, drag-and-drop sanitization, and 16 KB ELF/APK alignment.

---

## Native Android TV / Google TV Shell (`ui-android-tv/`)

`ui-android-tv/` provides a dedicated 10-foot living-room interface built natively in Kotlin with **Jetpack Compose for TV** (`androidx.tv:tv-material:1.0.1`, `minSdk = 31`, `targetSdk = 35`):

- **Strict Leanback & Privacy Manifest** (`src/main/AndroidManifest.xml`): Declares `android.intent.category.LEANBACK_LAUNCHER`, requires `android.software.leanback`, marks `android.hardware.touchscreen` optional, requests only `INTERNET` and `ACCESS_NETWORK_STATE`, and strips `RECORD_AUDIO`, `CAMERA`, location, and `AD_ID` permissions via `tools:node="remove"`.
- **10-Foot D-Pad Focus Ergonomics** (`MainActivity.kt`, `TvReceiveScreen.kt`, `TvSendScreen.kt`): Directional remote navigation across top-level `Receive` / `Send` tabs, Relay-Only privacy mode toggle, D-Pad quick prefix digits (`1`–`9`, hyphen, backspace) for basic 5-way remotes, and Decline-first focus on incoming offers to prevent accidental single-click acceptance.
- **Oversized 2-Word / 3-Word Code & Offline QR Pairing**: Displays segmented `7 – harbor – rocket` transfer codes in 52sp monospace pills alongside an offline vector `Canvas` QR code (`https://totaldrop.app/r#<code>`) scannable from across a living room on 1080p and 4K displays.
- **Dynamic Material 3 for TV Theme**: Extracts Android 12+ (`API 31+`) wallpaper tonal palettes (`dynamicDarkColorScheme` / `dynamicLightColorScheme`) into `androidx.tv.material3.MaterialTheme` with Dark (living-room default), Light, and System Auto modes.
- **Rust FFI Integration** (`TotalDropRustBridge.kt`): Connects Kotlin coroutines and `Flow<TvTransferEvent>` streams to `libtotaldrop_app_rust.so` / `librust_lib_totaldrop.so`.

---

## Isolated Per-ABI Target Units & Android 15 (16 KB Page Size) Compliance

Starting with Android 15 (API 35) and Google Play's 64-bit requirements, native shared libraries on 64-bit architectures must align ELF `PT_LOAD` segments to **16 KB (`0x4000` = `16384` bytes)** and be stored uncompressed inside APKs/AABs (`useLegacyPackaging = false`).

Each target directory isolates its Cargo linker flags and reproducible packaging script:

| Directory | Android ABI | Rust Target Triple | Page Alignment | Target Hardware & Outputs |
| :--- | :--- | :--- | :--- | :--- |
| `target-arm64-v8a/` | `arm64-v8a` | `aarch64-linux-android` | **16 KB** (`-Wl,-z,max-page-size=16384`) | Modern 64-bit phones, foldables, tablets, 64-bit Google TV; F-Droid/GitHub split APK, Play `.aab`, TV split APK |
| `target-armeabi-v7a/` | `armeabi-v7a` | `armv7-linux-androideabi` | 4 KB (32-bit ARMv7 + NEON) | Legacy 32-bit ARM phones & 32-bit Android TV / Google TV sticks; phone & TV split APKs |
| `target-x86_64/` | `x86_64` | `x86_64-linux-android` | **16 KB** (`-Wl,-z,max-page-size=16384`) | Chromebooks (ARCVM), Android PC, x86 Google TV, Android 15+ 16 KB CI emulators; phone & TV split APKs |

### Building Per-ABI Artifacts

With `ANDROID_NDK_HOME` (or `ANDROID_SDK_ROOT/ndk/<version>`) configured:

```bash
# 64-bit ARM (verifies 0x4000 ELF PT_LOAD alignment via llvm-readelf, builds split APKs + optional Play .aab)
./target-arm64-v8a/build-arm64-v8a.sh --bundle

# 32-bit ARMv7 + NEON (legacy phones & 32-bit Android TV sticks)
./target-armeabi-v7a/build-armeabi-v7a.sh

# 64-bit x86_64 (verifies 0x4000 ELF PT_LOAD alignment, Chromebook / Android PC / CI emulator split APKs)
./target-x86_64/build-x86_64.sh
```

Pass `--rust-only` to any `build-<abi>.sh` script to compile, stage, and verify only the Rust `.so` binaries (`librust_lib_totaldrop.so` and `libtotaldrop_app_rust.so`) and emit `SHA256SUMS` under `target-<abi>/out/`.

---

## Verification

Run the full Rust workspace test suite and strict Clippy lints (`unsafe_code = "forbid"` across all protocol/core crates):

```bash
RUSTUP_TOOLCHAIN=stable cargo fmt --check -p totaldrop-crypto -p totaldrop-proto -p totaldrop-core -p totaldrop-l10n
RUSTUP_TOOLCHAIN=stable cargo clippy --workspace --all-targets --locked -- -D warnings
RUSTUP_TOOLCHAIN=stable cargo test --workspace --locked
./tools/check-spdx.sh
```

---

## License

- Protocol, cryptographic, localization, and core engine crates (`crates/*`): `MIT OR Apache-2.0`
- Android Material 3 Expressive shell (`ui-m3-expressive/`), Android TV Compose shell (`ui-android-tv/`), and ABI packaging scripts (`target-*/`): `GPL-3.0-or-later`
