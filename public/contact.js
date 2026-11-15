// お問い合わせチャット（お客様側）。店舗のスタッフは LINE で受け答えする
(async () => {
  const $ = (id) => document.getElementById(id);
  const el = (tag, cls, text) => { const e = document.createElement(tag); if (cls) e.className = cls; if (text != null) e.textContent = text; return e; };
  const showError = (msg) => { $('error').textContent = msg; $('error').hidden = !msg; };
  const time = (s) => new Date(s).toLocaleString('ja-JP', { timeZone: 'Asia/Tokyo', month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' });

  const storeId = decodeURIComponent(location.pathname.split('/')[2] || '');
  const orderId = new URLSearchParams(location.search).get('order') || '';
  $('back').href = '/s/' + encodeURIComponent(storeId);

  try {
    const res = await fetch('/api/stores/' + encodeURIComponent(storeId));
    if (!res.ok) throw new Error();
    const { store } = await res.json();
    $('store-name').textContent = store.name + ' お問い合わせ';
    document.title = store.name + ' | お問い合わせ';
  } catch {
    return showError('店舗が見つかりません。注文画面からもう一度開いてください。');
  }

  // やり取りは端末に保存した合言葉で引き継ぐ（会員登録は不要）
  const key = 'chat:' + storeId;
  let thread = null;
  try { thread = JSON.parse(localStorage.getItem(key) || 'null'); } catch { /* 保存データが読めなければ新しく始める */ }
  let lastId = 0;
  const seen = new Set();

  // 注文の選択：この端末で購入した注文を一覧にする（支払いが済んだ、この店舗の注文のみ）
  const select = $('order-select');
  const pickable = new Map();
  async function fillOrders() {
    const ids = [...new Set([orderId, ...(window.rememberedOrders ? window.rememberedOrders(storeId) : [])].filter(Boolean))];
    const found = [];
    await Promise.all(ids.map(async (id) => {
      try {
        const res = await fetch('/api/orders/' + encodeURIComponent(id), { cache: 'no-store' });
        if (!res.ok) return;
        const o = await res.json();
        if (o.status !== 'pending' && o.store.id === storeId) found.push(o);
      } catch { /* 取得できない注文は一覧に出さない */ }
    }));
    found.sort((a, b) => String(b.paidAt).localeCompare(String(a.paidAt)));
    for (const o of found) {
      pickable.set(o.id, o);
      select.append(new Option('注文番号 ' + String(o.pickupNo ?? '').padStart(3, '0') + '　' + time(o.paidAt || o.createdAt) + '　¥' + o.total.toLocaleString('ja-JP'), o.id));
    }
    if (orderId && pickable.has(orderId)) select.value = orderId;
    $('order-pick').hidden = !found.length || !!thread;
  }
  function showThreadState(orderNo) {
    $('order-pick').hidden = !!thread || pickable.size === 0;
    $('new-thread').hidden = !thread;
    $('order-note').hidden = !thread;
    if (thread) $('order-note').textContent = orderNo ? 'この問い合わせは、注文番号 ' + String(orderNo).padStart(3, '0') + ' についてです。' : 'この問い合わせは、特定の注文に関するものではありません。';
  }
  $('new-thread-btn').onclick = () => {
    thread = null; lastId = 0; seen.clear();
    try { localStorage.removeItem(key); } catch { /* 無視 */ }
    select.value = ''; // 前の注文を引き継がず、あらためて選んでもらう
    $('log').replaceChildren(); $('empty').hidden = false; showThreadState(null);
  };
  await fillOrders();

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
    if (!thread) return;
    try {
      const res = await fetch('/api/chat/' + encodeURIComponent(thread.threadId) + '?after=' + lastId, { headers: { 'X-Chat-Token': thread.token }, cache: 'no-store' });
      if (res.status === 404) { thread = null; try { localStorage.removeItem(key); } catch { /* 無視 */ } return; } // 保存期間が過ぎて消えた
      if (!res.ok) return;
      const body = await res.json();
      showThreadState(body.orderNo);
      for (const m of body.messages) add(m);
    } catch { /* 通信できないときは次の機会に取り直す */ }
  }

  $('body').addEventListener('input', () => { $('count').textContent = $('body').value.length + ' / 500'; });

  $('form').onsubmit = async (ev) => {
    ev.preventDefault();
    const text = $('body').value.trim();
    if (!text) return;
    const btn = ev.target.querySelector('button');
    btn.disabled = true;
    showError('');
    try {
      const res = await fetch('/api/stores/' + encodeURIComponent(storeId) + '/chat', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ threadId: thread ? thread.threadId : undefined, token: thread ? thread.token : undefined, orderId: !thread && select.value ? select.value : undefined, body: text }),
      });
      if (res.status === 429) throw new Error('送信の回数が多すぎます。少し待ってからもう一度お試しください。');
      if (!res.ok) throw new Error('送信できませんでした。もう一度お試しください。');
      const data = await res.json();
      thread = { threadId: data.threadId, token: data.token };
      try { localStorage.setItem(key, JSON.stringify(thread)); } catch { /* 保存できなくても、この画面を開いている間は続けられる */ }
      showThreadState(select.value ? pickable.get(select.value)?.pickupNo : null);
      add(data.message);
      $('body').value = '';
      $('count').textContent = '0 / 500';
    } catch (e) {
      showError(e.message || '送信できませんでした。通信状況を確認してください。');
    }
    btn.disabled = false;
  };

  await load();
  // 返信を待つ：画面を見ているあいだだけ、数秒おきに確認する
  setInterval(() => { if (!document.hidden) load(); }, 4000);
  document.addEventListener('visibilitychange', () => { if (!document.hidden) load(); });
})();
