# Zero-cost YouTube provider local production-equivalent validation

- Date: 2026-08-26 (Asia/Tokyo)
- Baseline: `83142ef24e4c64cf27cf231e71a4013c54a18c28` plus the audited Range diff
- Image: `rinbam-media-resolver:range-fix-final`
- Dockerfile: `Railway.Dockerfile`
- Plan constraint: Railway Free only, `monthly_fixed_cost = 0`

## Validation results

| Check | Result |
|---|---|
| `bash -n scripts/start_media_resolver.sh` | PASS |
| `git diff --check` | PASS |
| `python3 -m unittest tests.test_media_resolver_backend` | 60 passed, 0 failed |
| Final Docker image build | PASS |
| Runtime privilege | final process runs as non-root user `resolver` (uid 10001) |
| `/health` in final single container | HTTP 200; PO provider configured and reachable |
| Forced yt-dlp client | `mweb` |
| Automatic PO tokens | gvs and subs tokens retrieved through bgutil HTTP provider |
| JavaScript challenge | Deno 2.4.1 detected and used |
| Forced media format | `jNQXAC9IVRw|18|https|mp4` |
| Normal resolver endpoint | HTTP 200, `ok=true`, `video/mp4`, 0.232 seconds observed |
| Proxy range | HTTP 206, 1,024 bytes, `Content-Range: bytes 0-1023/629172` |
| Cached-file range | HTTP 206; `bytes=2-5` returned exactly 4 bytes with `Content-Range: bytes 2-5/1905` |
| Invalid/multiple/oversized range | HTTP 416 or parser rejection; 10,000-digit integers are rejected before decimal conversion |
| Container memory after final non-root smoke | 176 MiB observed, below the Railway Free 512 MB limit |
| Container log scan | no PO-token material detected |
| Missing-provider fail-safe | resolver remained HTTP 200; provider status was `configured=false`, `reachable=false` |

The memory value is a single-request observation, not a concurrency or long-run
capacity guarantee. The Range delta remains production-unverified until its
committed image deploys on Railway Free and public `/resolve` plus `/files` or
`/proxy` byte-range smokes pass.
