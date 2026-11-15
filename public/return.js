// 返品・交換のお申し込み（お客様側）
(async () => {
  const $ = (id) => document.getElementById(id);
  const el = (tag, cls, text) => { const e = document.createElement(tag); if (cls) e.className = cls; if (text != null) e.textContent = text; return e; };
  const yen = (n) => '¥' + n.toLocaleString('ja-JP');
  const showError = (msg) => { $('error').textContent = msg; $('error').hidden = !msg; if (msg) $('error').scrollIntoView({ block: 'center' }); };
  const day = (s) => new Date(s).toLocaleDateString('ja-JP', { timeZone: 'Asia/Tokyo', year: 'numeric', month: 'long', day: 'numeric' });
  const dateTime = (s) => new Date(s).toLocaleString('ja-JP', { timeZone: 'Asia/Tokyo', month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' });

  const orderId = decodeURIComponent(location.pathname.split('/')[2] || '');
  $('back').href = '/order/' + encodeURIComponent(orderId);

  const MAX_PHOTOS = 5, MIN_PHOTOS = 2;
  // 写真の欄。種類ごとに用途を示す（whole は必須）
  const KINDS = [
    { kind: 'whole', label: '商品の全体', required: true },
    { kind: 'defect', label: '不具合の箇所のアップ' },
    { kind: 'label', label: 'ラベル・期限表示・バーコード' },
    { kind: 'other', label: 'その他（袋・箱・レシート画面など）' },
  ];
  const TIPS = {
    defect: '「商品の全体」と、傷・破れ・汚れなどの「不具合の箇所のアップ」を撮ってください。',
    wrong_item: '届いた商品の「全体」と、商品名・バーコードが分かる「ラベル」を撮ってください。',
    expired: '期限が印字された「ラベル」と、商品の「全体」を撮ってください。傷み・異物は、その箇所のアップも撮ってください。',
    other: '状況が分かる「商品の全体」と、気になる箇所を撮ってください。',
  };

  let data;
  async function load() {
    const res = await fetch('/api/orders/' + encodeURIComponent(orderId) + '/returns', { cache: 'no-store' });
    if (!res.ok) throw new Error(String(res.status));
    data = await res.json();
  }
  try { await load(); } catch { return showError('注文が見つかりません。メールのボタン、または購入の証明の画面から開き直してください。'); }

  $('store-name').textContent = data.store.name + ' 返品・交換';
  document.title = data.store.name + ' | 返品・交換のお申し込み';
  $('contact-link').href = '/contact/' + encodeURIComponent(data.store.id) + '?order=' + encodeURIComponent(orderId);
  $('sum-no').textContent = String(data.orderNo ?? '').padStart(3, '0');
  $('sum-total').textContent = yen(data.total);
  $('sum-deadline').textContent = day(data.deadline) + ' まで（お支払いから ' + data.windowDays + ' 日以内）';
  $('summary').hidden = false;

  const STATUS = {
    submitted: ['確認中', '店舗スタッフが内容を確認しています。結果はこの画面に表示されます（開き直して確認してください）。'],
    approved: (r) => ['承認されました', (r.resolution === 'exchange' ? '同じ商品と交換します。' : '返金します。') + '商品と、購入の証明の画面を、店頭にお持ちください。確認後に' + (r.resolution === 'exchange' ? '交換' : '返金') + 'します。'],
    completed: (r) => ['完了しました', r.resolution === 'exchange' ? '交換が完了しました。' : yen(r.refundAmount) + ' を返金しました。決済に使われた支払方法へ戻ります（反映までに日数がかかることがあります）。'],
    rejected: ['お申し出に沿えませんでした', '内容をご確認のうえ、ご不明な点は店舗にお問い合わせください。'],
  };
  function renderHistory() {
    $('history').hidden = data.returns.length === 0;
    $('history-list').replaceChildren(...data.returns.map((r) => {
      const [title, text] = typeof STATUS[r.status] === 'function' ? STATUS[r.status](r) : STATUS[r.status];
      const c = el('div', 'card ret-card ' + r.status);
      const head = el('div', 'ret-head');
      head.append(el('strong', null, '#' + r.code), el('span', 'ret-state', title), el('span', 'note', dateTime(r.createdAt)));
      c.append(head, el('p', 'ret-items', r.items.map((i) => i.name + ' × ' + i.qty).join('、')), el('p', null, text));
      if (r.staffNote) { const n = el('p', 'ret-note'); n.append(el('strong', null, '店舗からのメッセージ: '), document.createTextNode(r.staffNote)); c.append(n); }
      return c;
    }));
  }
  renderHistory();

  const usable = data.items.filter((i) => i.available > 0);
  if (!data.open || usable.length === 0) {
    $('closed').textContent = data.refunded ? 'この注文は返金済みです。' : !data.open ? '申込期限（お支払いから ' + data.windowDays + ' 日以内）を過ぎています。ご事情がある場合は、チャットで店舗にお問い合わせください。' : 'この注文の商品は、すべて申し込み済みです。';
    $('closed').hidden = false;
    return;
  }

  // 対象の商品と数量
  const picks = new Map();
  $('items').replaceChildren(...usable.map((i) => {
    const row = el('div', 'ret-item');
    const cb = el('input'); cb.type = 'checkbox'; cb.id = 'it-' + i.productId; cb.setAttribute('aria-label', i.name);
    const label = el('label'); label.htmlFor = cb.id; label.append(el('span', 'ret-name', i.name), el('span', 'note', yen(i.unitPrice) + '（税込）'));
    const sel = el('select'); sel.setAttribute('aria-label', i.name + ' の数量'); sel.disabled = true;
    for (let q = 1; q <= i.available; q++) sel.append(new Option(q + ' 個', String(q)));
    cb.onchange = () => { sel.disabled = !cb.checked; if (cb.checked) picks.set(i.productId, sel); else picks.delete(i.productId); };
    row.append(cb, label, sel);
    return row;
  }));

  // 写真：選んだ画像は端末で縮小・再保存してから送る（撮影時の位置情報などが消える）
  const photos = []; // { kind, data(base64), url }
  function renderSlots() {
    $('slots').replaceChildren(...KINDS.map((k) => {
      const mine = photos.filter((p) => p.kind === k.kind);
      const box = el('div', 'slot' + (k.required && !mine.length ? ' need' : ''));
      box.append(el('div', 'slot-label', k.label + (k.required ? '（必須）' : '')));
      const thumbs = el('div', 'thumbs');
      for (const p of mine) {
        const t = el('div', 'thumb');
        const img = el('img'); img.src = p.url; img.alt = k.label;
        const rm = el('button', 'thumb-x', '×'); rm.type = 'button'; rm.setAttribute('aria-label', '写真を削除');
        rm.onclick = () => { photos.splice(photos.indexOf(p), 1); URL.revokeObjectURL(p.url); renderSlots(); };
        t.append(img, rm); thumbs.append(t);
      }
      if (photos.length < MAX_PHOTOS) {
        // カメラで撮る：画面の中でカメラを起動する（端末のカメラアプリに頼らない）
        const shoot = el('button', 'add-photo primary-soft', 'カメラで撮る');
        shoot.type = 'button';
        shoot.onclick = () => openCamera(k);
        // 写真を選ぶ：撮影済みの写真を使う。画面内のカメラが使えない端末では、端末のカメラアプリを開く
        const pick = el('label', 'add-photo');
        const input = el('input'); input.type = 'file'; input.accept = 'image/*'; input.hidden = true;
        if (!camSupported) input.setAttribute('capture', 'environment');
        input.onchange = async () => {
          const file = input.files && input.files[0];
          if (!file) return;
          showError('');
          try { photos.push({ kind: k.kind, ...(await shrink(file)) }); } catch { showError('この写真を読み込めませんでした。別の写真でお試しください。'); }
          renderSlots();
        };
        pick.append(input, document.createTextNode(camSupported ? '写真を選ぶ' : '写真を撮る・選ぶ'));
        if (camSupported) thumbs.append(shoot);
        thumbs.append(pick);
      }
      box.append(thumbs);
      return box;
    }));
    $('submit').disabled = false;
  }
  const shrink = async (file) => encode(await createImageBitmap(file, { imageOrientation: 'from-image' }));
  /** 画像（写真ファイル・カメラの映像）を長辺 1280px 以内の JPEG にする。作り直すので、撮影データ（位置情報など）は残らない */
  async function encode(src) {
    const w = src.videoWidth || src.width, h = src.videoHeight || src.height;
    const scale = Math.min(1, 1280 / Math.max(w, h));
    const c = document.createElement('canvas');
    c.width = Math.round(w * scale); c.height = Math.round(h * scale);
    const ctx = c.getContext('2d'); ctx.fillStyle = '#fff'; ctx.fillRect(0, 0, c.width, c.height); ctx.drawImage(src, 0, 0, c.width, c.height);
    let blob;
    for (const q of [0.82, 0.7, 0.58, 0.46]) { blob = await new Promise((r) => c.toBlob(r, 'image/jpeg', q)); if (blob.size <= 480_000) break; }
    const b64 = await new Promise((resolve, reject) => { const fr = new FileReader(); fr.onload = () => resolve(String(fr.result).split(',')[1]); fr.onerror = reject; fr.readAsDataURL(blob); });
    return { data: b64, url: URL.createObjectURL(blob) };
  }
  // ---- 画面内のカメラ ----
  const camSupported = !!(navigator.mediaDevices && navigator.mediaDevices.getUserMedia);
  let camStream = null, camKind = null, facing = 'environment';
  try { if (localStorage.getItem('scanFacing') === 'user') facing = 'user'; } catch { /* 背面カメラ */ }
  const stopCamera = () => { if (camStream) camStream.getTracks().forEach((t) => t.stop()); camStream = null; $('cam-video').srcObject = null; };
  async function startCamera() {
    stopCamera();
    camStream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: { ideal: facing }, width: { ideal: 1920 }, height: { ideal: 1440 } }, audio: false });
    $('cam-video').srcObject = camStream;
    await $('cam-video').play().catch(() => {});
  }
  async function openCamera(k) {
    camKind = k;
    showError('');
    $('cam-kind').textContent = '「' + k.label + '」を撮ります。商品だけが写るようにしてください。';
    try { await startCamera(); }
    catch { return showError('カメラを起動できませんでした。ブラウザの設定でカメラの使用を許可するか、「写真を選ぶ」から撮影済みの写真を選んでください。'); }
    try { $('cam-switch').hidden = (await navigator.mediaDevices.enumerateDevices()).filter((d) => d.kind === 'videoinput').length < 2; } catch { $('cam-switch').hidden = true; }
    $('cam-dialog').showModal();
  }
  $('cam-shot').onclick = async () => {
    const v = $('cam-video');
    if (!v.videoWidth) return;
    $('cam-shot').disabled = true;
    try { photos.push({ kind: camKind.kind, ...(await encode(v)) }); if (navigator.vibrate) navigator.vibrate(40); }
    catch { showError('撮影できませんでした。もう一度お試しください。'); }
    $('cam-shot').disabled = false;
    $('cam-dialog').close();
    renderSlots();
  };
  $('cam-switch').onclick = async () => {
    facing = facing === 'environment' ? 'user' : 'environment';
    try { await startCamera(); try { localStorage.setItem('scanFacing', facing); } catch { /* 無視 */ } }
    catch { facing = facing === 'environment' ? 'user' : 'environment'; await startCamera().catch(() => {}); }
  };
  $('cam-cancel').onclick = () => $('cam-dialog').close();
  $('cam-dialog').addEventListener('close', stopCamera);
  document.addEventListener('visibilitychange', () => { if (document.hidden && $('cam-dialog').open) $('cam-dialog').close(); });

  const reason = () => document.querySelector('input[name=reason]:checked').value;
  const showTips = () => { $('photo-tips').textContent = TIPS[reason()]; };
  for (const r of document.querySelectorAll('input[name=reason]')) r.onchange = showTips;
  showTips(); renderSlots();

  $('form').hidden = false;
  $('form').onsubmit = async (ev) => {
    ev.preventDefault();
    showError('');
    if (picks.size === 0) return showError('返品・交換したい商品にチェックを入れてください。');
    if (!photos.some((p) => p.kind === 'whole')) return showError('「商品の全体」の写真が必要です。');
    if (photos.length < MIN_PHOTOS) return showError('写真は ' + MIN_PHOTOS + ' 枚以上必要です。不具合の箇所やラベルも撮ってください。');
    if (!$('agree').checked) return showError('「ご確認」にチェックを入れてください。');
    $('submit').disabled = true;
    try {
      const res = await fetch('/api/orders/' + encodeURIComponent(orderId) + '/returns', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          reason: reason(), detail: $('detail').value, wanted: document.querySelector('input[name=wanted]:checked').value, agree: true,
          items: [...picks].map(([productId, sel]) => ({ productId, qty: Number(sel.value) })),
          photos: photos.map((p) => ({ kind: p.kind, data: p.data })),
        }),
      });
      if (!res.ok) {
        const code = (await res.json().catch(() => ({}))).error;
        throw new Error(code === 'too_many_requests' ? '申し込みの回数が多すぎます。しばらくしてからお試しください。'
          : code === 'window_closed' ? '申込期限を過ぎています。'
          : code === 'items_unavailable' ? '選んだ商品は、すでに申し込まれています。画面を開き直してください。'
          : code === 'too_many_open' ? '確認中・対応中の申し込みが多いため、これ以上は受け付けられません。'
          : '申し込めませんでした。入力内容と写真を確認して、もう一度お試しください。');
      }
      const { code } = await res.json();
      for (const p of photos) URL.revokeObjectURL(p.url);
      await load(); renderHistory();
      $('form').hidden = true;
      const done = el('div', 'gate'); done.append(el('strong', null, 'お申し込みを受け付けました（#' + code + '）。'), document.createTextNode(' 店舗スタッフが確認します。結果は、この画面を開き直すと上の「お申し込みの状況」に表示されます。'));
      $('history').before(done);
      window.scrollTo({ top: 0, behavior: 'smooth' });
    } catch (e) {
      showError(e.message || '送信できませんでした。通信状況を確認してください。');
      $('submit').disabled = false;
    }
  };
})();
