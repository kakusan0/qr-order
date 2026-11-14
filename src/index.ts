import { Hono } from 'hono';
import type { Context } from 'hono';
import { deleteCookie, getCookie, setCookie } from 'hono/cookie';
import { createMiddleware } from 'hono/factory';
import qrcode from 'qrcode-generator';
import staffHtml from './staff.html';
import loginHtml from './login.html';

type Env = {
  DB: D1Database;
  ASSETS: Fetcher;
  STRIPE_SECRET_KEY: string;
  STRIPE_PUBLISHABLE_KEY: string;
  STRIPE_WEBHOOK_SECRET: string;
  /** オーナー（ユーザー名 owner）のパスワード */
  STAFF_PASSWORD: string;
  /** 任意。両方を設定すると注文作成時に Turnstile の確認を必須にする */
  TURNSTILE_SITE_KEY?: string;
  TURNSTILE_SECRET_KEY?: string;
  /** 任意。支払済注文の保存日数（既定 2922 日 = 約8年） */
  ORDER_RETENTION_DAYS?: string;
  /** テスト用。通常は未設定 */
  STRIPE_API_BASE?: string;
  TURNSTILE_VERIFY_URL?: string;
};

type Session = { username: string; role: 'admin' | 'staff' };

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
  handed_over_by: string | null;
  refunded_at: string | null;
  refunded_amount: number;
  age_check_required: number;
  age_checked_at: string | null;
  age_checked_by: string | null;
};

type ItemRow = { order_id: string; name: string; unit_price: number; tax_rate: number; qty: number; age_restricted: number };

type PaymentIntent = {
  id: string;
  status: string;
  amount_received: number;
  currency: string;
  metadata?: Record<string, string>;
};

const MIN_AMOUNT_JPY = 50; // Stripe の JPY 最低決済額
const MAX_PRICE_JPY = 1_000_000;
const MAX_LINES = 50;
const MAX_QTY = 20;
const OWNER = 'owner';
const SESSION_COOKIE = 'staff_session';
const SESSION_HOURS = 12;
const PENDING_TTL_HOURS = 24;

const app = new Hono<{ Bindings: Env; Variables: { session: Session } }>();
type C = Context<{ Bindings: Env; Variables: { session: Session } }>;

app.onError((err, c) => {
  console.error(err);
  return c.json({ error: 'internal_error' }, 500);
});

// ---------- 共通 ----------

const enc = new TextEncoder();
const hex = (b: ArrayBuffer | Uint8Array) =>
  [...(b instanceof Uint8Array ? b : new Uint8Array(b))].map((x) => x.toString(16).padStart(2, '0')).join('');
const fromHex = (s: string) => new Uint8Array((s.match(/../g) ?? []).map((h) => parseInt(h, 16)));
const randomHex = (bytes: number) => hex(crypto.getRandomValues(new Uint8Array(bytes)));
const nowIso = () => new Date().toISOString();
/** 日本時間の日付（YYYY-MM-DD） */
const jstDate = () => new Date(Date.now() + 9 * 3600_000).toISOString().slice(0, 10);

function timingSafeEqual(a: Uint8Array, b: Uint8Array): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a[i] ^ b[i];
  return diff === 0;
}

async function sha256(s: string): Promise<Uint8Array> {
  return new Uint8Array(await crypto.subtle.digest('SHA-256', enc.encode(s)));
}

async function hashPassword(password: string, saltHex: string): Promise<string> {
  const key = await crypto.subtle.importKey('raw', enc.encode(password), 'PBKDF2', false, ['deriveBits']);
  const bits = await crypto.subtle.deriveBits(
    { name: 'PBKDF2', hash: 'SHA-256', salt: fromHex(saltHex), iterations: 100_000 },
    key,
    256,
  );
  return hex(bits);
}

const clientIp = (c: C) => c.req.header('CF-Connecting-IP') ?? 'unknown';

