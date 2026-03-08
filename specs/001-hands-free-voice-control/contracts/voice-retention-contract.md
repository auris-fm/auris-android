# Contract: Voice Sample Retention Policy

## Purpose
Define retention and user-control behavior for anonymized voice samples.

## Policy
- Retain anonymized voice samples for up to 30 days.
- Retention is enabled by default.
- User can opt out at any time.
- Opt-out takes immediate effect for newly captured samples.

## Data Handling Rules
1. Samples must be de-identified before retention.
2. Each retained sample has capture and expiry timestamps.
3. Expired samples must be removed no later than policy deadline.
4. Commands still function when retention is disabled.

## User Control Semantics
- Opt-out changes do not retroactively alter already-retained samples unless separate deletion
  controls are introduced in future scope.
- After opt-out, no new samples are retained.

## Validation Signals
- Retention state attached to sample capture decision.
- Audit/event log includes retention-on/off state transitions.
