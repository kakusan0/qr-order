# QR オーダー（店頭 QR 注文・事前決済）

店頭の QR コードを読み取り → 商品を選択 → Apple Pay / Google Pay で決済 → 受取番号を提示して商品を受け取る、という流れのアプリです。

- アプリ: Cloudflare Workers（`src/index.ts`）+ 静的アセット（`public/`）
- データベース: Cloudflare D1（`migrations/`）
- 決済: Stripe（Apple Pay / Google Pay のみ表示。カード情報は当システムを通過・保存しません）

## 画面

| URL | 用途 |
| --- | --- |
| `/s/<店舗ID>` | お客様の注文画面（QR コードの飛び先） |
| `/order/<注文ID>` | 決済後の受取番号・ご利用明細 |
| `/staff` | スタッフ用：支払済注文の一覧、受け渡し完了、返金、掲示用 QR（Basic 認証・パスワードは `STAFF_PASSWORD`） |
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
npx wrangler secret put STAFF_PASSWORD
npm run deploy
```

Stripe 側の設定:

1. ダッシュボードで Apple Pay / Google Pay を有効化し、公開ドメインを「支払い方法のドメイン」に登録（Apple Pay に必須）。
2. Webhook エンドポイント `https://<ドメイン>/api/stripe/webhook` を追加し、イベント `payment_intent.succeeded` を選択。署名シークレットを `STRIPE_WEBHOOK_SECRET` に設定。
3. まずテストキー（`sk_test_` / `pk_test_`）で実機確認してから本番キーに切り替え。

ローカル開発は `.dev.vars.example` を `.dev.vars` にコピーし、`npm run db:migrate:local && npm run db:seed:local && npm run dev`。

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
