# QR オーダー（店頭 QR 注文・事前決済）

店頭の QR コードを読み取り → 商品を選択 → クレジットカード / Apple Pay / Google Pay / Link / PayPay で決済 → 受取番号を提示して商品を受け取る、という流れのアプリです。

- アプリ: Cloudflare Workers（`src/index.ts`）+ 静的アセット（`public/`）
- データベース: Cloudflare D1（`migrations/`）
- 決済: Stripe（カード（JCB ほか）/ Apple Pay / Google Pay / Link / PayPay。表示される手段は Stripe ダッシュボードの「決済手段」の設定に従う。カード情報は当システムを通過・保存しません）

## 画面

| URL | 用途 |
| --- | --- |
| `/s/<店舗ID>` | お客様のカート・決済画面。商品 QR をカメラで読み取るとカートに入る |
| `/order/<注文ID>` | 決済後の受取番号・ご利用明細 |
| `/staff` | 店舗管理（PC・スマホ対応）：受け渡し、年齢確認、返金（全額・一部）、商品の登録・編集・削除・有効/無効・商品 QR、店舗 QR、スタッフ管理。個人別アカウントでログイン（`/staff/login`） |
| `/legal/*` | 特定商取引法に基づく表記、利用規約、プライバシーポリシー |

商品を渡してよいのは **スタッフ画面に「支払済」と出ている注文だけ** です。支払済への変更は、Stripe で決済成功と金額一致をサーバー側で確認できた場合にのみ行われます。

## セットアップ

```bash
npm install
npx wrangler login
npx wrangler d1 create qr-order        # 出力の database_id を wrangler.jsonc に記入
npm run db:migrate:remote
npm run db:seed:remote                 # サンプル。seed.sql を実際の店舗・商品に書き換えてから
npx wrangler secret put STRIPE_SECRET_KEY
npx wrangler secret put STRIPE_PUBLISHABLE_KEY
npx wrangler secret put STRIPE_WEBHOOK_SECRET
npx wrangler secret put RESEND_API_KEY
npx wrangler secret put OWNER_EMAIL
npx wrangler secret put MAIL_FROM
npm run deploy
```

Stripe 側の設定:

1. ダッシュボードの「決済手段」でカード / Apple Pay / Google Pay / Link / PayPay を有効化し、公開ドメインを「支払い方法のドメイン」に登録（Apple Pay に必須）。
2. Webhook エンドポイント `https://<ドメイン>/api/stripe/webhook` を追加し、イベント `payment_intent.succeeded` を選択。署名シークレットを `STRIPE_WEBHOOK_SECRET` に設定。
3. まずテストキー（`sk_test_` / `pk_test_`）で実機確認してから本番キーに切り替え。

ローカル開発は `.dev.vars.example` を `.dev.vars` にコピーし、`npm run db:migrate:local && npm run db:seed:local && npm run dev`。

## 運用に関する仕組み

- **ログイン**: パスキーのみ（パスワードなし）。初回は `/staff/setup` でメールアドレスを入力し、メールで届く 6 桁の確認コード（10 分有効・5 回まで）で本人確認をしてからパスキーを登録する。最初の管理者は変数 `OWNER_EMAIL` のアドレス。以後のスタッフは管理者が「スタッフ」タブでメールアドレスと権限を登録する。端末紛失時は管理者が「再セットアップ」を押す。唯一の管理者が端末を失った場合は、Cloudflare で変数 `OWNER_RECOVERY=1` を一時的に設定すると `OWNER_EMAIL` で再セットアップできる（済んだら削除）。
- **メール送信**: Resend を使用。シークレット `RESEND_API_KEY` と、差出人アドレスの変数 `MAIL_FROM` が必要。
- **注文作成の制限**: 同じ接続元から 5 分間に 30 回まで。シークレット `TURNSTILE_SITE_KEY` と `TURNSTILE_SECRET_KEY`（Cloudflare ダッシュボードの Turnstile で作成）を両方設定すると、注文時に Turnstile の確認を必須にする。
- **年齢確認商品**: 商品に「年齢確認が必要」を付けると、お客様は 20 歳以上の申告が必要になり、スタッフが「年齢を確認した」を押すまで受け渡し完了にできない。
- **返金**: 管理者のみ。金額を指定して一部返金でき、全額を返金し終えると「返金済」になる。
- **自動整理（1時間ごと）**: 未払いのまま 24 時間たった注文は、Stripe 側の支払いを取り消したうえで削除。支払済の注文は既定で約 8 年（`ORDER_RETENTION_DAYS` で変更可）保存して削除。
- **受取番号**: 日本時間の日付が変わると 1 から振り直す。

## 法令対応

実装済み:

- **特定商取引法**: 表記ページ、および決済直前の最終確認画面に 分量・価格・支払方法と時期・引渡時期・キャンセル条件 を表示
- **消費税法（総額表示・インボイス）**: 価格は税込表示。明細に取引日時・品目・軽減税率対象の表示・税率ごとの合計と消費税額・登録番号（`stores.invoice_number`）を表示
- **割賦販売法（カード情報の非保持）**: カード情報は Stripe が直接取得。サーバー・DB には保存されない
- **個人情報保護法 / 電気通信事業法（外部送信規律）**: 会員登録なし・氏名や連絡先は取得しない。プライバシーポリシーに Stripe / Cloudflare への送信と外国での取扱いを記載
- **資金決済法**: 前払式のポイントや残高は持たないため対象外の設計

公開前に事業者側で必要な作業:

- `public/legal/` 内の黄色い【】箇所（事業者名・住所・連絡先など）を記入
- 適格請求書発行事業者の場合は `stores.invoice_number` に登録番号（T+13桁）を設定
- 酒類・たばこ等の年齢確認が必要な商品、医薬品等の許認可が必要な商品はこのアプリでは扱わない（年齢確認機能なし）
- 店内飲食（10%）と持ち帰り（8%）が混在する業態では税率の扱いを別途設計する
- 法定表記・規約の文面は雛形です。公開前に弁護士・税理士等の確認を受けてください
