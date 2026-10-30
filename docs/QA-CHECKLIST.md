# Manual QA checklist

Run through this checklist on a **real device** before every release.
Device matrix suggestion: one stock Android (Pixel), one Samsung/Xiaomi
(aggressive battery manager), one Android 8.0 (minSdk) and one current API.

Legend: ☐ unchecked · ✅ pass criteria

---

## 1. Setup & onboarding
- ☐ First launch shows onboarding; **Skip** jumps straight to Today.
- ☐ Page 3 shows live permission status that updates after returning from settings.
- ☐ Completing onboarding never shows it again (kill app, relaunch).

## 2. Add medicine manually
- ☐ 3 steps, back button works between steps, nothing saved until Save.
- ☐ Empty name → inline error, cannot continue.
- ☐ Specific days with zero days selected → warning shown.
- ☐ Every-few-hours stepper updates the "first dose at" preview.
- ☐ As-needed medicine saves and shows on Today with no reminder times.
- ☐ Editing an existing medicine keeps its history (History tab unchanged).

## 3. Add medicine by photo
- ☐ Camera permission denied → explanation + "Type it in myself" fallback works.
- ☐ Photo of a printed label (e.g. "Metformin 500 mg / Take 1 tablet by mouth twice daily") → confirmation screen with photo, name, strength, dosage, times prefilled.
- ☐ Low-confidence fields are red with "Please double-check this".
- ☐ Every field is editable on the confirmation screen.
- ☐ Blurry/unreadable photo → "We could not read that label" + retry + manual fallback **with the photo attached**.
- ☐ Airplane mode: scan flow fully works; "Check the name online" returns no suggestions but never crashes.
- ☐ With network: "Check the name online" suggests standardized names (RxNorm).
- ☐ "Delete photo after saving" ON → photo file is gone after save (check app files).
- ☐ Adding a medicine whose name matches an existing one (e.g. "Metformin" vs "Metformin ER") → duplicate-ingredient warning; "Save anyway" works.

## 4. Reminders — the critical path
- ☐ Set a reminder 2 minutes ahead → notification fires within ±5 s with medicine name, dosage and notes in large text.
- ☐ **Taken** action: notification is replaced by a quiet confirmation, dose shows Taken on Today, pill count decreases by 1.
- ☐ **Snooze 10 min** action: notification clears, re-fires 10 minutes later.
- ☐ **Skip** action: dose shows Skipped.
- ☐ No response: re-reminds at the configured interval (default 10 min), exactly max-times (default 3), then the dose turns **Missed** on Today.
- ☐ Changing snooze/re-remind settings in Settings applies to the next reminder.

## 5. Doze mode
```
adb shell dumpsys deviceidle force-idle
```
- ☐ With the device in forced Doze, a due reminder still fires (±1 min at worst).
```
adb shell dumpsys deviceidle unforce
```

## 6. Reboot & app update
- ☐ With reminders scheduled, reboot the device → reminders still fire afterwards (alarms re-registered by `DeviceEventReceiver`).
- ☐ Install an updated build over the existing app (`adb install -r`) → reminders still fire (`MY_PACKAGE_REPLACED` path).

## 7. Timezone & clock changes
- ☐ Change timezone (Settings → System → Date & time) → reminders fire at the correct **local** times; Today groups are correct.
- ☐ Manually shift the clock forward past a dose → the dose is not double-counted; overdue sweep marks it Missed at the right time.

## 8. Permission-degradation paths
- ☐ Deny notification permission → app works; Settings shows a red "Notifications" card; granting via the card fixes reminders.
- ☐ Revoke **Alarms & reminders** (Android 12+ app info) → Settings shows red "Exact alarms" card; reminders still arrive (a few minutes late) via the inexact fallback + safety-net worker; re-granting restores exactness.
- ☐ Full-screen intent permission off (Android 14+) → reminder appears as a heads-up banner instead of full-screen; no crash.
- ☐ Battery optimization ON → Settings shows red card; "Allow" opens the exemption dialog; after exemption, reminders fire reliably overnight.

## 9. Today & history
- ☐ Doses grouped Morning/Afternoon/Evening/Night in chronological order.
- ☐ Status flips Upcoming → Missed by itself after the grace period; "Read aloud" speaks today's plan (TTS enabled).
- ☐ History calendar colors days (green = all taken, red = missed); tapping a day lists its doses.
- ☐ Weekly percentage matches manual count of the last 7 days.
- ☐ Export report → PDF opens the share sheet; the PDF contains the period, summary, per-medicine table and the disclaimer.

## 10. Low stock
- ☐ Medicine with tracking on and few pills left → banner on Today and a notification from the maintenance worker ("Only 3 days of Metformin left").
- ☐ Refilling via the stepper clears the banner.

## 11. Accessibility
- ☐ TalkBack: every control announces a meaningful label; dose cards read medicine, time and status; decorative icons are silent.
- ☐ Font size 200% (Settings → Accessibility → Display size and text): no clipped or overlapping text; all buttons still tappable.
- ☐ Dark and light mode both readable; status chips remain high-contrast.
- ☐ Switch access or large-text mode does not break the 3-step add flow.

## 12. Data & privacy
- ☐ Airplane mode for a full session: every feature except the RxNorm check works.
- ☐ "Delete all my data" (Settings) requires confirm and empties Today/History.
- ☐ `adb backup`/cloud backup contains no app data (`allowBackup=false`).
- ☐ App-private photo directory contains no leftover label photos when deletion is enabled.

## 13. Regression quick-pass
- ☐ `./gradlew :app:testDebugUnitTest :app:lintDebug` green.
- ☐ Rotate/force-stop/resume during the add flow does not lose entered data (Process Death not covered by VM state persistence is a known gap — verify acceptable).
