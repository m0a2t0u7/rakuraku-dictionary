# らくらく辞書

Gboardの日本語の単語リストへ、ボタン1回で移動するAndroidアプリ。

- 新しく登録: 単語リスト → 日本語 → ＋ まで自動移動（ユーザー補助を使用）
- 日本語の単語リスト: 一覧まで自動移動
- CSVで一括登録: よみ,単語 のCSVをGboard用ZIPに変換

## インストール / 更新
[Releases](https://github.com/m0a2t0u7/rakuraku-dictionary/releases/latest) から最新のAPKをダウンロード。
アプリ内の「⋮ → 更新を確認」でも確認できます。

## ビルド
`main` にpushするとGitHub Actionsで署名付きAPKを作り、`versionName` のタグでReleasesに置きます。
版を上げるときは `app/build.gradle` の `versionCode` と `versionName` を両方上げてください。
