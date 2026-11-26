# QR オーダー（kakusan0/qr-order）— 次のセッションへの引き継ぎ

**このリポジトリは、いまは公開に使っていません。** QR オーダーは作品 wiki のリポジトリ `kakusan0/wiki` の `apps/qr-order/` に移り、`https://wiki.infoapp.org/qr-order/` で動いています（Worker `wiki` の 1 つにまとめた。Cloudflare に `qr-order` という Worker はもうない）。

- 直すとき・機能を足すときは、`kakusan0/wiki` の `apps/qr-order/` を直してください。仕様のまとめは wiki の `CLAUDE.md` と `apps/qr-order/README.md` にあります。
- このリポジトリには、移す前の履歴が残っています。wiki の側では、ページ一覧（`/wiki`）のパスキー確認をなくし、作品 wiki のページ（`/wiki/qr-order/#pages`）へ 301 で移すように変えてあります。このリポジトリの `src/wiki.html` は古い形です。
- 店舗管理画面（`/staff`）のパスキーでのログインは、wiki の側でもそのまま残しています（利用者の決定）。
- 守ること：キーやトークンはチャット・コード・Git に書かない。無料のプランだけを使う。マージは利用者が「マージして」と言ったときだけ。
