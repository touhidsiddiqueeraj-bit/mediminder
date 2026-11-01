# Architecture

## Layering

The app follows MVVM + Clean Architecture with a strict dependency rule:
**UI → domain ← data**, and Android specifics pushed to the edges.

```
┌──────────────────────────────────────────────────────────────┐
│ ui (Compose)          ViewModels observe domain use cases     │
│   TodayScreen / AddMedication / Scan / History / Settings …   │
└───────────────┬──────────────────────────────────────────────┘
                │ depends on
┌───────────────▼──────────────────────────────────────────────┐
│ domain (pure Kotlin, JVM-unit-testable)                       │
│   models: Medication, Schedule, DoseLog, ParsedLabel          │
│   engine: DoseGenerationEngine, AdherenceCalculator,          │
│           LowStockCalculator                                  │
│   parser: PrescriptionSegmenter, MedicationLabelParser,       │
│           DrugNameMatcher (OCR text → structured candidates)   │
│   usecases: AddMedication, MarkDose, Snooze, MarkMissed,      │
│             ObserveToday, Adherence, LowStock, Export,        │
│             ExtractMedicationsFromPhoto                        │
│   service interfaces: ReminderScheduler, OcrProvider,        │
│             DrugNameIndex, AdherenceReportGenerator,         │
│             DoseEventListener                                 │
└───────────────▲──────────────────────────────┬───────────────┘
                │ implements                   │ uses (Android impls)
┌───────────────┴──────────────────────────────▼───────────────┐
│ data + scheduling + notifications (Android)                   │
│   Room + SQLCipher · ML Kit OCR · offline drug dictionary     │
│   AlarmScheduler · ReminderPlanner · ReminderTrigger          │
│   DeviceEventReceiver · Maintenance/SafetyNet workers         │
│   NotificationHelper · PdfReportGenerator · TTS               │
└──────────────────────────────────────────────────────────────┘
```

## Data model

Exactly the model from the spec, with two pragmatic additions
(`DoseStatus.SCHEDULED` so future doses can exist as rows, and
`reminderAttempts` to drive the re-remind chain):

- **Medication** → `medications` table (name, genericName, dosageValue/Unit,
  form, instructions, photoUri, startDate, endDate, pillsRemaining, isActive)
- **Schedule** → `schedules` table (type DAILY | SPECIFIC_DAYS |
  EVERY_X_HOURS | AS_NEEDED, daysOfWeek, intervalHours, intervalDays, times)
- **DoseLog** → `dose_logs` table (medicationId, scheduledTime, actualTime,
  status SCHEDULED | TAKEN | MISSED | SKIPPED | SNOOZED, reminderAttempts)
  with a **unique index on (medicationId, scheduledTime)** — the invariant
  that makes dose materialization idempotent.

## Reminder pipeline (the critical path)

```
App start / edit / boot / timezone / update / 6h worker
        │
        ▼
ReminderPlanner.rescheduleAllAlarms()          [idempotent]
  1. generate doses for today+2 (DoseGenerationEngine, pure)
  2. upsert with IGNORE (existing rows keep history + attempts)
  3. prune future SCHEDULED rows no longer wanted
  4. alarm per pending dose: setExactAndAllowWhileIdle
     └─ no permission? → setAndAllowWhileIdle + arm 30-min SafetyNetWorker
        │
        ▼  (t₀)
ReminderAlarmReceiver → ReminderTrigger.fire(doseId)
  ├─ dose gone / inactive / handled → cancel + cleanup
  ├─ attempts ≥ max → mark MISSED + DoseEventListener hook
  └─ else → NotificationHelper.showDoseReminder()
             attempts += 1, re-arm at t₀ + interval  ──► repeats
             ReminderAnnouncer.announce() (if TTS on, best-effort)
Notification actions → ReminderActionReceiver
  Taken  → MarkDoseUseCase (TAKEN, decrement pills, cancel alarms)
  Snooze → SnoozeDoseUseCase (status SNOOZED, alarm +minutes)
  Skip   → MarkDoseUseCase (SKIPPED, cancel alarms)
```

