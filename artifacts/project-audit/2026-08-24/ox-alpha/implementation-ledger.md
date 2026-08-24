# Implementation Ledger — 2026-08-24 ox-alpha

基準: branch `codex/修正` @ `eda2f2678c16bfbbda56d398c613012376993e76`
許可範囲: ローカルの安全な最小修正 + テスト/lint/build。commit/push等は未実施(禁止)。

## 採用した変更 (1件)

### SR-01 ShareReceiverActivity の状態喪失防止 [DEFECT / P2 / FIXED]

- **ファイル**: `app/src/main/java/jp/mimac/urlsaver/ShareReceiverActivity.kt`
- **差分**: +6 / -5 行
  - L48: `import androidx.compose.runtime.saveable.rememberSaveable` 追加
  - L251〜255: `selectedTagIds` / `newTagName` / `tagCreateError` / `isSaving` / `resultMessage` を `remember` → `rememberSaveable` に変更
- **観測事実(E3)**: 共有受信シートは `ComponentActivity` 通常themeで回転時に再生成され、上記5状態が plain `remember` のため初期化される。保存直前のタグ選択・新規タグ名入力が消失し、ユーザーは再選択を強制される。
- **期待**: 入力途中の状態が画面回転で保持される(Compose標準のsaved instance state契約)。
- **修正**: Bundle互換型(Set<Long>はSerializable, String/Boolean/null可)のため autoSaver の rememberSaveable への置換で最小修正。挙動・見た目・契約不変条件への影響なし。
- **検証**: testDebugUnitTest 358件/0失敗(JDK21), assembleDebug PASS, lintDebug PASS, verify_mobile_ui_contract.py 変更前後ともPASS。
- **残存**: 実機/Simulatorでの回転再現は本セッションの実行範囲外 → NOT EXECUTED (E3ベースの修正)。
- **ロールバック**: 当該hunkを revert するのみ(依存・schema非関与)。

## 試行して差し戻した変更 (1件)

### ENV-01対策としての jvmToolchain(21) 指定 → 差し戻し

- `app/build.gradle.kts` に `kotlin { jvmToolchain(21) }` を追加したが、Gradle 8.11.1 の自動検出が Homebrew `openjdk@21` を解決できず (`No locally installed toolchains match`)、JDK17既定環境で assembleDebug も失敗する副作用を確認。
- 依存追加(foojay-resolver)は本任務で禁止、マシン固有パスの gradle.properties への混入も不適切なため **差し戻し**。
- 結論: リポジトリは既に `.java-version=21` と README.md L99/L191(`SDK 36向けRobolectricテストはJava 17では失敗する`)で要件を文書化済みであり、コード修正ではなく環境遵守が正解。

## 非変更判断 (誤検知回避)

- `DefaultUrlRepository.saveFromTextCard` の promote経路に memo merge がない件: text cardフローは最初からmemo引数を持たない設計のため不具合なし(指摘取下げ)。
- Manifest の mimeType無し SEND filter: 非テキスト共有は NoUrlFound エラー画面で安全に処理されるため意図された広めの受け口と判断(NIT記録のみ)。
