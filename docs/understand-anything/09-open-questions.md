# Open Questions

| 未確定事項 | なぜ未確定か | 追加で見るべき場所 | 影響 | 優先度 |
|---|---|---|---|---|
| 起動直後にカード表示が遅くなった主因 | コード読解だけではDB件数、実機I/O、sync pull、metadata backlogの比率が分からない | 実機ログ、`URLSaverAppModel.swift`, `MainListViewModel.kt`, DBコピー | UX低下、起動不安 | 高 |
| 共有タグ同期の現在の開発/本番設定値 | `.env*`値やInfo.plist置換値は表示していないため | BuildConfig生成結果、iOS scheme、Supabase local config | 同期可否 | 高 |
| 共有タグ詳細の「項目が見つかりません」の最新再現条件 | 今回は実機操作をしていないため | `TagDetailViewModel.kt`, `SharedTagCloud.swift`, 実機DB | 共有タグカード表示不能 | 高 |
| iOS Share Extension保存とHost App handoffの失敗率 | コード上はfallbackがあるが、実機OS挙動は未検証 | `ShareViewController.swift`, app group container, console logs | iOS共有保存失敗 | 中 |
| TikTok metadata取得の実サイト依存 | 外部サイトのレスポンスは変動するため | `MetadataFetcher.kt`, `MetadataFetcher.swift`, fixture/live HTML | サムネ/アイコン欠落 | 中 |
| Understand-Anything dashboardの利用可否 | skillは存在するがslash command実行経路が本セッションで未確認 | `~/.agents/skills/understand-dashboard`, Codex UI設定 | ダッシュボード自動生成 | 中 |
| image-2画像のファイル直接保存 | この環境の画像生成結果をrepo内PNGとして保存できるか未確認 | 画像生成ツールの出力仕様 | `visual/url-saver-understand-anything.png`未作成 | 低 |
| Supabase RLSの実運用検証 | SQL定義は読んだが本番/開発DBへ接続していない | `supabase/tests/shared_tag_sync_validation.sql`, staging環境 | 認可/同期安全性 | 高 |
