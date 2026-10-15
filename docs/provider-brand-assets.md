# Provider brand assets

Google sign-in mark: Google公式配布の [g-logo.png](https://developers.google.com/static/identity/images/g-logo.png) を2026-10-15に取得。200 × 204 px、RGBA PNGを無変換でiOS `AiGoogle.imageset/google-g.png` とAndroid `drawable-nodpi/ic_auth_google.png` に保存した。SHA-256: `d1ce9c2af0b10a7333abc99bc706f9a6a199e5b65bf3e3009624f076b8638e6a`。表示時は元の色と縦横比を保持する。

Apple sign-in mark: iOSは既存のネイティブ `apple.logo` SF Symbol を使う。AndroidはApple Developerの [Design Resources](https://developer.apple.com/design/resources/) にある「Sign in with Apple - Logo Only」の3x PNG黒版/白版を使う。ownerは2026-10-15に Apple Developer Artwork License Agreement for Sign in with Apple に同意した。元の132 × 132 px RGB PNGを無変換で `drawable-nodpi/ic_auth_apple_black.png` と `drawable-nodpi/ic_auth_apple_white.png` に保存した。黒版SHA-256: `9cb093ebe058d7f686178d73557847c509534793ce42f7092f4a7cf9d7b5eea2`。白版SHA-256: `e9832e747ee3dfb0c56939480a82fe330bdaf5f061e7dd84a6a271bd13830dca`。UIは32dpアイコン枠内で元PNGを44dp相当の正方形としてaspect-fit表示し、明るいsurfaceでは黒版、暗いsurfaceでは白版を使う。Android側のピクセル確認では元画像のマーク領域は46 × 57 px（44dp表示で約15 × 19dp）で、Logo-only artworkの内側clear-spaceを維持する。画像はcrop、tint、raster編集していない。配布物へDMGやAppleのライセンス文書自体は含めていない。

ChatGPT mark: Androidは既存の `drawable-nodpi/ai_chatgpt.png` を再利用する。白いmarkを明るい行でも認識できるよう、画像自体を変更せず24dpの黒い円形背景内に20dpで表示する。
