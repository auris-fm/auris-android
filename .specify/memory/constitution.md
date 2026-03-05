<!--
Sync Impact Report
- Version change: N/A -> 1.0.0
- Modified principles:
  - Template Principle 1 -> I. Kotlin, Compose, and Coroutines First
  - Template Principle 2 -> II. Module Boundary Enforcement
  - Template Principle 3 -> III. Data Contract and Migration Safety
  - Template Principle 4 -> IV. Shared Resource and Localization Governance
  - Template Principle 5 -> V. Verification-Driven Delivery
- Added sections:
  - Engineering Constraints
  - Development Workflow and Quality Gates
- Removed sections:
  - None
- Templates requiring updates:
  - ✅ updated: .specify/templates/plan-template.md
  - ✅ updated: .specify/templates/spec-template.md
  - ✅ updated: .specify/templates/tasks-template.md
  - ⚠ pending: .specify/templates/commands/*.md (directory exists, no command templates present)
  - ✅ updated: AGENTS.md
- Follow-up TODOs:
  - None
-->

# Pocket Casts Android Constitution

## Core Principles

### I. Kotlin, Compose, and Coroutines First
All new production code MUST be written in Kotlin. New UI surfaces MUST be implemented in
Jetpack Compose, and new Compose UI MUST use Material3 components unless the target module
is not yet migrated and requires Material2 compatibility. New asynchronous/state-management
flows MUST use Coroutines and Flow; RxJava may only be introduced for interoperability with
existing legacy APIs. Rationale: one primary stack reduces complexity, onboarding cost, and
long-term maintenance risk.

### II. Module Boundary Enforcement
The dependency flow MUST remain `app|automotive|wear -> features -> services -> core
services`. Feature modules MUST NOT depend on other feature modules. UI and feature code MUST
access network/database data through service/repository abstractions rather than bypassing to
lower layers directly. Any dependency graph change that affects modules MUST be validated with
`./gradlew buildHealth`. Rationale: strict boundaries preserve build health and prevent tight
coupling across a large multi-module Android codebase.

### III. Data Contract and Migration Safety
Any Room schema/entity/DAO change MUST include an explicit migration path and updated schema
artifacts under `modules/services/model/schemas/`. Migration behavior MUST be verified through
automated tests before merge. Contract changes that affect persisted user data or server-model
mapping MUST preserve backward compatibility or include a documented rollout/migration plan.
Rationale: data corruption and sync regressions are high-cost failures for podcast playback and
user libraries.

### IV. Shared Resource and Localization Governance
English strings MUST be added only in `modules/services/localization`, and non-translatable
values MUST be explicitly marked with `translatable="false"`. Image assets MUST be added only
to `modules/services/images`; SVG is preferred for vector-friendly graphics and WebP is the
default raster format unless a documented exception applies. Rationale: centralizing resources
keeps localization, packaging, and asset quality consistent across mobile, automotive, and wear
surfaces.

### V. Verification-Driven Delivery
Changes MUST be delivered in small, reviewable increments with tests for touched behavior.
ViewModels, managers, and business logic changes MUST include unit tests. `./gradlew
spotlessCheck` MUST pass; temporary notes MUST use `TODO` (never `FIXME`). Before merge,
engineers MUST run verification commands relevant to impacted modules (at minimum touched
module unit tests, plus lint/instrumentation where applicable), and feature-flagged behavior
MUST integrate with the existing `FeatureFlag` system. Rationale: explicit verification gates
prevent regressions in a release-critical consumer app.

## Engineering Constraints

- Build variants MUST preserve environment intent: `debug` uses staging (`.net`) endpoints;
  `debugProd`, `prototype`, and `release` use production (`.com`) endpoints.
- New analytics events MUST use the shared `AnalyticsTracker` service.
- New UI work MUST preserve existing design-system patterns unless a deliberate migration
  effort is approved and documented.
- Module dependency direction and feature-flag policies apply equally to `app`, `automotive`,
  and `wear` deliverables.

## Development Workflow and Quality Gates

- Every plan/spec/tasks artifact MUST include an explicit constitution alignment check.
- Tasks MUST be organized into independently testable increments with concrete file paths and
  required verification commands.
- Pull requests and reviews MUST document executed verification commands and outcomes.
- Constitution violations MUST block merge unless an approved exception documents scope,
  rationale, owner, and expiry date.

## Governance

This constitution supersedes conflicting workflow guidance for implementation and review.
Amendments require: (1) a written proposal, (2) explicit impact analysis on templates,
commands, and runtime guidance, and (3) maintainer approval. Versioning follows semantic
versioning for governance: MAJOR for incompatible principle changes/removals, MINOR for new
principles or materially expanded obligations, PATCH for clarifications with no obligation
change. Compliance review is mandatory during planning, task generation, code review, and
pre-merge verification.

**Version**: 1.0.0 | **Ratified**: 2026-03-05 | **Last Amended**: 2026-03-05
