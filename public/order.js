(() => {
  const $ = (id) => document.getElementById(id);
  const yen = (n) => '¥' + n.toLocaleString('ja-JP');
  const el = (tag, cls, text) => { const e = document.createElement(tag); if (cls) e.className = cls; if (text != null) e.textContent = text; return e; };
  const dt = (s) => new Date(s).toLocaleString('ja-JP', { timeZone: 'Asia/Tokyo', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' });
  const orderId = location.pathname.split('/')[2] || '';
  const started = Date.now();

  // スクリーンショットではなく実際の画面であることが分かるよう現在時刻を動かす
  setInterval(() => { $('clock').textContent = new Date().toLocaleTimeString('ja-JP'); }, 1000);

  function row(label, value, cls) {
    const tr = el('tr', cls);
    tr.append(el('td', null, label), el('td', 'num', value));
    return tr;
  }

  function render(o) {
    $('store-name').textContent = o.store.name;
    // 支払いが済んだらカートを空にする
    if (o.status !== 'pending') { try { localStorage.removeItem('cart:' + o.store.id); } catch { /* 無視 */ } }
    $('ticket').className = 'card ticket ' + o.status;
    const view = {
      pending: ['お支払いを確認しています…', 'まだ商品はお受け取りいただけません。このままお待ちください。'],
      paid: ['お支払い完了', 'この画面を店舗スタッフにお見せください。受取番号を照合して商品をお渡しします。'],
      handed_over: ['お受け取り済み', 'ご利用ありがとうございました。'],
      refunded: ['返金済み', 'このご注文は返金されました。'],
    }[o.status];
    $('state').textContent = view[0];
    $('hint').textContent = view[1];
    const showNo = o.status === 'paid' || o.status === 'handed_over';
    $('pickup').hidden = !showNo;
    $('pickup').textContent = o.pickupNo == null ? '…' : String(o.pickupNo).padStart(3, '0');

    $('receipt').hidden = o.status === 'pending';
    $('receipt-head').textContent = [
      o.store.name,
      o.store.invoiceNumber ? '登録番号 ' + o.store.invoiceNumber : null,
      o.paidAt ? '取引日時 ' + dt(o.paidAt) : null,
      '注文ID ' + o.id,
    ].filter(Boolean).join(' / ');
    $('lines').replaceChildren(
      ...o.items.map((i) => row(i.name + (i.taxRate === 8 ? ' ※' : '') + ' × ' + i.qty, yen(i.unitPrice * i.qty))),
      row('合計（税込）', yen(o.total), 'sum'),
      ...o.tax.map((t) => row(t.rate + '%対象 ' + yen(t.subtotal) + '（内消費税）', yen(t.tax))),
    );
  }

  async function poll() {
    let next = 3000;
    try {
      const res = await fetch('/api/orders/' + encodeURIComponent(orderId), { cache: 'no-store' });
      if (res.status === 404) {
        $('state').textContent = 'ご注文が見つかりません';
        return;
      }
      if (res.ok) {
        const o = await res.json();
        render(o);
        if (o.status === 'handed_over' || o.status === 'refunded') return;
        if (o.status === 'pending') {
          next = 2000;
          if (Date.now() - started > 5 * 60 * 1000) {
            $('state').textContent = 'お支払いを確認できませんでした';
            $('hint').textContent = 'お支払いが完了していない可能性があります。店舗スタッフにお声がけください。';
            return;
          }
        }
      }
    } catch { /* 通信エラー時は再試行 */ }
    setTimeout(poll, next);
  }
  poll();
})();
