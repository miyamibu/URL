# AI Provider Brand And Handoff

## Goal

ホームの `AI` chooserで表示するprovider名、ブランドasset、外部handoff境界を記録する。

## Provider contract

| Provider | Display name | Official destination | Asset decision |
|---|---|---|---|
| OpenAI | `ChatGPT` | `https://chatgpt.com/` | 公式Blossom原本。白い図形は暗い中立色の背景上で表示 |
| Google | `Gemini` | `https://gemini.google.com/` | Gemini公式ページが指定するGoogle CDNの星形アイコン |
| Anthropic | `Claude` | `https://claude.ai/new` | Anthropic公式press kitのClaude角丸アイコン |
| DeepSeek | `DeepSeek` | `https://chat.deepseek.com/` | DeepSeek公式ページのfavicon原本 |

2026-10-08のユーザー依頼と画像案承認により、各行の左先頭へ公式アイコンを表示する。原本、PNGへの形式変換、取得日、SHA-256、利用条件参照は [公式アイコンの取得元](ai-provider-icon-sources.md) に記録する。生成画像内のブランド図形は実装資産に使わない。

## Handoff behavior

- provider選択は、既存のAI-safe ZIP生成、preview、明示確認、redaction、snapshot再検証を切り替えない。
- 専用画面は自作タグ、対象/除外件数、選択providerへ送る単一ボタンを中心にする。押下を表示対象への明示確認とし、snapshot再検証、ZIP生成、OS共有を続けて実行する。
- 全URL/JSON、確認チェック、ZIP作成ボタン、生成ファイル名、長い説明は常設しない。送る情報の短い説明は開閉できる補足として残す。
- AndroidのChatGPTは既存のアプリ直接共有を試し、利用できない場合はOS共有へfallbackする。
- AndroidのGemini / Claude / DeepSeekとiOSの全providerはOS標準の共有先選択を使用する。
- official destinationは参照先であり、ZIP自動添付や送信成功を意味しない。
- provider API、OAuth、MCP、質問の自動入力、自動送信はこの導線へ追加しない。

## Asset adoption gate

ロゴを追加する場合は、providerごとに公式配布元、利用条件参照、取得日、原本URL、asset checksum、形や色を加工していないこと、Light/Dark背景、clear space、optical sizeを記録する。画像は縦横比を維持し、名称を併記してサービスの識別に使う。今回のローカル実装と検証は公開・配布承認を主張せず、release時のブランド条件確認とは分ける。
