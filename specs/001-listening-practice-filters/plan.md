# Implementation Plan: Playback Listening Practice Filters

**Branch**: `001-listening-practice-filters` | **Date**: 2026-03-08 | **Spec**: [/Users/dev/code/merlinran/pocket-casts-android/main/specs/001-listening-practice-filters/spec.md](/Users/dev/code/merlinran/pocket-casts-android/main/specs/001-listening-practice-filters/spec.md)
**Input**: Feature specification from `/specs/001-listening-practice-filters/spec.md`

**Note**: This plan is produced by `/speckit.plan` and covers Phase 0 research + Phase 1 design/contract outputs.

## Summary

Add three listening-practice playback filters (background noise, voice masking, low-pass) that listeners can switch during active playback without interruption. The design reuses the existing playback-effects pipeline in `modules/features/player` and `modules/services/repositories` so filter state can be applied in real time and surfaced consistently in the Effects UI.

## Technical Context

**Language/Version**: Kotlin (Android modules; JVM target managed by Gradle convention plugins)  
**Primary Dependencies**: AndroidX Media3 (ExoPlayer audio pipeline), Hilt DI, Kotlin Coroutines + Flow, RxJava2 (existing player flows), Room/Settings persistence infrastructure  
**Storage**: Existing playback effects settings pathways (global settings and/or per-podcast effects state), plus in-memory active playback state  
**Testing**: JUnit4, Mockito, Turbine, coroutines-test; module unit tests via Gradle test tasks  
**Target Platform**: Android mobile app (`:app`, player feature + repositories services), with cast playback compatibility constraints  
**Project Type**: Multi-module Android mobile application  
**Performance Goals**: Filter toggle audible effect applied within 1 second while keeping playback uninterrupted for at least 95% of toggles  
**Constraints**: No playback restart on filter change; player controls remain responsive; cast/offload limitations must degrade gracefully  
**Scale/Scope**: Single feature branch affecting player UI/viewmodel, playback effects model, and local playback audio processor chain

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- Constitution source: `/Users/dev/code/merlinran/pocket-casts-android/main/.specify/memory/constitution.md`
- Result (pre-Phase 0): **PASS (no enforceable gates defined)**
- Evidence: constitution file currently contains placeholder tokens (`[PRINCIPLE_*]`, `[SECTION_*]`) without ratified rules, so no concrete constraints can be violated.
- Action: proceed under repository standards from `AGENTS.md` (module boundaries, test expectations, no unnecessary scope expansion).

## Project Structure

### Documentation (this feature)

```text
specs/001-listening-practice-filters/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   └── playback-practice-filters-contract.md
└── tasks.md               # Created later by /speckit.tasks
```

### Source Code (repository root)

```text
modules/features/player/
├── src/main/java/au/com/shiftyjelly/pocketcasts/player/view/
├── src/main/java/au/com/shiftyjelly/pocketcasts/player/viewmodel/
└── src/main/res/layout/

modules/services/model/
└── src/main/java/au/com/shiftyjelly/pocketcasts/models/to/

modules/services/repositories/
├── src/main/java/au/com/shiftyjelly/pocketcasts/repositories/playback/
└── src/test/java/au/com/shiftyjelly/pocketcasts/repositories/playback/

modules/features/player/src/test/
```

**Structure Decision**: Use existing player feature + repositories playback pipeline; do not create new modules. This keeps dependency flow compliant (feature -> services) and minimizes migration risk.

## Phase 0 Research Focus

- Determine filter-selection behavior and conflict rule for concurrent selections.
- Determine persistence scope using existing effects architecture (session/global/per-podcast).
- Determine local vs cast behavior and fallback expectations when filters are unsupported.
- Determine practical audio-processing approach compatibility with existing `ShiftyAudioProcessorChain`.

## Phase 1 Design Outputs

- `data-model.md`: entities, fields, validation rules, and state transitions for practice filters.
- `contracts/playback-practice-filters-contract.md`: UI and playback-state contract for filter controls and behavior.
- `quickstart.md`: verification flow for manual and test execution.
- Agent context refresh via `.specify/scripts/bash/update-agent-context.sh codex`.

## Post-Design Constitution Check

- Result (after Phase 1 artifacts): **PASS (no enforceable gates defined)**
- Re-check notes: design remains within existing modules, defines explicit acceptance behavior, and avoids adding undefined governance exceptions.

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| None | N/A | N/A |
