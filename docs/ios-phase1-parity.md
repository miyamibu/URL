# iOS Phase 1a/1b Parity Notes

Goal
- Android の Phase 1a/1b 契約を iOS ネイティブ実装へ移植する際の、実装済み範囲と unavoidable gap を明示する。

Context
- 実装場所は `ios/`。
- Android の source of truth は `AGENTS.md`, `README.md`, `docs/phase1a-spec.md`, `docs/phase1b-draft.md`。

Constraints
- `normalizedUrl` unique、`openUrl = normalizedUrl`、card tap = Detail only、metadata-only update does not touch `updatedAt` は崩さない。
- iOS 独自の UI は許容するが、duplicate / delete grace / metadata copy の意味は揃える。

Done when
- parity target、iOS adaptation、既知 gap が短く確認できる。

## Implemented parity

- 単一 URL 保存
- Share Extension からの複数 URL 順次保存 + 集計 handoff
- 手動貼り付け保存
- `normalizedUrl` DB unique
- Main / Archive / Detail
- Main の右スワイプ archive / 左スワイプ pending delete + Undo
- persisted pending delete cleanup
- `userTitle` / `memo` 制約
- metadata state copy (`取得中`, `一時的に取得できません`, `自動取得できません`)

## iOS adaptations

- Share intake は `NSExtensionItem` / `NSItemProvider` を Android の source priority に相当する candidate groups へ変換する。
- 保存結果通知は extension UI と shared handoff report の両方を使う。
- metadata scheduling は app-active 時の即時 enqueue と `BGTaskScheduler` の best-effort を併用する。

## Real parity gaps

- iOS share sheet では Android の `ACTION_SEND` / `ACTION_SEND_MULTIPLE` フラグがないため、single-share と multi-share の完全一致判定はできず、payload 数ベースの近似になる。
- Share Extension から host app を Android のように即時遷移させる保証はないため、通知の表示タイミングは app 復帰時になる場合がある。
- Android WorkManager の retry/backoff 時刻そのものは iOS の `BGTaskScheduler` では保証できない。
