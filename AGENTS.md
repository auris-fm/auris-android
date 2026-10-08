# AGENTS.md

This file provides guidance to AI coding assistants (Claude Code, Cursor, Windsurf, etc.) when working with code in this repository.

## Common Commands

### Building
```bash
# Build debug APK (uses staging servers with .debug suffix)
./gradlew :app:assembleDebug

# Build debugProd APK (development build pointing to production servers)
./gradlew :app:assembleDebugProd

# Build prototype (minified pre-release build)
./gradlew :app:assemblePrototype

# Build release APK
./gradlew :app:assembleRelease

# Install the debug build (Pocket Casts staging servers; see the gateway note below
# for what the cloud/voice path points at — that is a stored preference, not the build)
./gradlew :app:installDebug

# Install the debugProd build (points at production servers)
./gradlew :app:installDebugProd
```

### Testing
```bash
# Run unit tests for the app module
./gradlew :app:testDebugUnitTest

# Run unit tests for a specific feature module
./gradlew :modules:features:search:testDebugUnitTest

# Run instrumentation tests on connected device
./gradlew :app:connectedDebugAndroidTest

# Run instrumentation tests for a specific module
./gradlew :modules:features:search:connectedDebugAndroidTest
```

### Code Quality
```bash
# Check code formatting (must pass before merge)
./gradlew spotlessCheck

# Auto-format code
./gradlew spotlessApply

# Install git hooks to run spotless on pre-commit
./gradlew installGitHooks

# Run lint on all application modules
./gradlew aggregatedLintRelease

# Run dependency analysis
./gradlew buildHealth
```

### Build Variants
- **debug**: Development with `.debug` suffix, uses staging servers (*.pocketcasts.net)
- **debugProd**: Development build pointing to production servers (*.pocketcasts.com)
- **prototype**: Minified pre-release build with ProGuard/R8
- **release**: Production release build with minification and shrinking

## Architecture Overview

### Multi-Module Structure

**Application Modules** (4):
- `app/` - Main mobile Android application
- `automotive/` - Android Automotive OS variant
- `tv/` - Android TV variant
- `wear/` - Wear OS variant

**Feature Modules** (`modules/features/`):
Self-contained features with UI, ViewModels, and feature-specific logic. Features depend on services but never on other features.

**Service Modules** (`modules/services/`):
Shared infrastructure and business logic. Core services include:
- `model` - Database entities and Room DAOs (122 database migrations!)
- `repositories` - Data layer abstracting servers and model (Repository pattern)
- `servers` - Network API clients (Retrofit + OkHttp)
- `compose` - Shared Compose components
- `ui` - Shared UI theming and components
- `analytics` - Analytics tracking
- `localization` - Strings and translations
- `voice` - Voice control: ASR/backends, the gate, earcons and TTS, and the client-side cloud sink (`CloudRouteSink`) that consumes the cloud contract. The contract types themselves (`CloudTurnRoute`, `CloudTurnTransport` and its socket implementation, `CloudRouteErrorCodes`, `CloudRouteEvent`, `CloudRouteModels`, the prefetch clients) live in the `cloud` package of `repositories`; see "Where the contract lives" below.

**Dependency Flow**:
```
Applications (app, automotive, wear)
    ↓
Features (account, search, player, etc.)
    ↓
Services (repositories, ui, compose, analytics, etc.)
    ↓
Core Services (model, servers)
```

### Architecture Pattern: MVVM

- **ViewModels**: Annotated with `@HiltViewModel`, use `StateFlow` for state management
- **UI State Pattern**: Sealed interfaces/classes for representing UI states (e.g., `Idle`, `Loading`, `Success`, `Error`)
- **Unidirectional Data Flow**: ViewModels expose immutable state via `StateFlow`, accept actions via functions
- **Dependency Injection**: Hilt (Dagger 2) throughout the codebase
- **Reactive Programming**: Dual strategy with RxJava2 (legacy) and Coroutines/Flow (modern)

### Technology Stack

**UI**:
- **Jetpack Compose** (primary for new features) with Material (Material2)
  > Note: The codebase currently uses Material (Material2) for Compose components. Migration to Material3 is in progress. **For all new Compose UI, use Material3 components unless you are working in a module that has not yet migrated.** If unsure, check the module's dependencies or consult the team. Existing code may still use Material2 until migration is complete.
- **XML Views** (legacy)
- View Binding enabled for XML layouts

**Dependency Injection**: Hilt

**Database**: Room with extensive migration history

**Networking**: Retrofit + OkHttp + Moshi

**Media**: Media3 (ExoPlayer) + Cast Framework for Chromecast

**Image Loading**: Coil

**Async**: Coroutines + WorkManager

**Testing**:
- JUnit, Mockito, Turbine (for Flow testing)
- Compose UI Test, UIAutomator, MockWebServer

## Development Guidelines

### Git Workflow

