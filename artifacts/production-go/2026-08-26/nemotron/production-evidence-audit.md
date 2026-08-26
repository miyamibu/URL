# Nemotron 3 Ultra Free production-evidence audit

- Session: `ses_fcbb44eb5ffel29kLsqL9guhrg`
- Model: `opencode/nemotron-3-ultra-free`
- Implementation HEAD: `b33a6c76806fc8b87efd95b211be02f82fedbc26`
- Mode: read-only evidence consistency review
- Model start: 2026-08-26T04:32:10Z
- Coordinator post-completion timestamp: 2026-08-26T04:33:22Z
- Timing boundary: the model self-reported a later end time of 04:38:00Z. The
  value is retained as raw text but is not treated as verified timing evidence.

## Raw final summary

```text
Railway Free:
- deployment 50be593b-5765-4e63-ba33-24ce95c494b8 Active
- health 200 / provider configured and reachable
- resolve 200 / MP4 / 0.705 seconds
- proxy 206 / 1,024 of 629,172 bytes
- no token material in deployment logs

Render Free:
- deployment dep-da76lj142hec73afne3g Live
- health 200 / source b33a6c7 / delegate configured
- Render-to-Railway resolve 200 / MP4 / 1.289 seconds
- proxy 206 / 1,024 of 629,172 bytes

Evidence attribution, deployment IDs, latency, byte counts, monthly_fixed_cost
= 0, no paid action, and Apple 1.0.19 review-pending status are consistent.
The external results are coordinator-held evidence and were not claimed as a
direct Nemotron observation.

Confirmed P0/P1/P2: none.
FINAL_PRODUCTION_EVIDENCE_REVIEW_STATUS: PASS
ZERO_COST_YOUTUBE_PRODUCTION_GATE: GO
PUBLIC_LAUNCH: NO_GO_APPLE_REVIEW_PENDING
```

After this review completed, the coordinator also resolved a second public
video (`M7lc1UVf-VE`) on Railway Free and received HTTP 200 / MP4 followed by
HTTP 206 for 256 of 43,776,160 bytes. This is additional coordinator evidence,
not a Nemotron observation.
