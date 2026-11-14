import { Hono } from 'hono';
import type { Context } from 'hono';
import qrcode from 'qrcode-generator';
import staffHtml from './staff.html';

type Env = {
  DB: D1Database;
  ASSETS: Fetcher;
  STRIPE_SECRET_KEY: string;
  STRIPE_PUBLISHABLE_KEY: string;
  STRIPE_WEBHOOK_SECRET: string;
  STAFF_PASSWORD: string;
  /** テスト用。通常は未設定（https://api.stripe.com） */
  STRIPE_API_BASE?: string;
};

type OrderRow = {
  id: string;
  store_id: string;
  status: 'pending' | 'paid' | 'handed_over' | 'refunded';
  total: number;
  payment_intent_id: string | null;
  pickup_no: number | null;
  created_at: string;
  paid_at: string | null;
  handed_over_at: string | null;
  refunded_at: string | null;
};

type ItemRow = { order_id: string; name: string; unit_price: number; tax_rate: number; qty: number };

type PaymentIntent = {
  id: string;
  status: string;
  amount_received: number;
  currency: string;
  metadata?: Record<string, string>;
};

const MIN_AMOUNT_JPY = 50; // Stripe の JPY 最低決済額
const MAX_LINES = 50;
const MAX_QTY = 20;

const app = new Hono<{ Bindings: Env }>();
type C = Context<{ Bindings: Env }>;

app.onError((err, c) => {
  console.error(err);
  return c.json({ error: 'internal_error' }, 500);
});

// ---------- Stripe ----------

class StripeError extends Error {
  constructor(public status: number, public type: string, public code: string, detail = '') {
    super(`stripe ${status} ${type} ${code} ${detail}`.replace(/\s+/g, ' ').trim().slice(0, 300));
  }
}

async function stripe<T>(
  env: Env,
  method: 'GET' | 'POST',
  path: string,
  params?: Record<string, string>,
  idempotencyKey?: string,
): Promise<T> {
  const headers: Record<string, string> = { Authorization: `Bearer ${env.STRIPE_SECRET_KEY}` };
  if (idempotencyKey) headers['Idempotency-Key'] = idempotencyKey;
  let body: string | undefined;
  if (params) {
    headers['Content-Type'] = 'application/x-www-form-urlencoded';
    body = new URLSearchParams(params).toString();
  }
  const base = env.STRIPE_API_BASE || 'https://api.stripe.com';
  const res = await fetch(`${base}/v1${path}`, { method, headers, body });
  const json = (await res.json().catch(() => ({}))) as T & { error?: { message?: string; type?: string; code?: string; param?: string } };
  if (!res.ok) {
    console.error(`Stripe ${path} ${res.status}: ${json.error?.message ?? ''}`);
    // 認証エラー以外は、原因特定のため Stripe の説明文も残す（リクエスト内容の不備の説明で、鍵は含まれない）
    const detail = res.status === 401 ? '' : `param=${json.error?.param ?? ''} ${json.error?.message ?? ''}`;
    throw new StripeError(res.status, json.error?.type ?? '', json.error?.code ?? '', detail);
  }
  return json;
}

const enc = new TextEncoder();

function timingSafeEqual(a: Uint8Array, b: Uint8Array): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a[i] ^ b[i];
  return diff === 0;
}

async function sha256(s: string): Promise<Uint8Array> {
  return new Uint8Array(await crypto.subtle.digest('SHA-256', enc.encode(s)));
}

