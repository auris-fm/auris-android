# Implementation Plan: Hands-Free Voice Control

**Branch**: `001-hands-free-voice-control` | **Date**: 2026-03-06 | **Spec**: [/Users/dev/code/merlinran/pocket-casts-android/specs/001-hands-free-voice-control/spec.md](/Users/dev/code/merlinran/pocket-casts-android/specs/001-hands-free-voice-control/spec.md)
**Input**: Feature specification from `/specs/001-hands-free-voice-control/spec.md`

**Note**: This template is filled in by the `/speckit.plan` command. See
`.specify/templates/plan-template.md` for the execution workflow.

## Summary

Implement a hands-free voice control capability that (1) uses continuous listening during active
playback and wake-word activation when playback is idle, (2) runs local and cloud intent
recognition in parallel with deterministic 1-second arbitration, and (3) executes core playback
commands reliably while safely handling unsupported advanced commands and retention policy
constraints.

## Technical Context

**Language/Version**: Kotlin (Android; existing repo toolchain)
**Primary Dependencies**: AndroidX, Hilt, Coroutines/Flow, `PlaybackManager`,
`MediaSessionManager`, `FeatureFlag`, `AnalyticsTracker`, existing deep link/intent pathways
**Storage**: Existing app settings + retained anonymized voice samples (30-day policy), no Room
schema migration planned in this phase
**Testing**: JUnit/Mockito/Turbine for unit tests, Android instrumentation for command-routing and
state-transition validation
**Target Platform**: Android app module (`app`) with service modules (`repositories`, `utils`,
`deeplink`, `analytics`, `preferences`)
**Project Type**: Multi-module Android app
**Performance Goals**: Supported command arbitration decision within 1 second online; supported
command execution within 2 seconds in typical conditions and 3 seconds in poor/offline conditions
**Constraints**: Deterministic single action per utterance; no post-decision cloud override;
continuous listening only while actively playing; wake-word mode otherwise; retention default-on
with immediate opt-out effect
**Scale/Scope**: `app` command entry orchestration, `modules/services/repositories` playback
command coordination, preferences/feature-flag wiring, analytics outcomes, targeted tests

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- [x] Principle I: Kotlin-first implementation is preserved; no new UI surface is required in
      this phase. If any new Compose UI is added later, Material3-by-default rule applies.
- [x] Principle II: Dependency direction remains valid (`app -> features/services -> core
      services`); no feature-to-feature dependency introduced. Run `./gradlew buildHealth` if
      dependency graph changes.
- [x] Principle III: No Room entity/DAO/schema migration is introduced in this phase.
- [x] Principle IV: Any user-facing strings added for command feedback will be centralized under
      `modules/services/localization`; no new image assets required.
- [x] Principle V: Verification includes `./gradlew spotlessCheck`, impacted module tests,
      feature-flagged rollout support, and analytics evidence.

**Gate Result (Pre-Design)**: PASS

## Project Structure

### Documentation (this feature)

```text
specs/001-hands-free-voice-control/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
└── tasks.md
```

### Source Code (repository root)

```text
app/
automotive/
wear/
modules/
├── features/
└── services/
```

**Structure Decision**:
- Planned implementation touch points:
  - `/Users/dev/code/merlinran/pocket-casts-android/app/src/main/java/au/com/shiftyjelly/pocketcasts/ui/MainActivity.kt`
  - `/Users/dev/code/merlinran/pocket-casts-android/modules/services/repositories/src/main/java/au/com/shiftyjelly/pocketcasts/repositories/playback/`
  - `/Users/dev/code/merlinran/pocket-casts-android/modules/services/preferences/src/main/java/au/com/shiftyjelly/pocketcasts/preferences/`
  - `/Users/dev/code/merlinran/pocket-casts-android/modules/services/analytics/src/main/java/au/com/shiftyjelly/pocketcasts/analytics/`
  - `/Users/dev/code/merlinran/pocket-casts-android/modules/services/deeplink/src/main/kotlin/au/com/shiftyjelly/pocketcasts/deeplink/`
  - Matching unit/instrumentation tests under affected modules.

## Phase 0: Outline & Research

### Research Inputs

- Dependencies: playback orchestration, feature flags, preferences, analytics, deep-link/intent
  routing, command arbitration policy.
- Integrations: local recognizer + cloud recognizer parallel strategy, playback execution,
  retention policy enforcement.
- Unknowns in this plan: none unresolved; research confirms defaults and operational boundaries.

### Research Tasks

- Find best practices for hybrid local/cloud voice-intent arbitration in mobile playback apps.
- Find best practices for deterministic one-action command execution under parallel inference.
- Find best practices for default-on retention with user opt-out and immediate enforcement.
- Find best practices for wake-word fallback when continuous listening is disabled by playback
  state.

## Phase 1: Design & Contracts

- Produce `data-model.md` with entities, fields, validation rules, and state transitions for
  command sessions and arbitration lifecycle.
- Produce contract docs under `contracts/` for voice command routing/arbitration and retention
  policy behavior.
- Produce `quickstart.md` with validation commands and expected outcomes.
- Run agent context update: `/.specify/scripts/bash/update-agent-context.sh codex`.
- Re-run constitution gate after design artifacts are complete.

## Post-Design Constitution Re-Check

- [x] Principle I: Design remains Kotlin/coroutines aligned and does not introduce conflicting UI
      stack requirements.
- [x] Principle II: Design keeps logic in app/services layers without cross-feature dependency
      violations.
- [x] Principle III: Design confirms no Room schema migration is required.
- [x] Principle IV: Design documents localization/resource governance for command feedback.
- [x] Principle V: Design includes explicit verification commands and feature-flag/analytics
      obligations.

**Gate Result (Post-Design)**: PASS

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

No constitution violations currently identified.
