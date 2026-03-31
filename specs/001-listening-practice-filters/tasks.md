# Tasks: Playback Listening Practice Filters

**Input**: Design documents from `/Users/dev/code/merlinran/pocket-casts-android/main/specs/001-listening-practice-filters/`
**Prerequisites**: `plan.md`, `spec.md`, `research.md`, `data-model.md`, `contracts/playback-practice-filters-contract.md`

**Tests**: No new test-authoring tasks are included because the feature spec did not explicitly require TDD/new automated tests. Verification and existing suite runs are included in Polish.

**Organization**: Tasks are grouped by user story to enable independent implementation and validation.

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Prepare shared UI strings/resources and playback feature scaffolding used across stories.

- [X] T001 Add practice-filter labels and status strings in `modules/services/localization/src/main/res/values/strings.xml`
- [X] T002 [P] Add practice-filter UI placeholders (selector row + status text container) in `modules/features/player/src/main/res/layout/fragment_effects.xml`
- [X] T003 [P] Create practice-filter contract constants file in `modules/features/player/src/main/java/au/com/shiftyjelly/pocketcasts/player/view/PracticeFilterUiContract.kt`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Introduce core runtime state and playback interfaces that all stories depend on.

**⚠️ CRITICAL**: Complete this phase before starting user story implementation.

- [X] T004 Create practice-filter domain enums/state models in `modules/services/model/src/main/java/au/com/shiftyjelly/pocketcasts/models/to/PracticeFilterState.kt`
- [X] T005 Extend `modules/services/repositories/src/main/java/au/com/shiftyjelly/pocketcasts/repositories/playback/PlaybackState.kt` with active practice-filter state fields
- [X] T006 Extend `modules/services/repositories/src/main/java/au/com/shiftyjelly/pocketcasts/repositories/playback/Player.kt` and `modules/services/repositories/src/main/java/au/com/shiftyjelly/pocketcasts/repositories/playback/LocalPlayer.kt` with practice-filter apply support
- [X] T007 Implement playback-manager practice-filter update path in `modules/services/repositories/src/main/java/au/com/shiftyjelly/pocketcasts/repositories/playback/PlaybackManager.kt`
- [X] T008 Add ViewModel state/actions for practice filters in `modules/features/player/src/main/java/au/com/shiftyjelly/pocketcasts/player/viewmodel/PlayerViewModel.kt`
- [X] T009 Add unsupported-player fallback state propagation in `modules/services/repositories/src/main/java/au/com/shiftyjelly/pocketcasts/repositories/playback/CastPlayer.kt` and `modules/services/repositories/src/main/java/au/com/shiftyjelly/pocketcasts/repositories/playback/PlaybackManager.kt`
- [X] T010 [P] Add practice-filter analytics tracking wiring in `modules/features/player/src/main/java/au/com/shiftyjelly/pocketcasts/player/view/EffectsFragment.kt`

**Checkpoint**: Playback pipeline can accept practice-filter selections and expose apply/failure state.

---

## Phase 3: User Story 1 - Toggle Practice Filters During Playback (Priority: P1) 🎯 MVP

**Goal**: Enable listeners to turn a filter on/off during playback without restarting audio.

**Independent Test**: Start playback, enable `Add noise`, then set `Off`; playback must continue while effect state changes immediately.

### Implementation for User Story 1

- [X] T011 [P] [US1] Implement practice-filter selector UI and active-state indicator in `modules/features/player/src/main/res/layout/fragment_effects.xml`
- [X] T012 [US1] Bind selector clicks and active-state rendering in `modules/features/player/src/main/java/au/com/shiftyjelly/pocketcasts/player/view/EffectsFragment.kt`
- [X] T013 [US1] Implement `SelectPracticeFilter`/`DisablePracticeFilter` handling in `modules/features/player/src/main/java/au/com/shiftyjelly/pocketcasts/player/viewmodel/PlayerViewModel.kt`
- [X] T014 [P] [US1] Create background-noise processor in `modules/services/repositories/src/main/java/au/com/shiftyjelly/pocketcasts/repositories/playback/ShiftyNoiseAudioProcessor.kt`
- [X] T015 [US1] Register background-noise routing and OFF behavior in `modules/services/repositories/src/main/java/au/com/shiftyjelly/pocketcasts/repositories/playback/ShiftyAudioProcessorChain.kt` and `modules/services/repositories/src/main/java/au/com/shiftyjelly/pocketcasts/repositories/playback/ShiftyRenderersFactory.kt`
- [X] T016 [US1] Apply background-noise selection in local playback path in `modules/services/repositories/src/main/java/au/com/shiftyjelly/pocketcasts/repositories/playback/SimplePlayer.kt`
- [X] T017 [US1] Show unavailable/failed apply messaging in `modules/features/player/src/main/java/au/com/shiftyjelly/pocketcasts/player/view/EffectsFragment.kt`
- [X] T018 [US1] Document US1 verification steps and outcomes in `specs/001-listening-practice-filters/quickstart.md`

