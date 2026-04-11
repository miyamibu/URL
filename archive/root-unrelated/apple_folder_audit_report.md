# 1. Resolved Target
- 確定対象: `/Users/mimac/Desktop/🍎`
- 解決方法: `~/Desktop` を hidden 含めて列挙し、名前が正確に `🍎` のディレクトリを確認した。候補比較は不要だった。

# 2. Executive Summary
- このフォルダーは、Apple Watch 用ラップタイマーと iPhone 連携 UI を含む、小規模な Xcode/Swift プロジェクト一式だった。実データ置き場ではなく、主にソースコードと Xcode メタデータで構成される。
- 重要な発見:
  - `/Users/mimac/Desktop/🍎/LapTimer.xcodeproj` は iOS アプリ `LapTimeriOS` と watchOS アプリ `LapTimerWatch` の 2 ターゲットを持つ Xcode プロジェクトだった。根拠: `project.pbxproj` の `PBXNativeTarget` / `productType` / `PRODUCT_BUNDLE_IDENTIFIER`。
  - `/Users/mimac/Desktop/🍎/SessionStore.swift` は watch 側の計測本体で、`UserDefaults.standard` に `lts_sessions_v1` / `lts_snapshot_v1` 等を保存し、再起動・クラッシュ復元ロジックを実装していた。
  - `/Users/mimac/Desktop/🍎/iOSSessionStore.swift` は iPhone 側のミラー保存層で、watch からの完全スナップショット再同期と、メモ更新・削除の差分反映を持っていた。
  - `/Users/mimac/Desktop/🍎/WatchConnectivityManager.swift` と `/Users/mimac/Desktop/🍎/iOSConnectivityManager.swift` により、`WatchConnectivity` でセッション作成・ラップ追加・停止・削除・メモ更新・再同期要求を送受信していた。
  - `/Users/mimac/Desktop/🍎/SessionListView.swift` と `/Users/mimac/Desktop/🍎/SessionDetailView.swift` では、停止済みセッションの削除、メモ編集、テキスト共有、XLSX 共有を行う UI が実装されていた。
  - `/Users/mimac/Desktop/🍎/XLSXExporter.swift` は外部ライブラリなしで ZIP/XML から `.xlsx` を生成し、`FileManager.default.temporaryDirectory` に書き出す実装だった。
  - 秘密情報の走査では API キー、秘密鍵、`.env`、証明書、private key は見つからなかった。根拠: ファイル名検出と `rg` による secret パターン検索。
  - 一方で `project.pbxproj` には Apple Developer Team ID `8R3B5675ZJ`、Bundle ID `com.mimac.LapTimeriOS` / `com.mimac.LapTimeriOS.watchapp` が含まれていた。これは秘密鍵ではないが、公開共有時のメタデータ露出に当たる。
  - `/Users/mimac/Desktop/🍎/LapTimer.xcodeproj/project.xcworkspace/xcuserdata/mimac.xcuserdatad/UserInterfaceState.xcuserstate` は Xcode 個人状態ファイルで、ローカルパス、ウィンドウ状態、device identifier 断片、`chat.activeConversation` 文字列を含んでいた。共有不要候補。
  - 完全重複は 1 組のみで、`LapTimerIOS.entitlements` と `LapTimerWatch.entitlements` が空 plist としてハッシュ一致した。
  - 壊れた symlink、0 byte file、読み取り失敗、未知の実行ファイル、未完成ダウンロード、巨大キャッシュは見つからなかった。
  - 全体容量は約 `200 KB` と非常に小さく、重いフォルダーではない。最大ファイルでも `SessionStore.swift` の約 `20.8 KB`、次点は `project.pbxproj` の約 `19.1 KB`、`UserInterfaceState.xcuserstate` の約 `18.0 KB`。
- すぐ注意すべき点: `xcuserdata` / `UserInterfaceState.xcuserstate` は個人環境情報を含むため、外部共有や Git 追跡対象なら除外推奨。`project.pbxproj` の Team ID も公開時には意識したい。
- 未確認点: 3 件。理由は `xcuserstate` の完全意味解析未実施、`.DS_Store` の内部構造未展開、ビルド/実行未確認。

