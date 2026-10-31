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
Notification actions → ReminderActionReceiver
  Taken  → MarkDoseUseCase (TAKEN, decrement pills, cancel alarms)
  Snooze → SnoozeDoseUseCase (status SNOOZED, alarm +minutes)
  Skip   → MarkDoseUseCase (SKIPPED, cancel alarms)
```

Sweep (`MarkMissedDosesUseCase`, cutoff = whole chain + 5 min buffer) runs at
app open, in the maintenance worker and after device events, catching
anything the alarm chain could not deliver (device off, force-stopped).

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
