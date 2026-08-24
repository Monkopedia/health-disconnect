# Screenshot Troubleshooting

## Baselines vs. verify
- **Golden baselines are committed** under `app/src/test/screenshots` (git-tracked). They are the
  source of truth, and they are what CI *records into*: there is no CI step that compares renders
  against them.
- Renders are frozen to a fixed clock (`LocalClock`, set to `FIXED_CLOCK` in the screenshot tests)
  so the chart's time axis and the "Last refreshed" header are deterministic run-to-run.
- **Record** (`./gradlew :app:roborazziGate`) regenerates the committed baselines — do this
  intentionally when a UI change is expected, then review the diff and commit the new PNGs.
- **CI records, it does not gate.** On a same-repo pull request, `.github/workflows/ci.yml` runs
  `recordRoborazziTableDebug` and commits any changed baselines back onto the PR branch, so the
  PNG diff in the PR is the review. Nothing fails a build on a pixel change, and there is no
  comparison threshold or pixel tolerance anywhere in the build.
- **Verify** (`./gradlew :app:verifyRoborazziGate`) compares fresh renders against the committed
  baselines and fails on an unexpected pixel change. It is a **local diagnostic you run by hand** —
  no CI job and no other Gradle task depends on it. It is the only way to make the suite compare
  rather than record, which is what makes it useful when chasing baseline drift.

## Where outputs are written
- Committed golden baselines: `app/src/test/screenshots`
- Verify diffs / actual images (on mismatch): `app/build/outputs/roborazzi`
- Dashboard HTML report: `app/build/reports/roborazzi/debug/index.html`
- If using `recordRoborazziTableDebug`, a side-by-side report is also generated under
  `app/build/reports/roborazzi/debug/index.html`.

## Stale browser cache
- Use hard refresh (`Ctrl/⌘ + Shift + R`) after opening updated screenshots.
- Clear site data for `localhost`-served files if images don’t reflect the latest build.
- If the image path changed (e.g. build variants), clear the browser cache before comparing.

## Corrupt or blank captures
- Confirm the suite ran all requested tasks:
  - `./gradlew :app:roborazziGate`
- Clean stale outputs before re-running:
  - `rm -rf app/build/outputs/roborazzi app/build/reports/roborazzi`
- Re-run in CI-like order with `./gradlew :app:allTests`.

## Common failure patterns
- OOM in long screenshot sets can indicate too much parallel capture; re-run targeted
  screenshot tests first, then full dashboard generation.
- If a screen is missing from dashboard side-by-side mode, check the Roborazzi naming and size bucketing in the base tests.
- Capturing an open Compose `DropdownMenu` popup drives Roborazzi's multiple-windows path and
  spins for ~20 min per bucket under Robolectric, so `settingsThemeDropdownExpandedScreen` is
  excluded from the record/verify gate (see the filter in `app/build.gradle.kts`). Don't re-add
  it to the gate without first fixing the popup-capture perf issue.

