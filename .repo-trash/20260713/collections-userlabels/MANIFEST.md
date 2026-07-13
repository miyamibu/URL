# Collections / UserLabels Retirement Manifest

## Goal

ユーザー向けに採用しない Collection / 保存先と Android UserLabel のactive実装を、既存URL・タグ・DB互換性を失わず可逆退役する。

## Scope

- Android Collection / UserLabel のUI、ViewModel、Repository書き込み、filter、保存先選択、local-tag bridge
- iOS Collection のUI、AppModel、Repository書き込み、filter、保存先選択、local-tag bridge
- 専用ファイルは `files/` に元のrepo相対パスを保って保存する。
- 混在ファイルから退役するhunkは `patches/` に復元可能な形で保存する。

## Preserved Compatibility

- Android Room version 22
- Android `collections` / `user_labels`、`collectionId` / `userLabelId`
- iOS `collections` / `collection_id`
- 全historical migration、Room schema、既存DB行
- `normalizedUrl` の重複契約と `openUrl = normalizedUrl`

## Forbidden Retirement Effects

- DB DROP、version bump、destructive migration
- 既存URL、タグ、Collection、UserLabel行の削除またはnull化
- Android実機のuninstall、`pm clear`、instrumentationによるデータ初期化
- Home、下部5操作、使い方、カードタップ契約の変更

## Inventory

| 元パス / 対象 | 保存先 | SHA-256 | 復元方法 |
| --- | --- | --- | --- |
| `app/src/main/java/jp/mimac/urlsaver/data/DefaultUrlRepositoryCollectionsSupport.kt` | `files/app/src/main/java/jp/mimac/urlsaver/data/DefaultUrlRepositoryCollectionsSupport.kt` | `9f269cf9353f50718ca0b53ec8ec1509b3195c4f97cdebf16b819796c5d9dda7` | 元パスへ戻し、退役したRepository APIと依存を同時に復元する。 |
| `app/src/main/java/jp/mimac/urlsaver/data/LocalTagCollectionEntryRef.kt` | `files/app/src/main/java/jp/mimac/urlsaver/data/LocalTagCollectionEntryRef.kt` | `3319632463e9c76583930db5a411ab509ba941b36434676ee60252ac00ef04ba` | 元パスへ戻し、対応するTagDao queryとFlowを同時に復元する。 |
| `app/src/main/java/jp/mimac/urlsaver/data/UserLabelDao.kt` | `files/app/src/main/java/jp/mimac/urlsaver/data/UserLabelDao.kt` | `05a5149db774a1968c81703c5b6b6a2d6385f2793352c9ffcece33278c111564` | 元パスへ戻し、`AppDatabase.userLabelDao()`を明示的に復元する。 |
| `app/src/main/java/jp/mimac/urlsaver/ui/components/CollectionFilterRow.kt` | `files/app/src/main/java/jp/mimac/urlsaver/ui/components/CollectionFilterRow.kt` | `2cd059dc15f0a0bc76cb9d152dd548a3db11f3e0ad13c58e6e4679371fcb876a` | 元パスへ戻し、呼び出し側UI contractを再承認して復元する。 |
| `app/src/test/java/jp/mimac/urlsaver/MainListViewModelCollectionLabelTest.kt` | `files/app/src/test/java/jp/mimac/urlsaver/MainListViewModelCollectionLabelTest.kt` | `bda3923ff2678b8ad2d74bf7dc23c50301513161c61735346c7d1919755c1687` | 対応するRepository/ViewModel APIと同時に元パスへ戻す。 |
| Android混在ファイルの退役境界 | `patches/android/mixed-collection-ui-removal.snippet` | `6cb5e040ed73127e1d7ceecf2050fcde6d1864cd417b8e6d74a1ee4e9e0ba5ee` | 記載symbol単位で復元し、広範囲restoreは行わない。 |
| iOS混在ファイルの退役境界 | `patches/ios/collections-retirement.patch` | `c4318043da2dab984843f45004476a3699684e1cab701b7fc538ac99820468f3` | 記載symbol単位で復元し、SQLite互換殻は変更しない。 |

Active sourceにはAndroidのread-only `CollectionDao.loadCollections()`、Room entity、iOSのlegacy decodeだけを互換殻として残す。UserLabelのactive DAOと書き込み経路は残さない。

## Restore

1. `files/` のファイルを記録された元パスへ戻す。
2. `patches/` の退役hunkを記録された元ファイルへ適用する。
3. Android/iOSのbuild・unit testと `python3 scripts/verify_mobile_ui_contract.py` を実行する。
4. DB schema versionを変更せず、既存データを保持した起動を確認する。