# 3. Coverage
- 総エントリ数: 36
- file / dir / symlink / package / other: 25 / 9 / 0 / 2 / 0
- hidden file / hidden dir 件数: 1 / 0
- 読み取り失敗件数: 0
- スキップ件数: 0
- スキップ理由: なし。全エントリを再帰列挙し、全 regular file を `file` で判定、重複候補は SHA-256 で確認した。
- `find` と Python の件数照合結果: `find` = 36, Python = 36 で一致。
- macOS 追加確認: `mdls` で alias 0 件、iCloud/Downloaded/WhereFroms の非デフォルト値 0 件、`xattr -lr` で quarantine なしを確認。

# 4. Top-Level Map
- `/Users/mimac/Desktop/🍎/.DS_Store`
  - 種別/サイズ感/重要度: `file` / `6.0 KB` / `Low`
  - 役割: Finder metadata file.
- `/Users/mimac/Desktop/🍎/ContentView.swift`
  - 種別/サイズ感/重要度: `file` / `1.6 KB` / `High`
  - 役割: Watch app root/idle screen.
- `/Users/mimac/Desktop/🍎/LapListView.swift`
  - 種別/サイズ感/重要度: `file` / `925 B` / `High`
  - 役割: Lap list detail view.
- `/Users/mimac/Desktop/🍎/LapTimer.xcodeproj`
  - 種別/サイズ感/重要度: `package` / `48.0 KB` / `High`
  - 役割: Xcode project package containing build graph, workspace, and user metadata.
- `/Users/mimac/Desktop/🍎/LapTimerIOS-Info.plist`
  - 種別/サイズ感/重要度: `file` / `899 B` / `High`
  - 役割: Bundle metadata plist.
- `/Users/mimac/Desktop/🍎/LapTimerIOS.entitlements`
  - 種別/サイズ感/重要度: `file` / `181 B` / `Medium`
  - 役割: Code-signing entitlements plist (currently empty).
- `/Users/mimac/Desktop/🍎/LapTimerIOSApp.swift`
  - 種別/サイズ感/重要度: `file` / `531 B` / `High`
  - 役割: iOS app entry point.
- `/Users/mimac/Desktop/🍎/LapTimerWatch-Info.plist`
  - 種別/サイズ感/重要度: `file` / `863 B` / `High`
  - 役割: Bundle metadata plist.
- `/Users/mimac/Desktop/🍎/LapTimerWatch.entitlements`
  - 種別/サイズ感/重要度: `file` / `181 B` / `Medium`
  - 役割: Code-signing entitlements plist (currently empty).
- `/Users/mimac/Desktop/🍎/LapTimerWatchApp.swift`
  - 種別/サイズ感/重要度: `file` / `1.1 KB` / `High`
  - 役割: watchOS app entry point.
- `/Users/mimac/Desktop/🍎/Models.swift`
  - 種別/サイズ感/重要度: `file` / `4.4 KB` / `High`
  - 役割: Shared data model and time-format helpers.
- `/Users/mimac/Desktop/🍎/ResultView.swift`
  - 種別/サイズ感/重要度: `file` / `2.3 KB` / `High`
  - 役割: Post-stop result screen.
- `/Users/mimac/Desktop/🍎/SessionDetailView.swift`
  - 種別/サイズ感/重要度: `file` / `8.4 KB` / `High`
  - 役割: iOS session detail, note edit, share, delete.
- `/Users/mimac/Desktop/🍎/SessionListView.swift`
  - 種別/サイズ感/重要度: `file` / `8.3 KB` / `High`
  - 役割: iOS session list, sync, XLSX export, share sheet.
- `/Users/mimac/Desktop/🍎/SessionStore.swift`
  - 種別/サイズ感/重要度: `file` / `20.8 KB` / `High`
  - 役割: watchOS persistence/recovery/timer core.
- `/Users/mimac/Desktop/🍎/SyncMessage.swift`
  - 種別/サイズ感/重要度: `file` / `2.1 KB` / `High`
  - 役割: WatchConnectivity message envelope/payloads.
- `/Users/mimac/Desktop/🍎/TextExporter.swift`
  - 種別/サイズ感/重要度: `file` / `2.2 KB` / `High`
  - 役割: Plain-text export formatter.
- `/Users/mimac/Desktop/🍎/TimerView.swift`
  - 種別/サイズ感/重要度: `file` / `2.4 KB` / `High`
  - 役割: watchOS running timer UI.
