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
- ☐ Photo of a prescription listing **several medicines** → "Medicine X of Y on this label" stepper; "Save and continue" advances to the next one; "Skip this medicine" leaves it out; nothing from a later medicine is saved with an earlier one.
- ☐ OCR typo (e.g. "Metf0rmin") → "We read … Did you mean: Metformin?" card; score band 0.60–0.85 highlights the field and shows one-tap candidate chips; a wrong pick can still be corrected by editing the field.
- ☐ Very weak match (< 0.60) → field left on the raw OCR text with suggestions offered, "Use exactly what we read" chip available.
- ☐ Low-confidence fields are red with "Please double-check this".
- ☐ Every field is editable on the confirmation screen.
- ☐ "Check the name" button works fully offline (offline dictionary; the app has no INTERNET permission at all).
- ☐ Blurry/unreadable photo → "We could not read that label" + retry + manual fallback **with the photo attached**.
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
- ☐ Airplane mode for a full session: every feature works — the app has no
      internet permission, so nothing should behave differently at all.
- ☐ "Delete all my data" (Settings) requires confirm and empties Today/History.
- ☐ `adb backup`/cloud backup contains no app data (`allowBackup=false`).
- ☐ App-private photo directory contains no leftover label photos when deletion is enabled.

## 13. Languages
- ☐ Settings → Language → বাংলা: the whole app (nav, Today, add wizard, scan
      flow, settings, dialogs) switches instantly, no restart needed. ✅ no
      English left anywhere (except brand "MediMinder" and "PDF").
- ☐ Same for हिन्दी, Español, Français, العربية, Português, and back to
      "My phone's language".
- ☐ Arabic: the layout mirrors right-to-left (icons, back arrow, chips),
      text is right-aligned, nothing overlaps.
- ☐ Kill the app process, let a reminder fire: the **notification** is in the
      chosen language on Android 8–12 too (adb: `adb shell am kill com.mediminder.app`).
- ☐ After switching language, dates ("Friday, 31 October"), times and the
      History calendar show in the app language.
- ☐ PDF report exports with correctly shaped text in Bangla/Hindi/Arabic
      (conjuncts/ligatures render, no boxes), and the table columns align.
- ☐ `python3 tools/check_translations.py` → "All translations consistent."
- ☐ Known limitation: notification channel names in Android Settings keep
      the language of the very first launch (Android ignores channel renames).

## 14. Read aloud (text-to-speech)
- ☐ Today screen → "Read today's plan aloud": engine is pre-warmed, speech
      starts immediately and lists upcoming doses with times. ✅ speaks on the
      first tap, not the second.
- ☐ All doses already taken → the button says "no more medicines to take
      today" instead of staying silent.
- ☐ With the app in Bangla (and a bn voice installed): speech is Bangla.
- ☐ With the app in Bangla but **no** bn voice installed: a short red hint
      appears under the button with "Open voice settings" (no crash, no
      silence). ✅ the hint disappears after installing the voice and returning.
- ☐ Settings → "Read reminders aloud" ON → open a reminder from the
      notification: the medicine name is spoken.
- ☐ Airplane mode: TTS works fully offline (system voices are on-device).
- ☐ Settings → Voice → "Test voice": speaks a sample sentence in the app
      language and the status line under it says "Voice is working."
- ☐ On a phone without any TTS engine: the status line says no speech engine
      was found and offers "Install a voice engine" (Play Store) plus
      "Open voice settings".

## 15. Full-screen reminder
- ☐ Screen **off/locked** when a reminder fires: the full-screen reminder
      lights up over the lock screen — medicine name, dose, scheduled time,
      and giant Taken / Snooze / Skip buttons (no unlock needed).
- ☐ Screen on and unlocked: a heads-up notification appears; **tapping it**
      opens the same full-screen reminder.
- ☐ **While using another app** (screen on, unlocked, MediMinder in the
      background): Android never lets us take over the screen — the reminder
      arrives as a **loud heads-up banner**: chime (alarm volume), vibration,
      medicine name + dosage, and Taken / Snooze / Skip buttons directly on
      the banner. With "Read reminders aloud" on, the phone also **speaks
      the line** at the fire moment.
- ☐ Update from ≤ 1.2.0: the first reminder after the update still rings
      (the channel moved to "reminders_v2" on the alarm stream; the old
      "reminders" channel is gone from Android's notification settings).
- ☐ Settings → Permissions → "Reminder sound & banner" opens the system
      page of the reminders channel, where Sound and Banner can be checked
      (needed on OEM skins that turn "Banner" off per app).
- ☐ "Taken" on the full-screen screen: screen closes, dose shows as taken
      in Today, the quiet confirmation notification appears, pill count
      decrements (same result as the notification button).
- ☐ "Snooze N min": screen closes, the dose re-fires after N minutes.
- ☐ Double-tapping "Taken" quickly records the dose only once (the state
      machine locks the buttons while the write is in flight).
- ☐ Re-remind while the screen is still open from the first alert: the
      screen reloads with the same dose (no stacked screens).
- ☐ Taking the dose from the notification while the full-screen reminder is
      open: the screen switches to "This dose was already recorded" with a
      Close button.
- ☐ Android 14+: Settings → Permissions card "Show reminders full screen"
      opens the dedicated full-screen-intent grant screen; with it denied,
      reminders still arrive as heads-up notifications.
- ☐ With "Read reminders aloud" ON, a firing reminder speaks the medicine
      name once — including while another app is in the foreground and no
      full-screen screen ever opens (not once per re-remind).

## 16. Regression quick-pass
- ☐ `./gradlew :app:testDebugUnitTest :app:lintDebug` green.
- ☐ Settings → About shows the installed version (e.g. `MediMinder 1.1.1`) and it matches Android Settings → Apps → MediMinder (the line is read from PackageManager, never hardcoded).
- ☐ Rotate/force-stop/resume during the add flow does not lose entered data (Process Death not covered by VM state persistence is a known gap — verify acceptable).
