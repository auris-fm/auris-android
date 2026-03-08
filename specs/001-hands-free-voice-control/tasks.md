# Tasks: Hands-Free Voice Control

**Input**: Design documents from `/specs/001-hands-free-voice-control/`
**Prerequisites**: plan.md (required), spec.md (required), research.md, data-model.md, contracts/, quickstart.md

**Tests**: Include tests for changed behavior. ViewModel/manager/business-logic changes require unit tests.

**Organization**: Tasks are grouped by user story to keep each increment independently testable where possible.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no incomplete dependencies)
- **[Story]**: User story label (`[US1]`, `[US2]`, `[US3]`) for story-phase tasks only
- Every task includes an exact file path

## Path Conventions

- Application module: `app/`
- Service modules: `modules/services/*`
- Unit tests: `<module>/src/test/`
- Instrumentation tests: `app/src/androidTest/`

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Establish module wiring and test scaffolding for hands-free voice control.

- [ ] T001 Update dependency/test configuration in `/Users/dev/code/merlinran/pocket-casts-android/app/build.gradle.kts` and `/Users/dev/code/merlinran/pocket-casts-android/modules/services/repositories/build.gradle.kts`
- [X] T002 Create voice-control package structure under `/Users/dev/code/merlinran/pocket-casts-android/modules/services/repositories/src/main/java/au/com/shiftyjelly/pocketcasts/repositories/playback/voicecontrol/`
- [X] T003 [P] Create unit-test package structure under `/Users/dev/code/merlinran/pocket-casts-android/modules/services/repositories/src/test/java/au/com/shiftyjelly/pocketcasts/repositories/playback/voicecontrol/`
- [X] T004 [P] Create instrumentation-test package structure under `/Users/dev/code/merlinran/pocket-casts-android/app/src/androidTest/java/au/com/shiftyjelly/pocketcasts/voicecontrol/`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Implement shared foundations that all stories depend on.

- [X] T005 Implement core data-model classes in `/Users/dev/code/merlinran/pocket-casts-android/modules/services/repositories/src/main/java/au/com/shiftyjelly/pocketcasts/repositories/playback/voicecontrol/VoiceCommandSession.kt`, `/Users/dev/code/merlinran/pocket-casts-android/modules/services/repositories/src/main/java/au/com/shiftyjelly/pocketcasts/repositories/playback/voicecontrol/CommandIntent.kt`, `/Users/dev/code/merlinran/pocket-casts-android/modules/services/repositories/src/main/java/au/com/shiftyjelly/pocketcasts/repositories/playback/voicecontrol/ArbitrationDecision.kt`, `/Users/dev/code/merlinran/pocket-casts-android/modules/services/repositories/src/main/java/au/com/shiftyjelly/pocketcasts/repositories/playback/voicecontrol/IntentResolutionOutcome.kt`, and `/Users/dev/code/merlinran/pocket-casts-android/modules/services/repositories/src/main/java/au/com/shiftyjelly/pocketcasts/repositories/playback/voicecontrol/AnonymizedVoiceSample.kt`
- [X] T006 [P] Define recognizer/arbitration interfaces in `/Users/dev/code/merlinran/pocket-casts-android/modules/services/repositories/src/main/java/au/com/shiftyjelly/pocketcasts/repositories/playback/voicecontrol/LocalRecognizer.kt`, `/Users/dev/code/merlinran/pocket-casts-android/modules/services/repositories/src/main/java/au/com/shiftyjelly/pocketcasts/repositories/playback/voicecontrol/CloudRecognizer.kt`, and `/Users/dev/code/merlinran/pocket-casts-android/modules/services/repositories/src/main/java/au/com/shiftyjelly/pocketcasts/repositories/playback/voicecontrol/VoiceArbitrationEngine.kt`
- [X] T007 Implement listening-mode resolution in `/Users/dev/code/merlinran/pocket-casts-android/modules/services/repositories/src/main/java/au/com/shiftyjelly/pocketcasts/repositories/playback/voicecontrol/ListeningModeResolver.kt`
- [X] T008 Implement feature flag + preference keys for voice-control rollout and retention opt-out in `/Users/dev/code/merlinran/pocket-casts-android/modules/services/utils/src/main/java/au/com/shiftyjelly/pocketcasts/utils/featureflag/Feature.kt`, `/Users/dev/code/merlinran/pocket-casts-android/modules/services/preferences/src/main/java/au/com/shiftyjelly/pocketcasts/preferences/Settings.kt`, and `/Users/dev/code/merlinran/pocket-casts-android/modules/services/preferences/src/main/java/au/com/shiftyjelly/pocketcasts/preferences/SettingsImpl.kt`
- [X] T009 [P] Add analytics event definitions for voice-command outcomes in `/Users/dev/code/merlinran/pocket-casts-android/modules/services/analytics/src/main/java/au/com/shiftyjelly/pocketcasts/analytics/AnalyticsEvent.kt`
- [X] T010 [P] Add localized feedback strings in `/Users/dev/code/merlinran/pocket-casts-android/modules/services/localization/src/main/res/values/strings.xml`

