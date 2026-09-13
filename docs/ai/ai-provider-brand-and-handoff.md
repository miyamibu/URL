# AI Provider Brand And Handoff

## Goal

ホームの `AI` chooserで表示するprovider名、ブランドasset、外部handoff境界を記録する。

## Provider contract

| Provider | Display name | Official destination | Asset decision |
|---|---|---|---|
| OpenAI | `ChatGPT` | `https://chatgpt.com/` | 公式配布assetの原本と利用条件を固定できるまではtext-only |
| Google | `Gemini` | `https://gemini.google.com/` | 公式配布assetと利用条件を固定できるまではtext-only |
| Anthropic | `Claude` | `https://claude.ai/new` | 公式配布assetと利用条件を固定できるまではtext-only |
| DeepSeek | `DeepSeek` | `https://chat.deepseek.com/` | logoを同梱せずtext-only |

## Handoff behavior

- provider選択は、既存のAI-safe ZIP生成、preview、明示確認、redaction、snapshot再検証を切り替えない。
- AndroidのChatGPTは既存のアプリ直接共有を試し、利用できない場合はOS共有へfallbackする。
- AndroidのGemini / Claude / DeepSeekとiOSの全providerはOS標準の共有先選択を使用する。
- official destinationは参照先であり、ZIP自動添付や送信成功を意味しない。
- provider API、OAuth、MCP、質問の自動入力、自動送信はこの導線へ追加しない。

## Asset adoption gate

ロゴを追加する場合は、providerごとに公式配布元、利用条件、取得日、原本URL、asset checksum、無加工であること、Light/Dark背景、clear space、optical sizeを記録する。条件を満たせないproviderはtext-onlyのままにする。