**Develop feature work on a branch off `main`. The only reason to branch off a release branch is to cherry-pick an already-merged commit into that release.**

Branching feature *development* off a release branch causes confusion later: when
the release branch is merged back into `main`, it is unclear which commits belong
to the release and which belong to the unrelated feature work. To avoid this:

- **Create every feature branch from `main`**, even if the
  change is also needed in an in-flight release. Never develop a feature on a
  branch based on `release/*`.
- If a change must also land in a release branch (e.g. a fix needed for an
  upcoming release):
  1. Develop and merge it on a branch off `main` first.
  2. Create a new branch off the release branch, cherry-pick the relevant commit(s)
     from the origin branch onto it, then open a PR into the release branch.
- Keep cherry-picks limited to the specific commits the release needs, so the release branch never accumulates
  feature work that does not belong in it.
- Cherry-picking may surface merge conflicts. Resolve them carefully, then verify the result before opening the PR:
  - The apps build on all platforms (`app`, `automotive`, `wear`).
  - All quality gates pass (`./gradlew spotlessCheck`, lint).
  - Unit and integration tests succeed.

### Technology Preferences

**Use Jetpack Compose for new UI**:
- All new screens/views must be written in Compose
- Migrating existing XML views to Compose is encouraged when updating them
- Use Material3 components from `modules/services/compose` and `modules/services/ui`

**Prefer Coroutines over RxJava**:
- New code should use Coroutines and Flow
- Convert RxJava code to Coroutines when you have a good opportunity
- Use `StateFlow` for state management in ViewModels

**Write in Kotlin**:
- All new features must be written in Kotlin
- Convert Java code to Kotlin at the first opportunity

### Code Style

- Line length: **120 characters**
- Must pass `spotlessCheck` before merge (auto-format with `spotlessApply`)
- Use **TODO** for temporary notes, never use **FIXME** (not allowed in repository)
- ktlint enforces Kotlin style with custom rules for Compose
- All warnings treated as errors in Kotlin compilation

### String Resources

- Add English strings only to `modules/services/localization`
- Translations are managed through GlotPress and pulled automatically
- Mark non-translatable strings with `translatable="false"`

### Image Resources

- Add images to `modules/services/images` only

**Prefer vector graphics (.svg) over rasterized formats when possible**:
- Scalable to any screen density without quality loss
- Smaller file size for simple graphics
- Use for icons, logos, and simple illustrations

**Prefer WebP format over PNG**:
- New images should be `.webp` format for better compression and smaller APK size
- PNG acceptable only for:
  - App icons and launcher icons (when required by Android)
  - Images requiring transparency that are very small
  - Third-party assets that cannot be modified

### Testing

- Write unit tests for ViewModels, managers, and business logic
- Use `MainCoroutineRule` for testing coroutines
- Use Turbine for testing Flows
- Shared test utilities available in `modules/services/sharedtest`
- Test files in `src/test/` for unit tests, `src/androidTest/` for instrumentation tests

### Feature Flags

The codebase uses a `FeatureFlag` system for A/B testing and gradual rollout. Check for feature flags before implementing changes to flagged features.

### Module Dependencies

- Features can depend on services, never on other features
- Service modules can depend on other services (e.g., `compose` → `ui`, `repositories` → `model`)
- Most feature modules depend on: `model`, `repositories`, `ui`, `compose`, `analytics`, `localization`
- `repositories` is the central data access layer
- Dependency analysis plugin enforces these rules (`./gradlew buildHealth`)

## Project-Specific Notes

### Database Migrations

The Room database has an extensive migration history — `modules/services/model/schemas/` holds one JSON per version, one level down in `au.com.shiftyjelly.pocketcasts.models.db.AppDatabase/` (91 at the time of writing; treat `find modules/services/model/schemas -name '*.json' | wc -l` as the count, not this number). When modifying entities:
- Always provide a migration path
- Export schema is enabled (`modules/services/model/schemas/`)
- Test migrations thoroughly

### Build Variants and Server URLs

- `debug` uses staging servers (*.pocketcasts.net)
- `debugProd`, `prototype`, and `release` use production servers (*.pocketcasts.com)
- Server URLs are configured via `buildConfigField` in build.gradle.kts

### Analytics

Use `AnalyticsTracker` service for event tracking. Analytics are integrated with Automattic Tracks.

### Log Monitoring

When monitoring logs on-device via `adb logcat`, **always filter by PID** so only the target app's logs are captured. Both the debug (`.debug` suffix) and production packages may be installed simultaneously. Use a self-healing loop that re-resolves the PID when the app restarts:

```bash
while true; do
  PID=$(adb shell "ps -A 2>/dev/null | grep pocketcasts.debug" | awk '{print $2}')
  if [ -n "$PID" ]; then
    adb logcat -v time --pid=$PID 2>/dev/null
  fi
  sleep 0.5
done | grep --line-buffered -iE "<keywords>"
```

**Do not hardcode keywords here.** To build the `<keywords>` pattern, scan both Kotlin and native sources for log output:

