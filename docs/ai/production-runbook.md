# AI / MCP Production Runbook

## Goal
Separate repo readiness from external publication.

## Context
`REPO_GO` means the repo-local code, docs, scripts, tests, build, and release hygiene are ready for owner-controlled external steps. It does not mean deploy, OpenAI submission, store submission, production secret entry, or live/store verification has happened.

## Repo Gate
1. Android unit/lint/build pass.
2. iOS build/test pass, or environment-only Simulator failure is classified as `NOT_VERIFIED`.
3. MCP typecheck and contract script pass.
4. Mobile UI contract and release hygiene pass.
5. Secret scan finds no committed production secrets.
6. `URLSaver-clean-review.zip` is generated outside repo root by default and excluded from git.

## Manual Steps
| Step | Owner action |
|---|---|
| Production deploy | Deploy web/admin and public pages after reviewing env and routing. |
| Production OAuth/MCP registration | Register provider/client/scopes and verify callback URLs. |
| OpenAI submission | Submit after deployed endpoint and privacy review. |
| Store submission | Select build, fill review forms, submit in Play/App Store Connect. |
| Production secrets | Enter only in external secret stores; do not commit or paste into chat. |
| Store/live recheck | Verify public URLs and console state after submission/deploy. |

## iOS Simulator Failure Classification
- Compile/assert/migration/schema failure: `NO_GO_INTERNAL`.
- `build-for-testing` PASS but `test-without-building` fails only with CoreSimulator Busy/preflight: `NOT_VERIFIED` unless a clear external outage is proven.
- Test PASS: iOS test blocker resolved.

## Rejected Defaults
- Login with ChatGPT is Docs only / adoption review, not the app login system.
- YouTube/TikTok/X unofficial video download is not part of AI/link-death remediation.
- Wayback/headless/browser external resolver is default off or docs-only.

## Failure handling
Do not mix external manual work into internal blockers. If repo-local validation fails, use `NO_GO_INTERNAL`; if only external publication remains, keep the repo classification `REPO_GO` and list Manual steps.
