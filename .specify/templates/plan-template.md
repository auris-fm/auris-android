# Implementation Plan: [FEATURE]

**Branch**: `[###-feature-name]` | **Date**: [DATE] | **Spec**: [link]
**Input**: Feature specification from `/specs/[###-feature-name]/spec.md`

**Note**: This template is filled in by the `/speckit.plan` command. See
`.specify/templates/plan-template.md` for the execution workflow.

## Summary

[Extract from feature spec: primary requirement + technical approach from research]

## Technical Context

**Language/Version**: Kotlin (Android target) [version if constrained]
**Primary Dependencies**: AndroidX, Jetpack Compose, Hilt, Coroutines/Flow,
Retrofit/OkHttp/Moshi, Room (as applicable)
**Storage**: Room/DataStore/files/remote only [specify what applies]
**Testing**: JUnit, Mockito, Turbine, Compose UI Test, instrumentation (as applicable)
**Target Platform**: Android mobile, Automotive, Wear (specify impacted surfaces)
**Project Type**: Multi-module Android app
**Performance Goals**: [e.g., startup/rendering/playback constraints or NEEDS CLARIFICATION]
**Constraints**: [e.g., offline behavior, memory, API latency, battery constraints]
**Scale/Scope**: [e.g., modules touched, screens affected, migration scope]

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- [ ] Principle I: Plan uses Kotlin and Compose-first architecture; Material3 is used for
      new Compose UI unless module migration constraints are documented.
- [ ] Principle II: Plan preserves module dependency direction (`app|automotive|wear ->
      features -> services -> core`) and includes `./gradlew buildHealth` if module
      dependencies change.
- [ ] Principle III: Any Room/data-contract change identifies migrations, schema artifacts,
      and migration tests.
- [ ] Principle IV: String/image resource changes are routed through
      `modules/services/localization` and `modules/services/images` with format policy noted.
- [ ] Principle V: Verification includes `./gradlew spotlessCheck` and impacted module tests
      (plus lint/instrumentation when applicable), and feature-flag implications are defined.

## Project Structure

### Documentation (this feature)

```text
specs/[###-feature]/
├── plan.md              # This file (/speckit.plan command output)
├── research.md          # Phase 0 output (/speckit.plan command)
├── data-model.md        # Phase 1 output (/speckit.plan command)
├── quickstart.md        # Phase 1 output (/speckit.plan command)
├── contracts/           # Phase 1 output (/speckit.plan command)
└── tasks.md             # Phase 2 output (/speckit.tasks command)
```

### Source Code (repository root)

```text
app/
automotive/
wear/
modules/
├── features/
└── services/

# Tests generally live in module-local paths:
# <module>/src/test/ and <module>/src/androidTest/
```

**Structure Decision**: [List exact module/file paths this feature will modify]

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| [e.g., temporary Rx interop] | [current need] | [why Flow-only path not yet possible] |
| [e.g., Material2 exception] | [migration blocker] | [why immediate Material3 migration not feasible] |