Sweep (`MarkMissedDosesUseCase`, cutoff = whole chain + 5 min buffer) runs at
app open, in the maintenance worker and after device events, catching
anything the alarm chain could not deliver (device off, force-stopped).

### Full-screen reminder

`showDoseReminder()` attaches one PendingIntent twice: as the notification's
content intent and as its **full-screen intent**. Both point at
`FullScreenReminderActivity` — a dedicated, lock-screen-capable screen
(`showWhenLocked`, `turnScreenOn`, `FLAG_KEEP_SCREEN_ON`, `excludeFromRecents`,
`singleTop` + empty taskAffinity so re-reminds reload it instead of stacking).
When the screen is on and the user is actively using the device, Android
shows the heads-up notification instead (platform rule: only the default
phone app may take over an in-use screen); tapping it opens the same screen.
That heads-up is therefore a first-class surface: alarm category, max
priority, the chime on the **alarm stream** and the three action buttons.

Channel note: since v1.2.1 the channel is `reminders_v2` (alarm stream;
the pre-1.2.1 `reminders` channel on the notification stream is deleted on
startup — channel settings are frozen at creation, so a new id was the only
way to change streams and to reset channel states that OEM skins or users
had silenced). Settings → Permissions links to the channel's system page so
Sound/Banner can be verified on any device.

Its UI runs on a pure reducer (`ui/reminder/ReminderUiState.kt`, unit-tested):
Loading → Content / AlreadyDone → Acting → Done. The reducer refuses a second
`ActionStarted` while a write is in flight (double-tap guard) and puts the
screen back to Content on failure so nothing is silently lost. The three
buttons call the same `MarkDoseUseCase` / `SnoozeDoseUseCase` the notification
actions use, so both entry points behave identically.

### Spoken reminders

`ReminderAnnouncer` (called at the end of `ReminderTrigger.fire()`) speaks the
localized reminder line **at the fire moment** through `ReminderSpeaker`, so
the voice also plays when the user is in another app and only the heads-up
banner appears. It is strictly best-effort: missing engine/language/hung
engine return within a capped budget and never block the notification chain.
The full-screen screen itself stays silent (it would double-speak with the
trigger on the lock-screen path).

On Android 14+ the full-screen-intent permission is denied by default; the
Settings permission card links to
`ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT`, and without it the app degrades
to heads-up notifications (never silent).

## Localization & the reminder text

Per-app language (7 locales, `values-*/strings.xml`) is applied with
`AppCompatDelegate.setApplicationLocales` and stored both by AppCompat
(`autoStoreLocales`) and in DataStore (`ReminderSettings.languageTag`).

Two Android quirks drive the design:

- On API < 33 the **application context does not follow per-app locales**, so
  everything outside the Activity — notification receivers, workers, the PDF
  report — resolves strings through `Context.withAppLanguage(languageTag)`
  (`util/AppLocales.kt`). `ReminderTrigger` / `ReminderActionReceiver` /
  `MaintenanceWorker` load the tag from DataStore before showing anything,
  which is why reminders arrive in the chosen language even when the app
  process cold-starts from an alarm.
- On API < 33 `Locale.getDefault()` is the *system* locale, so all UI-facing
  date/time formatters go through `appLocale()` instead.

Text-to-speech (`tts/ReminderSpeaker.kt`) is created when the Today screen
appears (not on the first tap — engines bind asynchronously and speaking too
early silently fails), always calls `setLanguage(appLocale())`, retries the
first utterance once, and exposes a `SpeakerState` flow
(READY / LANGUAGE_MISSING / FAILED) that the Today screen surfaces as a hint
with a shortcut to the system voice settings.

