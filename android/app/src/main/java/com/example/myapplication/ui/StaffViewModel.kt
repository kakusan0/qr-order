package com.example.myapplication.ui

import android.app.Activity
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.model.BarcodeLookupResult
import com.example.myapplication.data.model.ChatThread
import com.example.myapplication.data.model.CustomerUrlInfo
import com.example.myapplication.data.model.Order
import com.example.myapplication.data.model.PasskeyInfo
import com.example.myapplication.data.model.Product
import com.example.myapplication.data.model.ReturnRequest
import com.example.myapplication.data.model.ReturnStatus
import com.example.myapplication.data.model.ReturnWanted
import com.example.myapplication.data.model.StaffAccount
import com.example.myapplication.data.model.StaffRole
import com.example.myapplication.data.model.StaffSession
import com.example.myapplication.data.model.StaffTab
import com.example.myapplication.data.model.Store
import com.example.myapplication.data.model.ThemeMode
import com.example.myapplication.data.remote.ApiConfigStore
import com.example.myapplication.data.repository.MockStaffRepository
import com.example.myapplication.data.repository.QrOrderApiEndpoints
import com.example.myapplication.data.repository.RemoteStaffRepository
import com.example.myapplication.data.repository.StaffRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

data class StaffUiState(
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val baseUrl: String = QrOrderApiEndpoints.DEFAULT_BASE_URL,
    val useMockMode: Boolean = false,
    val connectionError: String? = null,
    val session: StaffSession? = null,
    val stores: List<Store> = emptyList(),
    val selectedStoreId: String = "",
    val selectedTab: StaffTab = StaffTab.ORDERS,
    val themeMode: ThemeMode = ThemeMode.AUTO,
    val orders: List<Order> = emptyList(),
    val products: List<Product> = emptyList(),
    val returns: List<ReturnRequest> = emptyList(),
    val chatThreads: List<ChatThread> = emptyList(),
    val accounts: List<StaffAccount> = emptyList(),
    val passkeys: List<PasskeyInfo> = emptyList(),
    val showDisplayQrScreen: Boolean = false
) {
    val currentStore: Store? get() = stores.find { it.id == selectedStoreId } ?: stores.firstOrNull()
    val visibleTabs: List<StaffTab>
        get() = StaffTab.entries.filter { !it.adminOnly || session?.isAdmin == true }
    val pendingAgeCheckCount: Int get() = orders.count { it.needsAgeCheck }
    val pendingReturnsCount: Int get() = returns.count { it.status == ReturnStatus.SUBMITTED }
    val waitingChatCount: Int get() = chatThreads.count { it.waitingForReply }
}

