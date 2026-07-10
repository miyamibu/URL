# AI-safe Export Contract

## Goal
Android / iOS のAI-friendly exportが、AIに渡しやすく、復元バックアップと混同されない形式で出力されることを保証する。

## Context
Exportはユーザー操作で生成されるローカルartifact。現在の実装形式は ZIP と JSON。

## ZIP Contract
必須ファイル:
- `manifest.json`
- `entries.jsonl`
- `entries/*.md`
- `schema.json`
- `README_FOR_AI.md`
- `redaction_report.json`

## Entry Fields
主要フィールド:
- `publicSafeId`
- `originalUrl`
- `normalizedUrl`
- `openUrl`
- `providerPermalink`
- `providerCanonicalId`
- `effectiveTitle`
- `fetchedAuthorName`
- `fetchedBodyKind`
- `bodySummary`
- `bodyExcerpt`
- `memoExcerpt`
- `metadataSource`
- `metadataFetchedAt`
- `savedSnapshotNotice`
- `sharedTagBoundary`
- `aiEligible`
- `aiExclusionReason`
- `redactionApplied`

## Constraints
- `fetchedBody` full body is not exported.
- `bodyExcerpt` and `memoExcerpt` are capped at 1000 characters.
- Email, phone, token-like values, Supabase URLs/JWT-like tokens, and local paths are redacted.
- Shared-tag entries are marked `aiEligible=false` by default.
- Archived and pending-delete entries are marked `aiEligible=false` by default.
- `savedSnapshotNotice` is emitted when saved metadata is present: 保存時点の情報であり、現在の内容とは異なる可能性があります。
- Export is not a restore backup.

## Validation method
- Android: `ExportRepositoryTest.prepareExport_zipIncludesAiSafeFilesAndExcludesRawFetchedBody`
- Android: `ExportRepositoryTest.prepareExport_sharedTagEntryIsMarkedAiIneligibleByDefault`
- iOS: `ExportArchiveBuilderTests.testZipOutputIsAiSafeAndDoesNotExportRawFetchedBody`
- iOS: `ExportArchiveBuilderTests.testSharedTagEntryIsMarkedAiIneligibleByDefault`

## Failure handling
If mandatory files, snapshot notice, or redaction fields are missing, do not claim AI-safe export readiness. Classify the repo as `NO_GO_INTERNAL`.