**Checkpoint**: User can enable/disable one practice filter in-session with uninterrupted playback.

---

## Phase 4: User Story 2 - Practice With Voice Masking (Priority: P2)

**Goal**: Add voice masking mode for partial speech obscuring during playback.

**Independent Test**: While spoken audio is playing, enable `Mask voice`; portions of speech are obscured, then return to normal when set to `Off`.

### Implementation for User Story 2

- [X] T019 [P] [US2] Create voice-masking processor in `modules/services/repositories/src/main/java/au/com/shiftyjelly/pocketcasts/repositories/playback/ShiftyVoiceMaskingAudioProcessor.kt`
- [X] T020 [US2] Add `VOICE_MASKING` option mapping to practice-filter selector UI in `modules/features/player/src/main/java/au/com/shiftyjelly/pocketcasts/player/view/EffectsFragment.kt`
- [X] T021 [US2] Extend filter-selection state transitions for `VOICE_MASKING` in `modules/features/player/src/main/java/au/com/shiftyjelly/pocketcasts/player/viewmodel/PlayerViewModel.kt`
- [X] T022 [US2] Route `VOICE_MASKING` through audio processor chain in `modules/services/repositories/src/main/java/au/com/shiftyjelly/pocketcasts/repositories/playback/ShiftyAudioProcessorChain.kt` and `modules/services/repositories/src/main/java/au/com/shiftyjelly/pocketcasts/repositories/playback/ShiftyRenderersFactory.kt`
- [X] T023 [US2] Ensure cast/unsupported fallback message for `VOICE_MASKING` in `modules/services/repositories/src/main/java/au/com/shiftyjelly/pocketcasts/repositories/playback/CastPlayer.kt` and `modules/features/player/src/main/java/au/com/shiftyjelly/pocketcasts/player/view/EffectsFragment.kt`
- [X] T024 [US2] Document US2 verification steps and outcomes in `specs/001-listening-practice-filters/quickstart.md`

**Checkpoint**: Voice masking works independently and can be toggled off without playback restart.

---

## Phase 5: User Story 3 - Practice With Low-Pass Filtering (Priority: P3)

**Goal**: Add low-pass mode to simulate muffled listening conditions.

**Independent Test**: Enable `Low-pass` during playback and confirm high-frequency detail is reduced while playback remains continuous.

### Implementation for User Story 3

- [X] T025 [P] [US3] Create low-pass processor in `modules/services/repositories/src/main/java/au/com/shiftyjelly/pocketcasts/repositories/playback/ShiftyLowPassAudioProcessor.kt`
- [X] T026 [US3] Add `LOW_PASS` option mapping and selected-state UI behavior in `modules/features/player/src/main/java/au/com/shiftyjelly/pocketcasts/player/view/EffectsFragment.kt`
- [X] T027 [US3] Extend filter-selection transitions for `LOW_PASS` in `modules/features/player/src/main/java/au/com/shiftyjelly/pocketcasts/player/viewmodel/PlayerViewModel.kt`
- [X] T028 [US3] Route `LOW_PASS` through audio processor chain in `modules/services/repositories/src/main/java/au/com/shiftyjelly/pocketcasts/repositories/playback/ShiftyAudioProcessorChain.kt` and `modules/services/repositories/src/main/java/au/com/shiftyjelly/pocketcasts/repositories/playback/ShiftyRenderersFactory.kt`
- [X] T029 [US3] Ensure low-pass switching preserves playback continuity in `modules/services/repositories/src/main/java/au/com/shiftyjelly/pocketcasts/repositories/playback/SimplePlayer.kt` and `modules/services/repositories/src/main/java/au/com/shiftyjelly/pocketcasts/repositories/playback/PlaybackManager.kt`
- [X] T030 [US3] Document US3 verification steps and outcomes in `specs/001-listening-practice-filters/quickstart.md`