- `/Users/mimac/Desktop/🍎/WatchConnectivityManager.swift`
  - 種別/サイズ感/重要度: `file` / `3.6 KB` / `High`
  - 役割: watchOS connectivity bridge.
- `/Users/mimac/Desktop/🍎/XLSXExporter.swift`
  - 種別/サイズ感/重要度: `file` / `16.2 KB` / `High`
  - 役割: Native XLSX writer.
- `/Users/mimac/Desktop/🍎/iOSConnectivityManager.swift`
  - 種別/サイズ感/重要度: `file` / `4.1 KB` / `High`
  - 役割: iOS connectivity bridge.
- `/Users/mimac/Desktop/🍎/iOSSessionStore.swift`
  - 種別/サイズ感/重要度: `file` / `6.3 KB` / `High`
  - 役割: iOS-side persisted session mirror.

# 5. Detailed Findings
## Documents
- path: `/Users/mimac/Desktop/🍎/LapTimerIOS-Info.plist`, `/Users/mimac/Desktop/🍎/LapTimerWatch-Info.plist`
  - 何を確認したか: XML plist 本文を読んで bundle metadata を確認。
  - どう判断したか: どちらもビルドに必須の正式な Info.plist。秘密情報はなく、watch 側には companion bundle identifier が明記されている。
  - 根拠: `CFBundleIdentifier = $(PRODUCT_BUNDLE_IDENTIFIER)`、watch 側 `WKCompanionAppBundleIdentifier = com.mimac.LapTimeriOS`。
- path: `/Users/mimac/Desktop/🍎/LapTimerIOS.entitlements`, `/Users/mimac/Desktop/🍎/LapTimerWatch.entitlements`
  - 何を確認したか: `plutil` 相当の本文確認と SHA-256 重複確認。
  - どう判断したか: 両方とも空 entitlements plist。現時点では必要最小限だが、重複ファイルなので整理余地あり。
  - 根拠: 内容が `<dict/>` のみ、SHA-256 が完全一致。

## Projects / Source code
- path: `/Users/mimac/Desktop/🍎/LapTimer.xcodeproj/project.pbxproj`
  - 何を確認したか: target 定義、ビルド設定、bundle identifier、Team ID、deployment target、ファイル参照。
  - どう判断したか: このフォルダーの主本体。2 ターゲット構成の Xcode プロジェクトで、ビルド設定は揃っている。
  - 根拠: `LapTimeriOS` / `LapTimerWatch` の `PBXNativeTarget`、`DEVELOPMENT_TEAM = 8R3B5675ZJ`、`IPHONEOS_DEPLOYMENT_TARGET = 18.0`、`WATCHOS_DEPLOYMENT_TARGET = 11.0`、`SWIFT_VERSION = 5.0`。
- path: `/Users/mimac/Desktop/🍎/SessionStore.swift`, `/Users/mimac/Desktop/🍎/Models.swift`, `/Users/mimac/Desktop/🍎/TimerView.swift`, `/Users/mimac/Desktop/🍎/ResultView.swift`, `/Users/mimac/Desktop/🍎/ContentView.swift`, `/Users/mimac/Desktop/🍎/LapListView.swift`
  - 何を確認したか: watch 側の主要 Swift ソース本文を読んだ。
  - どう判断したか: Apple Watch で START/LAP/STOP/RESET を行うラップタイマーの中核実装。セッション復旧・失敗マーク付けまで備える。
  - 根拠: `store.start() / lap() / stop() / reset()`、`RecoveryStatus` / `FailureReason` / `RecoveryTrigger`、`UserDefaults.standard` への `lts_sessions_v1` / `lts_snapshot_v1` 保存。
- path: `/Users/mimac/Desktop/🍎/LapTimerIOSApp.swift`, `/Users/mimac/Desktop/🍎/iOSSessionStore.swift`, `/Users/mimac/Desktop/🍎/SessionListView.swift`, `/Users/mimac/Desktop/🍎/SessionDetailView.swift`
  - 何を確認したか: iOS 側エントリ、一覧、詳細、永続化コードを読んだ。
  - どう判断したか: iPhone 側は watch の計測履歴閲覧・同期・削除・メモ付与・共有用 companion app。
  - 根拠: `store.requestFullSnapshot()`、`deleteSession`、`updateNote`、`TextEditor`、`ShareSheet`、`XLSX` エクスポート。
