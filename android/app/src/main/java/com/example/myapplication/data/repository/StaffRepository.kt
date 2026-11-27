package com.example.myapplication.data.repository

import com.example.myapplication.data.model.BarcodeLookupResult
import com.example.myapplication.data.model.ChatThread
import com.example.myapplication.data.model.CustomerUrlInfo
import com.example.myapplication.data.model.Order
import com.example.myapplication.data.model.PasskeyInfo
import com.example.myapplication.data.model.Product
import com.example.myapplication.data.model.ReturnRequest
import com.example.myapplication.data.model.ReturnWanted
import com.example.myapplication.data.model.StaffAccount
import com.example.myapplication.data.model.StaffRole
import com.example.myapplication.data.model.StaffSession
import com.example.myapplication.data.model.Store
import kotlinx.coroutines.flow.Flow

/**
 * kakusan0/qr-order の Cloudflare Workers API エンドポイント定義。
 * 将来的に Retrofit / Ktor による RemoteStaffRepository を実装する際、
 * これらのパスと StaffRepository インターフェースをそのまま接続できます。
 */
object QrOrderApiEndpoints {
    const val DEFAULT_BASE_URL = "https://qr-order.infoapp.org"

    const val SETUP_REQUEST = "/api/staff/setup/request"
    const val SETUP_VERIFY = "/api/staff/setup/verify"
    const val SETUP_FINISH = "/api/staff/setup/finish"
    const val LOGIN_OPTIONS = "/api/staff/login/options"
    const val LOGIN_VERIFY = "/api/staff/login/verify"
    const val LOGOUT = "/api/staff/logout"
    const val ME = "/api/staff/me"

    const val STORES = "/api/staff/stores"
    fun storeCustomerUrl(storeId: String) = "/api/staff/stores/$storeId/customer-url"
    fun storeLocation(storeId: String) = "/api/staff/stores/$storeId/location"
    fun storeQrRotation(storeId: String) = "/api/staff/stores/$storeId/qr-rotation"
    fun storeLineLink(storeId: String) = "/api/staff/stores/$storeId/line-link"
    const val GEO_RESOLVE = "/api/staff/geo/resolve"

    const val ORDERS = "/api/staff/orders"
    fun orderAgeCheck(orderId: String) = "/api/staff/orders/$orderId/age-check"
    fun orderRefund(orderId: String) = "/api/staff/orders/$orderId/refund"

    const val PRODUCTS = "/api/staff/products"
    fun product(productId: String) = "/api/staff/products/$productId"
    fun productAvailability(productId: String) = "/api/staff/products/$productId/availability"
    const val BARCODE_LOOKUP = "/api/staff/barcode-lookup"

    const val RETURNS = "/api/staff/returns"
    fun returnDecide(returnId: String) = "/api/staff/returns/$returnId/decide"
    fun returnComplete(returnId: String) = "/api/staff/returns/$returnId/complete"

    const val CHAT = "/api/staff/chat"
    fun chatThread(threadId: String) = "/api/staff/chat/$threadId"

    const val ACCOUNTS = "/api/staff/accounts"
    fun account(accountId: String) = "/api/staff/accounts/$accountId"
    fun accountActive(accountId: String) = "/api/staff/accounts/$accountId/active"
    fun accountReset(accountId: String) = "/api/staff/accounts/$accountId/reset"

    const val PASSKEYS = "/api/staff/passkeys"
    const val PASSKEYS_OPTIONS = "/api/staff/passkeys/options"
    const val PASSKEYS_VERIFY = "/api/staff/passkeys/verify"
    fun passkey(passkeyId: String) = "/api/staff/passkeys/$passkeyId"
}

/**
 * 店舗管理画面のデータアクセス層インターフェース。
 * モック実装（MockStaffRepository）と将来の実 API 実装（RemoteStaffRepository）を
 * 透過的に差し替えられる設計になっています。
 */
interface StaffRepository {
    val sessionFlow: Flow<StaffSession?>
    val storesFlow: Flow<List<Store>>
    fun ordersFlow(storeId: String): Flow<List<Order>>
    fun productsFlow(storeId: String): Flow<List<Product>>
    fun returnsFlow(storeId: String): Flow<List<ReturnRequest>>
    fun chatThreadsFlow(storeId: String): Flow<List<ChatThread>>
    val accountsFlow: Flow<List<StaffAccount>>
    val passkeysFlow: Flow<List<PasskeyInfo>>

    // Auth / Session
    suspend fun initialize(): Result<Unit> = Result.success(Unit)
    suspend fun refreshStoreData(storeId: String): Result<Unit> = Result.success(Unit)
    suspend fun loginWithPasskey(role: StaffRole = StaffRole.ADMIN): Result<StaffSession>
    suspend fun loginWithNativePasskey(activity: android.app.Activity): Result<StaffSession> =
        loginWithPasskey(StaffRole.ADMIN)
    suspend fun importSessionCookie(cookieValue: String): Result<StaffSession> =
        loginWithPasskey(StaffRole.ADMIN)
    suspend fun requestSetupCode(email: String): Result<String>
    suspend fun verifySetupCode(email: String, code: String): Result<Unit>
    suspend fun finishSetupPasskey(email: String, deviceLabel: String): Result<StaffSession>
    suspend fun finishSetupWithNativePasskey(activity: android.app.Activity, email: String): Result<StaffSession> =
        finishSetupPasskey(email, android.os.Build.MODEL)
    suspend fun logout()
    suspend fun switchDemoRole(role: StaffRole)

