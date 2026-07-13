# AI Receipt / Draft / Diff Contract

## Goal
AI Preview、Receipt、Draft、Diff proposalを、Androidの `DEBUG && AI_TRANSPARENCY_ENABLED` と iOSの `DEBUG`/Info.plist flag に限定した Debug-only UI として、通常のRelease UIには出さずrepo内で検証可能にする。

## Context
AndroidはRoom、iOSはSQLiteにローカル保存する。production AI providerやOpenAI submissionはManual stepsであり、このrepo内契約には含めない。
ChatGPT個人リンク同期カードはこのAI透明化UIとは別に、Android/iOS の共有タグプロフィール画面に表示する。外部接続未設定時は「現在は利用できません」と表示し、操作不可とする。
MCP/Store/Supabase live は未検証の外部ゲートであり、repo内のローカル実装/検証とは分離する。

## Constraints
- Feature flag default off。Androidはdebug opt-in、releaseはoff。iOSはdefault off。
- Receiptはmetadataのみ。raw prompt、raw fetchedBody、token、attachmentは保存しない。
- request/response sizeは正確値ではなく `ZERO/TINY/SMALL/MEDIUM/LARGE/HUGE` bucketで保存する。
- Draftはローカル保存のみ。Supabase同期しない。
- Diff proposalは表示/保存できるが、明示confirmなしに本体DBへ反映しない。
- Apply対象は許可フィールドだけ。現状は `userTitle` と `memo`。
- shared tag、archived、pending deleteはdefault AI対象外。
- provider未設定時はdeterministic `MockAiProvider` を使い、ネットワーク送信しない。
- ローカルデータ削除/アカウント削除ではReceipt/Draft/Diffを削除する。

## Done when
- Android/iOS両方でReceipt/Draft/Diffの保存、読込、削除、confirm-gated applyがテストされている。
- AI透明化のentryは Android の `DEBUG && AI_TRANSPARENCY_ENABLED`、iOS の `DEBUG`/Info.plist flag でのみ表示され、通常のRelease UIには出ない。
- raw prompt/body/tokenを保存していないことがテストで固定されている。

## Validation
- Android: `./gradlew testDebugUnitTest --tests jp.mimac.urlsaver.AiTransparencyRepositoryTest`
- iOS: `xcodebuild ... test-without-building` on a dedicated simulator.

## Failure handling
Receiptにraw payloadが混ざる、Diffがconfirmなしに適用される、またはshared/archived/pending deleteがAI対象になる場合は `NO_GO_INTERNAL`。
