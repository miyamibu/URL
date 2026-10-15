# Provider brand assets

Google sign-in mark: Google公式配布の [g-logo.png](https://developers.google.com/static/identity/images/g-logo.png) を2026-10-15に取得。200 × 204 px、RGBA PNGを無変換でiOS `AiGoogle.imageset/google-g.png` とAndroid `drawable-nodpi/ic_auth_google.png` に保存した。SHA-256: `d1ce9c2af0b10a7333abc99bc706f9a6a199e5b65bf3e3009624f076b8638e6a`。表示時は元の色と縦横比を保持する。

Apple sign-in mark: iOSは既存のネイティブ `apple.logo` SF Symbol を使う。Androidも背景の四角を表示せず、透明キャンバス上のリンゴマークだけを表示する。Apple Developerの [Design Resources](https://developer.apple.com/design/resources/) の「Sign in with Apple - Logo Only」配布物を使用し、ownerは2026-10-15に Apple Developer Artwork License Agreement for Sign in with Apple に同意した。

Androidの表示資産は配布物の `SVG/Logo - SIWA - Logo-only - Black.svg` / `White.svg` にあるforegroundの `<path>` を、pathData、fill色、nonzero fill-rule、56 × 56 viewBoxをそのまま維持してVectorDrawableへ形式変換した `drawable/ic_auth_apple_glyph_black.xml` / `ic_auth_apple_glyph_white.xml`。元SVGの背景UI用 `<rect x="6" y="6" width="44" height="44">` は描画対象に含めない。リンゴ形状の描き直し、path編集、raster編集、cropは行っていない。元SVG黒版SHA-256: `7ee1bc1706bc228f37a937de914e2ebe1605aebba3c35f11c3963087573fc11f`、白版: `fd66b19f3f09ade3650cc3289a744ee9636764068243805d9c74deeecbde191b`。元ファイルとpath一致の変換receiptはGit対象外の `artifacts/ui-review/2026-10-15/provider-status/android/43/source-apple-glyph/` に保持する。

32dpアイコン枠の中央で56 × 56の透明キャンバス全体を64dp相当としてaspect-fit表示し、公式15 × 19座標単位のマークは約17 × 22dpとなる。透明な外側clear-spaceを維持し、明るいsurfaceでは黒版、暗いsurfaceでは白版を使う。CTAと状態行は既存のテーマ背景・配置・操作を維持する。以前の不透明132 × 132 px RGB PNG `drawable-nodpi/ic_auth_apple_black.png` / `ic_auth_apple_white.png` は来歴保持のため残すが、このUIでは参照しない。元PNG黒版SHA-256: `9cb093ebe058d7f686178d73557847c509534793ce42f7092f4a7cf9d7b5eea2`、白版: `e9832e747ee3dfb0c56939480a82fe330bdaf5f061e7dd84a6a271bd13830dca`。配布物へDMGやAppleのライセンス文書自体は含めない。

ChatGPT mark: Androidは既存の `drawable-nodpi/ai_chatgpt.png` を再利用する。白いmarkを明るい行でも認識できるよう、画像自体を変更せず24dpの黒い円形背景内に20dpで表示する。
