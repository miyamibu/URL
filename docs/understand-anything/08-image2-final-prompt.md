# image-2 Final Prompt

## Prompt
黒背景の横長インフォグラフィック。題材は「URL共有アプリ_UrlSaver」。中央に親しみやすい案内役キャラクターがいて、金色の光の線で複雑なコードベースを分かりやすい地図へ変換している。左側にはAndroid、iOS、Share Extension、Room、SQLite、Supabase、Worker、Testが絡み合った複雑なコードの森。中央には「理解ツアー」「正規化ゲート」「同期の整理」という雰囲気。右側には大きく整理された構造図を配置し、スマホでも読める大きな日本語ラベルで、URL保存、URL正規化、重複判定、Main / Archive / Detail、Metadata取得、Shared Tag Sync、Android、iOS、DB、Worker、External、Testを示す。下部には「触ると危ない: normalizedUrl / openUrl / DB schema / apply-pull / updatedAtルール」と短く表示する。細かいコードの羅列ではなく、関係性を見せる。非エンジニアにも分かる説明画像。高品質、明瞭、余白あり、金色の光、ツアー感、Understand Anything風のコード地図化。

## Negative Prompt
実在するAPIキー、シークレット、メールアドレス、個人情報、細かすぎて読めない小文字、過剰なコード羅列、紫一色の単調な画面、ホラー調、暗すぎる文字、共有タグが全カードやmetadataまで完全同期するような誤解表現、本番DBへ接続している描写。

## Layout Notes
- 左35%: 複雑なコードベース。Android/iOS/Supabase/DB/Workerのノードが絡む。
- 中央25%: 案内役キャラクターと金色の変換ライン。
- 右40%: 整理済みのレイヤーマップ。
- 下部: 危険領域チェックリスト。
- 文字は大きく、短く、日本語中心。

## Text Labels to Include
- URL共有アプリ_UrlSaver
- URL保存
- URL正規化
- 重複判定
- Main / Archive / Detail
- Metadata取得
- Shared Tag Sync
- Android
- iOS
- DB
- Worker
- External
- Test
- 触ると危ない
- normalizedUrl
- openUrl
- DB schema
- apply / pull
- updatedAtルール

## Color Rules
- UI: 青
- API: 紫
- Service/Logic: 緑
- DB/Data: オレンジ
- Worker/Cron: 黄
- Config/Env: グレー
- External: 赤
- Test: 薄グレー
- 案内/変換ライン: 金色

## Expected Output
非エンジニアが1枚で「このアプリはURLを正規化して保存し、metadataは裏で取得し、共有タグだけを同期する。危険なのはURL同一判定、DB、metadata状態、Shared Tag Syncである」と理解できる横長の説明画像。