    // Orders (POST /api/staff/orders/:id/age-check, /refund)
    suspend fun confirmAgeCheck(orderId: String): Result<Unit>
    suspend fun refundOrder(orderId: String, amount: Int): Result<Unit>

    // Products (POST/PUT/DELETE /api/staff/products, /availability, /barcode-lookup)
    suspend fun createProduct(
        storeId: String,
        name: String,
        price: Int,
        taxRate: Int,
        barcode: String?,
        ageRestricted: Boolean
    ): Result<Product>

    suspend fun updateProduct(
        productId: String,
        name: String,
        price: Int,
        taxRate: Int,
        barcode: String?,
        ageRestricted: Boolean
    ): Result<Product>

    suspend fun deleteProduct(productId: String): Result<Unit>
    suspend fun setProductAvailability(productId: String, available: Boolean): Result<Unit>
    suspend fun lookupBarcode(storeId: String, barcode: String): BarcodeLookupResult

    // Returns (POST /api/staff/returns/:id/decide, /complete)
    suspend fun decideReturn(
        returnId: String,
        approve: Boolean,
        resolution: ReturnWanted?,
        staffNote: String
    ): Result<Unit>

    suspend fun completeReturn(returnId: String, refundAmount: Int?): Result<Unit>
    suspend fun loadReturnPhotoBytes(returnId: String, photoId: Int): ByteArray? = null

    // Chat & LINE (POST /api/staff/chat/:threadId, POST/DELETE /api/staff/stores/:id/line-link)
    suspend fun loadChatThreadMessages(threadId: String): Result<Unit> = Result.success(Unit)
    suspend fun sendChatReply(threadId: String, body: String): Result<Unit>
    suspend fun issueLineLinkCode(storeId: String): Result<String>
    suspend fun unlinkLine(storeId: String): Result<Unit>
    suspend fun simulateLineLinked(storeId: String): Result<Unit>

    // Store QR & Location (GET customer-url, POST qr-rotation, POST location)
    fun buildCustomerUrl(store: Store, epochSeconds: Long = System.currentTimeMillis() / 1000): CustomerUrlInfo
    suspend fun fetchCustomerUrl(store: Store): CustomerUrlInfo = buildCustomerUrl(store)
    fun buildProductQrUrl(store: Store, product: Product): String
    fun buildEslImageUrl(store: Store, product: Product): String
    suspend fun updateQrRotation(storeId: String, minutes: Int): Result<Unit>
    suspend fun updateStoreLocation(storeId: String, lat: Double?, lng: Double?, geofenceM: Int): Result<Unit>
    suspend fun resolveMapUrl(url: String): Result<Pair<Double, Double>> =
        GeoLocationParser.parse(url)?.let { Result.success(it) }
            ?: Result.failure(IllegalArgumentException("座標を読み取れませんでした"))

    // Staff Accounts (POST/DELETE /api/staff/accounts, /active, /reset)
    suspend fun addStaffAccount(email: String, role: StaffRole): Result<StaffAccount>
    suspend fun setStaffAccountActive(accountId: String, active: Boolean): Result<Unit>
    suspend fun resetStaffAccount(accountId: String): Result<Unit>
    suspend fun deleteStaffAccount(accountId: String): Result<Unit>

    // Passkeys (POST /api/staff/passkeys/*, DELETE /api/staff/passkeys/:id)
    suspend fun registerPasskey(label: String): Result<PasskeyInfo>
    suspend fun registerNativePasskey(activity: android.app.Activity, label: String): Result<PasskeyInfo> =
        registerPasskey(label)
    suspend fun deletePasskey(passkeyId: String): Result<Unit>
}

/**
 * Google マップ / Apple マップの共有リンクまたは座標テキストから緯度・経度を抽出するユーティリティ。
 * qr-order の public/geo-parse.js と同等の解析をサポートします。
 */
object GeoLocationParser {
    private val patterns = listOf(
        Regex("""@(-?\d+(?:\.\d+)?),\s*(-?\d+(?:\.\d+)?)"""),
        Regex("""[?&](?:q|ll|query|center)=(-?\d+(?:\.\d+)?),\s*(-?\d+(?:\.\d+)?)"""),
        Regex("""!3d(-?\d+(?:\.\d+)?)!4d(-?\d+(?:\.\d+)?)"""),
        Regex("""^\s*(-?\d+(?:\.\d+)?)\s*[,、\s]\s*(-?\d+(?:\.\d+)?)\s*$""")
    )

    fun parse(input: String): Pair<Double, Double>? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        for (regex in patterns) {
            val match = regex.find(trimmed) ?: continue
            val lat = match.groupValues[1].toDoubleOrNull() ?: continue
            val lng = match.groupValues[2].toDoubleOrNull() ?: continue
            if (lat in -90.0..90.0 && lng in -180.0..180.0) {
                return lat to lng
            }
        }
        return null
    }
}