- path: `/Users/mimac/Desktop/🍎/SyncMessage.swift`, `/Users/mimac/Desktop/🍎/WatchConnectivityManager.swift`, `/Users/mimac/Desktop/🍎/iOSConnectivityManager.swift`
  - 何を確認したか: 同期メッセージ定義と両側の `WCSessionDelegate` 実装を確認。
  - どう判断したか: ネットワーク外部送信ではなく、watch と iPhone 間のローカル同期のみ。
  - 根拠: `WatchConnectivity` import、`sessionCreated / lapAdded / sessionStopped / fullSnapshot / deleteSession / updateNote / requestSnapshot`。
- path: `/Users/mimac/Desktop/🍎` 全体
  - 何を確認したか: `.git`, `Package.swift`, `Podfile`, `Gemfile`, `requirements.txt`, `package.json`, `*.xcassets`, `*.storyboard`, `*Tests*` を探索。
  - どう判断したか: Git 管理情報、依存管理ファイル、テスト、アセットカタログ、ストーリーボードはこのフォルダーには存在しない。ソース断片としては最小構成。
  - 根拠: `find` 検索結果 0 件。

## Archives / Installers / Apps
- path: `/Users/mimac/Desktop/🍎/LapTimer.xcodeproj`, `/Users/mimac/Desktop/🍎/LapTimer.xcodeproj/project.xcworkspace`
  - 何を確認したか: package として中身を再帰列挙し、`contents.xcworkspacedata`、`xcshareddata/swiftpm/configuration`、`xcuserdata` を確認。
  - どう判断したか: macOS package だが中身は通常の Xcode メタデータ。実アプリ本体や installer は含まれない。
  - 根拠: `mdls` で `com.apple.xcode.project` / `com.apple.dt.document.workspace`、ファイル構成は `project.pbxproj` とユーザー状態ファイル中心。
- path: `/Users/mimac/Desktop/🍎` 全体
  - 何を確認したか: `zip`, `tar`, `gz`, `7z`, `dmg`, `pkg`, `.app` 等の実体を探索。
  - どう判断したか: package 2 個以外に archive / installer / compiled app は存在しない。
  - 根拠: 拡張子走査で該当 0 件。

## Data / Databases / Spreadsheets
- path: `/Users/mimac/Desktop/🍎/TextExporter.swift`, `/Users/mimac/Desktop/🍎/XLSXExporter.swift`
  - 何を確認したか: export 実装本文を確認。
  - どう判断したか: このフォルダー内に実データはないが、停止済みセッションをテキストと XLSX に出力するコードはある。
  - 根拠: `TextExporter.export(session:)` がラップ一覧とメモを文字列化、`XLSXExporter.export` が workbook/sheet XML を組み立てて `.xlsx` を一時ディレクトリへ書く。
- path: `/Users/mimac/Desktop/🍎` 全体
  - 何を確認したか: `*.sqlite*`, `*.db`, `*.csv`, `*.xlsx`, `*.json`, `*.yaml` 実ファイル有無を確認。
  - どう判断したか: 実データ・データベース・既存エクスポート成果物は含まれない。
  - 根拠: インベントリ上の該当拡張子 0 件。

## Media
- path: `/Users/mimac/Desktop/🍎` 全体
  - 何を確認したか: 画像・動画・音声拡張子の有無を走査。
  - どう判断したか: メディアファイルは含まれない。
  - 根拠: `file` / インベントリで `.png`, `.jpg`, `.mov`, `.mp3` 等 0 件。

## Config / Secrets / Credentials
- path: `/Users/mimac/Desktop/🍎/LapTimer.xcodeproj/project.pbxproj`
  - 何を確認したか: build setting の機微情報候補を抽出。
  - どう判断したか: 秘密鍵や token はないが、Apple Developer Team ID と bundle identifier は公開時に気になるメタデータ。
  - 根拠: `DEVELOPMENT_TEAM = 8R3B5675ZJ`、`PRODUCT_BUNDLE_IDENTIFIER = com.mimac.LapTimeriOS` / `com.mimac.LapTimeriOS.watchapp`。
