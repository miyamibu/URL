# ハンバーガーメニュー視覚レビュー

## Review summary

- iOS Simulator のホーム上段が「ハンバーガー / りんばむ / 検索」に整理されている。
- 下部の `グループ / エクスポート / + / タグ / アーカイブ` は維持されている。
- サービス・共有タグの横スクロール行と空状態の余白は既存の構造を維持している。
- ハンバーガーと検索のタップ領域は、既存の上段ボタンと同じネイティブ領域を使用している。

## Evidence

- iOS Simulator iPhone 17 / iOS 26.5: `ios-simulator-home-hamburger.png`
- Bundle ID: `com.mibu.codebridge.ios`

## Commands run

- `python3 scripts/verify_mobile_ui_contract.py`
- `xcodebuild -workspace ios/URLSaveriOS.xcworkspace -scheme URLSaveriOS -destination 'platform=iOS Simulator,name=iPhone 17,OS=26.5' build`

## Residual risk

- Simulatorでメニューを開いた後のポップオーバー内部は、今回の自動スクリーンショットではタップ操作まで実施していない。既存導線を呼ぶコードとSimulator buildは確認済みだが、実機での操作証跡は未取得。
- Android emulatorは接続されていないため、Androidの実画面スクリーンショットは未取得。
