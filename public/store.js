(async () => {
  const $ = (id) => document.getElementById(id);
  const yen = (n) => '¥' + n.toLocaleString('ja-JP');
  const el = (tag, cls, text) => { const e = document.createElement(tag); if (cls) e.className = cls; if (text != null) e.textContent = text; return e; };
  const showError = (msg) => { $('error').textContent = msg; $('error').hidden = false; };
  const MIN_AMOUNT = 50;

  const storeId = decodeURIComponent(location.pathname.split('/')[2] || '');
  const lastOrder = localStorage.getItem('lastOrder:' + storeId);
  if (lastOrder) {
    $('last-order').hidden = false;
    $('last-order').querySelector('a').href = '/order/' + lastOrder;
  }

  let data, config;
  try {
    const [s, c] = await Promise.all([fetch('/api/stores/' + encodeURIComponent(storeId)), fetch('/api/config')]);
    if (s.status === 404) return showError('店舗が見つかりません。QRコードをもう一度読み取ってください。');
    if (!s.ok || !c.ok) throw new Error();
    data = await s.json();
    config = await c.json();
  } catch {
    return showError('読み込みに失敗しました。通信状況を確認して再読み込みしてください。');
  }

  $('store-name').textContent = data.store.name;
  document.title = data.store.name + ' | ご注文';
  const cart = new Map(); // productId -> qty
  const total = () => data.products.reduce((s, p) => s + p.price * (cart.get(p.id) || 0), 0);

  // --- Stripe（Apple Pay / Google Pay のみ） ---
  let stripe = null, elements = null, paying = false;

  function setupPayment(amount) {
    if (!window.Stripe || !config.publishableKey) {
      $('pay-note').textContent = '現在お支払いを受け付けられません。店舗スタッフにお声がけください。';
      $('pay-note').hidden = false;
      return;
    }
    stripe = Stripe(config.publishableKey, { locale: 'ja' });
    elements = stripe.elements({ mode: 'payment', amount, currency: 'jpy' });
    const ece = elements.create('expressCheckout', {
      paymentMethods: { applePay: 'always', googlePay: 'always', link: 'never', amazonPay: 'never', paypal: 'never', klarna: 'never' },
      buttonHeight: 48,
    });
    ece.on('ready', ({ availablePaymentMethods }) => {
      if (!availablePaymentMethods) {
        $('pay-note').textContent = 'この端末・ブラウザでは Apple Pay / Google Pay をご利用いただけません。ウォレットにカードを登録のうえ、iPhone は Safari、Android は Chrome でお試しください。';
        $('pay-note').hidden = false;
      }
    });
    ece.on('confirm', async (event) => {
      if (paying) return;
      paying = true;
      try {
        const { error: submitError } = await elements.submit();
        if (submitError) throw new Error(submitError.message);

        const res = await fetch('/api/orders', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ storeId, items: [...cart].map(([productId, qty]) => ({ productId, qty })) }),
        });
        if (!res.ok) {
          event.paymentFailed({ reason: 'fail' });
          throw new Error(res.status === 409
            ? '販売を終了した商品が含まれています。再読み込みして選び直してください。'
            : res.status === 502
              ? '決済サービスに接続できませんでした。お支払いは発生していません。店舗スタッフにお声がけください。'
              : '注文を作成できませんでした。お支払いは発生していません。');
        }
        const { orderId, clientSecret } = await res.json();
        localStorage.setItem('lastOrder:' + storeId, orderId);

        const { error } = await stripe.confirmPayment({
          elements,
          clientSecret,
          confirmParams: { return_url: location.origin + '/order/' + orderId },
          redirect: 'if_required',
        });
        if (error) {
          fetch('/api/orders/' + orderId + '/client-error', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ message: [error.type, error.code, error.decline_code, error.message].filter(Boolean).join(' | ') }),
          }).catch(() => {});
          throw new Error(error.message || 'お支払いを完了できませんでした。');
        }
        location.href = '/order/' + orderId;
      } catch (e) {
        showError(e.message);
        paying = false;
      }
    });
    ece.mount('#pay');
  }

  // --- 画面 ---
  function render() {
    const sum = total();
    const rows = [];
    for (const p of data.products) {
      const q = cart.get(p.id) || 0;
      if (!q) continue;
      const tr = el('tr');
      tr.append(el('td', null, p.name + (p.taxRate === 8 ? ' ※' : '') + ' × ' + q), el('td', 'num', yen(p.price * q)));
      rows.push(tr);
    }
    const tr = el('tr', 'sum');
    tr.append(el('td', null, '合計（税込）'), el('td', 'num', yen(sum)));
    $('lines').replaceChildren(...rows, tr);

    const ok = sum >= MIN_AMOUNT;
    $('confirm').hidden = !ok;
    if (ok) {
      if (!elements) setupPayment(sum);
      else elements.update({ amount: sum });
    }
  }

  for (const p of data.products) {
    const row = el('div', 'card product');
    const name = el('div', 'name');
    name.append(el('div', null, p.name + (p.taxRate === 8 ? ' ※' : '')), el('div', 'price', yen(p.price) + '（税込）'));
    const stepper = el('div', 'stepper');
    const minus = el('button', null, '−'), out = el('output', null, '0'), plus = el('button', null, '＋');
    minus.setAttribute('aria-label', p.name + 'を減らす');
    plus.setAttribute('aria-label', p.name + 'を増やす');
    minus.disabled = true;
    const set = (q) => {
      if (paying) return;
      q = Math.max(0, Math.min(20, q));
      if (q) cart.set(p.id, q); else cart.delete(p.id);
      out.textContent = q;
      minus.disabled = q === 0;
      plus.disabled = q === 20;
      render();
    };
    minus.onclick = () => set((cart.get(p.id) || 0) - 1);
    plus.onclick = () => set((cart.get(p.id) || 0) + 1);
    stepper.append(minus, out, plus);
    row.append(name, stepper);
    $('products').append(row);
  }
  if (!data.products.length) $('products').append(el('p', 'note', '現在販売中の商品はありません。'));
})();
