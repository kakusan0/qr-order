# QR オーダー 店舗管理（kakusan0/qr-order）— 次のセッションへの引き継ぎ

このリポジトリは **店舗管理専用**（ネイティブ Android アプリ + 店舗管理 API サーバー）です。
お客様向けの注文・決済・電子棚札・利用ガイド画面は、作品 wiki リポジトリ `kakusan0/wiki` の `apps/qr-order/` に分離しています。

- `android/`：Kotlin + Jetpack Compose で実装した店舗管理用ネイティブ Android アプリ（`dev.workers.gmakihiro.qrorder.staff`）。パスキーログイン（Android Credential Manager）、注文一覧・年齢確認・返金、商品管理・JAN バーコードスキャン（CameraX + ML Kit）、返品・交換対応、お問い合わせチャット・LINE 連携、店舗 QR・表示専用画面・位置情報設定、スタッフ管理、パスキー管理を実装。
- `src/`・`public/download/`：Android アプリが接続する Cloudflare Workers サーバー（`/api/staff/*`、`/.well-known/assetlinks.json`、APK 配布ページ `/download`）。Web 版の店舗管理画面とお客様画面は削除済み。
- 守ること：キー・トークン・署名鍵（`keystore/`・`keystore.properties`）・個人情報（メールアドレス等）はチャット・コード・Git に書かない。無料のプランだけを使う。マージは利用者が「マージして」と言ったときだけ。