/** 一定時間あたりの回数制限。上限を超えたら false */
async function rateLimit(env: Env, name: string, subject: string, limit: number, windowSec: number): Promise<boolean> {
  const nowSec = Math.floor(Date.now() / 1000);
  const window = Math.floor(nowSec / windowSec);
  const row = await env.DB.prepare(
    `INSERT INTO rate_limits (key, count, expires_at) VALUES (?, 1, ?)
     ON CONFLICT(key) DO UPDATE SET count = count + 1 RETURNING count`,
  )
    .bind(`${name}:${window}:${subject}`, (window + 2) * windowSec)
    .first<{ count: number }>();
  return (row?.count ?? 1) <= limit;
}

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
  const json = (await res.json().catch(() => ({}))) as T & {
    error?: { message?: string; type?: string; code?: string; param?: string };
  };
  if (!res.ok) {
    console.error(`Stripe ${path} ${res.status}: ${json.error?.message ?? ''}`);
    // 認証エラー以外は、原因特定のため Stripe の説明文も残す（リクエスト内容の不備の説明で、鍵は含まれない）
    const detail = res.status === 401 ? '' : `param=${json.error?.param ?? ''} ${json.error?.message ?? ''}`;
    throw new StripeError(res.status, json.error?.type ?? '', json.error?.code ?? '', detail);
  }
  return json;
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
  const expected = enc.encode(hex(await crypto.subtle.sign('HMAC', key, enc.encode(`${t}.${body}`))));
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
    .bind(nowIso(), pi.id, order.id)
    .run();
  if (upd.meta.changes !== 1) return; // 別のリクエストが先に処理済み
  // 受取番号は日本時間の日付が変わると 1 から振り直す
  const row = await env.DB.prepare(
    `UPDATE stores
     SET last_pickup_no = CASE WHEN pickup_date = ?1 THEN last_pickup_no % 999 + 1 ELSE 1 END, pickup_date = ?1
     WHERE id = ?2 RETURNING last_pickup_no`,
  )
    .bind(jstDate(), order.store_id)
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

async function verifyTurnstile(env: Env, token: unknown, ip: string): Promise<boolean> {
  if (typeof token !== 'string' || !token || token.length > 4096) return false;
  try {
    const res = await fetch(env.TURNSTILE_VERIFY_URL || 'https://challenges.cloudflare.com/turnstile/v0/siteverify', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ secret: env.TURNSTILE_SECRET_KEY!, response: token, remoteip: ip }).toString(),
    });
    return ((await res.json()) as { success?: boolean }).success === true;
  } catch (e) {
    console.error(e);
    return false;
  }
}

// ---------- 公開 API ----------

app.get('/api/config', (c) =>
  c.json({
    publishableKey: c.env.STRIPE_PUBLISHABLE_KEY ?? '',
    turnstileSiteKey: c.env.TURNSTILE_SECRET_KEY ? (c.env.TURNSTILE_SITE_KEY ?? '') : '',
  }),
);

app.get('/api/stores/:id', async (c) => {
  const store = await c.env.DB.prepare(`SELECT id, name FROM stores WHERE id = ?`).bind(c.req.param('id')).first();
  if (!store) return c.json({ error: 'store_not_found' }, 404);
  const { results } = await c.env.DB.prepare(
    `SELECT id, name, price, tax_rate AS taxRate, age_restricted AS ageRestricted
     FROM products WHERE store_id = ? AND available = 1 ORDER BY sort, id`,
  )
    .bind(store.id)
    .all<{ ageRestricted: number }>();
  return c.json({ store, products: results.map((p) => ({ ...p, ageRestricted: p.ageRestricted === 1 })) });
});

