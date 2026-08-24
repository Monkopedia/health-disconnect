# Architecture Overview

## Layers

- **UI (Compose)**
  - `ui/*` screens are responsible for layout, interaction handlers, and rendering states.
  - `DataViewView` drives the chart/entries/list experience for a selected view.
- **View Models**
  - `DataViewAdapterViewModel` coordinates active views, metrics, and chart settings.
  - `AppThemeViewModel` handles theme persistence and restoration.
  - `PermissionsViewModel` owns Health Connect availability and permission query cadence.
- **Domain/Data**
  - `HealthDataModel` maps Health Connect records into normalized series and metrics.
  - Mapping and aggregation helpers provide unit-tested transforms before rendering.
- **Persistence**
  - `room/` stores view metadata and record selection/settings.
  - `DataStore` stores compact app preferences (theme mode and support flags).

## Data Flow

1. User changes UI controls (metric selection, chart options, refresh interactions).
2. View model updates local state and persists view config through `DataViewAdapter`.
3. `HealthDataModel` resolves selected metrics, queries `Room`/`DataStore` as needed, and emits
   reactive series streams.
4. Compose UI collects these streams and redraws charts, entries, and headers.

## Testing Boundaries

- Unit tests target:
  - ViewModel behavior (`DataViewAdapterViewModel`, `AppThemeViewModel`, `PermissionsViewModel`)
  - Domain math/extraction (`HealthDataModel*Test`)
  - Compose interaction and screenshot rendering (`*Test`)
- Integration points:
  - Data migrations and flow hydration are expected to be covered by dedicated integration suites.

## Current Tradeoffs / Tech Debt

- Mapping/normalization logic is centralized in `HealthDataModel`, but cleanup there is still
  outstanding. It is not tracked in `TODO.md` — that file has no architecture section and every
  item in it is checked off — so file a GitHub issue for anything picked up here.
- `DataViewView` is large and due for decomposition by screen section.
- Room/DataStore cache and health record mapping lifecycles should be constrained by explicit
  invalidation policies as data windows change.

## Screenshot Notes

- Screenshots are generated with Roborazzi into:
  - `app/src/test/screenshots` — the committed golden baselines, written directly by the
    `captureRoboImage("src/test/screenshots/...")` call in `ScreenRoborazziTest`. This is the
    source of truth; anything under `app/build/outputs/roborazzi` is scratch output.
  - side-by-side dashboard in `app/build/reports/roborazzi/debug/index.html`
- If outputs appear stale in a browser, hard-refresh and clear cache.
- If a screenshot appears empty, rerun `./gradlew :app:roborazziGate` to regenerate.
- CI records baselines and commits them back to the PR (`.github/workflows/ci.yml`); the PNG diff
  in the PR is the review. There is no CI step that verifies renders against the baselines.
  `./gradlew :app:verifyRoborazziGate` does compare, but it is a local diagnostic only.

