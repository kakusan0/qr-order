package com.example.myapplication.data.repository

import android.app.Activity
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
import com.example.myapplication.data.remote.ApiConfigStore
import com.example.myapplication.data.remote.ApiException
import com.example.myapplication.data.remote.PasskeyChallengePayload
import com.example.myapplication.data.remote.PasskeyManager
import com.example.myapplication.data.remote.QrOrderApiClient
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * kakusan0/qr-order の実サーバー API（Cloudflare Workers）と通信する StaffRepository 実装。
 */
class RemoteStaffRepository(
    private val configStore: ApiConfigStore,
    private val api: QrOrderApiClient = QrOrderApiClient(configStore)
) : StaffRepository {

    private val _session = MutableStateFlow<StaffSession?>(null)
    override val sessionFlow: Flow<StaffSession?> = _session

    private val _stores = MutableStateFlow<List<Store>>(emptyList())
    override val storesFlow: Flow<List<Store>> = _stores

    private val _ordersByStore = MutableStateFlow<Map<String, List<Order>>>(emptyMap())
    private val _productsByStore = MutableStateFlow<Map<String, List<Product>>>(emptyMap())
    private val _returnsByStore = MutableStateFlow<Map<String, List<ReturnRequest>>>(emptyMap())
    private val _chatByStore = MutableStateFlow<Map<String, List<ChatThread>>>(emptyMap())

    private val _accounts = MutableStateFlow<List<StaffAccount>>(emptyList())
    override val accountsFlow: Flow<List<StaffAccount>> = _accounts

    private val _passkeys = MutableStateFlow<List<PasskeyInfo>>(emptyList())
    override val passkeysFlow: Flow<List<PasskeyInfo>> = _passkeys

    private var pendingSetupChallenge: PasskeyChallengePayload? = null
    private var lastLoadedStoreId: String? = null

    override fun ordersFlow(storeId: String): Flow<List<Order>> =
        _ordersByStore.map { it[storeId].orEmpty() }

    override fun productsFlow(storeId: String): Flow<List<Product>> =
        _productsByStore.map { it[storeId].orEmpty() }

    override fun returnsFlow(storeId: String): Flow<List<ReturnRequest>> =
        _returnsByStore.map { it[storeId].orEmpty() }

    override fun chatThreadsFlow(storeId: String): Flow<List<ChatThread>> =
        _chatByStore.map { it[storeId].orEmpty() }

    private inline fun <T> runApi(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: ApiException) {
        if (e.statusCode == 401) {
            _session.value = null
        }
        Result.failure(e)
    } catch (e: Throwable) {
        Result.failure(IllegalStateException(e.message ?: "サーバーに接続できませんでした"))
    }

    override suspend fun initialize(): Result<Unit> = runApi {
        val me = api.getMe()
        _session.value = me
        if (me != null) {
            loadPostLoginData(me)
        } else {
            clearCachedData()
        }
    }

    private suspend fun loadPostLoginData(session: StaffSession) = coroutineScope {
        val stores = api.getStores()
        _stores.value = stores
        val passkeysDeferred = async { runCatching { api.getPasskeys() }.getOrDefault(emptyList()) }
        val accountsDeferred = async {
            if (session.isAdmin) runCatching { api.getAccounts() }.getOrDefault(emptyList()) else emptyList()
        }
        _passkeys.value = passkeysDeferred.await()
        _accounts.value = accountsDeferred.await()

        val targetStoreId = lastLoadedStoreId?.takeIf { id -> stores.any { it.id == id } }
            ?: stores.firstOrNull()?.id
        if (targetStoreId != null) {
            fetchStoreScoped(targetStoreId)
        }
    }

    override suspend fun refreshStoreData(storeId: String): Result<Unit> = runApi {
        val session = _session.value ?: api.getMe()?.also { _session.value = it }
            ?: return@runApi
        if (_stores.value.isEmpty()) {
            _stores.value = api.getStores()
        }
        val effectiveStoreId = storeId.ifBlank { _stores.value.firstOrNull()?.id.orEmpty() }
        if (effectiveStoreId.isNotBlank()) {
            fetchStoreScoped(effectiveStoreId)
        }
        _passkeys.value = runCatching { api.getPasskeys() }.getOrDefault(_passkeys.value)
        if (session.isAdmin) {
            _accounts.value = runCatching { api.getAccounts() }.getOrDefault(_accounts.value)
        }
    }

    private suspend fun fetchStoreScoped(storeId: String) = coroutineScope {
        lastLoadedStoreId = storeId
        val ordersDef = async { api.getOrders(storeId) }
        val productsDef = async { api.getProducts(storeId) }
        val returnsDef = async { api.getReturns(storeId) }
        val chatDef = async { api.getChatThreads(storeId) }

        val orders = ordersDef.await()
        val products = productsDef.await()
        val returns = returnsDef.await()
        val chatRes = chatDef.await()

        _ordersByStore.update { it + (storeId to orders) }
        _productsByStore.update { it + (storeId to products) }
        _returnsByStore.update { it + (storeId to returns) }
        _chatByStore.update { currentMap ->
            val existing = currentMap[storeId].orEmpty().associateBy { it.id }
            val merged = chatRes.threads.map { fresh ->
                val prev = existing[fresh.id]
                // 既に詳細メッセージを読み込み済みで件数が多い場合は、最新タイムスタンプが変わっていなければ保持
                if (prev != null && prev.messages.size > 1 && prev.lastMessageAt == fresh.lastMessageAt) {
                    fresh.copy(messages = prev.messages)
                } else fresh
            }
            currentMap + (storeId to merged)
        }
        _stores.update { list ->
            list.map { s ->
                if (s.id == storeId) {
                    s.copy(
                        lineLinked = chatRes.lineLinked,
                        lineLinkCode = chatRes.lineLinkCode
                    )
                } else s
            }
        }
    }

    private fun clearCachedData() {
        _stores.value = emptyList()
        _ordersByStore.value = emptyMap()
        _productsByStore.value = emptyMap()
        _returnsByStore.value = emptyMap()
        _chatByStore.value = emptyMap()
        _accounts.value = emptyList()
        _passkeys.value = emptyList()
    }

    override suspend fun loginWithPasskey(role: StaffRole): Result<StaffSession> =
        Result.failure(IllegalStateException("API モードでは「ブラウザ認証でログイン」または「ネイティブパスキー」を使用してください"))

    override suspend fun loginWithNativePasskey(activity: Activity): Result<StaffSession> = runApi {
        val challenge = api.getLoginOptions()
        val responseJson = PasskeyManager.authenticate(activity, challenge.optionsJson).getOrThrow()
        api.verifyLogin(challenge.challengeId, responseJson)
        val me = api.getMe() ?: throw IllegalStateException("ログイン後のセッション取得に失敗しました")
        _session.value = me
        loadPostLoginData(me)
        me
    }

    override suspend fun importSessionCookie(cookieValue: String): Result<StaffSession> = runApi {
        configStore.sessionCookie = cookieValue.trim()
        val me = api.getMe() ?: run {
            configStore.sessionCookie = null
            throw IllegalStateException("セッションが無効または期限切れです")
        }
        _session.value = me
        loadPostLoginData(me)
        me
    }

    override suspend fun requestSetupCode(email: String): Result<String> = runApi {
        api.requestSetupCode(email)
        "登録済みのアドレスであれば確認コードをメールで送信しました（有効期限 10 分）"
    }

    override suspend fun verifySetupCode(email: String, code: String): Result<Unit> = runApi {
        pendingSetupChallenge = api.verifySetupCode(email, code)
    }

    override suspend fun finishSetupPasskey(email: String, deviceLabel: String): Result<StaffSession> =
        Result.failure(IllegalStateException("ネイティブパスキー登録には端末の生体認証が必要です"))

    override suspend fun finishSetupWithNativePasskey(activity: Activity, email: String): Result<StaffSession> = runApi {
        val challenge = pendingSetupChallenge
            ?: throw IllegalStateException("確認コードの検証を先に完了してください")
        val responseJson = PasskeyManager.register(activity, challenge.optionsJson).getOrThrow()
        api.finishSetupPasskey(challenge.challengeId, responseJson)
        pendingSetupChallenge = null
        val me = api.getMe() ?: throw IllegalStateException("セットアップ後のセッション取得に失敗しました")
        _session.value = me
        loadPostLoginData(me)
        me
    }

    override suspend fun logout() {
        api.logout()
        _session.value = null
        clearCachedData()
    }

    override suspend fun switchDemoRole(role: StaffRole) {
        // 実 API 接続時はアカウントの実際の権限に従うため何もしない
    }

    override suspend fun confirmAgeCheck(orderId: String): Result<Unit> = runApi {
        api.confirmAgeCheck(orderId)
        lastLoadedStoreId?.let { _ordersByStore.update { map -> map + (it to api.getOrders(it)) } }
    }

    override suspend fun refundOrder(orderId: String, amount: Int): Result<Unit> = runApi {
        api.refundOrder(orderId, amount)
        lastLoadedStoreId?.let { _ordersByStore.update { map -> map + (it to api.getOrders(it)) } }
    }

    override suspend fun createProduct(
        storeId: String,
        name: String,
        price: Int,
        taxRate: Int,
        barcode: String?,
        ageRestricted: Boolean
    ): Result<Product> = runApi {
        val id = api.createProduct(storeId, name, price, taxRate, barcode, ageRestricted)
        val refreshed = api.getProducts(storeId)
        _productsByStore.update { it + (storeId to refreshed) }
        refreshed.find { it.id == id } ?: Product(
            id = id,
            storeId = storeId,
            name = name.trim(),
            price = price,
            taxRate = taxRate,
            barcode = barcode?.trim()?.ifEmpty { null },
            available = true,
            ageRestricted = ageRestricted
        )
    }

    override suspend fun updateProduct(
        productId: String,
        name: String,
        price: Int,
        taxRate: Int,
        barcode: String?,
        ageRestricted: Boolean
    ): Result<Product> = runApi {
        api.updateProduct(productId, name, price, taxRate, barcode, ageRestricted)
        val storeId = lastLoadedStoreId.orEmpty()
        if (storeId.isNotBlank()) {
            val refreshed = api.getProducts(storeId)
            _productsByStore.update { it + (storeId to refreshed) }
            refreshed.find { it.id == productId }?.let { return@runApi it }
        }
        Product(
            id = productId,
            storeId = storeId,
            name = name.trim(),
            price = price,
            taxRate = taxRate,
            barcode = barcode?.trim()?.ifEmpty { null },
            ageRestricted = ageRestricted
        )
    }

    override suspend fun deleteProduct(productId: String): Result<Unit> = runApi {
        api.deleteProduct(productId)
        lastLoadedStoreId?.let { storeId ->
            _productsByStore.update { it + (storeId to api.getProducts(storeId)) }
        }
    }

    override suspend fun setProductAvailability(productId: String, available: Boolean): Result<Unit> = runApi {
        api.setProductAvailability(productId, available)
        lastLoadedStoreId?.let { storeId ->
            _productsByStore.update { it + (storeId to api.getProducts(storeId)) }
        }
    }

    override suspend fun lookupBarcode(storeId: String, barcode: String): BarcodeLookupResult {
        val clean = barcode.trim()
        val existing = _productsByStore.value[storeId].orEmpty().find { it.barcode == clean }
        if (existing != null) {
            return BarcodeLookupResult(clean, null, null, existing)
        }
        val remote = runCatching { api.lookupBarcode(clean) }.getOrNull()
        return BarcodeLookupResult(
            barcode = clean,
            suggestedName = remote?.takeIf { it.found }?.name,
            source = remote?.takeIf { it.found }?.source,
            existingProduct = null
        )
    }

    override suspend fun decideReturn(
        returnId: String,
        approve: Boolean,
        resolution: ReturnWanted?,
        staffNote: String
    ): Result<Unit> = runApi {
        val decision = when {
            !approve -> "reject"
            resolution == ReturnWanted.EXCHANGE -> "exchange"
            else -> "refund"
        }
        api.decideReturn(returnId, decision, staffNote)
        lastLoadedStoreId?.let { storeId ->
            _returnsByStore.update { it + (storeId to api.getReturns(storeId)) }
        }
    }

    override suspend fun completeReturn(returnId: String, refundAmount: Int?): Result<Unit> = runApi {
        api.completeReturn(returnId, refundAmount)
        lastLoadedStoreId?.let { storeId ->
            _returnsByStore.update { it + (storeId to api.getReturns(storeId)) }
            _ordersByStore.update { it + (storeId to api.getOrders(storeId)) }
        }
    }

    override suspend fun loadReturnPhotoBytes(returnId: String, photoId: Int): ByteArray? =
        runCatching { api.getReturnPhotoBytes(returnId, photoId) }.getOrNull()

    override suspend fun loadChatThreadMessages(threadId: String): Result<Unit> = runApi {
        val messages = api.getChatMessages(threadId)
        val storeId = lastLoadedStoreId ?: return@runApi
        _chatByStore.update { map ->
            val updated = map[storeId].orEmpty().map { thread ->
                if (thread.id == threadId) thread.copy(messages = messages) else thread
            }
            map + (storeId to updated)
        }
    }

    override suspend fun sendChatReply(threadId: String, body: String): Result<Unit> = runApi {
        api.sendChatReply(threadId, body)
        loadChatThreadMessages(threadId)
    }

    override suspend fun issueLineLinkCode(storeId: String): Result<String> = runApi {
        val (code, minutes) = api.issueLineLinkCode(storeId)
        _stores.update { list ->
            list.map {
                if (it.id == storeId) it.copy(lineLinkCode = code, lineLinkExpiresAt = "発行から ${minutes} 分間有効") else it
            }
        }
        code
    }

    override suspend fun unlinkLine(storeId: String): Result<Unit> = runApi {
        api.unlinkLine(storeId)
        _stores.update { list ->
            list.map {
                if (it.id == storeId) it.copy(lineLinked = false, lineLinkCode = null, lineLinkExpiresAt = null) else it
            }
        }
    }

    override suspend fun simulateLineLinked(storeId: String): Result<Unit> = runApi {
        val chat = api.getChatThreads(storeId)
        _stores.update { list ->
            list.map {
                if (it.id == storeId) it.copy(lineLinked = chat.lineLinked, lineLinkCode = chat.lineLinkCode) else it
            }
        }
    }

    override fun buildCustomerUrl(store: Store, epochSeconds: Long): CustomerUrlInfo =
        CustomerUrlInfo(
            url = "${configStore.baseUrl}/s/${store.id}",
            rotationMinutes = store.qrRotateMinutes,
            tokenSuffix = null
        )

    override suspend fun fetchCustomerUrl(store: Store): CustomerUrlInfo =
        runCatching { api.getCustomerUrl(store.id) }.getOrElse { buildCustomerUrl(store) }

    override fun buildProductQrUrl(store: Store, product: Product): String =
        "${configStore.baseUrl}/p/${product.id}"

    override fun buildEslImageUrl(store: Store, product: Product): String =
        "${configStore.baseUrl}/esl/${product.id}.jpg"

    override suspend fun updateQrRotation(storeId: String, minutes: Int): Result<Unit> = runApi {
        api.updateQrRotation(storeId, minutes)
        _stores.value = api.getStores()
    }

    override suspend fun updateStoreLocation(
        storeId: String,
        lat: Double?,
        lng: Double?,
        geofenceM: Int
    ): Result<Unit> = runApi {
        api.updateStoreLocation(storeId, lat, lng, geofenceM)
        _stores.value = api.getStores()
    }

    override suspend fun resolveMapUrl(url: String): Result<Pair<Double, Double>> {
        GeoLocationParser.parse(url)?.let { return Result.success(it) }
        return runApi { api.resolveMapUrl(url) }
    }

    override suspend fun addStaffAccount(email: String, role: StaffRole): Result<StaffAccount> = runApi {
        api.addAccount(email, role)
        val accounts = api.getAccounts()
        _accounts.value = accounts
        accounts.find { it.email.equals(email.trim(), ignoreCase = true) }
            ?: StaffAccount("", email.trim(), role, active = true, setupAllowed = true, passkeyCount = 0, createdAt = "")
    }

    override suspend fun setStaffAccountActive(accountId: String, active: Boolean): Result<Unit> = runApi {
        api.setAccountActive(accountId, active)
        _accounts.value = api.getAccounts()
    }

    override suspend fun resetStaffAccount(accountId: String): Result<Unit> = runApi {
        api.resetAccount(accountId)
        _accounts.value = api.getAccounts()
    }

    override suspend fun deleteStaffAccount(accountId: String): Result<Unit> = runApi {
        api.deleteAccount(accountId)
        _accounts.value = api.getAccounts()
    }

    override suspend fun registerPasskey(label: String): Result<PasskeyInfo> =
        Result.failure(IllegalStateException("パスキー追加には生体認証が必要です"))

    override suspend fun registerNativePasskey(activity: Activity, label: String): Result<PasskeyInfo> = runApi {
        val challenge = api.getAddPasskeyOptions()
        val responseJson = PasskeyManager.register(activity, challenge.optionsJson).getOrThrow()
        api.verifyAddPasskey(challenge.challengeId, responseJson)
        val passkeys = api.getPasskeys()
        _passkeys.value = passkeys
        passkeys.lastOrNull() ?: PasskeyInfo("new", label, "", null)
    }

    override suspend fun deletePasskey(passkeyId: String): Result<Unit> = runApi {
        api.deletePasskey(passkeyId)
        _passkeys.value = api.getPasskeys()
    }
}