**Checkpoint**: Foundational voice-control infrastructure is ready; story phases can proceed.

---

## Phase 3: User Story 1 - Reliable Basic Voice Controls (Priority: P1) 🎯 MVP

**Goal**: Execute core playback commands hands-free with deterministic single-action behavior.

**Independent Test**: With active playback, supported commands (skip/rewind/speed/next) execute correctly without touch and produce one action per utterance.

### Tests for User Story 1

- [X] T011 [P] [US1] Add command-intent mapping tests in `/Users/dev/code/merlinran/pocket-casts-android/modules/services/repositories/src/test/java/au/com/shiftyjelly/pocketcasts/repositories/playback/voicecontrol/CommandIntentMapperTest.kt`
- [X] T012 [P] [US1] Add core command execution tests in `/Users/dev/code/merlinran/pocket-casts-android/modules/services/repositories/src/test/java/au/com/shiftyjelly/pocketcasts/repositories/playback/voicecontrol/VoiceCommandExecutorTest.kt`
- [X] T013 [US1] Add active-playback instrumentation flow test in `/Users/dev/code/merlinran/pocket-casts-android/app/src/androidTest/java/au/com/shiftyjelly/pocketcasts/voicecontrol/CoreVoiceControlsTest.kt`

### Implementation for User Story 1

- [X] T014 [P] [US1] Implement command phrase normalization/mapping in `/Users/dev/code/merlinran/pocket-casts-android/modules/services/repositories/src/main/java/au/com/shiftyjelly/pocketcasts/repositories/playback/voicecontrol/CommandIntentMapper.kt`
- [X] T015 [US1] Implement playback action execution via `PlaybackManager` in `/Users/dev/code/merlinran/pocket-casts-android/modules/services/repositories/src/main/java/au/com/shiftyjelly/pocketcasts/repositories/playback/voicecontrol/VoiceCommandExecutor.kt`
- [X] T016 [US1] Integrate hands-free command entry in `/Users/dev/code/merlinran/pocket-casts-android/app/src/main/java/au/com/shiftyjelly/pocketcasts/ui/MainActivity.kt`
- [X] T017 [US1] Implement user feedback emission for success/failure outcomes in `/Users/dev/code/merlinran/pocket-casts-android/app/src/main/java/au/com/shiftyjelly/pocketcasts/ui/MainActivityViewModel.kt`

**Checkpoint**: Core voice commands are functional and testable end-to-end for MVP scope.

---

## Phase 4: User Story 2 - Works Across Connectivity Conditions (Priority: P2)

**Goal**: Apply local/cloud parallel recognition with 1-second arbitration and no late override.

**Independent Test**: In online, slow-network, and offline scenarios, arbitration chooses one result correctly and executes at most one action.

### Tests for User Story 2

- [X] T018 [P] [US2] Add arbitration timeout/cloud-win/local-fallback tests in `/Users/dev/code/merlinran/pocket-casts-android/modules/services/repositories/src/test/java/au/com/shiftyjelly/pocketcasts/repositories/playback/voicecontrol/VoiceArbitrationEngineTest.kt`
- [X] T019 [P] [US2] Add listening-mode transition tests in `/Users/dev/code/merlinran/pocket-casts-android/modules/services/repositories/src/test/java/au/com/shiftyjelly/pocketcasts/repositories/playback/voicecontrol/ListeningModeResolverTest.kt`
- [X] T020 [US2] Add connectivity instrumentation tests in `/Users/dev/code/merlinran/pocket-casts-android/app/src/androidTest/java/au/com/shiftyjelly/pocketcasts/voicecontrol/ConnectivityArbitrationTest.kt`

