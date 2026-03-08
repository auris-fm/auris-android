# Data Model: Hands-Free Voice Control

## Entity: VoiceCommandSession

Represents an interaction session for voice-driven playback control.

### Fields
- `sessionId: String` - Unique session identifier.
- `startedAt: Instant` - Session start timestamp.
- `endedAt: Instant?` - Session end timestamp.
- `playbackStateAtStart: PlaybackState` - Initial playback state (`Active`, `Paused`, `Stopped`).
- `listeningMode: ListeningMode` - Effective mode (`Continuous`, `WakeWord`).
- `retentionEnabled: Boolean` - Whether voice sample retention is active at capture time.

### Validation Rules
- `sessionId` MUST be unique.
- `endedAt` MUST be >= `startedAt` when present.
- `listeningMode` MUST be `Continuous` only when playback is active.

### State Transitions
- `Initialized -> Active -> Completed`
- `Active -> Cancelled` on explicit interruption or fatal command pipeline failure.

## Entity: CommandIntent

Represents a normalized user command derived from recognizers.

### Fields
- `intentId: String` - Unique intent identifier.
- `sessionId: String` - Parent session reference.
- `intentType: IntentType` - Core command class (`SkipForward`, `Rewind`, `SpeedChange`,
  `NextEpisode`, `UnsupportedAdvanced`).
- `rawPhrase: String` - Captured utterance transcript (or normalized token).
- `confidenceLocal: Double?` - Local recognizer confidence.
- `confidenceCloud: Double?` - Cloud recognizer confidence.

### Validation Rules
- `intentType` MUST map to exactly one executable action or explicit unsupported outcome.
- Confidence values, when present, MUST be in `[0.0, 1.0]`.

### Relationships
- Many `CommandIntent` entries can belong to one `VoiceCommandSession`.

## Entity: ArbitrationDecision

Represents the deterministic selection between local/cloud recognition outcomes.

### Fields
- `decisionId: String` - Unique decision identifier.
- `intentId: String` - Referenced command intent.
- `arbitrationDeadlineMs: Int` - Fixed deadline (`1000`).
- `selectedSource: RecognitionSource` - `Cloud` or `Local`.
- `selectedAt: Instant` - Time decision finalized.
- `lateSourceIgnored: Boolean` - True when non-selected source arrived after decision.

### Validation Rules
- `selectedAt` MUST be within deadline window for cloud selection.
- Exactly one source MUST be selected for execution.

### State Transitions
- `Pending -> Selected -> Executed`
- `Pending -> Selected(LocalFallback) -> Executed`

## Entity: IntentResolutionOutcome

Represents post-arbitration execution result.

### Fields
- `outcomeId: String` - Unique outcome identifier.
- `decisionId: String` - Referenced arbitration decision.
- `resultType: ResultType` - `Executed`, `Unsupported`, `FailedSafely`.
- `userFeedbackType: FeedbackType` - UX feedback category shown/emitted.
- `executedAt: Instant?` - Execution timestamp when applicable.

### Validation Rules
- Exactly one outcome per decision.
- `executedAt` required for `Executed`.

## Entity: AnonymizedVoiceSample

Represents retained de-identified sample for quality analysis.

### Fields
- `sampleId: String` - Unique sample identifier.
- `sessionId: String` - Parent session reference.
- `capturedAt: Instant` - Capture timestamp.
- `expiresAt: Instant` - Retention expiry (`capturedAt + 30 days`).
- `retentionPolicyVersion: String` - Policy version used at capture.

### Validation Rules
- Samples MUST only be persisted when retention is enabled.
- `expiresAt` MUST equal policy-based retention duration.
- Opt-out changes apply immediately to new samples (no new sample creation after opt-out).
