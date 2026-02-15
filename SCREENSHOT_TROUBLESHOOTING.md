# Screenshot Troubleshooting

## Where outputs are written
- Screenshot image files: `app/build/outputs/roborazzi/screens`
- Dashboard HTML report: `app/build/reports/roborazzi/debug/index.html`
- If using `recordRoborazziTableDebug`, a side-by-side report is also generated under
  `app/build/reports/roborazzi/debug/index.html`.

## Stale browser cache
- Use hard refresh (`Ctrl/⌘ + Shift + R`) after opening updated screenshots.
- Clear site data for `localhost`-served files if images don’t reflect the latest build.
- If the image path changed (e.g. build variants), clear the browser cache before comparing.

## Corrupt or blank captures
- Confirm the suite ran all requested tasks:
  - `./gradlew :app:recordRoborazziDebug`
- Clean stale outputs before re-running:
  - `rm -rf app/build/outputs/roborazzi app/build/reports/roborazzi`
- Re-run in CI-like order with `./gradlew :app:allTests`.

## Common failure patterns
- OOM in long screenshot sets can indicate too much parallel capture; re-run targeted
  screenshot tests first, then full dashboard generation.
- If a screen is missing from dashboard side-by-side mode, check the Roborazzi naming and size bucketing in the base tests.

