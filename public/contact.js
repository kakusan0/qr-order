// お問い合わせチャット（お客様側）。店舗のスタッフは LINE で受け答えする。
// 注文についての問い合わせは、注文ごとに 1 つのやり取りにまとめる（購入の証明のリンクがあれば、どの端末からでも続きを開ける）。
// 注文に関係しない問い合わせは「注文は選ばない」で送る（この端末に保存した合言葉で続きを開く）。
(async () => {
  const $ = (id) => document.getElementById(id);
  const el = (tag, cls, text) => { const e = document.createElement(tag); if (cls) e.className = cls; if (text != null) e.textContent = text; return e; };
  const showError = (msg) => { $('error').textContent = msg; $('error').hidden = !msg; };
  const time = (s) => new Date(s).toLocaleString('ja-JP', { timeZone: 'Asia/Tokyo', month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' });
  const no = (n) => String(n ?? '').padStart(3, '0');

  const storeId = decodeURIComponent(location.pathname.split('/')[2] || '');
  const urlOrder = new URLSearchParams(location.search).get('order') || '';
  $('back').href = urlOrder ? '/order/' + encodeURIComponent(urlOrder) : '/s/' + encodeURIComponent(storeId);

  try {
    const res = await fetch('/api/stores/' + encodeURIComponent(storeId));
    if (!res.ok) throw new Error();
    const { store } = await res.json();
    $('store-name').textContent = store.name + ' お問い合わせ';
    document.title = store.name + ' | お問い合わせ';
  } catch {
    return showError('店舗が見つかりません。注文画面からもう一度開いてください。');
  }

  // 注文の一覧：この端末で購入した注文（支払いが済んだ、この店舗の注文のみ）と、リンクで開いた注文
  const select = $('order-select');
  const orders = new Map();
  const ids = [...new Set([urlOrder, ...(window.rememberedOrders ? window.rememberedOrders(storeId) : [])].filter(Boolean))];
  await Promise.all(ids.map(async (id) => {
    try {
      const res = await fetch('/api/orders/' + encodeURIComponent(id), { cache: 'no-store' });
      if (!res.ok) return;
      const o = await res.json();
      if (o.status !== 'pending' && o.store.id === storeId) orders.set(o.id, o);
    } catch { /* 取得できない注文は一覧に出さない */ }
  }));
  const sorted = [...orders.values()].sort((a, b) => String(b.paidAt).localeCompare(String(a.paidAt)));
  for (const o of sorted) select.append(new Option('注文番号 ' + no(o.pickupNo) + '　' + time(o.paidAt || o.createdAt) + '　¥' + o.total.toLocaleString('ja-JP'), o.id));
  // 最初は、リンクで開いた注文。なければ、この端末でいちばん新しい注文
  select.value = urlOrder && orders.has(urlOrder) ? urlOrder : sorted.length ? sorted[0].id : '';
  $('order-pick').hidden = false;

  // 注文に紐づかない問い合わせの合言葉
  const generalKey = 'chat:' + storeId;
  let general = null;
  try { general = JSON.parse(localStorage.getItem(generalKey) || 'null'); } catch { /* 新しく始める */ }

  let lastId = 0, seen = new Set(), loading = 0;
  function reset() {
    lastId = 0; seen = new Set();
    $('log').replaceChildren(); $('empty').hidden = false;
    const o = orders.get(select.value);
    $('order-note').textContent = o
      ? '注文番号 ' + no(o.pickupNo) + ' についてのやり取りです。この注文の問い合わせは、すべてここにまとまります。'
      : '特定の注文に関係しないお問い合わせです。';
  }
  function add(m) {
    if (seen.has(m.id)) return;
    seen.add(m.id);
    lastId = Math.max(lastId, m.id);
    const row = el('div', 'chat-msg ' + (m.sender === 'customer' ? 'mine' : 'theirs'));
    row.append(el('div', 'chat-who', m.sender === 'customer' ? 'あなた' : '店舗スタッフ'), el('div', 'chat-bubble', m.body), el('div', 'chat-time', time(m.createdAt)));
    $('log').append(row);
    $('empty').hidden = true;
    row.scrollIntoView({ block: 'end', behavior: 'smooth' });
  }
  async function load() {
    const ticket = ++loading;
    const orderId = select.value;
    try {
      let res;
      if (orderId) res = await fetch('/api/orders/' + encodeURIComponent(orderId) + '/chat?after=' + lastId, { cache: 'no-store' });
      else if (general) res = await fetch('/api/chat/' + encodeURIComponent(general.threadId) + '?after=' + lastId, { headers: { 'X-Chat-Token': general.token }, cache: 'no-store' });
      else return;
      if (ticket !== loading || orderId !== select.value) return; // 途中で注文を切り替えた
      if (!orderId && res.status === 404) { general = null; try { localStorage.removeItem(generalKey); } catch { /* 無視 */ } return; }
      if (!res.ok) return;
      for (const m of (await res.json()).messages) add(m);
    } catch { /* 通信できないときは次の機会に取り直す */ }
  }
  select.onchange = () => { reset(); load(); };

  $('body').addEventListener('input', () => { $('count').textContent = $('body').value.length + ' / 500'; });
  $('form').onsubmit = async (ev) => {
    ev.preventDefault();
    const text = $('body').value.trim();
    if (!text) return;
    const btn = ev.target.querySelector('button');
    btn.disabled = true;
    showError('');
    const orderId = select.value;
    try {
      const res = orderId
        ? await fetch('/api/orders/' + encodeURIComponent(orderId) + '/chat', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ body: text }) })
        : await fetch('/api/stores/' + encodeURIComponent(storeId) + '/chat', {
          method: 'POST', headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ threadId: general ? general.threadId : undefined, token: general ? general.token : undefined, body: text }),
        });
      if (res.status === 429) throw new Error('送信の回数が多すぎます。少し待ってからもう一度お試しください。');
      if (!res.ok) throw new Error('送信できませんでした。もう一度お試しください。');
      const data = await res.json();
      if (!orderId) {
        general = { threadId: data.threadId, token: data.token };
        try { localStorage.setItem(generalKey, JSON.stringify(general)); } catch { /* この画面を開いている間は続けられる */ }
      }
      if (orderId === select.value) add(data.message);
      $('body').value = '';
      $('count').textContent = '0 / 500';
    } catch (e) {
      showError(e.message || '送信できませんでした。通信状況を確認してください。');
    }
    btn.disabled = false;
  };

  reset();
  await load();
  // 返信を待つ：画面を見ているあいだだけ、数秒おきに確認する
  setInterval(() => { if (!document.hidden) load(); }, 4000);
  document.addEventListener('visibilitychange', () => { if (!document.hidden) load(); });
})();
