---

description: "Task list template for feature implementation"
---

# Tasks: [FEATURE NAME]

**Input**: Design documents from `/specs/[###-feature-name]/`
**Prerequisites**: plan.md (required), spec.md (required for user stories), research.md,
data-model.md, contracts/

**Tests**: Include test tasks for changed behavior. ViewModels, managers, and business logic
changes require unit tests per constitution.

**Organization**: Tasks are grouped by user story to enable independent implementation and
verification.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Path Conventions

- Application modules: `app/`, `automotive/`, `wear/`
- Feature modules: `modules/features/<feature>/`
- Service modules: `modules/services/<service>/`
- Unit tests: `<module>/src/test/`
- Instrumentation tests: `<module>/src/androidTest/`

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Identify impacted modules and establish implementation scaffolding.

- [ ] T001 Map impacted modules and source sets in plan
- [ ] T002 Add/update dependencies in affected `build.gradle.kts` files
- [ ] T003 [P] Prepare test scaffolding in `<module>/src/test/` and/or
      `<module>/src/androidTest/`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core prerequisites that MUST complete before user-story implementation.

- [ ] T004 Confirm architecture choices (Kotlin, Compose, Coroutines/Flow)
- [ ] T005 [P] Define/adjust shared models/contracts used by multiple stories
- [ ] T006 [P] Implement feature-flag wiring when rollout requires gating
- [ ] T007 Define error/loading/empty states and analytics events
- [ ] T008 If data layer changes, add Room migration + schema export updates

**Checkpoint**: Foundation complete; user-story slices can proceed independently.

---

## Phase 3: User Story 1 - [Title] (Priority: P1) 🎯 MVP

**Goal**: [Brief description]

**Independent Test**: [How to validate story in isolation]

### Tests for User Story 1

- [ ] T009 [P] [US1] Add/update unit tests in `<module>/src/test/`
- [ ] T010 [P] [US1] Add/update UI/instrumentation tests in `<module>/src/androidTest/`
      when UI flow changes

### Implementation for User Story 1

- [ ] T011 [P] [US1] Implement data/model updates in exact file paths
- [ ] T012 [P] [US1] Implement ViewModel/logic updates in exact file paths
- [ ] T013 [US1] Implement Compose UI updates in exact file paths
- [ ] T014 [US1] Add/adjust localization and image assets via service modules
- [ ] T015 [US1] Add analytics + feature-flag checks where applicable

**Checkpoint**: User Story 1 is independently functional and testable.

---

## Phase 4: User Story 2 - [Title] (Priority: P2)

**Goal**: [Brief description]

**Independent Test**: [How to validate story in isolation]

### Tests for User Story 2

- [ ] T016 [P] [US2] Add/update unit tests in `<module>/src/test/`
- [ ] T017 [P] [US2] Add/update instrumentation tests as needed

### Implementation for User Story 2

- [ ] T018 [P] [US2] Implement model/repository changes in exact file paths
- [ ] T019 [US2] Implement ViewModel/domain logic updates in exact file paths
- [ ] T020 [US2] Implement UI updates in exact file paths

**Checkpoint**: User Stories 1 and 2 both work independently.

---

## Phase 5: User Story 3 - [Title] (Priority: P3)

**Goal**: [Brief description]

**Independent Test**: [How to validate story in isolation]

### Tests for User Story 3

- [ ] T021 [P] [US3] Add/update unit tests in `<module>/src/test/`
- [ ] T022 [P] [US3] Add/update instrumentation tests as needed

### Implementation for User Story 3

- [ ] T023 [P] [US3] Implement model/repository updates in exact file paths
- [ ] T024 [US3] Implement ViewModel/domain logic updates in exact file paths
- [ ] T025 [US3] Implement UI updates in exact file paths

**Checkpoint**: All targeted stories are independently functional.

---

## Phase N: Polish & Cross-Cutting Concerns

**Purpose**: Final hardening and repository-level validation.

- [ ] TXXX [P] Documentation updates (spec, quickstart, module docs)
- [ ] TXXX Validate Room migration tests and exported schemas (if data changed)
- [ ] TXXX Run `./gradlew spotlessCheck`
- [ ] TXXX Run impacted unit tests (e.g., `:app:testDebugUnitTest` or module-specific)
- [ ] TXXX Run instrumentation tests for changed UI flows when required
- [ ] TXXX Run `./gradlew aggregatedLintRelease` for app-surface changes
- [ ] TXXX Run `./gradlew buildHealth` when dependency graph changed

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies
- **Foundational (Phase 2)**: Depends on Setup; blocks user stories
- **User Stories (Phase 3+)**: Depend on Foundational completion
- **Polish (Final Phase)**: Depends on all in-scope stories completion

### Within Each User Story

- Tests for changed behavior before or alongside implementation
- Model/repository layer before ViewModel/business logic
- ViewModel/business logic before UI wiring
- Story complete and independently verified before moving on

### Parallel Opportunities

- Tasks marked `[P]` are parallelizable when they touch different files
- Different user stories may proceed in parallel after foundational completion
- Unit test updates can run in parallel with unrelated UI tasks

---

## Notes

- Use exact module and file paths in every task
- Keep tasks small and independently verifiable
- Use `TODO` for temporary follow-ups; do not use `FIXME`
- Ensure each story can ship without requiring unfinished lower-priority stories