app.post('/api/orders', async (c) => {
  // 同じ接続元からの注文作成は 5 分間に 30 回まで（店内 Wi-Fi の共用を考慮した上限）
  if (!(await rateLimit(c.env, 'order', clientIp(c), 30, 300))) return c.json({ error: 'too_many_requests' }, 429);

  const body = (await c.req.json().catch(() => null)) as
    | { storeId?: unknown; items?: unknown; ageConfirmed?: unknown; turnstileToken?: unknown }
    | null;
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

  if (c.env.TURNSTILE_SECRET_KEY && !(await verifyTurnstile(c.env, body.turnstileToken, clientIp(c)))) {
    return c.json({ error: 'verification_failed' }, 403);
  }

  const store = await c.env.DB.prepare(`SELECT id, name FROM stores WHERE id = ?`).bind(body.storeId).first<{ id: string; name: string }>();
  if (!store) return c.json({ error: 'store_not_found' }, 404);

  // 金額は必ずサーバー側の価格から計算する
  const ids = [...qty.keys()];
  const { results: products } = await c.env.DB.prepare(
    `SELECT id, name, price, tax_rate, age_restricted FROM products
     WHERE store_id = ? AND available = 1 AND id IN (${ids.map(() => '?').join(',')})`,
  )
    .bind(store.id, ...ids)
    .all<{ id: string; name: string; price: number; tax_rate: number; age_restricted: number }>();
  if (products.length !== ids.length) return c.json({ error: 'product_unavailable' }, 409);

  const ageCheck = products.some((p) => p.age_restricted === 1);
  if (ageCheck && body.ageConfirmed !== true) return c.json({ error: 'age_confirmation_required' }, 400);

  const total = products.reduce((s, p) => s + p.price * qty.get(p.id)!, 0);
  if (total < MIN_AMOUNT_JPY) return c.json({ error: 'amount_too_small' }, 400);

  const orderId = crypto.randomUUID();
  await c.env.DB.batch([
    c.env.DB.prepare(`INSERT INTO orders (id, store_id, total, created_at, age_check_required) VALUES (?, ?, ?, ?, ?)`).bind(
      orderId,
      store.id,
      total,
      nowIso(),
      ageCheck ? 1 : 0,
    ),
    ...products.map((p) =>
      c.env.DB.prepare(
        `INSERT INTO order_items (order_id, product_id, name, unit_price, tax_rate, qty, age_restricted) VALUES (?, ?, ?, ?, ?, ?, ?)`,
      ).bind(orderId, p.id, p.name, p.price, p.tax_rate, qty.get(p.id)!, p.age_restricted),
    ),
  ]);

  // 利用できる決済手段は Stripe ダッシュボードの設定に従う。カード情報は当システムを通過しない
  let pi: { id: string; client_secret: string };
  try {
    pi = await stripe<{ id: string; client_secret: string }>(
      c.env,
      'POST',
      '/payment_intents',
      {
        amount: String(total),
        currency: 'jpy',
        'automatic_payment_methods[enabled]': 'true',
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
    `SELECT order_id, name, unit_price, tax_rate, qty, age_restricted FROM order_items WHERE order_id = ?`,
  )
    .bind(order.id)
    .all<ItemRow>();

  c.header('Cache-Control', 'no-store');
  return c.json({
    id: order.id,
    status: order.status,
    total: order.total,
    refundedAmount: order.refunded_amount,
    pickupNo: order.pickup_no,
    ageCheckRequired: order.age_check_required === 1,
    ageChecked: order.age_checked_at !== null,
    createdAt: order.created_at,
    paidAt: order.paid_at,
    handedOverAt: order.handed_over_at,
    refundedAt: order.refunded_at,
    store: { id: order.store_id, name: store!.name, invoiceNumber: store!.invoice_number },
    items: items.map((i) => ({ name: i.name, unitPrice: i.unit_price, taxRate: i.tax_rate, qty: i.qty })),
    tax: taxSummary(items),
  });
});

// 決済画面で起きたエラーの内容を、原因調査のため未払いの注文に記録する
app.post('/api/orders/:id/client-error', async (c) => {
  const body = (await c.req.json().catch(() => null)) as { message?: unknown } | null;
  const message = typeof body?.message === 'string' ? body.message.replace(/\s+/g, ' ').slice(0, 300) : '';
  if (message) {
    await c.env.DB.prepare(`UPDATE orders SET payment_error = ? WHERE id = ? AND status = 'pending'`)
      .bind(`client: ${message}`, c.req.param('id'))
      .run();
  }
  return c.json({ ok: true });
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

// ---------- スタッフのログイン ----------

async function readSession(c: C): Promise<Session | null> {
  const token = getCookie(c, SESSION_COOKIE);
  if (!token) return null;
  const row = await c.env.DB.prepare(
    `SELECT s.username, s.role, s.expires_at, st.active, st.role AS staff_role
     FROM sessions s LEFT JOIN staff st ON st.username = s.username WHERE s.token_hash = ?`,
  )
    .bind(hex(await sha256(token)))
    .first<{ username: string; role: string; expires_at: string; active: number | null; staff_role: string | null }>();
  if (!row || row.expires_at < nowIso()) return null;
  if (row.username === OWNER) return { username: OWNER, role: 'admin' };
  if (row.active !== 1) return null; // 無効化・削除されたアカウントは即時に使えなくなる
  return { username: row.username, role: row.staff_role === 'admin' ? 'admin' : 'staff' };
}

app.post('/api/staff/login', async (c) => {
  const body = (await c.req.json().catch(() => null)) as { username?: unknown; password?: unknown } | null;
  const username = typeof body?.username === 'string' ? body.username.trim().toLowerCase() : '';
  const password = typeof body?.password === 'string' ? body.password : '';
  if (!username || !password || username.length > 64 || password.length > 256) return c.json({ error: 'invalid_credentials' }, 401);

  // 総当たり対策：接続元ごと・ユーザー名ごとに 15 分あたりの試行回数を制限する
  const okIp = await rateLimit(c.env, 'login-ip', clientIp(c), 20, 900);
  const okUser = await rateLimit(c.env, 'login-user', username, 8, 900);
  if (!okIp || !okUser) return c.json({ error: 'too_many_requests' }, 429);

  let role: Session['role'] | null = null;
  if (username === OWNER) {
    if (c.env.STAFF_PASSWORD && timingSafeEqual(await sha256(password), await sha256(c.env.STAFF_PASSWORD))) role = 'admin';
  } else {
    const row = await c.env.DB.prepare(`SELECT password_hash, salt, role FROM staff WHERE username = ? AND active = 1`)
      .bind(username)
      .first<{ password_hash: string; salt: string; role: Session['role'] }>();
    // アカウントの有無で応答時間が変わらないよう、無くてもハッシュ計算は行う
    const hash = await hashPassword(password, row?.salt ?? '00000000000000000000000000000000');
    if (row && timingSafeEqual(enc.encode(hash), enc.encode(row.password_hash))) role = row.role;
  }
  if (!role) return c.json({ error: 'invalid_credentials' }, 401);

  const token = randomHex(32);
  await c.env.DB.prepare(`INSERT INTO sessions (token_hash, username, role, expires_at) VALUES (?, ?, ?, ?)`)
    .bind(hex(await sha256(token)), username, role, new Date(Date.now() + SESSION_HOURS * 3600_000).toISOString())
    .run();
  setCookie(c, SESSION_COOKIE, token, {
    path: '/',
    httpOnly: true,
    secure: true,
    sameSite: 'Strict',
    maxAge: SESSION_HOURS * 3600,
  });
  return c.json({ username, role });
});

app.post('/api/staff/logout', async (c) => {
  const token = getCookie(c, SESSION_COOKIE);
  if (token) await c.env.DB.prepare(`DELETE FROM sessions WHERE token_hash = ?`).bind(hex(await sha256(token))).run();
  deleteCookie(c, SESSION_COOKIE, { path: '/', secure: true });
  return c.json({ ok: true });
});

app.get('/staff/login', (c) => c.html(loginHtml, 200, { 'Cache-Control': 'no-store', 'X-Frame-Options': 'DENY' }));

// 画面：未ログインならログイン画面へ
app.use('/staff', async (c, next) => {
  const session = await readSession(c);
  if (!session) return c.redirect('/staff/login');
  c.set('session', session);
  await next();
});
app.use('/staff/*', async (c, next) => {
  const session = await readSession(c);
  if (!session) return c.text('Unauthorized', 401);
  c.set('session', session);
  await next();
});
// API：ログイン必須。書き込みは同じサイトからの操作に限る（CSRF 対策）
app.use('/api/staff/*', async (c, next) => {
  const session = await readSession(c);
  if (!session) return c.json({ error: 'unauthorized' }, 401);
  if (c.req.method !== 'GET') {
    const origin = c.req.header('Origin');
    if (origin && origin !== new URL(c.req.url).origin) return c.json({ error: 'forbidden' }, 403);
  }
  c.set('session', session);
  await next();
});
const adminOnly = createMiddleware<{ Bindings: Env; Variables: { session: Session } }>(async (c, next) => {
  if (c.get('session').role !== 'admin') return c.json({ error: 'admin_only' }, 403);
  await next();
});

app.get('/staff', (c) => c.html(staffHtml, 200, { 'Cache-Control': 'no-store', 'X-Frame-Options': 'DENY' }));
app.get('/api/staff/me', (c) => c.json(c.get('session')));

// ---------- スタッフ：注文 ----------

function qrSvg(c: C, url: string) {
  const qr = qrcode(0, 'M');
  qr.addData(url);
  qr.make();
  return c.body(qr.createSvgTag({ cellSize: 8, margin: 32, scalable: true }), 200, { 'Content-Type': 'image/svg+xml' });
}

app.get('/staff/qr.svg', async (c) => {
  const storeId = c.req.query('store') ?? '';
  const store = await c.env.DB.prepare(`SELECT id FROM stores WHERE id = ?`).bind(storeId).first();
  if (!store) return c.text('store not found', 404);
  return qrSvg(c, `${new URL(c.req.url).origin}/s/${encodeURIComponent(storeId)}`);
});

// 商品QR：読み取ると注文画面が開き、その商品がカートに入る
app.get('/staff/product-qr.svg', async (c) => {
  const product = await c.env.DB.prepare(`SELECT id, store_id FROM products WHERE id = ?`)
    .bind(c.req.query('id') ?? '')
    .first<{ id: string; store_id: string }>();
  if (!product) return c.text('product not found', 404);
  return qrSvg(
    c,
    `${new URL(c.req.url).origin}/s/${encodeURIComponent(product.store_id)}?add=${encodeURIComponent(product.id)}`,
  );
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
                    ORDER BY COALESCE(handed_over_at, refunded_at) DESC LIMIT 30)`,
  )
    .bind(storeId)
    .all<OrderRow>();
  let items: ItemRow[] = [];
  if (orders.length) {
    const res = await c.env.DB.prepare(
      `SELECT order_id, name, unit_price, tax_rate, qty, age_restricted FROM order_items
       WHERE order_id IN (${orders.map(() => '?').join(',')})`,
    )
      .bind(...orders.map((o) => o.id))
      .all<ItemRow>();
    items = res.results;
  }
  c.header('Cache-Control', 'no-store');
  return c.json({
    orders: orders.map((o) => ({
      id: o.id,
      status: o.status,
      total: o.total,
      refundedAmount: o.refunded_amount,
      pickupNo: o.pickup_no,
      paidAt: o.paid_at,
      handedOverAt: o.handed_over_at,
      handedOverBy: o.handed_over_by,
      refundedAt: o.refunded_at,
      ageCheckRequired: o.age_check_required === 1,
      ageCheckedAt: o.age_checked_at,
      ageCheckedBy: o.age_checked_by,
      items: items
        .filter((i) => i.order_id === o.id)
        .map((i) => ({ name: i.name, qty: i.qty, ageRestricted: i.age_restricted === 1 })),
    })),
  });
});

// 年齢確認商品を含む注文は、スタッフが年齢を確認した記録がないと受け渡せない
app.post('/api/staff/orders/:id/age-check', async (c) => {
  const res = await c.env.DB.prepare(
    `UPDATE orders SET age_checked_at = ?, age_checked_by = ?
     WHERE id = ? AND status = 'paid' AND age_check_required = 1 AND age_checked_at IS NULL`,
  )
    .bind(nowIso(), c.get('session').username, c.req.param('id'))
    .run();
  if (res.meta.changes !== 1) return c.json({ error: 'not_applicable' }, 409);
  return c.json({ ok: true });
});

app.post('/api/staff/orders/:id/handover', async (c) => {
  // 支払済の注文だけを1回だけ受け渡し済みにできる（二重受け渡し防止）
  const res = await c.env.DB.prepare(
    `UPDATE orders SET status = 'handed_over', handed_over_at = ?, handed_over_by = ?
     WHERE id = ? AND status = 'paid' AND (age_check_required = 0 OR age_checked_at IS NOT NULL)`,
  )
    .bind(nowIso(), c.get('session').username, c.req.param('id'))
    .run();
  if (res.meta.changes === 1) return c.json({ ok: true });
  const order = await getOrder(c.env, c.req.param('id'));
  if (order?.status === 'paid' && order.age_check_required === 1) return c.json({ error: 'age_check_required' }, 409);
  return c.json({ error: 'not_paid_or_already_handed_over' }, 409);
});

// 返金（全額・一部）。管理者のみ
app.post('/api/staff/orders/:id/refund', adminOnly, async (c) => {
  const body = (await c.req.json().catch(() => null)) as { amount?: unknown; requestId?: unknown } | null;
  const order = await getOrder(c.env, c.req.param('id'));
  if (!order) return c.json({ error: 'order_not_found' }, 404);
  if ((order.status !== 'paid' && order.status !== 'handed_over') || !order.payment_intent_id) {
    return c.json({ error: 'not_refundable' }, 409);
  }
  const remaining = order.total - order.refunded_amount;
  const amount = body?.amount === undefined || body?.amount === null ? remaining : body.amount;
  if (typeof amount !== 'number' || !Number.isInteger(amount) || amount < 1 || amount > remaining) {
    return c.json({ error: 'bad_amount', remaining }, 400);
  }
  const requestId =
    typeof body?.requestId === 'string' && /^[A-Za-z0-9-]{8,64}$/.test(body.requestId) ? body.requestId : crypto.randomUUID();

  // 先に返金枠を確保する（同時操作で注文額を超えて返金しないため）
  const reserve = await c.env.DB.prepare(
    `UPDATE orders SET refunded_amount = refunded_amount + ?1
     WHERE id = ?2 AND status IN ('paid', 'handed_over') AND refunded_amount + ?1 <= total`,
  )
    .bind(amount, order.id)
    .run();
  if (reserve.meta.changes !== 1) return c.json({ error: 'not_refundable' }, 409);

  try {
    await stripe(
      c.env,
      'POST',
      '/refunds',
      { payment_intent: order.payment_intent_id, amount: String(amount) },
      `refund-${order.id}-${requestId}`,
    );
  } catch (e) {
    await c.env.DB.prepare(`UPDATE orders SET refunded_amount = refunded_amount - ? WHERE id = ?`).bind(amount, order.id).run();
    console.error(e);
    return c.json({ error: 'payment_provider_error' }, 502);
  }

  const now = nowIso();
  await c.env.DB.batch([
    c.env.DB.prepare(`INSERT INTO refunds (id, order_id, amount, staff, created_at) VALUES (?, ?, ?, ?, ?)`).bind(
      crypto.randomUUID(),
      order.id,
      amount,
      c.get('session').username,
      now,
    ),
    // 全額を返金し終えたら「返金済」にする
    c.env.DB.prepare(
      `UPDATE orders SET refunded_at = ?1, status = CASE WHEN refunded_amount >= total THEN 'refunded' ELSE status END WHERE id = ?2`,
    ).bind(now, order.id),
  ]);
  return c.json({ ok: true, refunded: amount, remaining: remaining - amount });
});

// ---------- スタッフ：商品 ----------

app.get('/api/staff/products', async (c) => {
  const { results } = await c.env.DB.prepare(
    `SELECT id, name, price, tax_rate AS taxRate, available, age_restricted AS ageRestricted
     FROM products WHERE store_id = ? ORDER BY sort, id`,
  )
    .bind(c.req.query('store') ?? '')
    .all<{ available: number; ageRestricted: number }>();
  c.header('Cache-Control', 'no-store');
  return c.json({
    products: results.map((p) => ({ ...p, available: p.available === 1, ageRestricted: p.ageRestricted === 1 })),
  });
});

/** 商品の入力内容を検証する。不正なら null */
function parseProduct(body: { name?: unknown; price?: unknown; taxRate?: unknown; ageRestricted?: unknown } | null) {
  const name = typeof body?.name === 'string' ? body.name.trim() : '';
  const price = body?.price;
  const taxRate = body?.taxRate;
  if (
    !name ||
    name.length > 60 ||
    typeof price !== 'number' ||
    !Number.isInteger(price) ||
    price < MIN_AMOUNT_JPY || // 1点でも決済できる金額にする
    price > MAX_PRICE_JPY ||
    (taxRate !== 8 && taxRate !== 10)
  ) {
    return null;
  }
  return { name, price, taxRate, ageRestricted: body?.ageRestricted === true ? 1 : 0 };
}

app.post('/api/staff/products', adminOnly, async (c) => {
  const body = (await c.req.json().catch(() => null)) as
    | { storeId?: unknown; name?: unknown; price?: unknown; taxRate?: unknown; ageRestricted?: unknown }
    | null;
  const product = parseProduct(body);
  if (!product || typeof body?.storeId !== 'string') return c.json({ error: 'bad_request' }, 400);
  const store = await c.env.DB.prepare(`SELECT id FROM stores WHERE id = ?`).bind(body.storeId).first();
  if (!store) return c.json({ error: 'store_not_found' }, 404);
  const id = 'p-' + randomHex(6);
  await c.env.DB.prepare(
    `INSERT INTO products (id, store_id, name, price, tax_rate, age_restricted, available, sort)
     VALUES (?1, ?2, ?3, ?4, ?5, ?6, 1, (SELECT COALESCE(MAX(sort), 0) + 1 FROM products WHERE store_id = ?2))`,
  )
    .bind(id, body.storeId, product.name, product.price, product.taxRate, product.ageRestricted)
    .run();
  return c.json({ id }, 201);
});

// 編集。QR コード（商品ID）は変わらない。過去の注文は注文時点の名前・価格のまま残る
app.put('/api/staff/products/:id', adminOnly, async (c) => {
  const product = parseProduct(await c.req.json().catch(() => null));
  if (!product) return c.json({ error: 'bad_request' }, 400);
  const res = await c.env.DB.prepare(`UPDATE products SET name = ?, price = ?, tax_rate = ?, age_restricted = ? WHERE id = ?`)
    .bind(product.name, product.price, product.taxRate, product.ageRestricted, c.req.param('id'))
    .run();
  if (res.meta.changes !== 1) return c.json({ error: 'product_not_found' }, 404);
  return c.json({ ok: true });
});

// 削除。過去の注文の明細は商品名・価格を写しで持っているので影響しない
app.delete('/api/staff/products/:id', adminOnly, async (c) => {
  const res = await c.env.DB.prepare(`DELETE FROM products WHERE id = ?`).bind(c.req.param('id')).run();
  if (res.meta.changes !== 1) return c.json({ error: 'product_not_found' }, 404);
  return c.json({ ok: true });
});

// 有効・無効（品切れ対応）は一般スタッフも切り替えられる
app.post('/api/staff/products/:id/availability', async (c) => {
  const body = (await c.req.json().catch(() => null)) as { available?: unknown } | null;
  if (typeof body?.available !== 'boolean') return c.json({ error: 'bad_request' }, 400);
  const res = await c.env.DB.prepare(`UPDATE products SET available = ? WHERE id = ?`)
    .bind(body.available ? 1 : 0, c.req.param('id'))
    .run();
  if (res.meta.changes !== 1) return c.json({ error: 'product_not_found' }, 404);
  return c.json({ ok: true });
});

// ---------- スタッフ：アカウント管理（管理者のみ） ----------

const validUsername = (s: unknown): s is string => typeof s === 'string' && /^[a-z0-9][a-z0-9._-]{2,31}$/.test(s) && s !== OWNER;
const validPassword = (s: unknown): s is string => typeof s === 'string' && s.length >= 10 && s.length <= 256;

app.get('/api/staff/accounts', adminOnly, async (c) => {
  const { results } = await c.env.DB.prepare(`SELECT id, username, role, active, created_at FROM staff ORDER BY created_at`).all<{
    id: string;
    username: string;
    role: string;
    active: number;
    created_at: string;
  }>();
  return c.json({ accounts: results.map((a) => ({ ...a, active: a.active === 1, createdAt: a.created_at })) });
});

app.post('/api/staff/accounts', adminOnly, async (c) => {
  const body = (await c.req.json().catch(() => null)) as { username?: unknown; password?: unknown; role?: unknown } | null;
  const username = typeof body?.username === 'string' ? body.username.trim().toLowerCase() : '';
  if (!validUsername(username) || !validPassword(body?.password) || (body?.role !== 'admin' && body?.role !== 'staff')) {
    return c.json({ error: 'bad_request' }, 400);
  }
  const exists = await c.env.DB.prepare(`SELECT 1 FROM staff WHERE username = ?`).bind(username).first();
  if (exists) return c.json({ error: 'username_taken' }, 409);
  const salt = randomHex(16);
  await c.env.DB.prepare(`INSERT INTO staff (id, username, password_hash, salt, role, created_at) VALUES (?, ?, ?, ?, ?, ?)`)
    .bind(crypto.randomUUID(), username, await hashPassword(body.password, salt), salt, body.role, nowIso())
    .run();
  return c.json({ ok: true }, 201);
});

app.post('/api/staff/accounts/:id/active', adminOnly, async (c) => {
  const body = (await c.req.json().catch(() => null)) as { active?: unknown } | null;
  if (typeof body?.active !== 'boolean') return c.json({ error: 'bad_request' }, 400);
  const row = await c.env.DB.prepare(`UPDATE staff SET active = ? WHERE id = ? RETURNING username`)
    .bind(body.active ? 1 : 0, c.req.param('id'))
    .first<{ username: string }>();
  if (!row) return c.json({ error: 'account_not_found' }, 404);
  if (!body.active) await c.env.DB.prepare(`DELETE FROM sessions WHERE username = ?`).bind(row.username).run();
  return c.json({ ok: true });
});

app.post('/api/staff/accounts/:id/password', adminOnly, async (c) => {
  const body = (await c.req.json().catch(() => null)) as { password?: unknown } | null;
  if (!validPassword(body?.password)) return c.json({ error: 'bad_request' }, 400);
  const salt = randomHex(16);
  const row = await c.env.DB.prepare(`UPDATE staff SET password_hash = ?, salt = ? WHERE id = ? RETURNING username`)
    .bind(await hashPassword(body.password, salt), salt, c.req.param('id'))
    .first<{ username: string }>();
  if (!row) return c.json({ error: 'account_not_found' }, 404);
  await c.env.DB.prepare(`DELETE FROM sessions WHERE username = ?`).bind(row.username).run(); // 変更前のログインを無効にする
  return c.json({ ok: true });
});

app.delete('/api/staff/accounts/:id', adminOnly, async (c) => {
  const row = await c.env.DB.prepare(`DELETE FROM staff WHERE id = ? RETURNING username`)
    .bind(c.req.param('id'))
    .first<{ username: string }>();
  if (!row) return c.json({ error: 'account_not_found' }, 404);
  await c.env.DB.prepare(`DELETE FROM sessions WHERE username = ?`).bind(row.username).run();
  return c.json({ ok: true });
});

// ---------- 画面 ----------

const page = (path: string) => (c: C) => c.env.ASSETS.fetch(new Request(new URL(path, c.req.url)));
app.get('/s/:storeId', page('/store'));
app.get('/order/:orderId', page('/order'));

app.all('/api/*', (c) => c.json({ error: 'not_found' }, 404));

// ---------- 定期処理（1時間ごと） ----------

async function cleanup(env: Env): Promise<void> {
  // 1) 未払いのまま 24 時間たった注文を削除する。支払いの状態を Stripe で確かめてから消す
  const cutoff = new Date(Date.now() - PENDING_TTL_HOURS * 3600_000).toISOString();
  const { results: stale } = await env.DB.prepare(
    `SELECT * FROM orders WHERE status = 'pending' AND created_at < ? ORDER BY created_at LIMIT 15`,
  )
    .bind(cutoff)
    .all<OrderRow>();
  for (const order of stale) {
    if (order.payment_intent_id) {
      try {
        const pi = await stripe<PaymentIntent>(env, 'GET', `/payment_intents/${order.payment_intent_id}`);
        if (pi.status === 'succeeded') {
          await markPaid(env, order, pi); // 通知が届いていなかった支払いを救済する
          continue;
        }
        if (pi.status === 'processing' || pi.status === 'requires_capture') continue; // 処理中は触らない
        // あとから支払われないよう、支払いの受付自体を取り消す
        if (pi.status !== 'canceled') await stripe(env, 'POST', `/payment_intents/${pi.id}/cancel`, {});
      } catch (e) {
        console.error('cleanup: keep order', order.id, e);
        continue; // 状態を確認できない注文は消さない
      }
    }
    await env.DB.batch([
      env.DB.prepare(`DELETE FROM order_items WHERE order_id = (SELECT id FROM orders WHERE id = ? AND status = 'pending')`).bind(order.id),
      env.DB.prepare(`DELETE FROM orders WHERE id = ? AND status = 'pending'`).bind(order.id),
    ]);
  }

  // 2) 保存期間を過ぎた注文データを削除する（帳簿の保存期間 7 年に余裕を持たせた日数が既定）
  const days = Math.max(Number(env.ORDER_RETENTION_DAYS) || 2922, 30);
  const old = new Date(Date.now() - days * 86400_000).toISOString();
  await env.DB.batch([
    env.DB.prepare(`DELETE FROM order_items WHERE order_id IN (SELECT id FROM orders WHERE created_at < ?)`).bind(old),
    env.DB.prepare(`DELETE FROM refunds WHERE order_id IN (SELECT id FROM orders WHERE created_at < ?)`).bind(old),
    env.DB.prepare(`DELETE FROM orders WHERE created_at < ?`).bind(old),
    // 3) 期限切れのログインと回数制限の記録
    env.DB.prepare(`DELETE FROM sessions WHERE expires_at < ?`).bind(nowIso()),
    env.DB.prepare(`DELETE FROM rate_limits WHERE expires_at < ?`).bind(Math.floor(Date.now() / 1000)),
  ]);
}

export default {
  fetch: app.fetch,
  scheduled(_event: ScheduledController, env: Env, ctx: ExecutionContext) {
    ctx.waitUntil(cleanup(env));
  },
};
