# ChatGPT手動ファイル共有 実機確認記録

## 判定

- ローカル実装・自動テスト: GO
- 物理iPhone: VERIFIED through ChatGPT composer (送信前)
- 物理Android: NOT VERIFIED
- ChatGPT側の添付/composer: VERIFIED on physical iPhone (質問未入力・未送信)
- store/public release: NOT READY

## 物理iPhone

- 端末: iPhone 12 / iOS 26.5.2
- CoreDevice ID: `E9D5CA0F-0729-5DFD-94B9-EFE2AB589C0E`
- USB UDID: `00008101-00066D96340A001E`
- 確認対象: `com.mibu.codebridge.ios`、share extension `com.mibu.codebridge.ios.share`
- backend: USB Appium 3.5.0 + WebDriverAgent 15.1.0
- install: 既存データを消さない上書きinstall。`iphone-install-chatgpt-handoff-final.json` が成功を記録し、database UUID/sequenceを維持
- ChatGPT install確認: `com.openai.chat`、version `1.2026.188`

## 確認できた操作

1. canonical appを起動し、既存データが残っていることを確認。
2. ホーム下部の「エクスポート」から「ChatGPTに聞く」を開いた。
3. 自作タグ `統合確認-20260710` を選択し、対象3件を表示した。
4. りんばむ側に質問入力欄がなく、質問は共有先で入力し、自動送信・アカウント接続・モデル選択を行わない説明を確認した。
5. 対象URLとZIPへ入るJSON/Markdownの全内容、対象3件/除外0件、伏せ字を確認した。
6. 明示確認OFFではZIP作成不可、確認ONで作成可能になることを確認した。
7. `3件のChatGPT用ZIPを作成しました` を確認した。
8. `ChatGPTに送る` でiOS SharingUIServiceの共有シートshellが開くことを確認した。
9. 共有先を横スクロールして `ChatGPT` セルを選択し、ChatGPT通常トーク画面へ遷移した。
10. 添付カードに `Rinbam Chatgpt 20260718…` のZIPが表示され、質問欄は空欄（placeholder `ChatGPT に質問する`）のまま、送信操作を行わない状態を確認した。

## 2026-07-18 再確認で解消した境界

- ChatGPT用ZIP固有の共有シートで、ファイル名 `rinbam-chatgpt-…zip` と `ZIPアーカイブ・29 KB` を確認した。
- iOS共有シートのChatGPT候補表示・選択、ChatGPT通常トーク画面へのZIP添付、質問欄空欄をUSB Appium + WDAで確認した。
- ChatGPTの `送信` ボタンは押していない。ユーザーがChatGPT側で質問を入力して通常送信する仕様を維持している。

## 未確認境界

- 物理Android端末は接続されていないため、Androidの直接共有／共有画面フォールバックの物理端末操作は未確認。
- ChatGPTへの最終送信（質問入力を含む）は行っていない。これは本機能の責務外であり、ユーザーがChatGPT側で行う操作。

## 画像

- `iphone-chatgpt-home-final.png`
- `iphone-export-screen-final.png`
- `iphone-chatgpt-step1-final.png`
- `iphone-chatgpt-selected-final.png`
- `iphone-chatgpt-preview-scroll1-final.png`
- `iphone-chatgpt-preview-scroll2-final.png`
- `iphone-chatgpt-confirmed-final.png`
- `iphone-chatgpt-zip-created-final.png`
- `iphone-chatgpt-share-sheet-final.png`
- `iphone-chatgpt-composer-final.png`（ChatGPT用ZIP添付・質問欄空欄・未送信）

### 2026-07-18追加ソース

- `iphone-chatgpt-composer-final.xml`（Appium/WDA accessibility source。`com.openai.chat`、ZIP添付カード、`新規メッセージ`空欄、`送信`要素を含む）

## 自動検証

- Android: 327 unit tests、lintDebug、assembleDebug、bundleRelease、assembleRelease PASS
- Android release signing: generated `app-release.aab` は`jarsigner -verify -strict`でunsigned。upload proofではない
- iOS: full suite 140件中failure 0、live Supabase環境未設定の3件のみskip。ChatGPT export対象25/25 PASS
- iOS unsigned Release build: PASS
- `python3 scripts/verify_mobile_ui_contract.py`: PASS
- `python3 scripts/verify_mcp_contract.py`: PASS
- `bash scripts/check_release_hygiene.sh`: PASS
- `git diff --check`: PASS
