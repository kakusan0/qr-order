package com.example.myapplication.data.remote

import com.example.myapplication.data.model.ChatMessage
import com.example.myapplication.data.model.ChatSender
import com.example.myapplication.data.model.ChatThread
import com.example.myapplication.data.model.CustomerUrlInfo
import com.example.myapplication.data.model.Order
import com.example.myapplication.data.model.OrderItem
import com.example.myapplication.data.model.OrderStatus
import com.example.myapplication.data.model.PasskeyInfo
import com.example.myapplication.data.model.Product
import com.example.myapplication.data.model.ReturnItem
import com.example.myapplication.data.model.ReturnPhoto
import com.example.myapplication.data.model.ReturnPhotoKind
import com.example.myapplication.data.model.ReturnReason
import com.example.myapplication.data.model.ReturnRequest
import com.example.myapplication.data.model.ReturnStatus
import com.example.myapplication.data.model.ReturnWanted
import com.example.myapplication.data.model.StaffAccount
import com.example.myapplication.data.model.StaffRole
import com.example.myapplication.data.model.StaffSession
import com.example.myapplication.data.model.Store
import com.example.myapplication.data.repository.QrOrderApiEndpoints
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.TimeUnit

class ApiException(
    val statusCode: Int,
    val errorCode: String?,
    override val message: String
) : IOException(message)

data class PasskeyChallengePayload(
    val challengeId: String,
    val optionsJson: String
)

data class ChatListResponse(
    val lineConfigured: Boolean,
    val lineLinked: Boolean,
    val lineLinkCode: String?,
    val threads: List<ChatThread>
)

data class RemoteBarcodeLookup(
    val found: Boolean,
    val name: String?,
    val source: String?
)

/**
 * kakusan0/qr-order (Cloudflare Workers / Hono) の全スタッフ API エンドポイントと通信する HTTP クライアント。
 */