- path: `/Users/mimac/Desktop/🍎/LapTimer.xcodeproj/project.xcworkspace/xcuserdata/mimac.xcuserdatad/UserInterfaceState.xcuserstate`
  - 何を確認したか: `plutil -p` と `strings` で binary plist を部分解析。
  - どう判断したか: 個人環境依存の UI 状態ファイルで、共有不要かつ情報露出源。
  - 根拠: ローカルパス `file:///Users/mimac/Desktop/%F0%9F%8D%8E/LapTimer.xcodeproj`、`chat.activeConversation`、device identifier 断片、ウィンドウ座標が含まれていた。
- path: `/Users/mimac/Desktop/🍎` 全体
  - 何を確認したか: `.env`, `.env.*`, `id_rsa`, `id_ed25519`, `*.pem`, `*.key`, `*.p12`, `*.mobileprovision`, `GoogleService-Info.plist`、secret パターン検索。
  - どう判断したか: 明示的な資格情報ファイルは見つからなかった。
  - 根拠: ファイル名検出 0 件、`rg` のヒットは `WatchConnectivity` 関連のみ。

## Duplicates
- path: `/Users/mimac/Desktop/🍎/LapTimerIOS.entitlements`, `/Users/mimac/Desktop/🍎/LapTimerWatch.entitlements`
  - 何を確認したか: サイズ一致後に SHA-256 を計算。
  - どう判断したか: 内容完全一致の真の重複。現状空 plist なので分離維持コストは低いが、整理候補。
  - 根拠: 両方 181 bytes、SHA-256 `97704a8960b4facceef54397a08fb5d0a456247c3627359215aa2a27df22656c`。

## Broken / Unreadable / Suspicious
- path: `/Users/mimac/Desktop/🍎` 全体
  - 何を確認したか: broken symlink, unreadable path, 0 byte, unreadable `find`/Python discrepancy, quarantine/xattr, alias, iCloud placeholder を確認。
  - どう判断したか: 壊れ・読めない・リンク異常は見つからず、フォルダー監査としては良好。
  - 根拠: broken symlink 0、failures 0、0 byte 0、alias 0、non-default iCloud/download metadata 0。
- path: `/Users/mimac/Desktop/🍎/.DS_Store`
  - 何を確認したか: `file`, `mdls`, `xattr`。
  - どう判断したか: 通常の Finder metadata。危険ではないが共有価値は低い。
  - 根拠: `Apple Desktop Services Store`、`com.apple.FinderInfo` xattr。

## Likely Junk / Temp / Cache
- path: `/Users/mimac/Desktop/🍎/.DS_Store`
  - 何を確認したか: hidden metadata file として存在確認。
  - どう判断したか: 典型的な削除候補。コードやビルドには不要。
  - 根拠: Finder 表示状態保存用の隠しファイル。
- path: `/Users/mimac/Desktop/🍎/LapTimer.xcodeproj/project.xcworkspace/xcuserdata/mimac.xcuserdatad/UserInterfaceState.xcuserstate`
  - 何を確認したか: binary plist 内容断片と最終更新時刻を確認。
  - どう判断したか: 個人ローカル状態。今フォルダーで最も「共有不要」寄り。
  - 根拠: 2026-04-04 16:34:05 +0900 に更新、Xcode UI/デバイス/会話状態を保持。
- path: `/Users/mimac/Desktop/🍎/LapTimer.xcodeproj/xcuserdata/mimac.xcuserdatad/xcschemes/xcschememanagement.plist`
  - 何を確認したか: plist 本文。
  - どう判断したか: スキーム順序だけの個人設定で、再生成可能。
  - 根拠: `SchemeUserState` と `orderHint` しか持たない。
- path: `/Users/mimac/Desktop/🍎/LapTimer.xcodeproj/project.xcworkspace/xcshareddata/swiftpm/configuration`
  - 何を確認したか: ディレクトリ中身と `du`。
  - どう判断したか: 空ディレクトリ。将来用の痕跡か、Xcode 自動生成の残り。
  - 根拠: 子要素 0、サイズ 0 KB。

# 6. Inventory Tables
- 表中の `rel_path` はすべて基底パス `/Users/mimac/Desktop/🍎` からの相対。

### 拡張子別件数表
| extension | count |
| --- | --- |
| .swift | 16 |
| .plist | 3 |
| .entitlements | 2 |
| .pbxproj | 1 |
| .xcuserstate | 1 |
| .xcworkspacedata | 1 |
| [none] | 1 |

