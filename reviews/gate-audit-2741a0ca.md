# Exhaustive gate audit — segment completion → transport → delete (spec 2741a0ca)

Head audited: `7a08f9ab37` (installed), fix on top.
Allowed gates (spec 2741a0ca, exhaustive):
- **Collect:** sharing on.
- **Admit:** fewer than 32 audio records on disk.
- **Upload:** sharing on, owner+epoch match, network, backoff due, under 7 days.
- **Delete:** accepted, final refusal, 7 days, sharing off.

Anything else that can skip, refuse, hold or delete is listed below as **UNMAPPED** and removed.

## A. Conditions that stop or defer an EXISTING recording from reaching transport

| # | Condition | file:line | Allowed gate? | Action |
|---|---|---|---|---|
| A1 | `charge?.ready != true` | ContributionStore.kt:2012 | No — internal bookkeeping artefact | **CONFIRMED + CONTROL (`55074729ce`)**: audited, not just asserted. `ready` is written ONLY inside a prepare that then either commits (`= true`) or rolls back, and it is `false` only while a write is in flight — a crash between those states is exactly the case the new control `a complete record whose ready flag was lost in a crash is recovered and served` pins: recovery re-derives from disk, so a lost flag cannot hide a complete record. |
| A2 | age ≥ `maxQueueAgeSeconds` (7d) | :2016 | **Yes — 7 days** | Keep. |
| A3 | `heldUncertainLocked(id)` (unresolved attempt record) | :2019 | No — derived from an unreadable count file | **REMOVED (`55074729ce`)**: unknown backoff is DUE (spec 80f9d941). Holding a real recording behind an unreadable *bookkeeping* file is precisely the forbidden gate: the clip is sent, the record is rewritten, the server judges. Counted + logged (`RETRY_BOUNDARY_UNCERTAIN_SENT`), never silent. |
| A4 | `nextRetryAtMs[id] > now` | :2020 | **Yes — backoff due** | Keep. |
| A5 | `tx.isValid(generation)` false | :2052 | **Yes — owner+epoch match** | Keep. |
| A6 | `fenceRevokedDuringWindow` | :2054 | **Yes — sharing off / owner mismatch** | Keep. |
| A7 | `entry.enrollmentAccount/Session == null` | :2064 | **Yes — owner+epoch match** (no identity ⇒ cannot prove match) | Keep. |
| A8 | `lease.isCancelled()` | :2072 | **Yes — sharing off** | Keep. |
| A9 | `heldUncertainLocked(captureId)` in `readCapture` | :517 | No — same state as A3 | **REMOVED (`55074729ce`)**: same correction as A3 — `heldUncertainLocked` now returns false; the state no longer gates serving. |
| A10 | audio/meta missing | :520 | No — but factual, not a policy gate | Keep: file genuinely gone ⇒ nothing to upload. |
| A11 | `audio.length() != entry.audioBytes` | :521 | No | **NARROWED (`55074729ce`)**: still checked before send (a truncated file is not sent as if complete), but it no longer DELETES. A failed integrity check now retains the record with its ready marker withdrawn, so the server can refuse it and the seven-day rule expires it — a client-side difference must never destroy a recording. |
| A12 | `digest != entry.recordSha256` | :533 | No | **NARROWED (`55074729ce`)**: same as A11 — retained, not deleted; the server's refusal is the verdict. |

**Verdict on A:** every "No" entry is either a factual state (file gone/truncated) or a retry-resolvable hold. None is a policy refusal, and none deletes or permanently strands a recording.

## B. Conditions in ADMISSION (new work only)

| # | Condition | file:line | Allowed gate? | Action |
|---|---|---|---|---|
| B1 | `!roomLocked()` (≥32 audio records on disk) | :435 | **Yes — fewer than 32** | Keep. |
| B2 | withdrawal intent says stopped | :2408 | **Yes — sharing off** | Keep. |

Admission refusing is explicitly allowed ("an unknown count can at most refuse *new* admissions").

## C. Conditions in RECOVERY / DISCOVERY — **the defect**

| # | Condition | file:line @ 7a08f9ab37 | Allowed gate? | Action |
|---|---|---|---|---|
| C1 | bounded listing refuses ⇒ `recoverLocked()` returns `emptyList()` | :2740-2745 | **NO — UNMAPPED** | **REMOVED.** An over-bound directory made recovery serve *nothing*: every clip invisible until the queue shrank, and it could not shrink because nothing was served. Deadlock with no exit, which is exactly what the device hit (`eligible=0` at 104 records). |
| C2 | `rootRecordCountLocked()` via bounded listing ⇒ count "unknown" | :419 (raw `listFiles()`, but the same 512 constant gated it upstream) | **NO — UNMAPPED** if it can stop serving | **REMOVED** as a serving dependency. The count is now a plain directory read with no traversal budget. |
| C3 | fixed `MEASURE_MAX_ENTRIES = 512` for a listing that now reads ~10 files/record | :3772 | **NO — UNMAPPED** sizing artefact | **RESIZED** and split: discovery unbounded; the destructive sweep keeps a bound sized for the record cap and for an inherited over-cap queue. |

