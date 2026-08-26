# Ox Alpha Free byte-range counter-review

- Coordinator-observed completion: 2026-08-26 (Asia/Tokyo)
- Model: `opencode/x-preview-f-free`
- Session: `ses_fcbdef8b7ffeswhlbo3XfnYP1b`
- Mode: read-only counter-review; no edit, commit, push, or deploy authority
- Scope: the uncommitted `/files` Range implementation above baseline
  `83142ef24e4c64cf27cf231e71a4013c54a18c28`
- Top constraint: `monthly_fixed_cost = 0`

## Captured result

Ox independently inspected the implementation and tests, reran the 60-test
resolver suite, ran `git diff --check`, and adversarially probed normal,
open-ended, suffix, clamped, reversed, multiple, empty-file, 20-digit,
21-digit, 4,300-digit, and 5,000-digit Range values. It measured 10,000 huge
Range rejections in 144.9 ms in that worker environment and found no exception.

It confirmed that a valid single Range returns 206 with exact
`Content-Range`, `Accept-Ranges`, and `Content-Length`; invalid,
unsatisfiable, and multiple ranges return 416; streaming is bounded to the
requested byte count; and a request without Range remains a full HTTP 200.

- Confirmed P0/P1/P2: none
- `OX_RANGE_REVIEW_STATUS: PASS`
- `RAILWAY_FREE_DEPLOYMENT_GATE: NO_GO_UNTIL_DEPLOYED`
- `PUBLIC_LAUNCH: NO_GO_APPLE_REVIEW_PENDING`

The coordinator retained the deployment gate: this implementation review does
not constitute Railway production proof.