### 種別別件数表
| kind | count |
| --- | --- |
| directory | 9 |
| file | 25 |
| package | 2 |

### サイズ上位20件
| rank | size | rel_path | kind | mtime |
| --- | --- | --- | --- | --- |
| 1 | 20.8 KB | SessionStore.swift | file | 2026-04-02T23:17:16+09:00 |
| 2 | 19.1 KB | LapTimer.xcodeproj/project.pbxproj | file | 2026-04-04T14:36:28.447229+09:00 |
| 3 | 18.0 KB | LapTimer.xcodeproj/project.xcworkspace/xcuserdata/mimac.xcuserdatad/UserInterfaceState.xcuserstate | file | 2026-04-04T16:34:05.194667+09:00 |
| 4 | 16.2 KB | XLSXExporter.swift | file | 2026-04-02T22:02:31+09:00 |
| 5 | 8.4 KB | SessionDetailView.swift | file | 2026-04-02T23:16:41+09:00 |
| 6 | 8.3 KB | SessionListView.swift | file | 2026-04-02T22:04:51+09:00 |
| 7 | 6.3 KB | iOSSessionStore.swift | file | 2026-04-02T22:04:14+09:00 |
| 8 | 6.0 KB | .DS_Store | file | 2026-04-04T10:43:21.256843+09:00 |
| 9 | 4.4 KB | Models.swift | file | 2026-04-02T21:57:19+09:00 |
| 10 | 4.1 KB | iOSConnectivityManager.swift | file | 2026-04-02T22:02:31+09:00 |
| 11 | 3.6 KB | WatchConnectivityManager.swift | file | 2026-04-02T22:02:31+09:00 |
| 12 | 2.4 KB | TimerView.swift | file | 2026-04-02T21:07:46+09:00 |
| 13 | 2.3 KB | ResultView.swift | file | 2026-04-02T11:38:34+09:00 |
| 14 | 2.2 KB | TextExporter.swift | file | 2026-04-02T21:09:27+09:00 |
| 15 | 2.1 KB | SyncMessage.swift | file | 2026-04-02T21:10:55+09:00 |
| 16 | 1.6 KB | ContentView.swift | file | 2026-04-02T21:07:41+09:00 |
| 17 | 1.1 KB | LapTimerWatchApp.swift | file | 2026-04-02T21:23:00+09:00 |
| 18 | 925 B | LapListView.swift | file | 2026-04-02T11:38:34+09:00 |
| 19 | 899 B | LapTimerIOS-Info.plist | file | 2026-04-02T21:22:42+09:00 |
| 20 | 863 B | LapTimerWatch-Info.plist | file | 2026-04-04T14:40:54.047268+09:00 |