async function verifyStripeSignature(body: string, header: string | undefined, secret: string): Promise<boolean> {
  if (!header || !secret) return false;
  let t = '';
  const sigs: string[] = [];
  for (const part of header.split(',')) {
    const i = part.indexOf('=');
    const k = part.slice(0, i).trim();
    const v = part.slice(i + 1).trim();
    if (k === 't') t = v;
    if (k === 'v1') sigs.push(v);
  }
  const ts = Number(t);
  if (!ts || Math.abs(Date.now() / 1000 - ts) > 300) return false;
  const key = await crypto.subtle.importKey('raw', enc.encode(secret), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
  const mac = new Uint8Array(await crypto.subtle.sign('HMAC', key, enc.encode(`${t}.${body}`)));
  const expected = enc.encode([...mac].map((b) => b.toString(16).padStart(2, '0')).join(''));
  return sigs.some((s) => timingSafeEqual(enc.encode(s), expected));
}

// ---------- 注文 ----------

/** 決済成功を確認できた注文だけを「支払済」にし、受取番号を発行する。何度呼ばれても結果は同じ。 */
async function markPaid(env: Env, order: OrderRow, pi: PaymentIntent): Promise<void> {
  if (order.status !== 'pending') return;
  if (
    pi.status !== 'succeeded' ||
    pi.currency !== 'jpy' ||
    pi.amount_received !== order.total ||
    pi.metadata?.order_id !== order.id ||
    (order.payment_intent_id && order.payment_intent_id !== pi.id)
  ) {
    console.warn('payment does not match order', order.id, pi.id);
    return;
  }
  const upd = await env.DB.prepare(
    `UPDATE orders SET status = 'paid', paid_at = ?, payment_intent_id = ? WHERE id = ? AND status = 'pending'`,
  )
    .bind(new Date().toISOString(), pi.id, order.id)
    .run();
  if (upd.meta.changes !== 1) return; // 別のリクエストが先に処理済み
  const row = await env.DB.prepare(
    `UPDATE stores SET last_pickup_no = last_pickup_no % 999 + 1 WHERE id = ? RETURNING last_pickup_no`,
  )
    .bind(order.store_id)
    .first<{ last_pickup_no: number }>();
  await env.DB.prepare(`UPDATE orders SET pickup_no = ? WHERE id = ?`).bind(row!.last_pickup_no, order.id).run();
}

function getOrder(env: Env, id: string) {
  return env.DB.prepare(`SELECT * FROM orders WHERE id = ?`).bind(id).first<OrderRow>();
}

/** 税率ごとの税込合計と内消費税（1注文・1税率につき端数処理1回、切り捨て） */
function taxSummary(items: ItemRow[]) {
  const byRate = new Map<number, number>();
  for (const it of items) byRate.set(it.tax_rate, (byRate.get(it.tax_rate) ?? 0) + it.unit_price * it.qty);
  return [...byRate.entries()]
    .sort((a, b) => a[0] - b[0])
    .map(([rate, subtotal]) => ({ rate, subtotal, tax: Math.floor((subtotal * rate) / (100 + rate)) }));
}

// ---------- 公開 API ----------

app.get('/api/config', (c) => c.json({ publishableKey: c.env.STRIPE_PUBLISHABLE_KEY ?? '' }));

app.get('/api/stores/:id', async (c) => {
  const store = await c.env.DB.prepare(`SELECT id, name FROM stores WHERE id = ?`).bind(c.req.param('id')).first();
  if (!store) return c.json({ error: 'store_not_found' }, 404);
  const { results } = await c.env.DB.prepare(
    `SELECT id, name, price, tax_rate AS taxRate FROM products WHERE store_id = ? AND available = 1 ORDER BY sort, id`,
  )
    .bind(store.id)
    .all();
  return c.json({ store, products: results });
});

app.post('/api/orders', async (c) => {
  const body = (await c.req.json().catch(() => null)) as { storeId?: unknown; items?: unknown } | null;
  if (!body || typeof body.storeId !== 'string' || !Array.isArray(body.items)) return c.json({ error: 'bad_request' }, 400);

  const qty = new Map<string, number>();
  for (const it of body.items as { productId?: unknown; qty?: unknown }[]) {
    if (!it || typeof it.productId !== 'string' || !Number.isInteger(it.qty) || (it.qty as number) < 1) {
      return c.json({ error: 'bad_request' }, 400);
    }
    qty.set(it.productId, (qty.get(it.productId) ?? 0) + (it.qty as number));
  }
  if (qty.size === 0 || qty.size > MAX_LINES || [...qty.values()].some((q) => q > MAX_QTY)) {
    return c.json({ error: 'bad_request' }, 400);
  }

  const store = await c.env.DB.prepare(`SELECT id, name FROM stores WHERE id = ?`).bind(body.storeId).first<{ id: string; name: string }>();
  if (!store) return c.json({ error: 'store_not_found' }, 404);

  // 金額は必ずサーバー側の価格から計算する
  const ids = [...qty.keys()];
  const { results: products } = await c.env.DB.prepare(
    `SELECT id, name, price, tax_rate FROM products
     WHERE store_id = ? AND available = 1 AND id IN (${ids.map(() => '?').join(',')})`,
  )
    .bind(store.id, ...ids)
    .all<{ id: string; name: string; price: number; tax_rate: number }>();
  if (products.length !== ids.length) return c.json({ error: 'product_unavailable' }, 409);

  const total = products.reduce((s, p) => s + p.price * qty.get(p.id)!, 0);
  if (total < MIN_AMOUNT_JPY) return c.json({ error: 'amount_too_small' }, 400);

  const orderId = crypto.randomUUID();
  await c.env.DB.batch([
    c.env.DB.prepare(`INSERT INTO orders (id, store_id, total, created_at) VALUES (?, ?, ?, ?)`).bind(
      orderId,
      store.id,
      total,
      new Date().toISOString(),
    ),
    ...products.map((p) =>
      c.env.DB.prepare(
        `INSERT INTO order_items (order_id, product_id, name, unit_price, tax_rate, qty) VALUES (?, ?, ?, ?, ?, ?)`,
      ).bind(orderId, p.id, p.name, p.price, p.tax_rate, qty.get(p.id)!),
    ),
  ]);

  // Apple Pay / Google Pay は Stripe 上では card 扱い。カード情報は当システムを通過しない
  let pi: { id: string; client_secret: string };
  try {
    pi = await stripe<{ id: string; client_secret: string }>(
    c.env,
    'POST',
    '/payment_intents',
    {
      amount: String(total),
      currency: 'jpy',
      'payment_method_types[]': 'card',
      description: `${store.name} 注文 ${orderId}`,
      'metadata[order_id]': orderId,
      'metadata[store_id]': store.id,
    },
    `order-${orderId}`,
  );
  } catch (e) {
    // 支払いを開始できなかった理由を残す（注文は未払いのまま。請求は発生しない）
    const reason = e instanceof StripeError ? e.message : 'stripe unreachable';
    await c.env.DB.prepare(`UPDATE orders SET payment_error = ? WHERE id = ?`).bind(reason, orderId).run();
    console.error(e);
    return c.json({ error: 'payment_provider_error' }, 502);
  }
  await c.env.DB.prepare(`UPDATE orders SET payment_intent_id = ? WHERE id = ?`).bind(pi.id, orderId).run();

  return c.json({ orderId, total, clientSecret: pi.client_secret }, 201);
});

app.get('/api/orders/:id', async (c) => {
  let order = await getOrder(c.env, c.req.param('id'));
  if (!order) return c.json({ error: 'order_not_found' }, 404);

  // Webhook が遅れている/届かない場合に備え、未払いの間は Stripe に直接確認する
  if (order.status === 'pending' && order.payment_intent_id) {
    try {
      const pi = await stripe<PaymentIntent>(c.env, 'GET', `/payment_intents/${order.payment_intent_id}`);
      await markPaid(c.env, order, pi);
      order = (await getOrder(c.env, order.id))!;
    } catch (e) {
      console.error(e);
    }
  }

  const store = await c.env.DB.prepare(`SELECT name, invoice_number FROM stores WHERE id = ?`)
    .bind(order.store_id)
    .first<{ name: string; invoice_number: string | null }>();
  const { results: items } = await c.env.DB.prepare(
    `SELECT order_id, name, unit_price, tax_rate, qty FROM order_items WHERE order_id = ?`,
  )
    .bind(order.id)
    .all<ItemRow>();

  c.header('Cache-Control', 'no-store');
  return c.json({
    id: order.id,
    status: order.status,
    total: order.total,
    pickupNo: order.pickup_no,
    createdAt: order.created_at,
    paidAt: order.paid_at,
    handedOverAt: order.handed_over_at,
    refundedAt: order.refunded_at,
    store: { name: store!.name, invoiceNumber: store!.invoice_number },
    items: items.map((i) => ({ name: i.name, unitPrice: i.unit_price, taxRate: i.tax_rate, qty: i.qty })),
    tax: taxSummary(items),
  });
});

app.post('/api/stripe/webhook', async (c) => {
  const raw = await c.req.text();
  if (!(await verifyStripeSignature(raw, c.req.header('Stripe-Signature'), c.env.STRIPE_WEBHOOK_SECRET))) {
    return c.json({ error: 'invalid_signature' }, 400);
  }
  const event = JSON.parse(raw) as { type: string; data: { object: PaymentIntent } };
  if (event.type === 'payment_intent.succeeded') {
    const pi = event.data.object;
    const orderId = pi.metadata?.order_id;
    const order = orderId ? await getOrder(c.env, orderId) : null;
    if (order) await markPaid(c.env, order, pi);
  }
  return c.json({ received: true });
});

// ---------- 店舗スタッフ用（Basic 認証） ----------

async function staffAuth(c: C, next: () => Promise<void>) {
  const header = c.req.header('Authorization') ?? '';
  let ok = false;
  if (c.env.STAFF_PASSWORD && header.startsWith('Basic ')) {
    try {
      const decoded = atob(header.slice(6));
      const password = decoded.slice(decoded.indexOf(':') + 1);
      ok = timingSafeEqual(await sha256(password), await sha256(c.env.STAFF_PASSWORD));
    } catch {
      ok = false;
    }
  }
  if (!ok) {
    return c.text('Unauthorized', 401, { 'WWW-Authenticate': 'Basic realm="staff", charset="UTF-8"' });
  }
  await next();
}
app.use('/staff', staffAuth);
app.use('/staff/*', staffAuth);
app.use('/api/staff/*', staffAuth);

app.get('/staff', (c) => c.html(staffHtml, 200, { 'Cache-Control': 'no-store', 'X-Frame-Options': 'DENY' }));

app.get('/staff/qr.svg', async (c) => {
  const storeId = c.req.query('store') ?? '';
  const store = await c.env.DB.prepare(`SELECT id FROM stores WHERE id = ?`).bind(storeId).first();
  if (!store) return c.text('store not found', 404);
  const qr = qrcode(0, 'M');
  qr.addData(`${new URL(c.req.url).origin}/s/${encodeURIComponent(storeId)}`);
  qr.make();
  return c.body(qr.createSvgTag({ cellSize: 8, margin: 32, scalable: true }), 200, { 'Content-Type': 'image/svg+xml' });
});

app.get('/api/staff/stores', async (c) => {
  const { results } = await c.env.DB.prepare(`SELECT id, name FROM stores ORDER BY id`).all();
  return c.json({ stores: results });
});

app.get('/api/staff/orders', async (c) => {
  const storeId = c.req.query('store') ?? '';
  const { results: orders } = await c.env.DB.prepare(
    `SELECT * FROM (SELECT * FROM orders WHERE store_id = ?1 AND status = 'paid' ORDER BY paid_at LIMIT 200)
     UNION ALL
     SELECT * FROM (SELECT * FROM orders WHERE store_id = ?1 AND status IN ('handed_over', 'refunded')
                    ORDER BY COALESCE(refunded_at, handed_over_at) DESC LIMIT 20)`,
  )
    .bind(storeId)
    .all<OrderRow>();
  let items: ItemRow[] = [];
  if (orders.length) {
    const res = await c.env.DB.prepare(
      `SELECT order_id, name, unit_price, tax_rate, qty FROM order_items
       WHERE order_id IN (SELECT id FROM orders WHERE store_id = ? AND status != 'pending')`,
    )
      .bind(storeId)
      .all<ItemRow>();
    items = res.results;
  }
  c.header('Cache-Control', 'no-store');
  return c.json({
    orders: orders.map((o) => ({
      id: o.id,
      status: o.status,
      total: o.total,
      pickupNo: o.pickup_no,
      paidAt: o.paid_at,
      handedOverAt: o.handed_over_at,
      refundedAt: o.refunded_at,
      items: items.filter((i) => i.order_id === o.id).map((i) => ({ name: i.name, qty: i.qty })),
    })),
  });
});

app.post('/api/staff/orders/:id/handover', async (c) => {
  // 支払済の注文だけを1回だけ受け渡し済みにできる（二重受け渡し防止）
  const res = await c.env.DB.prepare(
    `UPDATE orders SET status = 'handed_over', handed_over_at = ? WHERE id = ? AND status = 'paid'`,
  )
    .bind(new Date().toISOString(), c.req.param('id'))
    .run();
  if (res.meta.changes !== 1) return c.json({ error: 'not_paid_or_already_handed_over' }, 409);
  return c.json({ ok: true });
});

app.post('/api/staff/orders/:id/refund', async (c) => {
  const order = await getOrder(c.env, c.req.param('id'));
  if (!order) return c.json({ error: 'order_not_found' }, 404);
  if ((order.status !== 'paid' && order.status !== 'handed_over') || !order.payment_intent_id) {
    return c.json({ error: 'not_refundable' }, 409);
  }
  await stripe(c.env, 'POST', '/refunds', { payment_intent: order.payment_intent_id }, `refund-${order.id}`);
  await c.env.DB.prepare(`UPDATE orders SET status = 'refunded', refunded_at = ? WHERE id = ?`)
    .bind(new Date().toISOString(), order.id)
    .run();
  return c.json({ ok: true });
});

// ---------- 画面 ----------

const page = (path: string) => (c: C) => c.env.ASSETS.fetch(new Request(new URL(path, c.req.url)));
app.get('/s/:storeId', page('/store'));
app.get('/order/:orderId', page('/order'));

app.all('/api/*', (c) => c.json({ error: 'not_found' }, 404));

export default app;
