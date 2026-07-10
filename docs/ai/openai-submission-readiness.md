# OpenAI Submission Readiness

## Scope
OpenAI submission is a manual external action. Codex does not submit, publish, deploy, or enter production secrets.

Official references to recheck at submission time:

- https://developers.openai.com/apps-sdk/app-submission-guidelines
- https://developers.openai.com/apps-sdk/deploy/submission
- https://developers.openai.com/apps-sdk/reference

## Required External Account / Permission

- OpenAI/ChatGPT account with app/plugin submission access.
- Workspace permission for Developer Mode and app management.
- Ability to configure auth/OAuth.
- Ability to provide public privacy policy URL and support/contact metadata.

## Identity Verification

- Complete any required OpenAI account or organization verification before submission.
- Keep identity documents and account credentials outside repo/chat.

## App Management Permissions

- Confirm the account can create developer-mode apps.
- Confirm the account can submit the app/plugin when ready.
- If permissions are missing, classify as `BLOCKED_EXTERNAL`.

## Public HTTPS MCP Server

- MCP server must be public HTTPS for submission.
- Staging must pass smoke first.
- Production MCP must remain disabled until explicit launch window approval.

## Auth

- Auth required.
- Invalid/no token rejected.
- User boundary enforced for every search/fetch/list operation.
- No anonymous personal data.

## Privacy Policy URL

- Must be public, stable, and match actual AI/MCP data flow.
- Placeholder is not acceptable for submission.

## App Name / Description

- Name: `りんばむ saved links`.
- Description: read-only connector for the authenticated user's eligible saved links.
- Avoid claiming write, sync, or full web browsing features.

## Screenshots

Prepare screenshots for:

- Connector settings.
- Auth flow.
- Search prompt.
- Fetch saved snapshot prompt.
- Shared-tag exclusion prompt.
- Error/not-found prompt.

## Test Prompts / Responses

Use `docs/ai/openai-apps-developer-mode-test-plan.md`. Save sanitized responses and ensure no raw body/prompt/token appears.

## Tool Annotations

- `readOnlyHint: true`
- `destructiveHint: false`
- `openWorldHint: false`
- `idempotentHint: true`

Missing or null annotations are a validation error.

## Do Not Submit If

- Any write tool exists.
- The connector acts as a generic OpenAI proxy.
- raw `fetchedBody` is returned by default.
- shared-tag data is included by default.
- auth is missing or can fail open.
- privacy policy/store disclosures are stale.
- rollback/incident plan is missing.
- production secrets are in repo, screenshots, logs, or chat.

## Submit / Do Not Submit Decision Checklist

- [ ] Public HTTPS MCP endpoint verified.
- [ ] Auth required and invalid token rejected.
- [ ] User boundary test passed.
- [ ] Descriptor annotations validated.
- [ ] No write tools.
- [ ] No raw body default.
- [ ] shared tag default exclusion verified.
- [ ] Privacy policy URL verified.
- [ ] Developer Mode prompt evidence saved.
- [ ] Rollback plan reviewed.
- [ ] Release owner approves manual submission.