**Checkpoint**: Low-pass mode works independently and coexists with previous stories via latest-selection-wins behavior.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Final consistency checks, regressions, and documentation alignment.

- [X] T031 [P] Align implementation names with design docs in `specs/001-listening-practice-filters/data-model.md` and `specs/001-listening-practice-filters/contracts/playback-practice-filters-contract.md`
- [X] T032 Run `./gradlew :modules:features:player:testDebugUnitTest :modules:services:repositories:testDebugUnitTest` and record results in `specs/001-listening-practice-filters/quickstart.md`
- [X] T033 Run `./gradlew :app:assembleDebugProd` and record smoke-check notes in `specs/001-listening-practice-filters/quickstart.md`
- [ ] T034 [P] Validate full manual flow and regression checklist in `specs/001-listening-practice-filters/quickstart.md`

---

## Dependencies & Execution Order

### Phase Dependencies

- Setup (Phase 1): can start immediately.
- Foundational (Phase 2): depends on Setup and blocks all user stories.
- User Story phases (Phase 3-5): depend on Foundational completion.
- Polish (Phase 6): depends on completion of all targeted stories.

### User Story Dependency Graph

```text
US1 (P1, MVP) -> US2 (P2) -> US3 (P3)

Implementation note:
- US2 and US3 both depend on Phase 2 and can be developed in parallel if teams coordinate edits to shared files.
```

### Parallel Opportunities

- Phase 1: `T002` and `T003` can run in parallel.
- Phase 2: `T010` can run in parallel with `T007-T009` after `T008` exposes state hooks.
- US1: `T011` and `T014` can run in parallel.
- US2: `T019` can run in parallel with UI prep for `T020`.
- US3: `T025` can run in parallel with UI prep for `T026`.
- Polish: `T031` and `T034` can run in parallel.

---

## Parallel Example: User Story 1

```bash
Task: T011 [US1] Implement selector UI in modules/features/player/src/main/res/layout/fragment_effects.xml
Task: T014 [US1] Create processor in modules/services/repositories/src/main/java/au/com/shiftyjelly/pocketcasts/repositories/playback/ShiftyNoiseAudioProcessor.kt
```

## Parallel Example: User Story 2

```bash
Task: T019 [US2] Create processor in modules/services/repositories/src/main/java/au/com/shiftyjelly/pocketcasts/repositories/playback/ShiftyVoiceMaskingAudioProcessor.kt
Task: T020 [US2] Add VOICE_MASKING UI mapping in modules/features/player/src/main/java/au/com/shiftyjelly/pocketcasts/player/view/EffectsFragment.kt
```

## Parallel Example: User Story 3

```bash
Task: T025 [US3] Create processor in modules/services/repositories/src/main/java/au/com/shiftyjelly/pocketcasts/repositories/playback/ShiftyLowPassAudioProcessor.kt
Task: T026 [US3] Add LOW_PASS UI mapping in modules/features/player/src/main/java/au/com/shiftyjelly/pocketcasts/player/view/EffectsFragment.kt
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1 and Phase 2.
2. Complete Phase 3 (US1).
3. Validate US1 independently using the phase checkpoint and `quickstart.md`.
4. Demo/release MVP behavior before adding additional filters.

### Incremental Delivery

1. Deliver US1 (noise + off toggle) as first value slice.
2. Add US2 (voice masking) and re-run independent story validation.
3. Add US3 (low-pass) and re-run independent story validation.
4. Finish with Phase 6 cross-cutting checks.

### Parallel Team Strategy

1. Team completes Setup + Foundational together.
2. After Phase 2, one engineer can own US2 while another owns US3, with merge coordination for shared files.
3. Perform a final integrated regression pass in Phase 6.