@OptIn(ExperimentalCoroutinesApi::class)
class StaffViewModel(
    private val configStore: ApiConfigStore
) : ViewModel() {

    private val mockRepository = MockStaffRepository()
    private val remoteRepository = RemoteStaffRepository(configStore)

    private val activeRepository: StaffRepository
        get() = if (configStore.useMockMode) mockRepository else remoteRepository

    private val activeRepoFlow = configStore.configFlow
        .map { if (it.useMockMode) mockRepository else remoteRepository }
        .distinctUntilChanged()

    private val selectedStoreId = MutableStateFlow("")
    private val selectedTab = MutableStateFlow(StaffTab.ORDERS)
    private val themeMode = MutableStateFlow(ThemeMode.AUTO)
    private val showDisplayQrScreen = MutableStateFlow(false)
    private val isInitializing = MutableStateFlow(true)
    private val isRefreshing = MutableStateFlow(false)
    private val connectionError = MutableStateFlow<String?>(null)

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val sessionFlow = activeRepoFlow.flatMapLatest { it.sessionFlow }
    private val storesFlow = activeRepoFlow.flatMapLatest { it.storesFlow }
    private val accountsFlow = activeRepoFlow.flatMapLatest { it.accountsFlow }
    private val passkeysFlow = activeRepoFlow.flatMapLatest { it.passkeysFlow }

    private val effectiveStoreIdFlow = combine(storesFlow, selectedStoreId) { stores, preferred ->
        stores.find { it.id == preferred }?.id ?: stores.firstOrNull()?.id.orEmpty()
    }.distinctUntilChanged()

    private val storeScopedData = combine(activeRepoFlow, effectiveStoreIdFlow) { repo, storeId ->
        repo to storeId
    }.flatMapLatest { (repo, storeId) ->
        if (storeId.isEmpty()) {
            flowOf(StoreScopedData())
        } else {
            combine(
                repo.ordersFlow(storeId),
                repo.productsFlow(storeId),
                repo.returnsFlow(storeId),
                repo.chatThreadsFlow(storeId)
            ) { orders, products, returns, threads ->
                StoreScopedData(orders, products, returns, threads)
            }
        }
    }

    private val uiPreferences = combine(
        selectedTab,
        themeMode,
        showDisplayQrScreen,
        isInitializing,
        isRefreshing
    ) { tab, theme, displayQr, initLoading, refreshing ->
        UiPreferences(tab, theme, displayQr, initLoading, refreshing)
    }

    private val coreData = combine(
        sessionFlow,
        storesFlow,
        effectiveStoreIdFlow,
        accountsFlow,
        passkeysFlow
    ) { session, stores, storeId, accounts, passkeys ->
        CoreData(session, stores, storeId, accounts, passkeys)
    }

    val uiState: StateFlow<StaffUiState> = combine(
        configStore.configFlow,
        connectionError,
        uiPreferences,
        coreData,
        storeScopedData
    ) { config, error, prefs, core, scoped ->
        val effectiveTab = if (prefs.tab.adminOnly && core.session?.isAdmin != true) {
            StaffTab.ORDERS
        } else {
            prefs.tab
        }
        StaffUiState(
            isLoading = prefs.isInitializing,
            isRefreshing = prefs.isRefreshing,
            baseUrl = config.baseUrl,
            useMockMode = config.useMockMode,
            connectionError = error,
            session = core.session,
            stores = core.stores,
            selectedStoreId = core.effectiveStoreId,
            selectedTab = effectiveTab,
            themeMode = prefs.themeMode,
            orders = scoped.orders,
            products = scoped.products,
            returns = scoped.returns,
            chatThreads = scoped.chatThreads,
            accounts = core.accounts,
            passkeys = core.passkeys,
            showDisplayQrScreen = prefs.showDisplayQrScreen
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = StaffUiState(
            isLoading = true,
            baseUrl = configStore.baseUrl,
            useMockMode = configStore.useMockMode
        )
    )

    private data class StoreScopedData(
        val orders: List<Order> = emptyList(),
        val products: List<Product> = emptyList(),
        val returns: List<ReturnRequest> = emptyList(),
        val chatThreads: List<ChatThread> = emptyList()
    )

    private data class UiPreferences(
        val tab: StaffTab,
        val themeMode: ThemeMode,
        val showDisplayQrScreen: Boolean,
        val isInitializing: Boolean,
        val isRefreshing: Boolean
    )

    private data class CoreData(
        val session: StaffSession?,
        val stores: List<Store>,
        val effectiveStoreId: String,
        val accounts: List<StaffAccount>,
        val passkeys: List<PasskeyInfo>
    )

    init {
        viewModelScope.launch {
            configStore.configFlow.collectLatest { config ->
                isInitializing.value = true
                connectionError.value = null
                val repo = if (config.useMockMode) mockRepository else remoteRepository
                repo.initialize()
                    .onFailure { err ->
                        connectionError.value = err.message ?: "サーバーに接続できませんでした"
                    }
                isInitializing.value = false
            }
        }

        // API モードでログイン中かつ店舗選択中なら 20 秒ごとに自動更新
        viewModelScope.launch {
            combine(
                configStore.configFlow.map { it.useMockMode },
                sessionFlow.map { it != null },
                effectiveStoreIdFlow
            ) { useMock, loggedIn, storeId ->
                Triple(useMock, loggedIn, storeId)
            }.distinctUntilChanged().collectLatest { (useMock, loggedIn, storeId) ->
                if (!useMock && loggedIn && storeId.isNotBlank()) {
                    while (true) {
                        delay(20.seconds)
                        remoteRepository.refreshStoreData(storeId)
                    }
                }
            }
        }
    }

    private fun emitMessage(text: String) {
        _messages.tryEmit(text)
    }

    fun updateConnectionSettings(baseUrl: String, useMock: Boolean) {
        configStore.updateConfig(baseUrl, useMock)
        showDisplayQrScreen.value = false
        emitMessage(
            if (useMock) "モックモードに切り替えました"
            else "API 接続先を ${configStore.baseUrl} に設定しました"
        )
    }

    fun refreshNow() = viewModelScope.launch {
        if (configStore.useMockMode) {
            emitMessage("モックモードで動作中です")
            return@launch
        }
        val storeId = uiState.value.selectedStoreId
        isRefreshing.value = true
        connectionError.value = null
        val result = if (uiState.value.session == null) {
            remoteRepository.initialize()
        } else {
            remoteRepository.refreshStoreData(storeId)
        }
        result
            .onSuccess { emitMessage("最新データを取得しました") }
            .onFailure {
                val msg = it.message ?: "更新に失敗しました"
                connectionError.value = msg
                emitMessage(msg)
            }
        isRefreshing.value = false
    }

    fun selectStore(storeId: String) {
        selectedStoreId.value = storeId
        if (!configStore.useMockMode && storeId.isNotBlank()) {
            viewModelScope.launch {
                isRefreshing.value = true
                remoteRepository.refreshStoreData(storeId)
                    .onFailure { emitMessage(it.message ?: "店舗データの取得に失敗しました") }
                isRefreshing.value = false
            }
        }
    }

    fun selectTab(tab: StaffTab) {
        selectedTab.value = tab
    }

    fun cycleThemeMode() {
        val next = when (themeMode.value) {
            ThemeMode.AUTO -> ThemeMode.LIGHT
            ThemeMode.LIGHT -> ThemeMode.DARK
            ThemeMode.DARK -> ThemeMode.AUTO
        }
        themeMode.value = next
        emitMessage("テーマを「${next.labelJa}」に切り替えました")
    }

    fun openDisplayQrScreen(open: Boolean) {
        showDisplayQrScreen.value = open
    }

    // Auth
    fun loginWithPasskey(role: StaffRole = StaffRole.ADMIN) = viewModelScope.launch {
        activeRepository.loginWithPasskey(role)
            .onSuccess {
                connectionError.value = null
                emitMessage("${it.email}（${it.role.labelJa}）でログインしました")
            }
            .onFailure { emitMessage(it.message ?: "ログインに失敗しました") }
    }

    fun loginWithNativePasskey(activity: Activity) = viewModelScope.launch {
        isRefreshing.value = true
        activeRepository.loginWithNativePasskey(activity)
            .onSuccess {
                connectionError.value = null
                emitMessage("${it.email}（${it.role.labelJa}）でログインしました")
            }
            .onFailure {
                val msg = it.message ?: "パスキーログインに失敗しました"
                connectionError.value = msg
                emitMessage(msg)
            }
        isRefreshing.value = false
    }

    fun importSessionCookie(cookieValue: String, onSuccess: () -> Unit = {}) = viewModelScope.launch {
        isRefreshing.value = true
        activeRepository.importSessionCookie(cookieValue)
            .onSuccess {
                connectionError.value = null
                emitMessage("${it.email}（${it.role.labelJa}）でログインしました")
                onSuccess()
            }
            .onFailure {
                val msg = it.message ?: "セッションの確認に失敗しました"
                connectionError.value = msg
                emitMessage(msg)
            }
        isRefreshing.value = false
    }

    fun requestSetupCode(email: String, onSuccess: (String) -> Unit) = viewModelScope.launch {
        activeRepository.requestSetupCode(email)
            .onSuccess { msg ->
                emitMessage(msg)
                onSuccess(msg)
            }
            .onFailure { emitMessage(it.message ?: "確認コードの送信に失敗しました") }
    }

    fun verifySetupCode(email: String, code: String, onSuccess: () -> Unit) = viewModelScope.launch {
        activeRepository.verifySetupCode(email, code)
            .onSuccess {
                emitMessage("確認コードを確認しました。パスキーを登録してください。")
                onSuccess()
            }
            .onFailure { emitMessage(it.message ?: "確認に失敗しました") }
    }

    fun finishSetupPasskey(email: String, deviceLabel: String) = viewModelScope.launch {
        activeRepository.finishSetupPasskey(email, deviceLabel)
            .onSuccess { emitMessage("パスキーを登録してログインしました") }
            .onFailure { emitMessage(it.message ?: "パスキー登録に失敗しました") }
    }

    fun finishSetupWithNativePasskey(activity: Activity, email: String) = viewModelScope.launch {
        isRefreshing.value = true
        activeRepository.finishSetupWithNativePasskey(activity, email)
            .onSuccess {
                connectionError.value = null
                emitMessage("パスキーを登録してログインしました")
            }
            .onFailure { emitMessage(it.message ?: "パスキー登録に失敗しました") }
        isRefreshing.value = false
    }

    fun logout() = viewModelScope.launch {
        activeRepository.logout()
        showDisplayQrScreen.value = false
        emitMessage("ログアウトしました")
    }

    fun switchDemoRole(role: StaffRole) = viewModelScope.launch {
        activeRepository.switchDemoRole(role)
        emitMessage("デモ権限を「${role.labelJa}」に切り替えました")
    }

    // Orders
    fun confirmAgeCheck(orderId: String) = viewModelScope.launch {
        activeRepository.confirmAgeCheck(orderId)
            .onSuccess { emitMessage("年齢確認を完了しました") }
            .onFailure { emitMessage(it.message ?: "更新に失敗しました") }
    }

    fun refundOrder(orderId: String, amount: Int) = viewModelScope.launch {
        activeRepository.refundOrder(orderId, amount)
            .onSuccess { emitMessage("¥%,d を返金しました".format(amount)) }
            .onFailure { emitMessage(it.message ?: "返金に失敗しました") }
    }

    // Products
    fun createProduct(
        name: String,
        price: Int,
        taxRate: Int,
        barcode: String?,
        ageRestricted: Boolean,
        onSuccess: () -> Unit = {}
    ) = viewModelScope.launch {
        val storeId = uiState.value.selectedStoreId
        activeRepository.createProduct(storeId, name, price, taxRate, barcode, ageRestricted)
            .onSuccess {
                emitMessage("商品「${it.name}」を登録しました")
                onSuccess()
            }
            .onFailure { emitMessage(it.message ?: "商品登録に失敗しました") }
    }

    fun updateProduct(
        productId: String,
        name: String,
        price: Int,
        taxRate: Int,
        barcode: String?,
        ageRestricted: Boolean,
        onSuccess: () -> Unit = {}
    ) = viewModelScope.launch {
        activeRepository.updateProduct(productId, name, price, taxRate, barcode, ageRestricted)
            .onSuccess {
                emitMessage("商品「${it.name}」を更新しました")
                onSuccess()
            }
            .onFailure { emitMessage(it.message ?: "商品更新に失敗しました") }
    }

    fun deleteProduct(productId: String) = viewModelScope.launch {
        activeRepository.deleteProduct(productId)
            .onSuccess { emitMessage("商品を削除しました") }
            .onFailure { emitMessage(it.message ?: "商品削除に失敗しました") }
    }

    fun setProductAvailability(productId: String, available: Boolean) = viewModelScope.launch {
        activeRepository.setProductAvailability(productId, available)
            .onSuccess { emitMessage(if (available) "商品を有効にしました" else "商品を無効（品切れ）にしました") }
            .onFailure { emitMessage(it.message ?: "更新に失敗しました") }
    }

    fun lookupBarcode(barcode: String, onResult: (BarcodeLookupResult) -> Unit) = viewModelScope.launch {
        val storeId = uiState.value.selectedStoreId
        val result = activeRepository.lookupBarcode(storeId, barcode)
        onResult(result)
    }

    // Returns
    fun decideReturn(
        returnId: String,
        approve: Boolean,
        resolution: ReturnWanted?,
        staffNote: String
    ) = viewModelScope.launch {
        activeRepository.decideReturn(returnId, approve, resolution, staffNote)
            .onSuccess {
                val label = when {
                    !approve -> "申請を却下しました"
                    resolution == ReturnWanted.EXCHANGE -> "交換で承認しました"
                    else -> "返金で承認しました"
                }
                emitMessage(label)
            }
            .onFailure { emitMessage(it.message ?: "更新に失敗しました") }
    }

    fun completeReturn(returnId: String, refundAmount: Int?) = viewModelScope.launch {
        activeRepository.completeReturn(returnId, refundAmount)
            .onSuccess {
                val msg = if (refundAmount != null && refundAmount > 0) {
                    "商品を回収し、¥%,d を返金して完了しました".format(refundAmount)
                } else {
                    "商品の回収・交換を完了しました"
                }
                emitMessage(msg)
            }
            .onFailure { emitMessage(it.message ?: "完了処理に失敗しました") }
    }

    suspend fun loadReturnPhotoBytes(returnId: String, photoId: Int): ByteArray? =
        activeRepository.loadReturnPhotoBytes(returnId, photoId)

    // Chat & LINE
    fun loadChatThreadMessages(threadId: String) = viewModelScope.launch {
        activeRepository.loadChatThreadMessages(threadId)
            .onFailure { emitMessage(it.message ?: "メッセージ履歴の読み込みに失敗しました") }
    }

    fun sendChatReply(threadId: String, body: String, onSuccess: () -> Unit = {}) = viewModelScope.launch {
        activeRepository.sendChatReply(threadId, body)
            .onSuccess {
                emitMessage("返信を送信しました")
                onSuccess()
            }
            .onFailure { emitMessage(it.message ?: "送信に失敗しました") }
    }

    fun issueLineLinkCode() = viewModelScope.launch {
        val storeId = uiState.value.selectedStoreId
        activeRepository.issueLineLinkCode(storeId)
            .onSuccess { emitMessage("LINE 連携コード「連携 $it」を発行しました") }
            .onFailure { emitMessage(it.message ?: "発行に失敗しました") }
    }

    fun unlinkLine() = viewModelScope.launch {
        val storeId = uiState.value.selectedStoreId
        activeRepository.unlinkLine(storeId)
            .onSuccess { emitMessage("LINE 連携を解除しました") }
            .onFailure { emitMessage(it.message ?: "解除に失敗しました") }
    }

    fun simulateLineLinked() = viewModelScope.launch {
        val storeId = uiState.value.selectedStoreId
        activeRepository.simulateLineLinked(storeId)
            .onSuccess {
                emitMessage(
                    if (configStore.useMockMode) "LINE 通知先と連携しました（デモ）"
                    else "LINE 連携状態を再確認しました"
                )
            }
            .onFailure { emitMessage(it.message ?: "確認に失敗しました") }
    }

    // Store QR & Location
    fun buildCustomerUrl(store: Store, epochSeconds: Long = System.currentTimeMillis() / 1000): CustomerUrlInfo =
        activeRepository.buildCustomerUrl(store, epochSeconds)

    suspend fun fetchCustomerUrl(store: Store): CustomerUrlInfo =
        activeRepository.fetchCustomerUrl(store)

    fun buildProductQrUrl(store: Store, product: Product): String =
        activeRepository.buildProductQrUrl(store, product)

    fun buildEslImageUrl(store: Store, product: Product): String =
        activeRepository.buildEslImageUrl(store, product)

    fun updateQrRotation(minutes: Int) = viewModelScope.launch {
        val storeId = uiState.value.selectedStoreId
        activeRepository.updateQrRotation(storeId, minutes)
            .onSuccess { emitMessage("QR コードの更新間隔を保存しました") }
            .onFailure { emitMessage(it.message ?: "保存に失敗しました") }
    }

    fun updateStoreLocation(lat: Double?, lng: Double?, geofenceM: Int) = viewModelScope.launch {
        val storeId = uiState.value.selectedStoreId
        activeRepository.updateStoreLocation(storeId, lat, lng, geofenceM)
            .onSuccess { emitMessage("店舗の位置情報を保存しました") }
            .onFailure { emitMessage(it.message ?: "保存に失敗しました") }
    }

    fun resolveMapUrl(input: String, onSuccess: (Double, Double) -> Unit) = viewModelScope.launch {
        activeRepository.resolveMapUrl(input)
            .onSuccess { (lat, lng) ->
                onSuccess(lat, lng)
                emitMessage("緯度・経度を読み取りました")
            }
            .onFailure {
                emitMessage(it.message ?: "座標を読み取れませんでした（例: 35.7100, 139.8107 や Google マップ URL）")
            }
    }

    // Staff Accounts
    fun addStaffAccount(email: String, role: StaffRole, onSuccess: () -> Unit = {}) = viewModelScope.launch {
        activeRepository.addStaffAccount(email, role)
            .onSuccess {
                emitMessage("${it.email} を追加しました（セットアップ案内送信済み）")
                onSuccess()
            }
            .onFailure { emitMessage(it.message ?: "追加に失敗しました") }
    }

    fun setStaffAccountActive(accountId: String, active: Boolean) = viewModelScope.launch {
        activeRepository.setStaffAccountActive(accountId, active)
            .onSuccess { emitMessage(if (active) "アカウントを再開しました" else "アカウントを停止しました") }
            .onFailure { emitMessage(it.message ?: "変更に失敗しました") }
    }

    fun resetStaffAccount(accountId: String) = viewModelScope.launch {
        activeRepository.resetStaffAccount(accountId)
            .onSuccess { emitMessage("再セットアップを有効にしました（古いパスキーは無効化）") }
            .onFailure { emitMessage(it.message ?: "操作に失敗しました") }
    }

    fun deleteStaffAccount(accountId: String) = viewModelScope.launch {
        activeRepository.deleteStaffAccount(accountId)
            .onSuccess { emitMessage("スタッフアカウントを削除しました") }
            .onFailure { emitMessage(it.message ?: "削除に失敗しました") }
    }

    // Passkeys
    fun registerPasskey(label: String) = viewModelScope.launch {
        activeRepository.registerPasskey(label)
            .onSuccess { emitMessage("パスキー「${it.label}」を登録しました") }
            .onFailure { emitMessage(it.message ?: "登録に失敗しました") }
    }

    fun registerNativePasskey(activity: Activity, label: String) = viewModelScope.launch {
        activeRepository.registerNativePasskey(activity, label)
            .onSuccess { emitMessage("パスキー「${it.label}」を登録しました") }
            .onFailure { emitMessage(it.message ?: "パスキー登録に失敗しました") }
    }

    fun deletePasskey(passkeyId: String) = viewModelScope.launch {
        activeRepository.deletePasskey(passkeyId)
            .onSuccess { emitMessage("パスキーを削除しました") }
            .onFailure { emitMessage(it.message ?: "削除に失敗しました") }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return StaffViewModel(ApiConfigStore(context.applicationContext)) as T
                }
            }
    }
}