The PDF report draws through `StaticLayout` (not `Canvas.drawText`), because
Bangla, Hindi and Arabic require text shaping for conjuncts and ligatures.

## Scan & parsing pipeline (photo → confirmed medicine)

Prescriptions commonly contain **several medicines**; one photo yields one
candidate per line cluster, confirmed by the user one at a time.

```
photo (app-private)
  → OcrImagePreprocessor                     [data/scanner, Android]
      EXIF-upright · down-sample huge photos · grayscale auto-contrast
      · if the first pass looks skewed, one deskew/auto-crop retry
  → OcrProvider.recognize()                 [domain interface]
      impl: MlKitOcrProvider — on-device ML Kit, returns OcrLine(text,
      box, confidence) so medication lines can be told apart from
      doctor names, addresses and dates. Never throws.
  → PrescriptionSegmenter                    [domain/parser, pure]
      clusters OCR lines into one candidate per medication
  → MedicationLabelParser.parse(cluster)     [domain/parser, pure]
      per cluster: strength → frequency → dosage → instructions →
      raw name, each with per-field confidence (ParsedLabel)
  → DrugNameMatcher + DrugNameIndex          [domain/parser + data/scanner]
      fuzzy-corrects the raw name against the offline dictionary
      (prefix + trigram index, Levenshtein + OCR-confusion fixes
      like 0/o, 1/l, 5/s, rn/m)
  → name confidence bands:
        score ≥ 0.85  pre-filled with the dictionary name (HIGH)
        score ≥ 0.60  pre-filled but flagged "please double-check",
                     top candidates as one-tap chips (MEDIUM)
        score < 0.60  raw OCR text kept, suggestions offered (LOW)
  → ExtractedMedication list → ScanViewModel → confirmation screen
      (photo + every field editable, reviewed one medicine at a time,
       "We read X — did you mean Y?" is never silent,
       nothing saved or scheduled before an explicit Save)
```

### Offline drug-name dictionary

- `app/src/main/assets/drug_names.jsonl` (~10,000 generic + brand names,
  ~725 KB) — rebuilt from the openFDA NDC Directory by
  `tools/rebuild_drug_dictionary.py` (see `tools/README.md`).
- Copied into the `drug_names` Room table on first use (`DrugNameSeeder`),
  held in memory by `LocalDrugNameIndex` with a prefix + trigram index.
- Brand → generic links are stored, which also powers the duplicate
  active-ingredient warning (`DuplicateMedicationChecker`).
- Entirely offline: the app has no `INTERNET` permission, so no name check
  can ever leave the phone.

## Caregiver support (phase 2)

`DoseEventListener` is called on every terminal transition
(`onDoseTaken`, `onDoseMissed`) by `ReminderTrigger`, `MarkDoseUseCase` and
`MarkMissedDosesUseCase`. Today it is bound to `NoopDoseEventListener`.

To ship "text my daughter when I miss a dose":
1. Implement `SmsDoseEventListener` (or push) in a new package.
2. Replace the binding in `RepositoryModule` — one line.
3. Add a settings screen section for the caregiver phone number.

No other layer changes; the alarm/DB/history code is untouched.

## Proposed multi-module evolution

The codebase already enforces layer boundaries by package. When the team or
compile times grow, split along the same seams:

```
:app                      # DI wiring, MainActivity, navigation only
:core:model               # domain models
:core:common              # dispatchers, clock, result types
:core:database            # Room + SQLCipher (data/local)
:core:data                # repositories, RxNorm, scanner, reports
:core:domain              # engines, parser, use cases, service interfaces
:core:notifications       # channels + NotificationHelper
:core:scheduling          # alarms, receivers, workers
:feature:onboarding / today / add-medication / scan / history / settings / detail
```

Rules: `feature:*` may only depend on `core:domain` (+ `core:designsystem`);
nothing depends on `:app`. Hilt aggregates in `:app`.
