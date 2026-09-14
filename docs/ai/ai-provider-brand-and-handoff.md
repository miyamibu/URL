# AI Provider Brand And Handoff

## Goal

ホームの `AI` chooserで表示するprovider名、ブランドasset、外部handoff境界を記録する。

## Checked on

2026-08-29（Asia/Tokyo）

## Provider contract

| Provider | Display name | Official destination | Brand source | Current asset decision |
|---|---|---|---|---|
| OpenAI | `ChatGPT` | `https://chatgpt.com/` | `https://openai.com/brand/` | 公式download assetの原本・利用条件・checksumをrepo内で固定できるまではtext-only。非公式iconや再描画SVGは使わない |
| Google | `Gemini` | `https://gemini.google.com/` | `https://about.google/brand-resource-center/products-and-services/` | Gemini固有の再配布可能assetと利用条件を公式resourceで特定できていないためtext-only |
| Anthropic | `Claude` | `https://claude.ai/new` | `https://www.anthropic.com/news` の公式press kit導線 | 公式press kitの対象asset・利用条件・checksumをrepo内で固定できるまではtext-only |
| DeepSeek | `DeepSeek` | `https://chat.deepseek.com/` | `https://cdn.deepseek.com/policies/en-US/deepseek-terms-of-use.html` | Terms 6.2が許可なしの名称・logo等の利用を制限するため `BLOCKED_BRAND`。logoを同梱しない |

## Handoff behavior

- provider選択は、既存のAI-safe ZIP生成・preview・明示確認・redaction契約を切り替えない。
- ChatGPTはAndroidで既存の明示package共有を試し、失敗時はOS共有へfallbackする。
- Gemini / Claude / DeepSeekはprivate URL schemeや非公開APIを推測せず、OS共有だけを使用する。
- iOSは全providerでOS共有シートを使用する。共有先の選択、質問入力、添付送信はユーザーが行う。
- `official destination` は文書化されたHTTPSの参照先であり、ZIP自動添付や送信成功を意味しない。
- provider API、OAuth、MCP、質問の自動入力、自動送信はこの導線へ追加しない。

## Asset adoption gate

ロゴを追加する場合は、providerごとに公式配布元、利用条件、取得日、原本URL、asset checksum、無加工であること、Light/Dark背景、clear space、optical sizeを記録し、Android/iOSへ同一原本から追加する。条件を満たせないproviderはtext-onlyのままにする。