class QrOrderApiClient(
    private val configStore: ApiConfigStore
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val jstFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm")
        .withZone(ZoneId.of("Asia/Tokyo"))

    private val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(StaffSessionCookieJar(configStore))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private fun baseUrl(): String = configStore.baseUrl

    private fun formatIsoToJst(iso: String?): String {
        if (iso.isNullOrBlank()) return ""
        return runCatching { jstFormatter.format(Instant.parse(iso)) }.getOrElse { iso }
    }

    private fun buildRequest(
        path: String,
        query: Map<String, String> = emptyMap(),
        method: String = "GET",
        jsonBody: JSONObject? = null
    ): Request {
        val urlBuilder = "${baseUrl()}$path".toHttpUrl().newBuilder()
        query.forEach { (k, v) -> urlBuilder.addQueryParameter(k, v) }
        val builder = Request.Builder()
            .url(urlBuilder.build())
            .header("Accept", "application/json")
            .header("Origin", baseUrl())
            .header("User-Agent", "qr-order-android/1.0 (Android)")

        when (method) {
            "GET" -> builder.get()
            "DELETE" -> builder.delete(jsonBody?.toString()?.toRequestBody(jsonMediaType))
            "PUT" -> builder.put((jsonBody ?: JSONObject()).toString().toRequestBody(jsonMediaType))
            "POST" -> builder.post((jsonBody ?: JSONObject()).toString().toRequestBody(jsonMediaType))
            else -> error("Unsupported method: $method")
        }
        return builder.build()
    }

    private suspend fun executeJson(
        path: String,
        query: Map<String, String> = emptyMap(),
        method: String = "GET",
        body: JSONObject? = null
    ): JSONObject = withContext(Dispatchers.IO) {
        val request = buildRequest(path = path, query = query, method = method, jsonBody = body)
        client.newCall(request).execute().use { response ->
            val raw = response.body.string()
            if (response.code == 401 && !path.contains("/login/") && !path.contains("/setup/")) {
                configStore.sessionCookie = null
            }
            if (!response.isSuccessful) {
                val errCode = runCatching { JSONObject(raw).optString("error").ifBlank { null } }.getOrNull()
                throw ApiException(
                    statusCode = response.code,
                    errorCode = errCode,
                    message = translateError(response.code, errCode)
                )
            }
            if (raw.isBlank()) JSONObject() else JSONObject(raw)
        }
    }

    private suspend fun executeBytes(path: String): ByteArray = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${baseUrl()}$path")
            .header("Origin", baseUrl())
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw ApiException(response.code, null, translateError(response.code, null))
            }
            response.body.bytes()
        }
    }

    // ---------- Auth / Session ----------

    suspend fun getMe(): StaffSession? {
        return try {
            val json = executeJson(QrOrderApiEndpoints.ME)
            val username = json.optString("username").ifBlank { return null }
            val role = if (json.optString("role") == "admin") StaffRole.ADMIN else StaffRole.STAFF
            StaffSession(email = username, role = role)
        } catch (e: ApiException) {
            if (e.statusCode == 401) null else throw e
        }
    }

    suspend fun requestSetupCode(email: String) {
        executeJson(
            path = QrOrderApiEndpoints.SETUP_REQUEST,
            method = "POST",
            body = JSONObject().put("email", email.trim())
        )
    }

    suspend fun verifySetupCode(email: String, code: String): PasskeyChallengePayload {
        val json = executeJson(
            path = QrOrderApiEndpoints.SETUP_VERIFY,
            method = "POST",
            body = JSONObject()
                .put("email", email.trim())
                .put("code", code.trim())
        )
        return PasskeyChallengePayload(
            challengeId = json.getString("challengeId"),
            optionsJson = json.getJSONObject("options").toString()
        )
    }

    suspend fun finishSetupPasskey(challengeId: String, responseJson: String) {
        executeJson(
            path = QrOrderApiEndpoints.SETUP_FINISH,
            method = "POST",
            body = JSONObject()
                .put("challengeId", challengeId)
                .put("response", JSONObject(responseJson))
        )
    }

    suspend fun getLoginOptions(): PasskeyChallengePayload {
        val json = executeJson(
            path = QrOrderApiEndpoints.LOGIN_OPTIONS,
            method = "POST"
        )
        return PasskeyChallengePayload(
            challengeId = json.getString("challengeId"),
            optionsJson = json.getJSONObject("options").toString()
        )
    }

    suspend fun verifyLogin(challengeId: String, responseJson: String) {
        executeJson(
            path = QrOrderApiEndpoints.LOGIN_VERIFY,
            method = "POST",
            body = JSONObject()
                .put("challengeId", challengeId)
                .put("response", JSONObject(responseJson))
        )
    }

    suspend fun logout() {
        runCatching {
            executeJson(path = QrOrderApiEndpoints.LOGOUT, method = "POST")
        }
        configStore.sessionCookie = null
    }

    // ---------- Stores ----------

    suspend fun getStores(): List<Store> {
        val json = executeJson(QrOrderApiEndpoints.STORES)
        val arr = json.optJSONArray("stores") ?: JSONArray()
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            Store(
                id = o.getString("id"),
                name = o.getString("name"),
                qrRotateMinutes = o.optInt("qrRotateMinutes", 0),
                lat = if (o.isNull("lat")) null else o.optDouble("lat"),
                lng = if (o.isNull("lng")) null else o.optDouble("lng"),
                geofenceM = o.optInt("geofence", 200),
                baseHost = baseUrl()
            )
        }
    }

    suspend fun getCustomerUrl(storeId: String): CustomerUrlInfo {
        val json = executeJson(QrOrderApiEndpoints.storeCustomerUrl(storeId))
        val url = json.getString("url")
        val rotate = json.optInt("rotateMinutes", 0)
        return CustomerUrlInfo(
            url = url,
            rotationMinutes = rotate,
            tokenSuffix = url.substringAfter("?k=", missingDelimiterValue = "").ifBlank { null }
        )
    }

    suspend fun updateQrRotation(storeId: String, minutes: Int) {
        executeJson(
            path = QrOrderApiEndpoints.storeQrRotation(storeId),
            method = "POST",
            body = JSONObject().put("minutes", minutes)
        )
    }

    suspend fun updateStoreLocation(storeId: String, lat: Double?, lng: Double?, radius: Int) {
        val body = JSONObject().put("radius", radius)
        if (lat == null || lng == null) {
            body.put("clear", true)
        } else {
            body.put(
                "geo",
                JSONObject()
                    .put("lat", lat)
                    .put("lng", lng)
                    .put("accuracy", 0)
            )
        }
        executeJson(
            path = QrOrderApiEndpoints.storeLocation(storeId),
            method = "POST",
            body = body
        )
    }

    suspend fun resolveMapUrl(url: String): Pair<Double, Double> {
        val json = executeJson(
            path = QrOrderApiEndpoints.GEO_RESOLVE,
            query = mapOf("url" to url.trim())
        )
        return json.getDouble("lat") to json.getDouble("lng")
    }

    // ---------- Orders ----------

    suspend fun getOrders(storeId: String): List<Order> {
        val json = executeJson(
            path = QrOrderApiEndpoints.ORDERS,
            query = mapOf("store" to storeId)
        )
        val arr = json.optJSONArray("orders") ?: JSONArray()
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            val total = o.optInt("total", 0)
            val refunded = o.optInt("refundedAmount", 0)
            val rawStatus = o.optString("status")
            val status = when {
                rawStatus == "refunded" || refunded >= total -> OrderStatus.REFUNDED
                refunded > 0 -> OrderStatus.PARTIALLY_REFUNDED
                else -> OrderStatus.PAID
            }
            val itemsArr = o.optJSONArray("items") ?: JSONArray()
            val items = List(itemsArr.length()) { j ->
                val it = itemsArr.getJSONObject(j)
                OrderItem(
                    productId = it.optString("productId", ""),
                    name = it.optString("name", ""),
                    unitPrice = it.optInt("unitPrice", 0),
                    taxRate = it.optInt("taxRate", 10),
                    qty = it.optInt("qty", 1),
                    ageRestricted = it.optBoolean("ageRestricted", false)
                )
            }
            Order(
                id = o.getString("id"),
                storeId = storeId,
                pickupNo = o.optInt("pickupNo", 0),
                status = status,
                total = total,
                refundedAmount = refunded,
                items = items,
                ageCheckRequired = o.optBoolean("ageCheckRequired", false),
                ageCheckedAt = o.optNullableString("ageCheckedAt")?.let(::formatIsoToJst),
                ageCheckedBy = o.optNullableString("ageCheckedBy"),
                geoDistanceM = if (o.isNull("geoDistance")) null else o.optInt("geoDistance"),
                geoAccuracyM = if (o.isNull("geoAccuracy")) null else o.optInt("geoAccuracy"),
                paidAt = formatIsoToJst(o.optNullableString("paidAt"))
            )
        }
    }

    suspend fun confirmAgeCheck(orderId: String) {
        executeJson(
            path = QrOrderApiEndpoints.orderAgeCheck(orderId),
            method = "POST"
        )
    }

    suspend fun refundOrder(orderId: String, amount: Int) {
        executeJson(
            path = QrOrderApiEndpoints.orderRefund(orderId),
            method = "POST",
            body = JSONObject()
                .put("amount", amount)
                .put("requestId", UUID.randomUUID().toString())
        )
    }

    // ---------- Products ----------

    suspend fun getProducts(storeId: String): List<Product> {
        val json = executeJson(
            path = QrOrderApiEndpoints.PRODUCTS,
            query = mapOf("store" to storeId)
        )
        val arr = json.optJSONArray("products") ?: JSONArray()
        return List(arr.length()) { i ->
            val p = arr.getJSONObject(i)
            Product(
                id = p.getString("id"),
                storeId = storeId,
                name = p.getString("name"),
                price = p.getInt("price"),
                taxRate = p.optInt("taxRate", 10),
                barcode = p.optNullableString("barcode"),
                available = p.optBoolean("available", true),
                ageRestricted = p.optBoolean("ageRestricted", false),
                sort = i
            )
        }
    }

    suspend fun createProduct(
        storeId: String,
        name: String,
        price: Int,
        taxRate: Int,
        barcode: String?,
        ageRestricted: Boolean
    ): String {
        val body = JSONObject()
            .put("storeId", storeId)
            .put("name", name.trim())
            .put("price", price)
            .put("taxRate", taxRate)
            .put("ageRestricted", ageRestricted)
            .put("barcode", barcode?.trim().orEmpty())
        val res = executeJson(path = QrOrderApiEndpoints.PRODUCTS, method = "POST", body = body)
        return res.getString("id")
    }

    suspend fun updateProduct(
        productId: String,
        name: String,
        price: Int,
        taxRate: Int,
        barcode: String?,
        ageRestricted: Boolean
    ) {
        val body = JSONObject()
            .put("name", name.trim())
            .put("price", price)
            .put("taxRate", taxRate)
            .put("ageRestricted", ageRestricted)
            .put("barcode", barcode?.trim().orEmpty())
        executeJson(path = QrOrderApiEndpoints.product(productId), method = "PUT", body = body)
    }

    suspend fun deleteProduct(productId: String) {
        executeJson(path = QrOrderApiEndpoints.product(productId), method = "DELETE")
    }

    suspend fun setProductAvailability(productId: String, available: Boolean) {
        executeJson(
            path = QrOrderApiEndpoints.productAvailability(productId),
            method = "POST",
            body = JSONObject().put("available", available)
        )
    }

    suspend fun lookupBarcode(code: String): RemoteBarcodeLookup {
        val json = executeJson(
            path = QrOrderApiEndpoints.BARCODE_LOOKUP,
            query = mapOf("code" to code.trim())
        )
        return RemoteBarcodeLookup(
            found = json.optBoolean("found", false),
            name = json.optNullableString("name"),
            source = json.optNullableString("source")
        )
    }

    // ---------- Returns ----------

    suspend fun getReturns(storeId: String): List<ReturnRequest> {
        val json = executeJson(
            path = QrOrderApiEndpoints.RETURNS,
            query = mapOf("store" to storeId)
        )
        val arr = json.optJSONArray("returns") ?: JSONArray()
        return List(arr.length()) { i ->
            val r = arr.getJSONObject(i)
            val id = r.getString("id")
            val itemsArr = r.optJSONArray("items") ?: JSONArray()
            val items = List(itemsArr.length()) { j ->
                val it = itemsArr.getJSONObject(j)
                ReturnItem(
                    productId = it.optString("productId", ""),
                    name = it.optString("name", ""),
                    unitPrice = it.optInt("unitPrice", 0),
                    qty = it.optInt("qty", 1)
                )
            }
            val photosArr = r.optJSONArray("photos") ?: JSONArray()
            val photos = List(photosArr.length()) { j ->
                val ph = photosArr.getJSONObject(j)
                val kind = when (ph.optString("kind")) {
                    "whole" -> ReturnPhotoKind.WHOLE
                    "defect" -> ReturnPhotoKind.DEFECT
                    "label" -> ReturnPhotoKind.LABEL
                    else -> ReturnPhotoKind.OTHER
                }
                val photoId = ph.getInt("id")
                ReturnPhoto(
                    id = photoId,
                    kind = kind,
                    caption = kind.labelJa,
                    imageUrl = "${baseUrl()}/api/staff/returns/$id/photos/$photoId"
                )
            }
            ReturnRequest(
                id = id,
                orderId = r.optString("orderId", ""),
                orderPickupNo = r.optInt("orderNo", 0),
                storeId = storeId,
                code = r.optString("code", ""),
                reason = ReturnReason.entries.find { it.apiValue == r.optString("reason") } ?: ReturnReason.OTHER,
                detail = r.optString("detail", ""),
                wanted = if (r.optString("wanted") == "exchange") ReturnWanted.EXCHANGE else ReturnWanted.REFUND,
                status = ReturnStatus.entries.find { it.apiValue == r.optString("status") } ?: ReturnStatus.SUBMITTED,
                resolution = when (r.optNullableString("resolution")) {
                    "refund" -> ReturnWanted.REFUND
                    "exchange" -> ReturnWanted.EXCHANGE
                    else -> null
                },
                refundAmount = r.optInt("refundAmount", 0),
                staffNote = r.optNullableString("staffNote"),
                decidedBy = r.optNullableString("decidedBy"),
                decidedAt = r.optNullableString("decidedAt")?.let(::formatIsoToJst),
                completedAt = r.optNullableString("completedAt")?.let(::formatIsoToJst),
                createdAt = formatIsoToJst(r.optNullableString("createdAt")),
                geoDistanceM = if (r.isNull("geoDistance")) null else r.optInt("geoDistance"),
                geoAccuracyM = if (r.isNull("geoAccuracy")) null else r.optInt("geoAccuracy"),
                items = items,
                photos = photos
            )
        }
    }

    suspend fun getReturnPhotoBytes(returnId: String, photoId: Int): ByteArray =
        executeBytes("/api/staff/returns/$returnId/photos/$photoId")

    suspend fun decideReturn(returnId: String, decision: String, note: String) {
        executeJson(
            path = QrOrderApiEndpoints.returnDecide(returnId),
            method = "POST",
            body = JSONObject()
                .put("decision", decision)
                .put("note", note.trim())
        )
    }

    suspend fun completeReturn(returnId: String, refundAmount: Int?) {
        val body = JSONObject().put("requestId", "ret-$returnId-${UUID.randomUUID().toString().take(8)}")
        if (refundAmount != null) body.put("amount", refundAmount)
        executeJson(
            path = QrOrderApiEndpoints.returnComplete(returnId),
            method = "POST",
            body = body
        )
    }

    // ---------- Chat & LINE ----------

    suspend fun getChatThreads(storeId: String): ChatListResponse {
        val json = executeJson(
            path = QrOrderApiEndpoints.CHAT,
            query = mapOf("store" to storeId)
        )
        val line = json.optJSONObject("line") ?: JSONObject()
        val arr = json.optJSONArray("threads") ?: JSONArray()
        val threads = List(arr.length()) { i ->
            val t = arr.getJSONObject(i)
            val lastBody = t.optNullableString("lastBody").orEmpty()
            val lastSender = if (t.optString("lastSender") == "store") ChatSender.STORE else ChatSender.CUSTOMER
            val lastAt = formatIsoToJst(t.optNullableString("lastMessageAt"))
            ChatThread(
                id = t.getString("id"),
                storeId = storeId,
                code = t.optString("code", ""),
                orderId = null,
                orderPickupNo = if (t.isNull("orderNo")) null else t.optInt("orderNo"),
                createdAt = lastAt,
                lastMessageAt = lastAt,
                messages = if (lastBody.isNotEmpty()) {
                    listOf(ChatMessage(id = -1, sender = lastSender, body = lastBody, createdAt = lastAt))
                } else {
                    emptyList()
                }
            )
        }
        return ChatListResponse(
            lineConfigured = line.optBoolean("configured", false),
            lineLinked = line.optBoolean("linked", false),
            lineLinkCode = line.optNullableString("linkCode"),
            threads = threads
        )
    }

    suspend fun getChatMessages(threadId: String): List<ChatMessage> {
        val json = executeJson(QrOrderApiEndpoints.chatThread(threadId))
        val arr = json.optJSONArray("messages") ?: JSONArray()
        return List(arr.length()) { i ->
            val m = arr.getJSONObject(i)
            ChatMessage(
                id = m.getInt("id"),
                sender = if (m.optString("sender") == "store") ChatSender.STORE else ChatSender.CUSTOMER,
                body = m.optString("body", ""),
                createdAt = formatIsoToJst(m.optNullableString("createdAt"))
            )
        }
    }

    suspend fun sendChatReply(threadId: String, body: String) {
        executeJson(
            path = QrOrderApiEndpoints.chatThread(threadId),
            method = "POST",
            body = JSONObject().put("body", body.trim())
        )
    }

    suspend fun issueLineLinkCode(storeId: String): Pair<String, Int> {
        val json = executeJson(
            path = QrOrderApiEndpoints.storeLineLink(storeId),
            method = "POST"
        )
        return json.getString("code") to json.optInt("minutes", 15)
    }

    suspend fun unlinkLine(storeId: String) {
        executeJson(
            path = QrOrderApiEndpoints.storeLineLink(storeId),
            method = "DELETE"
        )
    }

    // ---------- Staff Accounts ----------

    suspend fun getAccounts(): List<StaffAccount> {
        val json = executeJson(QrOrderApiEndpoints.ACCOUNTS)
        val arr = json.optJSONArray("accounts") ?: JSONArray()
        return List(arr.length()) { i ->
            val a = arr.getJSONObject(i)
            StaffAccount(
                id = a.getString("id"),
                email = a.getString("email"),
                role = if (a.optString("role") == "admin") StaffRole.ADMIN else StaffRole.STAFF,
                active = a.optBoolean("active", true),
                setupAllowed = a.optBoolean("setupAllowed", false),
                passkeyCount = a.optInt("passkeys", 0),
                createdAt = ""
            )
        }
    }

    suspend fun addAccount(email: String, role: StaffRole): Boolean {
        val json = executeJson(
            path = QrOrderApiEndpoints.ACCOUNTS,
            method = "POST",
            body = JSONObject()
                .put("email", email.trim())
                .put("role", role.apiValue)
        )
        return json.optBoolean("invited", false)
    }

    suspend fun setAccountActive(accountId: String, active: Boolean) {
        executeJson(
            path = QrOrderApiEndpoints.accountActive(accountId),
            method = "POST",
            body = JSONObject().put("active", active)
        )
    }

    suspend fun resetAccount(accountId: String) {
        executeJson(
            path = QrOrderApiEndpoints.accountReset(accountId),
            method = "POST"
        )
    }

    suspend fun deleteAccount(accountId: String) {
        executeJson(
            path = QrOrderApiEndpoints.account(accountId),
            method = "DELETE"
        )
    }

    // ---------- Passkeys ----------

    suspend fun getPasskeys(): List<PasskeyInfo> {
        val json = executeJson(QrOrderApiEndpoints.PASSKEYS)
        val arr = json.optJSONArray("passkeys") ?: JSONArray()
        return List(arr.length()) { i ->
            val p = arr.getJSONObject(i)
            PasskeyInfo(
                id = p.getString("id"),
                label = p.optNullableString("label") ?: "登録済み端末",
                createdAt = formatIsoToJst(p.optNullableString("createdAt")),
                lastUsedAt = p.optNullableString("lastUsedAt")?.let(::formatIsoToJst)
            )
        }
    }

    suspend fun getAddPasskeyOptions(): PasskeyChallengePayload {
        val json = executeJson(
            path = QrOrderApiEndpoints.PASSKEYS_OPTIONS,
            method = "POST"
        )
        return PasskeyChallengePayload(
            challengeId = json.getString("challengeId"),
            optionsJson = json.getJSONObject("options").toString()
        )
    }

    suspend fun verifyAddPasskey(challengeId: String, responseJson: String) {
        executeJson(
            path = QrOrderApiEndpoints.PASSKEYS_VERIFY,
            method = "POST",
            body = JSONObject()
                .put("challengeId", challengeId)
                .put("response", JSONObject(responseJson))
        )
    }

    suspend fun deletePasskey(passkeyId: String) {
        executeJson(
            path = QrOrderApiEndpoints.passkey(passkeyId),
            method = "DELETE"
        )
    }

    private fun JSONObject.optNullableString(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    private fun translateError(status: Int, code: String?): String = when (code) {
        "unauthorized" -> "ログインセッションが切れました。再度ログインしてください。"
        "admin_only" -> "この操作は管理者のみ実行できます。"
        "forbidden" -> "アクセスが拒否されました（Origin 不一致）。"
        "bad_request" -> "入力内容が正しくありません。"
        "too_many_requests" -> "短時間に操作が多すぎます。少し時間をおいてお試しください。"
        "invalid_code" -> "確認コードが正しくないか、有効期限が切れています。"
        "login_failed" -> "パスキー認証に失敗しました。サーバー側が Android Origin 未対応の場合は「ブラウザ認証でログイン」を使用してください。"
        "registration_failed" -> "パスキーの登録に失敗しました。"
        "barcode_taken" -> "同じバーコードの商品がすでに登録されています。"
        "not_applicable" -> "この注文はすでに確認済みか対象外です。"
        "not_refundable" -> "この注文は返金できません。"
        "bad_amount" -> "返金額が返金可能額を超えているか不正です。"
        "payment_provider_error" -> "Stripe での返金処理に失敗しました。"
        "note_required" -> "却下理由の入力は必須です。"
        "not_pending" -> "この申請はすでに処理済みです。"
        "not_approved" -> "承認済みの申請のみ完了できます。"
        "location_inaccurate" -> "位置情報の精度が不足しています。"
        "email_taken" -> "そのメールアドレスはすでに登録されています。"
        "last_admin" -> "最後の管理者は停止・削除できません。"
        "cannot_delete_last_passkey" -> "最後のパスキーは削除できません。"
        "mail_failed" -> "確認メールの送信に失敗しました（サーバーのメール設定を確認してください）。"
        else -> "通信エラー（HTTP $status${if (code != null) ": $code" else ""}）"
    }
}
