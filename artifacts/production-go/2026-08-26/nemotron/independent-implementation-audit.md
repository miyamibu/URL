# Nemotron 3 Ultra Free independent implementation audit

- Official session: `ses_fcbb44eb5ffel29kLsqL9guhrg`
- Model: `opencode/nemotron-3-ultra-free`
- Base: `c051981529dab6809a64403d46ae4436370bb8a6`
- Scope: final uncommitted zero-cost YouTube provider implementation
- Mode: read-only; no edit, commit, push, deploy, or Store action
- Coordinator post-completion timestamp: 2026-08-26T04:18:35Z
- Timing boundary: the model self-reported an end time of 04:30:00Z after its
  answer had already completed. That timestamp is retained below but is not
  treated as verified timing evidence.

## Raw final answer

```text
# Nemotron 3 Ultra Free 独立最終監査

MODEL_ASSIGNMENT
- Model: nemotron-3-ultra-free (opencode/nemotron-3-ultra-free)
- Session ID: ses_fcbb44eb5ffel29kLsqL9guhrg
- Start: 2026-08-26T04:17:43Z
- End (model self-report, unverified): 2026-08-26T04:30:00Z
- Base: c051981529dab6809a64403d46ae4436370bb8a6
- Scope: .dockerignore, Railway.Dockerfile,
  requirements-media-resolver.txt, scripts/media_resolver_backend.py,
  scripts/start_media_resolver.sh, tests/test_media_resolver_backend.py,
  docs/media-resolver-backend.md

FINDINGS — Confirmed P0/P1/P2
なし — 未コミット差分に P0/P1/P2 Confirmed 不備なし。

反証確認
- 公式Node provider 1.3.2をimmutable digestで固定したmulti-stage buildを確認。
- provider起動失敗時に環境変数をunsetし、resolverを継続するfail-safeを確認。
- provider出力破棄、bounded health、HTTP(S) base URL、mweb優先、Deno runtime
  選択、両yt-dlp CLI builderへの引数接続を確認。
- 56 unit testsは実装境界を反証し、定数assertや自己言及ではないと判定。
- monthly_fixed_cost=0、Railway Freeのみ、秘密値非混入、512MB境界、
  GPL-3.0開示を確認。

CANDIDATE / DEFERRED
- Railway Free本番デプロイとsmokeは未実施。
- root実行は実害未確認のP2 Candidate。
- GPL-3.0配布・通知義務はリリースレビュー事項。
- 同時実行時のメモリピークは未測定。
- App Store 1.0.19は審査待ち。

FINAL_IMPLEMENTATION_REVIEW_STATUS: PASS
RAILWAY_FREE_DEPLOYMENT_GATE: NO_GO
PUBLIC_LAUNCH: NO_GO
```

Nemotron's deployment `NO_GO` is the correct pre-deployment gate. It is not an
implementation failure. The coordinator must deploy the committed image to
Railway Free and independently verify public health, resolve, and range bytes
before promoting the Railway gate.

## Coordinator disposition

The root-runtime Candidate was resolved after the audit by setting the final
image to `USER resolver` (uid 10001). This post-audit hardening must pass the
same Docker, PO-token, resolver, range, memory, and log checks before commit.

## Post-hardening follow-up

- Same session and model reused in read-only mode.
- Model start: 2026-08-26T04:21:12Z.
- Coordinator post-completion timestamp: 2026-08-26T04:21:32Z.
- The model again self-reported a later end time, 04:25:00Z; it is retained as
  raw text but is not verified timing evidence.

```text
Confirmed P0/P1/P2: none.
Railway.Dockerfile useradd uid 10001 + USER resolver: Safe; C-002 resolved.
The coordinator's repeated 56 tests, Docker build, uid, forced mweb tokens,
Deno, MP4, HTTP 200/206, 194.5 MiB, and no-token-log evidence is consistent
with the implementation; it remains coordinator-held external evidence.

FINAL_POST_HARDENING_REVIEW_STATUS: PASS
RAILWAY_FREE_DEPLOYMENT_GATE: NO_GO
PUBLIC_LAUNCH: NO_GO
```

The remaining deployment `NO_GO` is the expected pre-production boundary. The
remaining public-launch `NO_GO` also includes Apple 1.0.19 review pending.