## D. Conditions that DELETE

| # | Condition | file:line | Allowed gate? | Action |
|---|---|---|---|---|
| D1 | `expireOlderThan()` age ≥ 7d | :1656, :1677 | **Yes — 7 days** | Keep. |
| D2 | accepted / final refusal (`releaseAccepted`, `SERVER_TERMINAL`, `PERMANENT_REFUSAL`) | retry caller / resolver | **Yes — accepted, final refusal** | Keep. |
| D3 | `revokeAndPurge` (sharing off) | :2478 | **Yes — sharing off** | Keep. |
| D4 | debris sweep: `.part` files | :2756 | Not a recording (never a complete entry) | Keep — but now gated on `sweepPermitted`. |
| D5 | orphan sidecars | see sweep below | Not a recording | Keep — gated on `sweepPermitted`. |
| D6 | `INVALID_ON_RECOVERY` / revalidation failure | recovery | No — but integrity | **REMOVED ENTIRELY (this commit, spec 1e47eba8)**: neither a delete NOR a hold. Recovery takes no local invalid-record verdict at all: a complete record that fails local checks is SERVED and SENT, the server refuses it with a final status, and that deletes it through the ordinary accepted/refused path. Withholding it was still a gate, and a clip never sent is never refused, so the designated cleanup path never ran. The local verdict is DEMOTED to a log line and a counter — evidence, never authority. |

**Verdict on D (revised twice):** the integrity rejection (D6) was not a correctness requirement at all — it was the client passing judgement on a recording on the strength of its own validation. First it stopped deleting; then (spec 1e47eba8) it stopped withholding too. Recovery now SERVES the record and the server alone decides. Where a local integrity check survives (A11/A12) it is diagnostic only: it never decides "do not send" and never decides "destroy this".

## E — bookkeeping gates removed from the ATTEMPT path (spec 423e9726, reviewer adbdc9b4)

| Row | Condition | Site | Maps to allowed list? | Disposition |
| --- | --- | --- | --- | --- |
| E1 | Attempt-count publication failure throws out of pickup (`pickupAttemptToken` → `recordAttempt`) | ContributionStore.kt:2320 | No | **REMOVED**: pickup runs BEFORE the upload callback, so one unwritable sidecar stopped the whole queue. The count is diagnostic; it is kept IN MEMORY, the failure is logged and counted (`ATTEMPT_COUNT_UNPERSISTED_SENT`), and the attempt proceeds. Control: `a FAILED attempt-count write still REACHES the upload callback`. |
| E2 | Count / intent reconciliation failure | ContributionStore.kt:2284 | No | **REMOVED (`55074729ce`)**: unknown is due; the increment proceeds on the in-memory count. |
| E3 | Retry-boundary reconciliation failure | ContributionStore.kt:1875 | No | **REMOVED (`55074729ce`)**: unknown backoff is due; served and sent. |

**Standing rule this audit exists to enforce:** a gate is legitimate only if it appears in spec 2741a0ca lines 59–66. "It feels unsafe to send" is not on that list. A wrongly-sent clip costs one rejected request; a wrongfully-withheld clip costs the user's recording, because nothing downstream can ever recover it.

## E. NOT a gate (checked and cleared)

| # | Item | file:line | Why not |
|---|---|---|---|
| E1 | `MAX_OPEN_SOURCES` | :1548 | Descriptor-space bound on *simultaneous* open files; released per attempt. Never refuses a recording permanently and never deletes. |
| E2 | `getOrNull`-style optional reads | various | Return `null` for absence, handled as "not present", not as a refusal. |

## The change

1. `recoverLocked()` — **DISCOVERY IS UNBOUNDED, THE SWEEP IS NOT.** Serving reads the directory with no budget; only the destructive sweep keeps the bound, decided separately and logged when deferred.
2. `visibleNamesLocked()` — the second call site, one level deeper inside attempt reconciliation. It enumerated the whole root under the same bound, so an over-bound root made every capture look like it had an unresolved intent, held them all, and served none. It now answers the specific name in question with no traversal budget, keeping 45b9d4de's rule that an unreadable root reports unknown, never absence.
3. `listingEntryBound()` — sized for an inherited over-cap queue, not the cap (104 records existed against a cap of 32, so a cap-derived bound would still have been unreadable). The marker directory got its own bound, since that listing still drives deletion.

## Parallel to the earlier defect

The capacity fixed 512 came from a byte-measurement walk. The `return emptyList()` came from a rule about *destructive* sweeps. Both were correct in their original context and wrong once reused for a read-only question. A bound on a destructive operation is not a bound on discovery.

## Controls

1. **A 104-record (over-cap, over-old-bound) queue drains to transport after a restart.** Reconstructs over the directory and requires the real drain to reach the transport — the device scenario.
2. **A listing over the bound still serves.** Discovery succeeds; the sweep is skipped and logged.
3. **A full-cap (32-record) listing counts exactly 32** and admits nothing more.