### 更新日時が新しい上位20件
| rank | mtime | rel_path | kind | size |
| --- | --- | --- | --- | --- |
| 1 | 2026-04-04T16:34:05.195408+09:00 | LapTimer.xcodeproj/project.xcworkspace/xcuserdata/mimac.xcuserdatad | directory | 96 B |
| 2 | 2026-04-04T16:34:05.194667+09:00 | LapTimer.xcodeproj/project.xcworkspace/xcuserdata/mimac.xcuserdatad/UserInterfaceState.xcuserstate | file | 18.0 KB |
| 3 | 2026-04-04T16:26:45.502468+09:00 | LapTimer.xcodeproj/xcuserdata/mimac.xcuserdatad/xcschemes | directory | 96 B |
| 4 | 2026-04-04T16:26:45.502395+09:00 | LapTimer.xcodeproj/xcuserdata/mimac.xcuserdatad/xcschemes/xcschememanagement.plist | file | 461 B |
| 5 | 2026-04-04T14:40:54.047268+09:00 | LapTimerWatch-Info.plist | file | 863 B |
| 6 | 2026-04-04T14:36:28.447373+09:00 | LapTimer.xcodeproj | package | 160 B |
| 7 | 2026-04-04T14:36:28.447229+09:00 | LapTimer.xcodeproj/project.pbxproj | file | 19.1 KB |
| 8 | 2026-04-04T10:43:21.256843+09:00 | .DS_Store | file | 6.0 KB |
| 9 | 2026-04-04T10:43:21.256312+09:00 | . | directory | 768 B |
| 10 | 2026-04-04T10:19:54.870250+09:00 | LapTimer.xcodeproj/project.xcworkspace/xcuserdata | directory | 96 B |
| 11 | 2026-04-04T10:19:54.870173+09:00 | LapTimer.xcodeproj/project.xcworkspace | package | 160 B |
| 12 | 2026-04-04T10:19:51.136279+09:00 | LapTimer.xcodeproj/xcuserdata/mimac.xcuserdatad | directory | 96 B |
| 13 | 2026-04-04T10:19:51.136245+09:00 | LapTimer.xcodeproj/xcuserdata | directory | 96 B |
| 14 | 2026-04-04T10:19:50.863952+09:00 | LapTimer.xcodeproj/project.xcworkspace/xcshareddata/swiftpm | directory | 96 B |
| 15 | 2026-04-04T10:19:50.863950+09:00 | LapTimer.xcodeproj/project.xcworkspace/xcshareddata/swiftpm/configuration | directory | 64 B |
| 16 | 2026-04-04T10:19:50.863915+09:00 | LapTimer.xcodeproj/project.xcworkspace/xcshareddata | directory | 96 B |
| 17 | 2026-04-04T10:19:50.842169+09:00 | LapTimer.xcodeproj/project.xcworkspace/contents.xcworkspacedata | file | 135 B |
| 18 | 2026-04-02T23:17:16+09:00 | SessionStore.swift | file | 20.8 KB |
| 19 | 2026-04-02T23:16:41+09:00 | SessionDetailView.swift | file | 8.4 KB |
| 20 | 2026-04-02T22:04:51+09:00 | SessionListView.swift | file | 8.3 KB |

### 完全重複一覧
| dup_group | size | sha256 | rel_path | same_name |
| --- | --- | --- | --- | --- |
| 1 | 181 B | 97704a8960b4facceef54397a08fb5d0a456247c3627359215aa2a27df22656c | LapTimerIOS.entitlements | False |
| 1 | 181 B | 97704a8960b4facceef54397a08fb5d0a456247c3627359215aa2a27df22656c | LapTimerWatch.entitlements | False |

### 読み取り失敗一覧
なし

### 空ディレクトリ一覧
| rel_path | kind |
| --- | --- |
| LapTimer.xcodeproj/project.xcworkspace/xcshareddata/swiftpm/configuration | directory |

### 0 byte file 一覧
なし

# 7. Risk Review
- severity 判定基準:
  - High: 明示的な秘密情報、実害ある壊れ、実行可能な不審物、深刻な読み取り不能。
  - Medium: 個人/組織メタデータ露出、共有に不要な環境依存物、ビルドや配布を妨げうる欠落。
  - Low: 再生成可能なメタデータ、重複、空ディレクトリ、共有価値の低い clutter。
  - Informational: 監査上の事実だが即時対処不要。

## High
- 該当なし。秘密鍵・token・`.env`・証明書・壊れたリンク・不審実行ファイルは確認されなかった。

## Medium
- `/Users/mimac/Desktop/🍎/LapTimer.xcodeproj/project.xcworkspace/xcuserdata/mimac.xcuserdatad/UserInterfaceState.xcuserstate`
  - 理由: ローカル UI 状態、ローカルパス、device identifier 断片、`chat.activeConversation` 文字列を含む。共有/公開に不要。
- `/Users/mimac/Desktop/🍎/LapTimer.xcodeproj/project.pbxproj`
  - 理由: Apple Developer Team ID と bundle identifier を含む。秘密鍵ではないが、公開時には露出情報として扱うべき。
- `/Users/mimac/Desktop/🍎` 全体
  - 理由: テスト・アセット・README・Git メタデータがなく、もしこれを「完成済み配布用一式」と見なすなら不完全の可能性がある。これは壊れではなく completeness リスク。

## Low
- `/Users/mimac/Desktop/🍎/.DS_Store`: Finder metadata。不要候補。
- `/Users/mimac/Desktop/🍎/LapTimer.xcodeproj/xcuserdata/mimac.xcuserdatad/xcschemes/xcschememanagement.plist`: 個人スキーム順序。再生成可能。
- `/Users/mimac/Desktop/🍎/LapTimer.xcodeproj/project.xcworkspace/xcshareddata/swiftpm/configuration`: 空ディレクトリ。
- `/Users/mimac/Desktop/🍎/LapTimerIOS.entitlements` と `/Users/mimac/Desktop/🍎/LapTimerWatch.entitlements`: 空 plist の真の重複。

