# Feature Specification: [FEATURE NAME]

**Feature Branch**: `[###-feature-name]`
**Created**: [DATE]
**Status**: Draft
**Input**: User description: "$ARGUMENTS"

## Constitution Alignment *(mandatory)*

- **Kotlin/Compose/Coroutines-first plan**: [Confirm language, UI stack, and async model;
  include Material3 or documented Material2 exception]
- **Module boundary impact**: [List affected modules and confirm dependency direction remains
  valid; note if `./gradlew buildHealth` is required]
- **Data contract safety**: [State whether Room entities/DAOs/migrations/schemas change; list
  required migration and compatibility validation]
- **Resource governance**: [List new/changed strings and images, with paths under
  `modules/services/localization` and `modules/services/images`]
- **Verification commands**: [List required `./gradlew` commands, including
  `spotlessCheck` and impacted tests/lint/instrumentation]

## User Scenarios & Testing *(mandatory)*

### User Story 1 - [Brief Title] (Priority: P1)

[Describe this user journey in plain language]

**Why this priority**: [Explain value and priority]

**Independent Test**: [Describe how this can be tested independently]

**Acceptance Scenarios**:

1. **Given** [initial state], **When** [action], **Then** [expected outcome]
2. **Given** [initial state], **When** [action], **Then** [expected outcome]

---

### User Story 2 - [Brief Title] (Priority: P2)

[Describe this user journey in plain language]

**Why this priority**: [Explain value and priority]

**Independent Test**: [Describe how this can be tested independently]

**Acceptance Scenarios**:

1. **Given** [initial state], **When** [action], **Then** [expected outcome]

---

### User Story 3 - [Brief Title] (Priority: P3)

[Describe this user journey in plain language]

**Why this priority**: [Explain value and priority]

**Independent Test**: [Describe how this can be tested independently]

**Acceptance Scenarios**:

1. **Given** [initial state], **When** [action], **Then** [expected outcome]

---

### Edge Cases

- What happens when [offline/network failure condition]?
- How does the flow recover when [sync/auth/playback dependency] fails?
- How does UI behave when [empty/loading/error state] persists?

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST [specific capability]
- **FR-002**: System MUST [specific validation/guardrail]
- **FR-003**: Users MUST be able to [key interaction]
- **FR-004**: System MUST [data persistence/sync behavior]
- **FR-005**: System MUST [analytics/feature-flag behavior if applicable]

### Key Entities *(include if feature involves data)*

- **[Entity 1]**: [What it represents, key attributes without implementation]
- **[Entity 2]**: [What it represents, relationships to other entities]

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: [Measurable user outcome]
- **SC-002**: [Measurable reliability/performance outcome]
- **SC-003**: [Measurable quality outcome tied to acceptance tests]
- **SC-004**: [Business/product impact metric]
