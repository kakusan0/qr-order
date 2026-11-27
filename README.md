# QR オーダー — 店舗管理（Android アプリ & 管理 API）

店頭 QR 注文システム「QR オーダー」の **店舗管理専用リポジトリ** です。
お客様画面（カート・決済・電子棚札・利用ガイド等）は [`kakusan0/wiki`](https://github.com/kakusan0/wiki) の `apps/qr-order/` に分離しています。

## 構成

| ディレクトリ | 内容 |
| --- | --- |
| `android/` | 店舗管理用ネイティブ Android アプリ（Kotlin / Jetpack Compose / Material 3 / CameraX + ML Kit / Credential Manager パスキー対応） |
| `src/` | 店舗管理 API サーバー（Cloudflare Workers / Hono / D1、`/api/staff/*`・`/.well-known/assetlinks.json`・`/download/qr.svg`） |
| `public/download/` | Android アプリ（APK）配布ページ（`/download`） |
| `migrations/` | Cloudflare D1 のスキーママイグレーション |

## Android アプリ（`android/`）の主な機能

- **認証**: Android Credential Manager によるパスキーログイン・初回メール確認コードセットアップ
- **注文**: 支払済・年齢確認待ち・返金済の絞り込み、年齢確認、全額／一部返金
- **商品**: 商品登録・編集・削除・販売切替、CameraX + ML Kit による JAN バーコード読み取り・商品名自動補完、商品 QR 表示
- **返品**: 返品・交換申請一覧、写真プレビュー、承認・却下・回収完了
- **問い合わせ**: お客様チャット確認・返信、LINE 公式アカウント連携コード発行・解除
- **店舗 QR**: 店舗 QR 表示・入口タブレット用表示専用モード・定期更新間隔・GPS ジオフェンス設定
- **スタッフ / パスキー**: スタッフ追加・有効/無効・再セットアップ・削除、パスキー追加・削除

### ビルド方法

```bash
cd android
./gradlew testDebugUnitTest assembleDebug
```

リリース用 APK をビルドする場合は、`android/keystore.properties`（Git 管理外）に署名鍵の設定を記入して `./gradlew assembleRelease` を実行します。署名鍵（`.jks`）と `keystore.properties` は絶対に Git にコミットしないでください。

## サーバー（Cloudflare Workers）のセットアップ・デプロイ

```bash
npm install
npx wrangler login
npm run typecheck
npm run deploy
```

必要なシークレット（Cloudflare ダッシュボードまたは `wrangler secret put` で設定）：

- `OWNER_EMAIL`（最初の管理者メールアドレス）
- `ANDROID_CERT_SHA256`（Android アプリ署名証明書の SHA-256 フィンガープリント、カンマ区切り）
- `ANDROID_PACKAGES`（例：`dev.workers.gmakihiro.qrorder.staff`）
- `STRIPE_SECRET_KEY`, `STRIPE_PUBLISHABLE_KEY`, `STRIPE_WEBHOOK_SECRET`
- `RESEND_API_KEY`, `MAIL_FROM`
- 任意：`YAHOO_CLIENT_ID`, `LINE_CHANNEL_ACCESS_TOKEN`, `LINE_CHANNEL_SECRET`
