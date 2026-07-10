# OpenAI Apps Developer Mode Test Plan

## Purpose
Connect ChatGPT Developer Mode to the staging read-only MCP endpoint and verify expected behavior before any OpenAI submission. This is a manual external-account step; Codex does not create, submit, or publish the app.

Official references to recheck at test time:

- https://developers.openai.com/api/docs/guides/developer-mode
- https://developers.openai.com/apps-sdk/deploy/connect-chatgpt
- https://developers.openai.com/apps-sdk/build/mcp-server
- https://developers.openai.com/apps-sdk/reference

## MCP URL

Placeholder:

```text
https://staging.example.com/api/mcp
```

Use staging first. Do not use production unless the release owner has approved a production smoke window.

## Auth / OAuth Preparation

- MCP must require auth.
- Noauth must return 401 or safe disabled response.
- OAuth or bearer-token setup must bind to one user.
- Invalid token must be rejected.
- Token values must not be pasted into docs, screenshots, chat, or repo files.

## Connector Metadata

| Field | Draft value |
|---|---|
| Connector name | りんばむ saved links |
| Connector description | Read-only search and fetch for the authenticated user's eligible saved links in りんばむ. It does not write, delete, publish, or fetch live URLs. |
| Privacy policy URL | `https://example.com/privacy/` placeholder until owner verifies the public URL. |
| MCP URL | staging HTTPS URL above. |

## Test Prompts

| Prompt | Expected behavior |
|---|---|
| りんばむで最近保存したAI関連リンクを探して | Calls `search` or recent-list read-only tool. Returns only current user's eligible local saved links, with summaries/excerpts, not raw body. |
| りんばむでこの保存リンクの保存時スナップショットを出して | Calls `fetch` for a provided publicSafeId. Shows saved-time title/author/summary/excerpt and saved snapshot notice. |
| りんばむで共有タグを除外して検索して | Search excludes shared-tag-derived URLs and shared tags by default. |
| りんばむでタグ一覧を出して。ただし共有タグは含めないで | Calls `rinbam.list_tags`. Returns local personal tags only. |
| りんばむで存在しないリンクをfetchして | Returns a not-found or safe error. Does not leak other users' data or raw IDs. |

## Screenshots To Capture

- Developer Mode enabled state.
- Connector/app metadata screen.
- MCP URL/auth configuration with secrets hidden.
- Each prompt response.
- A noauth or invalid-token rejection from a sanitized curl/browser test if available.

## Failure Handling

- Disable staging MCP with `URLSAVER_MCP_ENABLED=false` if any personal data is returned without auth.
- Stop if write tools appear in the connector.
- Stop if raw body, raw prompt, token, refresh token, or attachment content appears.
- Stop if shared-tag data appears by default.
- Stop if Developer Mode is unavailable for the account/workspace; classify as `BLOCKED_EXTERNAL`.

## Stop Conditions Before OpenAI Submission

- No public HTTPS MCP endpoint.
- No verified auth/user boundary.
- Tool annotations missing or inconsistent.
- Privacy policy URL not verified.
- Screenshots or test prompt evidence missing.
- Any generic OpenAI proxy or write tool is present.
- Incident/rollback plan is missing.
