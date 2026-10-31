# Screenshots

The images in this folder are used by the top-level [`README.md`](../README.md).

## What they are

High-fidelity **design mockups** rendered from the app's real Compose UI:
the exact Material 3 color scheme, typography scale (body ≥ 18sp, buttons
64dp, radius-16 cards), wording from `strings.xml`, and the layouts of
`TodayScreen`, `ScanScreen`, `AddMedicationScreen`, `HistoryScreen` and
`SettingsScreen`. They are not emulator captures — but every color, string
and component on them mirrors the shipped code, pixel-for-pixel at 2×.

| File | Screen |
| --- | --- |
| `banner.png` | README hero banner (logo + mini Today phone) |
| `today.png` | Today screen — dose cards, low-stock banner, add buttons |
| `scan-camera.png` | Camera step — framing + capture button |
| `scan-review.png` | Confirmation step — photo, "We read / Did you mean", red low-confidence fields, candidate chip, multi-medicine progress |
| `add-medication.png` | Manual wizard, step 2 — frequency cards + reminder times |
| `history.png` | History — weekly bars, month calendar, PDF export |
| `settings.png` | Settings — steppers + reliability permission cards |
| `notification.png` | Lock-screen reminder notification with Taken / Snooze / Skip |
| `logo.png` | App icon (matches `ic_launcher_foreground`) |

## Replacing them with real captures

Real device screenshots are always preferable once you can take them.
On a connected device or emulator:

```bash
adb exec-out screencap -p > docs/screenshots/today.png
```

Keep the same file names and the README needs no changes. Recommended
device profile: a ~412dp-wide phone, light theme, system font at default
scale, English locale, time set to 9:41 for consistency with the mockups.

## Regenerating the mockups

The mockups are rendered from an HTML file with the theme tokens copied from
`ui/theme/Theme.kt` (Playwright + headless Chromium, 2× device scale). If a
screen changes materially, re-render so the README stays truthful:

1. Update the screen in question in [`tools/screenshots/screens.html`](../tools/screenshots/screens.html)
   (colors live in the `:root { --… }` block; strings come from
   `res/values/strings.xml`).
2. Render at 2× (fetches Roboto once, then writes `docs/screenshots/*.png`
   and optimizes them):

   ```bash
   pip install playwright && playwright install chromium   # once
   python3 tools/screenshots/render_screens.py
   ```

3. Verify every phone panel still fits (the script prints any overflow)
   and that no text is clipped before committing.
