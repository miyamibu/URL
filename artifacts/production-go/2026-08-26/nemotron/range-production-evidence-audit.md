# Nemotron 3 Ultra Free independent Range production-evidence audit

- Coordinator-observed completion: 2026-08-26 (Asia/Tokyo)
- Model: `opencode/nemotron-3-ultra-free`
- Session: `ses_fcbb44eb5ffel29kLsqL9guhrg`
- Model-reported start: 2026-08-26T04:56:17Z
- Model-reported end: 2026-08-26T05:00:00Z
- Implementation: `b7c1022b1a99cd67a51690a8d4b24b7bb4ac08d6`
- Mode: independent read-only audit; Ox output was not supplied
- Top constraint: `monthly_fixed_cost = 0`

## Captured result

Nemotron independently reconciled the implementation commit, Range code,
tests, zero-cost production record, Railway Free deployment
`da76813b-e4b7-454b-85f6-89765df118f0`, two-video `/files` Range results,
Render Free delegation, non-root container evidence, and bounded log evidence.
It also verified that the earlier full-file HTTP 200 observation invalidated the
prior GO until the new implementation was deployed and re-run.

- Confirmed P0/P1/P2: none
- `FINAL_RANGE_PRODUCTION_EVIDENCE_REVIEW_STATUS: PASS`
- `ZERO_COST_YOUTUBE_PRODUCTION_GATE: GO`
- `CI_GATE: PENDING` at the first audit snapshot because Android/iOS had not
  both completed; the model did not count pending jobs as GO
- `PUBLIC_LAUNCH: NO_GO_APPLE_REVIEW_PENDING`

## CI completion update

The coordinator subsequently verified through GitHub CLI that run
`32931526266`, head
`b7c1022b1a99cd67a51690a8d4b24b7bb4ac08d6`, completed successfully with all
five jobs successful. Nemotron's same session updated its conclusion to
`CI_GATE: PASS`, but explicitly reported that this CI value was accepted from
coordinator evidence rather than independently queried in its worker. That
attribution boundary is retained here.

The model-reported timestamps are retained as self-reported metadata. The
coordinator-owned GitHub CLI result, rather than the model's restatement, is
the primary CI evidence.