### Implementation for User Story 2

- [X] T021 [P] [US2] Implement parallel recognizer orchestration in `/Users/dev/code/merlinran/pocket-casts-android/modules/services/repositories/src/main/java/au/com/shiftyjelly/pocketcasts/repositories/playback/voicecontrol/VoiceRecognitionOrchestrator.kt`
- [X] T022 [US2] Implement deterministic 1-second arbitration and late-result ignore behavior in `/Users/dev/code/merlinran/pocket-casts-android/modules/services/repositories/src/main/java/au/com/shiftyjelly/pocketcasts/repositories/playback/voicecontrol/VoiceCommandRouter.kt`
- [X] T023 [US2] Wire online/offline fallback execution path in `/Users/dev/code/merlinran/pocket-casts-android/modules/services/repositories/src/main/java/au/com/shiftyjelly/pocketcasts/repositories/playback/voicecontrol/VoiceCommandRouter.kt` and `/Users/dev/code/merlinran/pocket-casts-android/app/src/main/java/au/com/shiftyjelly/pocketcasts/ui/MainActivity.kt`
- [X] T024 [US2] Add source-selection and latency tracking in `/Users/dev/code/merlinran/pocket-casts-android/modules/services/analytics/src/main/java/au/com/shiftyjelly/pocketcasts/analytics/AnalyticsTracker.kt`

**Checkpoint**: Connectivity-aware arbitration behavior is independently validated.

---

## Phase 5: User Story 3 - Safe Path for Future Advanced Commands (Priority: P3)

**Goal**: Safely handle unsupported advanced commands and enforce retention policy controls.

**Independent Test**: Unsupported advanced commands fail safely without playback disruption, and retention opt-out immediately prevents new sample persistence.

### Tests for User Story 3

- [X] T025 [P] [US3] Add unsupported-advanced command safety tests in `/Users/dev/code/merlinran/pocket-casts-android/modules/services/repositories/src/test/java/au/com/shiftyjelly/pocketcasts/repositories/playback/voicecontrol/UnsupportedAdvancedCommandTest.kt`
- [X] T026 [P] [US3] Add retention policy and immediate opt-out tests in `/Users/dev/code/merlinran/pocket-casts-android/modules/services/repositories/src/test/java/au/com/shiftyjelly/pocketcasts/repositories/playback/voicecontrol/VoiceRetentionManagerTest.kt`
- [X] T027 [US3] Add retention-control instrumentation test in `/Users/dev/code/merlinran/pocket-casts-android/app/src/androidTest/java/au/com/shiftyjelly/pocketcasts/voicecontrol/RetentionOptOutFlowTest.kt`

### Implementation for User Story 3

- [X] T028 [P] [US3] Implement unsupported-advanced intent handling with safe feedback in `/Users/dev/code/merlinran/pocket-casts-android/modules/services/repositories/src/main/java/au/com/shiftyjelly/pocketcasts/repositories/playback/voicecontrol/VoiceCommandRouter.kt`
- [X] T029 [US3] Implement retained-sample lifecycle management (30-day expiry) in `/Users/dev/code/merlinran/pocket-casts-android/modules/services/repositories/src/main/java/au/com/shiftyjelly/pocketcasts/repositories/playback/voicecontrol/VoiceRetentionManager.kt`
- [X] T030 [US3] Implement retention opt-out behavior wiring in `/Users/dev/code/merlinran/pocket-casts-android/modules/services/preferences/src/main/java/au/com/shiftyjelly/pocketcasts/preferences/Settings.kt`, `/Users/dev/code/merlinran/pocket-casts-android/modules/services/preferences/src/main/java/au/com/shiftyjelly/pocketcasts/preferences/SettingsImpl.kt`, and `/Users/dev/code/merlinran/pocket-casts-android/modules/features/settings/src/main/java/au/com/shiftyjelly/pocketcasts/settings/PlaybackSettingsFragment.kt`
- [X] T031 [US3] Add retention-state transition analytics in `/Users/dev/code/merlinran/pocket-casts-android/modules/services/analytics/src/main/java/au/com/shiftyjelly/pocketcasts/analytics/AnalyticsTracker.kt`

