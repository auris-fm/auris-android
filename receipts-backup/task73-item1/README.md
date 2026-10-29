
## OPEN FOLLOW-UP (claude review minor 8, 2026-10-24)
`upload-suite-536-flaky1.log` records a single failure of `ContributionUploadAdmissionRaceTest >
a revocation landing after registration cancels the call that is already in flight` (AssertionError
at line 200) that passed on isolated and full reruns. UNDIAGNOSED intermittent race — the green
reruns do NOT explain it. Hardening to a deterministic interleaving is tracked as the next task73
lane item (promised to spec at 07:45Z).
