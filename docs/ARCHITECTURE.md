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
│   parser: MedicationLabelParser (OCR text → structure)        │
│   usecases: AddMedication, MarkDose, Snooze, MarkMissed,      │
│             ObserveToday, Adherence, LowStock, Export         │
│   service interfaces: ReminderScheduler, LabelScanner,        │
│             AdherenceReportGenerator, DoseEventListener       │
└───────────────▲──────────────────────────────┬───────────────┘
                │ implements                   │ uses (Android impls)
┌───────────────┴──────────────────────────────▼───────────────┐
│ data + scheduling + notifications (Android)                   │
│   Room + SQLCipher · ML Kit scanner · RxNorm (optional)       │
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
Notification actions → ReminderActionReceiver
  Taken  → MarkDoseUseCase (TAKEN, decrement pills, cancel alarms)
  Snooze → SnoozeDoseUseCase (status SNOOZED, alarm +minutes)
  Skip   → MarkDoseUseCase (SKIPPED, cancel alarms)
```

Sweep (`MarkMissedDosesUseCase`, cutoff = whole chain + 5 min buffer) runs at
app open, in the maintenance worker and after device events, catching
anything the alarm chain could not deliver (device off, force-stopped).

## Parsing pipeline (scan flow)

```
photo (app-private) → MlKitLabelScanner.recognizeText()   [on-device]
    → MedicationLabelParser.parse(text)
        1. strength   "500 mg", "10 mcg", "100 units"
        2. frequency  as-needed > every-N-hours/qNh > bedtime >
                      N-times-per-day (+ moment refinement) > meals >
                      every-other-day > morning/afternoon/evening
        3. dosage     "1 tablet", "two capsules", "take 5 ml"
        4. instructions  "with food", "on an empty stomach" …
        5. name       first plausible product line: cut at first
                      instruction word, strip strength & form words
        → ParsedLabel with per-field confidence
    → MedicationDraft.fromParsed() → confirmation screen
        (photo + every field editable, low-confidence flagged red,
         optional RxNorm name check, nothing saved before "Save")
```

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
