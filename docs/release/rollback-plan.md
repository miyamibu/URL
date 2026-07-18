# Rollback Plan

## Goal
Disable unsafe AI/MCP/store release behavior quickly without deleting user data.

## Disable AI Transparency Flags

- Android release default is off. If a future remote flag exists, set it off.
- Confirm normal UI no longer shows AI entry.
- Do not delete user saved URLs.

## Disable Manual ChatGPT Handoff

- Manual `ChatGPTに聞く` is an app-build feature, not the MCP/provider flag. Disabling `URLSAVER_MCP_ENABLED` does not remove this local ZIP + OS-share entry.
- If its preview, redaction, confirmation, or archive contract is unsafe, halt the Android internal rollout / expire the iOS TestFlight build and return testers to a known-good build. Removing the entry requires an approved replacement app build unless an independently verified remote flag is added later.
- The manual ChatGPT handoff currently has no independently verified remote kill switch. Do not claim it can be disabled through MCP/provider flags. For a privacy or attachment incident, stop the affected rollout, expire the affected TestFlight build where possible, and ship an approved replacement build before resuming.
- A ZIP already sent to ChatGPT or another recipient cannot be recalled by disabling MCP. Follow the recipient's deletion path and the incident process; do not delete local user data as a substitute.

## Disable MCP Flag

1. Set `URLSAVER_MCP_ENABLED=false`.
2. Redeploy/restart the web/admin service.
3. Confirm `GET /api/mcp` returns 404 `mcp_disabled` or safe auth response.
4. Confirm ChatGPT connector cannot fetch data.

## Revoke MCP Tokens

- Revoke OAuth/client credentials in the provider console.
- Invalidate staged bearer tokens.
- Save only token IDs or timestamps, never token values.

## Rotate HMAC/Auth Secret If Needed

- Rotate `URLSAVER_MCP_ID_SECRET` if exposed.
- Rotate OAuth/client secret if exposed.
- Re-run smoke tests before re-enable.

## Revert App Build

- Android: stop rollout or move testers to previous internal build in Play Console.
- iOS: expire TestFlight build or stop testing the build in App Store Connect.
- Do not delete user data as rollback unless there is explicit owner approval and a backup/recovery plan.

## Remove / Disable Connector

- Disable Developer Mode app or production connector.
- Remove MCP URL from connector settings if needed.
- Confirm prompts no longer call tools.

## Disable Staging Endpoint

- Set staging `URLSAVER_MCP_ENABLED=false`.
- Optionally remove staging route or block it at edge/firewall.

## Rollback Android Internal Test

1. Open Play Console internal testing.
2. Halt bad build availability.
3. Restore previous known-good internal build if needed.
4. Notify testers with user communication draft.

## Rollback TestFlight

1. Open App Store Connect TestFlight.
2. Expire or remove bad build from tester group.
3. Keep previous known-good build active if available.
4. Notify testers with user communication draft.

## User Communication Draft

> りんばむのテスト機能で問題を確認したため、一時的に該当機能を停止しました。保存済みURLの通常利用には影響しないよう対応しています。追加確認が終わり次第、必要な修正版または再開予定をお知らせします。

## Incident Note Template

```text
Incident:
Detected at:
Detected by:
Affected surface:
User impact:
Immediate action:
Flags disabled:
Tokens/secrets rotated:
Build rollback action:
Evidence saved:
Current status:
Next owner:
```

## Postmortem Checklist

- [ ] Root cause identified.
- [ ] Scope of affected users/data confirmed.
- [ ] Raw prompt/body/token exposure checked.
- [ ] Store/OpenAI/privacy disclosure impact reviewed.
- [ ] Regression test or checklist row added.
- [ ] Re-enable criteria documented.
