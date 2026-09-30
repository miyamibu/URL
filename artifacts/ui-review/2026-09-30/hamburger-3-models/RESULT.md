# ハンバーガーメニュー比較結果

Status: **DONE（3案のブラウザプロトタイプ比較）**（2026-09-30）

| 指定モデル | 実行証拠 | 成果物 |
| --- | --- | --- |
| space-bunny Free | `multi_agent_v1` の `opencode-zen/space-bunny-free`、agent `01a0f03f-f892-7302-8d9d-fd1f2ee6d0a9` がHTMLを返した | `space-bunny/index.html`、`space-bunny/screenshot-right.png` |
| GPT-5.5 Pro(Web) | 再起動した `chatgpt-web/gpt-5.5-pro` の agent `01a0f0ad-5747-79f1-a3c6-78c0e74c9155` が依頼を受けてHTMLを作成し、非空の最終応答を返した。以前の agent `01a0f03f-b92c-7781-aa63-775c5ecedb7c` は送信前失敗 | `gpt-5.5-pro-web/index.html`、`gpt-5.5-pro-web/screenshot-final.png` |
| thinkingmachines/inkling | OpenRouter free Worker が `thinkingmachines/inkling:free` で非空のHTML応答を返した。V1のサブエージェント登録には同モデルがなく起動拒否 | `inkling/index.html`、`inkling/screenshot-right.png` |

GPT-5.6 Luna(Max) のサブエージェント `01a0f040-5a93-7aa3-9486-f2f0d6415830` が契約の検証観点を読み取り専用で確認した。

3案のHTMLは各指定モデルの出力を基に保存した。統合担当は `BRIEF.md` の契約と視認性に合うよう局所修正した。GPT-5.5 Pro(Web)案では、メニュー開状態で右上ボタンを覆っていた暗幕の範囲と、背景文字が透けるメニュー面を修正した。ネイティブAndroid/iOSソースは変更していない。

撮影条件: ローカルHTMLを Codex のサイドパネルで表示・操作確認し、同じ Playwright 撮影手順で 390 × 844 CSS px、拡大率変更なし、メニュー開状態をPNG保存した。3ファイルの画像寸法はすべて 390 × 844 px。これはブラウザプロトタイプの証拠であり、実機アプリの画面・機能証拠ではない。

ユーザーの指摘により、先行2案はメニューボタンを実アプリと同じ右上へ修正し、検索をその左に配置した。GPT-5.5 Pro(Web)案も最初から右上に配置されている。以前の先行2案の `screenshot-final.png` は修正前の画像として残し、比較には `screenshot-right.png` を使用する。

検証: `python3 scripts/verify_mobile_ui_contract.py` はPASS。3案ともメニュー項目5件を確認した。GPT-5.5 Pro(Web)案はサイドパネルで右上ボタン・Escape・外側クリックによる開閉と「使い方」のプロトタイプ内反応を確認した。commit、push、実機操作は行っていない。