## Informational
- `/Users/mimac/Desktop/🍎` 全体容量は約 200 KB で軽量。重いファイル問題はない。
- `/Users/mimac/Desktop/🍎` 内に実測セッションデータはなく、コードと Xcode メタデータのみ。
- `UserDefaults` 保存キーはソース内にあるが、保存実体はこのフォルダー外のアプリ sandbox に置かれる。

# 8. Unknowns / Limits
- `/Users/mimac/Desktop/🍎/LapTimer.xcodeproj/project.xcworkspace/xcuserdata/mimac.xcuserdatad/UserInterfaceState.xcuserstate` は binary archived plist のため、`plutil -p` と `strings` で内容断片を確認したが、全オブジェクトを意味論的に完全復元したわけではない。
- `/Users/mimac/Desktop/🍎/.DS_Store` は Finder metadata と確認したが、内部レコードを専用 parser で全展開していない。必要性が低いため。
- ビルド/実行はしていないため、`/Users/mimac/Desktop/🍎` が現在の Xcode / SDK でそのまま成功ビルドするか、watch-iPhone 実機同期が動くかは未確認。追加で `xcodebuild` や Xcode GUI があれば確認できる。

# 9. Auditor Notes
- PASS A / PASS B の抜け漏れ再点検結果: hidden は `.DS_Store` を含めて列挙済み、package は `LapTimer.xcodeproj` と `project.xcworkspace` の内部まで再帰確認済み、重複・broken symlink・empty dir・secret scan も実施済み。
- 件数整合: `find` 36 件と Python 36 件で整合。未説明カテゴリは `.DS_Store` と `xcuserstate` だけだったが、両方の中身種別を確認済み。
- 見落としリスクが残る箇所: `xcuserstate` の深い内部意味と、ビルド未実行による runtime completeness。これは本文中で要再確認扱いにした。
- 根拠が弱い主張は付けていない。推測が入る箇所は「完成済み配布用一式なら不完全の可能性」と限定表現に留めた。
- 追加確認推奨: `xcodebuild -list`, `xcodebuild -scheme ...`, `.gitignore` 方針確認、公開前の `xcuserdata` 除外確認。

# 10. Final Verdict
- 主用途: Apple Watch でのラップ計測と iPhone 側履歴閲覧・メモ・共有を行う Swift/Xcode プロジェクト。
- 重要保管物: 16 本の `.swift` ソース、`project.pbxproj`、2 つの Info.plist。
- 整理候補: `.DS_Store`、`UserInterfaceState.xcuserstate`、`xcschememanagement.plist`、空 `swiftpm/configuration`、重複した空 entitlements。
- 注意対象: `project.pbxproj` の Team ID / Bundle ID、`xcuserdata` の個人環境情報。

今すぐ安全にできる次の行動:
1. `/Users/mimac/Desktop/🍎/LapTimer.xcodeproj/project.xcworkspace/xcuserdata/mimac.xcuserdatad/UserInterfaceState.xcuserstate` を共有対象から外す。
2. `/Users/mimac/Desktop/🍎/LapTimer.xcodeproj/xcuserdata/mimac.xcuserdatad/xcschemes/xcschememanagement.plist` を共有対象から外す。
3. `/Users/mimac/Desktop/🍎/.DS_Store` を整理候補として除外方針に入れる。
4. `/Users/mimac/Desktop/🍎/LapTimerIOS.entitlements` と `/Users/mimac/Desktop/🍎/LapTimerWatch.entitlements` の分離維持が必要か見直す。
5. `/Users/mimac/Desktop/🍎/LapTimer.xcodeproj/project.pbxproj` の Team ID / Bundle ID を公開して問題ないか確認する。
6. このフォルダーを Git 管理するなら `xcuserdata/` と `.DS_Store` を ignore 対象にする。
7. 必要なら `xcodebuild -list` でスキーム一覧を確認し、ビルド可能性を追加監査する。
8. 必要なら `xcodebuild` で iOS / watchOS ターゲットの build-only 監査を行う。
9. 実際の計測データも監査したい場合は、このフォルダーではなく app sandbox の `UserDefaults` / app container を別途対象にする。