- **Kotlin:** Grep `Timber\.[idwe]\(` in the relevant module's `src/main/`. Extract distinctive message prefixes (text before `%` or `$`). Use full prefixes with trailing punctuation to avoid false matches — e.g. `Intent:` not `Intent`, `Executing:` not `Execut`.
- **JNI/NDK (C++):** Grep `#define LOG_TAG` in `src/main/cpp/` for native log tags. Also include upstream library tags like `whisper.cpp`. Common tags: `OboeCapture`, `NativeVAD`, `WhisperJni`, `EmbeddingJni`, `NativeWakeWord`, `NativeVadProc`, `whisper.cpp`.

Combine both into a grep alternation. **Exclude noisy tags:** use logcat's `OkHttp:S` filter spec to suppress OkHttp at the source, avoiding data payloads that may accidentally match keywords. The full command:

```bash
adb logcat -v time --pid=$PID OkHttp:S
```

### Cloud/voice gateway environment

The Auris gateway base URL is a **stored preference**, not the build. It resolves through
`GatewayUrlProvider` (`modules/services/preferences/src/main/java/.../preferences/gateway/`),
whose implementation reads preferences; the compile-time `AURIS_GATEWAY_URL` `buildConfigField`
only supplies the default. **A replace-install keeps the stored value**, so a device can keep
talking to a previous backend while the build looks correct.

To find out which backend a phone is actually using, read the device's preference rather than
the source. The debug build installs as `fm.auris.debug`; the release build drops the `.debug`
suffix (`applicationIdSuffix` in the root build script):

```bash
adb shell run-as <applicationId> cat /data/data/<applicationId>/shared_prefs/auris_cloud.xml
```

- `base_url` — the gateway in use (staging is `https://api-staging.auris.fm`).
- `direct_upstream` — the local kill switch (`GatewayUrlProvider.isDirectUpstreamForced()`;
  the cloud path is active when cutover is on, i.e. this is `false`).

Staging's gateway serves the app's ordinary API paths as well as the cloud route. The
`auris-edge-turn` Cloudflare Worker serves **only** the cloud routes and 404s everything else,
so pointing an app's base URL at the Worker breaks sign-in before a turn can run.

## Where the contract lives

The authority for the cloud assistant and voice-control contract is **core's specs**, not this
repo — `docs/specs/cloud-assistant.md` and the edge/particle plan in the `core` checkout. This
file deliberately does not restate them: a copy here would drift exactly the way a stale count
does.

Routes, event types, error codes, capabilities and the spoken-fallback rules are defined there
and implemented here. When the contract changes, the spec changes first and this repo follows
it. When code and a spec disagree, the spec wins, and the disagreement is a bug to report rather
than to reinterpret. Comments cite the spec sections they implement (grep for
`cloud-assistant.md`) — keep those citations true.

## Verification and cleanup discipline

**A check must be able to fail on the thing it is checking.** Never let a failure look like a pass: don't redirect a command's errors into the signal you read, and treat an empty or silent result as *unknown* rather than clean until the check has been seen failing on a case that should fail. This covers the surface itself — a search that cannot see the answer (a filesystem scan for a file that exists only inside git objects, a log with no per-request lines) is not a check, so prove the instrument can observe the event *before* the event.

**Deleting a branch: decide by what merged, not by dates.** List its PR records — `gh pr list --head <branch> --state all --json number,state,mergedAt,headRefOid` (works after the branch is deleted, and can return several, since a head branch gets reused). Safe means both: a record is `MERGED` with a `mergedAt`, and the branch tip *is* that record's `headRefOid`, so nothing landed after the merge. A SHA alone proves nothing — a closed-unmerged PR prints one too, and its tip matches it — and if the tip moved past the merged head, read the added commits before deciding. The content checks (an ancestor of `main`, or `git cherry` reading zero) confirm but never deny: a later edit in `main` looks like a missing line, and a squash-merged branch is neither an ancestor nor patch-identical. Dates decide nothing — a rebase preserves author dates.

**Removing a worktree: never `--force` past a refusal.** Check `git status --short` in that checkout at the moment of removal. A provably clean checkout does not need the flag; a refusal is the guard telling you to look.

**Attribution: commit authorship is uniform here** — every lane commits as the owner — so `--author` identifies nothing. Use dates and subjects.

Applies to all Auris repos; canonical text lives in core's `CLAUDE.md`.

## PR review discipline

Before any PR merges, **every review comment on it must be addressed** — either fixed in code or rejected with a written reason. Deferral to follow-up tickets is only for comments irrelevant to the PR's changes; anything relevant is handled or rejected-with-reason, never parked. No comment is skipped silently. Addressing comments is **continuous while the PR is open**: keep checking for new comments and handle each as it lands — not a single round. Applies to all Auris repos; canonical text lives in core's CLAUDE.md. The final merge decision is always the owner's (@merlinran).
