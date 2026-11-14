(async () => {
  const $ = (id) => document.getElementById(id);
  const yen = (n) => '¥' + n.toLocaleString('ja-JP');
  const el = (tag, cls, text) => { const e = document.createElement(tag); if (cls) e.className = cls; if (text != null) e.textContent = text; return e; };
  const showError = (msg) => { $('error').textContent = msg; $('error').hidden = false; };
  const MAX_QTY = 20;

  const storeId = decodeURIComponent(location.pathname.split('/')[2] || '');
  const cartKey = 'cart:' + storeId;
  const lastOrder = localStorage.getItem('lastOrder:' + storeId);
  if (lastOrder) {
    $('last-order').hidden = false;
    $('last-order').querySelector('a').href = '/order/' + lastOrder;
  }

  let data, config;
  try {
    const [s, c] = await Promise.all([fetch('/api/stores/' + encodeURIComponent(storeId), { cache: 'no-store' }), fetch('/api/config')]);
    if (s.status === 404) return showError('店舗が見つかりません。QRコードをもう一度読み取ってください。');
    if (!s.ok || !c.ok) throw new Error();
    data = await s.json();
    config = await c.json();
  } catch {
    return showError('読み込みに失敗しました。通信状況を確認して再読み込みしてください。');
  }

  $('store-name').textContent = data.store.name;
  document.title = data.store.name + ' | ご注文';
  const products = new Map(data.products.map((p) => [p.id, p])); // 販売中（有効）の商品のみ

  // --- カート（QR を読み取るたびにページが開き直るため端末に保存する） ---
  const cart = new Map(); // productId -> qty
  try {
    for (const [id, q] of JSON.parse(localStorage.getItem(cartKey) || '[]')) {
      if (products.has(id) && Number.isInteger(q) && q > 0) cart.set(id, Math.min(q, MAX_QTY));
    }
  } catch { /* 壊れた保存データは無視 */ }
  const saveCart = () => { try { localStorage.setItem(cartKey, JSON.stringify([...cart])); } catch { /* 保存できなくても続行 */ } };
  const total = () => [...cart].reduce((s, [id, q]) => s + products.get(id).price * q, 0);

  let paying = false;
  let noticeTimer;
  function notice(msg, warn) {
    $('notice').textContent = msg;
    $('notice').className = 'notice' + (warn ? ' warn' : '');
    $('notice').hidden = false;
    clearTimeout(noticeTimer);
    noticeTimer = setTimeout(() => { $('notice').hidden = true; }, 4000);
  }

  function setQty(id, q) {
    if (paying) return;
    q = Math.max(0, Math.min(MAX_QTY, q));
    if (q) cart.set(id, q); else cart.delete(id);
    saveCart();
    render();
  }

  /** 商品をカートに1点追加する。追加できたら true */
  function addProduct(productId) {
    const p = products.get(productId);
    if (!p) { notice('この商品は現在販売していません。', true); return false; }
    const q = cart.get(productId) || 0;
    if (q >= MAX_QTY) { notice(p.name + ' は1回のご注文で' + MAX_QTY + '点までです。', true); return false; }
    setQty(productId, q + 1);
    notice(p.name + ' をカートに入れました。');
    return true;
  }

  /** 読み取った QR の文字列を解釈してカートに追加する */
  function handleCode(text) {
    let url;
    try { url = new URL(text); } catch { notice('商品の QR コードではありません。', true); return false; }
    const parts = url.pathname.split('/');
    const add = url.searchParams.get('add');
    if (url.origin !== location.origin || parts[1] !== 's' || !add) { notice('商品の QR コードではありません。', true); return false; }
    if (decodeURIComponent(parts[2] || '') !== storeId) { notice('別の店舗の商品です。', true); return false; }
    return addProduct(add);
  }

  // --- Stripe（カード / Apple Pay / Google Pay / Link / PayPay。表示される手段は Stripe の設定に従う） ---
  let stripe = null, elements = null;

  function payNote(msg) {
    $('pay-note').textContent = msg;
    $('pay-note').hidden = !msg;
  }

  function setupPayment(amount) {
    if (!window.Stripe || !config.publishableKey) {
      return payNote('現在お支払いを受け付けられません。店舗スタッフにお声がけください。');
    }
    stripe = Stripe(config.publishableKey, { locale: 'ja' });
    elements = stripe.elements({ mode: 'payment', amount, currency: 'jpy' });
    const payment = elements.create('payment', { layout: { type: 'accordion', defaultCollapsed: false, radios: true, spacedAccordionItems: false } });
    payment.on('ready', () => { $('pay-button').hidden = false; });
    payment.on('loaderror', () => payNote('決済方法を読み込めませんでした。再読み込みしてください。'));
    payment.mount('#pay');
  }

  async function pay() {
    if (paying || !elements || cart.size === 0) return;
    paying = true;
    $('pay-button').disabled = true;
    $('error').hidden = true;
    payNote('');
    try {
      // 入力内容の確認（カード番号の不備などはここで止まり、注文は作られない）
      const { error: submitError } = await elements.submit();
      if (submitError) throw new Error(submitError.message);

      const res = await fetch('/api/orders', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ storeId, items: [...cart].map(([productId, qty]) => ({ productId, qty })) }),
      });
      if (!res.ok) {
        throw new Error(res.status === 409
          ? '販売を終了した商品が含まれています。再読み込みしてカートを確認してください。'
          : res.status === 502
            ? '決済サービスに接続できませんでした。お支払いは発生していません。店舗スタッフにお声がけください。'
            : '注文を作成できませんでした。お支払いは発生していません。');
      }
      const { orderId, clientSecret } = await res.json();
      localStorage.setItem('lastOrder:' + storeId, orderId);

      // PayPay などは外部の画面へ移動し、完了後に注文状況の画面へ戻る
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
      $('error').scrollIntoView({ block: 'center' });
      paying = false;
      $('pay-button').disabled = false;
    }
  }
  $('pay-button').onclick = pay;

  // --- 画面 ---
  function render() {
    const sum = total();
    const cards = [], rows = [];
    for (const [id, q] of cart) {
      const p = products.get(id);
      const mark = p.taxRate === 8 ? ' ※' : '';

      const card = el('div', 'card product');
      const name = el('div', 'name');
      name.append(el('div', null, p.name + mark), el('div', 'price', yen(p.price) + '（税込）'));
      const stepper = el('div', 'stepper');
      const minus = el('button', null, q === 1 ? '×' : '−'), out = el('output', null, String(q)), plus = el('button', null, '＋');
      minus.setAttribute('aria-label', q === 1 ? p.name + 'を削除' : p.name + 'を減らす');
      plus.setAttribute('aria-label', p.name + 'を増やす');
      plus.disabled = q >= MAX_QTY;
      minus.onclick = () => setQty(id, q - 1);
      plus.onclick = () => setQty(id, q + 1);
      stepper.append(minus, out, plus);
      card.append(name, stepper);
      cards.push(card);

      const tr = el('tr');
      tr.append(el('td', null, p.name + mark + ' × ' + q), el('td', 'num', yen(p.price * q)));
      rows.push(tr);
    }
    $('cart').replaceChildren(...(cards.length ? cards : [el('p', 'note', 'カートは空です。商品の QR コードをスキャンしてください。')]));

    const tr = el('tr', 'sum');
    tr.append(el('td', null, '合計（税込）'), el('td', 'num', yen(sum)));
    $('lines').replaceChildren(...rows, tr);

    // 商品が1点でも入っていれば決済方法を表示する
    const hasItems = cart.size > 0;
    $('confirm').hidden = !hasItems;
    if (hasItems) {
      if (!elements) setupPayment(sum);
      else elements.update({ amount: sum });
    }
    $('pay-button').textContent = yen(sum) + ' を支払う';
  }

  // --- カメラで商品 QR を読み取る（映像は端末内だけで処理し、送信しない） ---
  const video = $('scan-video');
  const canvas = document.createElement('canvas');
  let stream = null, raf = 0, lastText = '', lastSeen = 0;

  function stopScan() {
    cancelAnimationFrame(raf);
    if (stream) stream.getTracks().forEach((t) => t.stop());
    stream = null;
    video.srcObject = null;
    $('scanner').hidden = true;
    $('scan-open').hidden = false;
  }

  function tick() {
    if (!stream) return;
    if (video.readyState >= 2 && video.videoWidth) {
      const scale = Math.min(1, 640 / Math.max(video.videoWidth, video.videoHeight));
      canvas.width = Math.round(video.videoWidth * scale);
      canvas.height = Math.round(video.videoHeight * scale);
      const ctx = canvas.getContext('2d', { willReadFrequently: true });
      ctx.drawImage(video, 0, 0, canvas.width, canvas.height);
      const img = ctx.getImageData(0, 0, canvas.width, canvas.height);
      const code = window.jsQR(img.data, img.width, img.height, { inversionAttempts: 'dontInvert' });
      if (code && code.data) {
        const now = Date.now();
        // 同じ QR を写し続けても1回だけ追加する。いったん外して2秒たつと、もう1点追加できる
        if (code.data !== lastText || now - lastSeen > 2000) {
          const ok = handleCode(code.data);
          $('scan-status').textContent = $('notice').textContent;
          if (ok && navigator.vibrate) navigator.vibrate(60);
        }
        lastText = code.data;
        lastSeen = now;
      }
    }
    raf = requestAnimationFrame(tick);
  }

  async function startScan() {
    if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia || !window.jsQR) {
      return notice('このブラウザではカメラを使えません。スマートフォン標準のカメラアプリで商品の QR コードを読み取ってください。', true);
    }
    try {
      stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: { ideal: 'environment' } }, audio: false });
    } catch {
      return notice('カメラを起動できませんでした。ブラウザの設定でカメラの使用を許可してください。', true);
    }
    lastText = '';
    $('scanner').hidden = false;
    $('scan-open').hidden = true;
    $('scan-status').textContent = '商品の QR コードを枠内に写してください。';
    video.srcObject = stream;
    await video.play().catch(() => {});
    tick();
  }

  $('scan-open').onclick = startScan;
  $('scan-close').onclick = stopScan;
  document.addEventListener('visibilitychange', () => { if (document.hidden && stream) stopScan(); });
  $('scan-open').hidden = false;

  // 標準カメラアプリで商品 QR を読み取って開いた場合（/s/<店舗>?add=<商品ID>）
  const add = new URLSearchParams(location.search).get('add');
  if (add) {
    history.replaceState(null, '', location.pathname); // 再読み込みで二重に追加しない
    addProduct(add);
  }
  render();
})();