**Checkpoint**: Advanced-command safe fallback and retention controls are independently functional.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Final verification, cleanup, and documentation updates.

- [X] T032 [P] Update implementation notes and validation guidance in `/Users/dev/code/merlinran/pocket-casts-android/specs/001-hands-free-voice-control/quickstart.md`
- [ ] T033 Run formatting verification with `./gradlew spotlessCheck` from `/Users/dev/code/merlinran/pocket-casts-android/`
- [X] T034 Run unit test suites with `./gradlew :modules/services/deeplink:testDebugUnitTest :modules/services/repositories:testDebugUnitTest :app:testDebugUnitTest` from `/Users/dev/code/merlinran/pocket-casts-android/`
- [ ] T035 Run instrumentation validation with `./gradlew :app:connectedDebugAndroidTest` from `/Users/dev/code/merlinran/pocket-casts-android/`
- [ ] T036 Run dependency health check with `./gradlew buildHealth` from `/Users/dev/code/merlinran/pocket-casts-android/` when dependency graph changes
- [X] T037 [P] Final pass for TODO-only temporary notes and task traceability in `/Users/dev/code/merlinran/pocket-casts-android/specs/001-hands-free-voice-control/tasks.md`

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 (Setup)**: No dependencies
- **Phase 2 (Foundational)**: Depends on Phase 1; blocks all story phases
- **Phase 3 (US1)**: Depends on Phase 2
- **Phase 4 (US2)**: Depends on Phase 2 and reuses US1 core command execution path
- **Phase 5 (US3)**: Depends on Phase 2 and reuses arbitration/routing infrastructure from US2
- **Phase 6 (Polish)**: Depends on all targeted story phases

### User Story Dependencies

- **US1 (P1)**: First deliverable and MVP baseline
- **US2 (P2)**: Extends command pipeline with connectivity-aware arbitration
- **US3 (P3)**: Adds safe advanced-command handling and retention controls on established pipeline

### Within Each User Story

- Tests first (`T011-T013`, `T018-T020`, `T025-T027`)
- Core implementation next
- Integration wiring last
- Validate independent test criteria before moving forward

### Parallel Opportunities

- Setup: `T003` and `T004` can run in parallel
- Foundational: `T006`, `T009`, and `T010` can run in parallel once `T005` starts
- US1: `T011` and `T012` can run in parallel; `T014` can proceed once test scaffolding is ready
- US2: `T018` and `T019` can run in parallel; `T021` can run in parallel with `T020`
- US3: `T025` and `T026` can run in parallel; `T028` can run in parallel with `T027`
- Polish: `T032` and `T037` can run in parallel with verification prep

---

## Parallel Example: User Story 1

```bash
# Run in parallel after foundational completion:
Task: "T011 [US1] .../CommandIntentMapperTest.kt"
Task: "T012 [US1] .../VoiceCommandExecutorTest.kt"
Task: "T014 [US1] .../CommandIntentMapper.kt"
```

## Parallel Example: User Story 2

```bash
# Run in parallel during connectivity/arbitration work:
Task: "T018 [US2] .../VoiceArbitrationEngineTest.kt"
Task: "T019 [US2] .../ListeningModeResolverTest.kt"
Task: "T021 [US2] .../VoiceRecognitionOrchestrator.kt"
```

## Parallel Example: User Story 3

```bash
# Run in parallel during retention and safe-fallback work:
Task: "T025 [US3] .../UnsupportedAdvancedCommandTest.kt"
Task: "T026 [US3] .../VoiceRetentionManagerTest.kt"
Task: "T028 [US3] .../VoiceCommandRouter.kt"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1 and Phase 2
2. Complete Phase 3 (US1)
3. Validate US1 independent test criteria
4. Demo/review before expanding scope

### Incremental Delivery

1. Deliver US1 (core command reliability)
2. Deliver US2 (connectivity-aware arbitration)
3. Deliver US3 (safe advanced fallback + retention controls)
4. Complete Phase 6 verification and polish

### Notes

- Keep tasks in checkbox format for progress tracking
- Each story must remain independently verifiable against spec criteria
- If implementation details shift, update task file paths before execution
- Use `TODO` for temporary notes; do not use `FIXME`
