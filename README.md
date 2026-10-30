# MediMinder �💊

**A reliable medication reminder app designed for older adults.**

Users add medicines by photographing the label (on-device OCR) or by typing
them in. MediMinder then fires exact, unmissable reminders with big, clear
text, and keeps a shareable adherence history.

> MediMinder is a reminder tool. It does not replace medical advice and never
> suggests changing a dose.

---

## Contents
- [Features](#features)
- [Tech stack](#tech-stack)
- [Project structure](#project-structure)
- [Getting started](#getting-started)
- [Testing](#testing)
- [CI/CD](#cicd)
- [How reminders stay reliable](#how-reminders-stay-reliable)
- [Permissions rationale](#permissions-rationale)
- [Privacy](#privacy)
- [Accessibility](#accessibility)
- [Roadmap](#roadmap--phase-2-caregiver-support)

---

## Features

| Area | What it does |
| --- | --- |
| **Add by photo** | CameraX photo → ML Kit on-device OCR → regex/rule parser extracts name, strength, dosage, form and frequency ("twice daily" → 08:00 & 20:00) → **confirmation screen with the photo and every field editable**, low-confidence fields flagged. Falls back to the manual form with the photo attached if extraction fails. |
| **Add manually** | 3-step wizard: medicine → schedule → review. Forms: tablet/capsule/liquid/drops/injection. Frequencies: daily, specific weekdays, every X hours (crossing midnight), "every N days", as-needed. Optional end date, notes, pill count. |
| **RxNorm check (optional)** | On the confirmation screen, a "Check the name online" button corrects OCR typos against [RxNorm](https://www.nlm.nih.gov/research/umls/rxnorm/). Fully degrades offline — the feature simply hides behind the same button. |
| **Reminders** | Exact alarms (`setExactAndAllowWhileIdle`) with full-screen intent, big text, gentle custom chime + vibration, and **Taken / Snooze 10 min / Skip** actions. If unanswered, re-reminds up to 3× (configurable), then marks the dose **Missed**. |
| **Today screen** | Doses grouped Morning/Afternoon/Evening/Night with status chips and one-tap "Taken". Low-stock banners ("Only 3 days of Metformin left"). Optional voice read-out (TTS). |
| **History & reports** | Month calendar with per-day adherence, weekly summary, per-medicine stats, and an exportable **PDF report** for the doctor. |
| **Safety** | Warns when a new medicine shares an active ingredient with an existing one. Visible disclaimer everywhere. |
| **Privacy** | All data in an **encrypted** database (SQLCipher + Android Keystore). Photos stay on the device and can be deleted right after extraction. No analytics, no tracking, no cloud backup by default. |

## Tech stack

- **Kotlin** 2.0, **Jetpack Compose + Material 3**, minSdk 26, target/compileSdk 35
- **MVVM + Clean Architecture** layering, **Hilt** DI, KSP
- **Room** (offline-first) encrypted with **SQLCipher**
- **CameraX** + **ML Kit Text Recognition** (on-device, bundled Latin model)
- **AlarmManager** (exact) + **WorkManager** fallback, **NotificationCompat**
- **DataStore** preferences, **Retrofit + kotlinx.serialization** (RxNorm only)
- **Coil** for photo display, custom **PdfDocument** report generator

## Project structure

Single `:app` module today, with strict package layering (the proposed
multi-module evolution is in [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)):

```
app/src/main/java/com/mediminder/app/
├── domain/                    # pure Kotlin, no Android deps — 100% JVM-testable
│   ├── model/                 # Medication, Schedule, DoseLog, ParsedLabel, enums
│   ├── parser/                # MedicationLabelParser (OCR text -> structure) + LabelDefaults
│   ├── engine/                # DoseGenerationEngine, AdherenceCalculator, LowStockCalculator
│   ├── repository/            # interfaces + ReminderSettings
│   ├── service/               # ReminderScheduler, LabelScanner, DoseEventListener (caregiver hook), ReRemindPolicy
│   └── usecase/               # Add/Mark/Snooze/Missed/Adherence/LowStock… use cases
├── data/                      # implementations
│   ├── local/                 # Room entities, DAOs, converters, SQLCipher passphrase guard
│   ├── repository/            # Medication/DoseLog/Settings/DrugName repositories
│   ├── remote/                # RxNorm API (optional)
│   ├── scanner/               # ML Kit wrapper
│   ├── report/                # PDF report generator
│   └── mapper/
├── scheduling/                # AlarmScheduler, ReminderPlanner, ReminderTrigger,
│                              # Boot/Timezone/Update receiver, Maintenance + SafetyNet workers
├── notifications/             # channels + NotificationHelper
├── tts/                       # optional voice readout
├── ui/                        # Compose: theme, components, navigation, screens
└── di/                        # Hilt modules
```

## Getting started

Requirements: **Android Studio Ladybug+**, JDK 17 (bundled), an Android device
or emulator on API 26+.

```bash
git clone https://github.com/touhidsiddiqueeraj-bit/mediminder.git
cd mediminder
./gradlew :app:assembleDebug
# install on a connected device
adb install app/build/outputs/apk/debug/app-debug.apk
```

There is no API key, no backend and no configuration step — the app is fully
offline-first.

### Troubleshooting local builds
- `SDK location not found` → create `local.properties` with
  `sdk.dir=/path/to/Android/sdk` (Android Studio does this automatically).
- JVM crashes on small machines → lower `org.gradle.jvmargs` in
  `gradle.properties`, or build with `--max-workers=1`.

## Testing

```bash
# Parser, dose engine, adherence, low stock, re-remind policy (JVM, fast)
./gradlew :app:testDebugUnitTest

# Instrumented UI + Room DAO tests (device/emulator needed)
./gradlew :app:connectedDebugAndroidTest

# Static analysis
./gradlew :app:lintDebug
```

The parser test suite covers the label formats named in the spec
("Take 1 tablet by mouth twice daily", "500mg every 8 hours") plus sig
abbreviations (b.i.d./t.i.d./q.i.d./q8h), word numbers ("two tablets"),
ranges ("every 4–6 hours"), PRN medicines, bedtime/morning/evening phrasing,
bottle-size vs dose disambiguation, and pharmacy-header noise.

## CI/CD

Two workflows (`.github/workflows/`), plus Dependabot for dependencies:

| Workflow | Trigger | What it does |
| --- | --- | --- |
| **ci.yml** | push to `main`, PRs | Wrapper validation, `assembleDebug`, unit tests, lint, instrumented-test compile. Uploads the debug APK as an artifact for manual QA; test/lint reports on failure. |
| **release.yml** | tag `v*` or manual | Builds a signed release **APK + AAB** and publishes them to a GitHub Release with auto-generated notes. |

### Release signing (optional but recommended)

Add these repository secrets (Settings → Secrets and variables → Actions) and
the release workflow will sign properly; without them you still get a
debug-signed release APK for testing:

| Secret | Value |
| --- | --- |
| `KEYSTORE_BASE64` | `base64 -w0 your-release.keystore` |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | key alias |
| `KEY_PASSWORD` | key password |

Cut a release:

```bash
git tag v1.0.0 && git push origin v1.0.0
```

## How reminders stay reliable

A missed notification is the worst possible failure for this app, so the
design assumes everything will try to stop it:

1. **Exact alarms.** `setExactAndAllowWhileIdle(RTC_WAKEUP, …)` — fires in
   Doze mode, to the second.
2. **Dose materialization.** `DoseGenerationEngine` (pure, unit-tested)
   materializes dose rows for today + 2 days ahead. Rows are idempotent
   (unique index on medication + scheduled time), so replanning never
   duplicates or double-fires.
3. **Re-register after every disruption.** `DeviceEventReceiver` listens for
   `BOOT_COMPLETED`, `TIMEZONE_CHANGED`, `TIME_SET` and
   `MY_PACKAGE_REPLACED` and replans all alarms.
4. **Re-remind chain.** On fire: notify → count attempt → re-arm after
   10 min (configurable) → after the configured budget, mark **Missed**
   and fire the caregiver event hook.
5. **WorkManager safety nets.** A 6-hour `MaintenanceWorker` (sweeps missed
   doses, replans, low-stock warnings) plus a 30-minute `SafetyNetWorker`
   that is armed *only* while the exact-alarm permission is missing.
6. **Permission-degradation path.** No exact-alarm permission → inexact
   Doze-tolerant alarms + the safety net; no notification permission →
   Settings shows a red card with a one-tap fix.
7. **Idempotent planner.** `rescheduleAllAlarms()` is safe to call at any
   time (app start, after every edit, boot, timezone change) — same
   request codes replace their alarms instead of stacking.

## Permissions rationale

| Permission | Why |
| --- | --- |
| `POST_NOTIFICATIONS` | The reminder itself. |
| `SCHEDULE_EXACT_ALARM` | Exact fire time. On Android 12+ the user is guided to grant it; the app degrades to inexact alarms + WorkManager if denied. |
| `USE_FULL_SCREEN_INTENT` | Full-screen reminder when the phone is locked (only used when opened from a reminder — health data is never shown on the lock screen otherwise). |
| `RECEIVE_BOOT_COMPLETED` | Re-register alarms after reboot. |
| `VIBRATE` / `WAKE_LOCK` | Alert pattern; firing from Doze. |
| `CAMERA` | Photograph the medicine label. Optional: manual entry works without it. |
| `INTERNET` / `ACCESS_NETWORK_STATE` | Only the optional RxNorm name check. |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Guided exemption so OEM battery managers cannot defer alarms. |

## Privacy

- Medicine data lives in a **SQLCipher-encrypted** Room database; the
  passphrase is generated once and encrypted with an AES/GCM key that never
  leaves the Android Keystore.
- Photos are captured into app-private storage, processed on-device, and can
  be deleted immediately after saving (default on).
- `allowBackup=false` and empty backup rules — nothing leaves the device
  unless the user explicitly shares the PDF report.
- No analytics, ads or third-party trackers of any kind.
- In-app privacy policy screen (plain language). GDPR/HIPAA principles by
  design: data minimization, on-device processing, explicit opt-in for
  anything remote.

## Accessibility

- Body text ≥18sp (all typography in `sp`); layouts survive system font
  scale up to 200% (no fixed heights on text, generous spacing).
- High-contrast light/dark palettes targeting WCAG AAA body-text contrast.
- All touch targets ≥56dp (buttons ≥64dp); icons always carry text labels.
- Full TalkBack support: content descriptions, merged semantics on dose
  cards, state descriptions on status chips, heading semantics.
- Plain, friendly language throughout; optional TTS read-out of reminders
  and of the day's plan.
- Onboarding includes a guided, skippable permission walkthrough.

## Roadmap — Phase 2 caregiver support

The data layer is already wired for it: every taken/missed dose flows through
the `DoseEventListener` interface (bound to a no-op implementation today).
Adding "notify a family member if a dose is missed" means writing one class
(SMS gateway or push) and replacing the DI binding — see
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md#caregiver-support-phase-2).

## Manual QA

Before every release, walk through [`docs/QA-CHECKLIST.md`](docs/QA-CHECKLIST.md)
(Doze, reboot, timezone change, permission denials, OEM battery managers,
TalkBack, font scaling).
