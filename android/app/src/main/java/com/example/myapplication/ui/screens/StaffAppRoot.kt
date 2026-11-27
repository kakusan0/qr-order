package com.example.myapplication.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.myapplication.data.model.Order
import com.example.myapplication.data.model.OrderItem
import com.example.myapplication.data.model.OrderStatus
import com.example.myapplication.data.model.StaffTab
import com.example.myapplication.data.model.ThemeMode
import com.example.myapplication.ui.StaffViewModel
import com.example.myapplication.ui.components.ConnectionSettingsDialog
import com.example.myapplication.ui.components.CountPill
import com.example.myapplication.ui.components.StatusPill
import com.example.myapplication.ui.components.WebLoginDialog
import com.example.myapplication.ui.theme.QrOrderStaffTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StaffAppRoot(
    viewModel: StaffViewModel
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var showConnectionDialog by remember { mutableStateOf(false) }
    var webLoginPath by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    fun showLocalSnackbar(message: String) {
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    QrOrderStaffTheme(themeMode = state.themeMode) {
        if (state.isLoading) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                Box(contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            return@QrOrderStaffTheme
        }

        val session = state.session
        val currentStore = state.currentStore

        if (session == null) {
            Scaffold(
                snackbarHost = { SnackbarHost(snackbarHostState) }
            ) { innerPadding ->
                Box(modifier = Modifier.padding(innerPadding)) {
                    LoginSetupScreen(
                        baseUrl = state.baseUrl,
                        useMockMode = state.useMockMode,
                        connectionError = state.connectionError,
                        onOpenConnectionSettings = { showConnectionDialog = true },
                        onOpenWebLogin = { path -> webLoginPath = path },
                        onNativePasskeyLogin = viewModel::loginWithNativePasskey,
                        onPasskeyLogin = viewModel::loginWithPasskey,
                        onRequestSetupCode = viewModel::requestSetupCode,
                        onVerifySetupCode = viewModel::verifySetupCode,
                        onFinishSetupPasskey = viewModel::finishSetupPasskey,
                        onFinishSetupWithNativePasskey = viewModel::finishSetupWithNativePasskey
                    )
                }
            }
        } else if (state.showDisplayQrScreen && currentStore != null) {
            DisplayQrScreen(
                store = currentStore,
                buildCustomerUrl = viewModel::buildCustomerUrl,
                fetchCustomerUrl = viewModel::fetchCustomerUrl,
                onClose = { viewModel.openDisplayQrScreen(false) }
            )
        } else {
            val visibleTabs = state.visibleTabs
            val selectedIndex = visibleTabs.indexOf(state.selectedTab).coerceAtLeast(0)
            val pagerState = rememberPagerState(
                initialPage = selectedIndex,
                pageCount = { visibleTabs.size }
            )
            val latestSelectedTab by rememberUpdatedState(state.selectedTab)

            LaunchedEffect(selectedIndex, visibleTabs) {
                if (selectedIndex in visibleTabs.indices && pagerState.currentPage != selectedIndex) {
                    pagerState.animateScrollToPage(selectedIndex)
                }
            }

            LaunchedEffect(pagerState, visibleTabs) {
                snapshotFlow { pagerState.settledPage }
                    .distinctUntilChanged()
                    .collect { page ->
                        val tab = visibleTabs.getOrNull(page) ?: return@collect
                        if (tab != latestSelectedTab) {
                            viewModel.selectTab(tab)
                        }
                    }
            }

            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                snackbarHost = { SnackbarHost(snackbarHostState) },
                topBar = {
                    Column {
                        TopAppBar(
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            ),
                            title = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "店舗管理",
                                        fontWeight = FontWeight.Black,
                                        style = MaterialTheme.typography.titleLarge
                                    )
                                    Box(modifier = Modifier.clickable { showConnectionDialog = true }) {
                                        StatusPill(
                                            text = if (state.useMockMode) "MOCK" else "API",
                                            containerColor = if (state.useMockMode) {
                                                MaterialTheme.colorScheme.surfaceVariant
                                            } else {
                                                MaterialTheme.colorScheme.primaryContainer
                                            },
                                            contentColor = if (state.useMockMode) {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            } else {
                                                MaterialTheme.colorScheme.onPrimaryContainer
                                            }
                                        )
                                    }
                                }
                            },
                            actions = {
                                if (state.stores.isNotEmpty() && currentStore != null) {
                                    var storeMenuExpanded by remember { mutableStateOf(false) }
                                    Box {
                                        TextButton(onClick = { storeMenuExpanded = true }) {
                                            Icon(Icons.Default.Storefront, contentDescription = null)
                                            Spacer(Modifier.width(4.dp))
                                            Text(
                                                text = currentStore.name,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            Icon(Icons.Default.ArrowDropDown, contentDescription = "店舗を選択")
                                        }
                                        DropdownMenu(
                                            expanded = storeMenuExpanded,
                                            onDismissRequest = { storeMenuExpanded = false }
                                        ) {
                                            state.stores.forEach { store ->
                                                DropdownMenuItem(
                                                    text = {
                                                        Text(
                                                            text = store.name,
                                                            fontWeight = if (store.id == currentStore.id) FontWeight.Bold else FontWeight.Normal
                                                        )
                                                    },
                                                    onClick = {
                                                        viewModel.selectStore(store.id)
                                                        storeMenuExpanded = false
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                                if (!state.useMockMode) {
                                    IconButton(
                                        onClick = viewModel::refreshNow,
                                        enabled = !state.isRefreshing
                                    ) {
                                        if (state.isRefreshing) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(18.dp),
                                                strokeWidth = 2.dp
                                            )
                                        } else {
                                            Icon(Icons.Default.Refresh, contentDescription = "最新の情報に更新")
                                        }
                                    }
                                }
                                IconButton(onClick = viewModel::cycleThemeMode) {
                                    val icon = when (state.themeMode) {
                                        ThemeMode.AUTO -> Icons.Default.BrightnessAuto
                                        ThemeMode.LIGHT -> Icons.Default.LightMode
                                        ThemeMode.DARK -> Icons.Default.DarkMode
                                    }
                                    Icon(icon, contentDescription = "テーマ切替: ${state.themeMode.labelJa}")
                                }
                            }
                        )

                        val activeTabIndex = pagerState.currentPage.coerceIn(0, (visibleTabs.size - 1).coerceAtLeast(0))
                        Surface(color = MaterialTheme.colorScheme.surface) {
                            PrimaryScrollableTabRow(
                                selectedTabIndex = activeTabIndex,
                                containerColor = MaterialTheme.colorScheme.surface,
                                edgePadding = 12.dp
                            ) {
                                visibleTabs.forEachIndexed { index, tab ->
                                    val badge = when (tab) {
                                        StaffTab.ORDERS -> state.pendingAgeCheckCount
                                        StaffTab.RETURNS -> state.pendingReturnsCount
                                        StaffTab.CHAT -> state.waitingChatCount
                                        else -> 0
                                    }
                                    val isSelected = activeTabIndex == index
                                    Tab(
                                        selected = isSelected,
                                        onClick = { viewModel.selectTab(tab) },
                                        text = {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Text(
                                                    text = tab.labelJa,
                                                    fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium
                                                )
                                                if (badge > 0) {
                                                    CountPill(
                                                        count = badge,
                                                        containerColor = MaterialTheme.colorScheme.error,
                                                        contentColor = MaterialTheme.colorScheme.onError
                                                    )
                                                }
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            ) { innerPadding ->
                HorizontalPager(
                    state = pagerState,
                    key = { page -> visibleTabs[page].name },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) { page ->
                    when (visibleTabs[page]) {
                        StaffTab.ORDERS -> OrdersTab(
                            orders = state.orders,
                            isAdmin = session.isAdmin,
                            onConfirmAgeCheck = viewModel::confirmAgeCheck,
                            onRefundOrder = viewModel::refundOrder
                        )

                        StaffTab.PRODUCTS -> ProductsTab(
                            store = currentStore,
                            products = state.products,
                            isAdmin = session.isAdmin,
                            buildProductQrUrl = viewModel::buildProductQrUrl,
                            buildEslImageUrl = viewModel::buildEslImageUrl,
                            onCreateProduct = viewModel::createProduct,
                            onUpdateProduct = viewModel::updateProduct,
                            onDeleteProduct = viewModel::deleteProduct,
                            onSetAvailability = viewModel::setProductAvailability,
                            onLookupBarcode = viewModel::lookupBarcode,
                            onShowMessage = ::showLocalSnackbar
                        )

                        StaffTab.RETURNS -> ReturnsTab(
                            returns = state.returns,
                            isAdmin = session.isAdmin,
                            loadReturnPhotoBytes = viewModel::loadReturnPhotoBytes,
                            onDecideReturn = viewModel::decideReturn,
                            onCompleteReturn = viewModel::completeReturn
                        )

                        StaffTab.CHAT -> ChatTab(
                            store = currentStore,
                            threads = state.chatThreads,
                            isAdmin = session.isAdmin,
                            useMockMode = state.useMockMode,
                            onLoadThreadMessages = viewModel::loadChatThreadMessages,
                            onSendReply = viewModel::sendChatReply,
                            onIssueLineLinkCode = viewModel::issueLineLinkCode,
                            onUnlinkLine = viewModel::unlinkLine,
                            onSimulateLineLinked = viewModel::simulateLineLinked,
                            onShowMessage = ::showLocalSnackbar
                        )

                        StaffTab.STORE_QR -> StoreQrTab(
                            store = currentStore,
                            isAdmin = session.isAdmin,
                            buildCustomerUrl = viewModel::buildCustomerUrl,
                            fetchCustomerUrl = viewModel::fetchCustomerUrl,
                            onOpenDisplayQr = { viewModel.openDisplayQrScreen(true) },
                            onUpdateQrRotation = viewModel::updateQrRotation,
                            onUpdateStoreLocation = viewModel::updateStoreLocation,
                            onResolveMapUrl = viewModel::resolveMapUrl,
                            onShowMessage = ::showLocalSnackbar
                        )

                        StaffTab.STAFF -> StaffAccountsTab(
                            accounts = state.accounts,
                            currentUserEmail = session.email,
                            onAddAccount = viewModel::addStaffAccount,
                            onSetActive = viewModel::setStaffAccountActive,
                            onResetAccount = viewModel::resetStaffAccount,
                            onDeleteAccount = viewModel::deleteStaffAccount
                        )

                        StaffTab.PASSKEYS -> PasskeysTab(
                            session = session,
                            passkeys = state.passkeys,
                            useMockMode = state.useMockMode,
                            baseUrl = state.baseUrl,
                            onRegisterPasskey = viewModel::registerPasskey,
                            onRegisterNativePasskey = viewModel::registerNativePasskey,
                            onDeletePasskey = viewModel::deletePasskey,
                            onSwitchDemoRole = viewModel::switchDemoRole,
                            onOpenConnectionSettings = { showConnectionDialog = true },
                            onLogout = viewModel::logout
                        )
                    }
                }
            }
        }

        if (showConnectionDialog) {
            ConnectionSettingsDialog(
                currentBaseUrl = state.baseUrl,
                currentUseMock = state.useMockMode,
                onSave = viewModel::updateConnectionSettings,
                onImportManualCookie = { cookie -> viewModel.importSessionCookie(cookie) },
                onDismiss = { showConnectionDialog = false }
            )
        }

        webLoginPath?.let { path ->
            WebLoginDialog(
                baseUrl = state.baseUrl,
                initialPath = path,
                onSessionCaptured = { cookie ->
                    webLoginPath = null
                    viewModel.importSessionCookie(cookie)
                },
                onDismiss = { webLoginPath = null }
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 412, heightDp = 915)
@Composable
private fun StaffOrdersPreview() {
    val previewOrders = listOf(
        Order(
            id = "ord-004",
            storeId = "shop-001",
            pickupNo = 4,
            status = OrderStatus.PAID,
            total = 1000,
            items = listOf(
                OrderItem("prod-002", "クラフトビール IPA 350ml", 680, 10, 1, ageRestricted = true),
                OrderItem("prod-003", "自家製バタークロワッサン", 320, 8, 1)
            ),
            ageCheckRequired = true,
            geoDistanceM = 18,
            geoAccuracyM = 12,
            paidAt = "2026/11/27 11:05"
        ),
        Order(
            id = "ord-003",
            storeId = "shop-001",
            pickupNo = 3,
            status = OrderStatus.PAID,
            total = 900,
            items = listOf(OrderItem("prod-001", "ドリップコーヒー（ホット）", 450, 8, 2)),
            geoDistanceM = 11,
            geoAccuracyM = 15,
            paidAt = "2026/11/27 10:48"
        )
    )
    QrOrderStaffTheme(themeMode = ThemeMode.LIGHT) {
        Surface(color = MaterialTheme.colorScheme.background) {
            OrdersTab(
                orders = previewOrders,
                isAdmin = true,
                onConfirmAgeCheck = {},
                onRefundOrder = { _, _ -> }
            )
        }
    }
}
