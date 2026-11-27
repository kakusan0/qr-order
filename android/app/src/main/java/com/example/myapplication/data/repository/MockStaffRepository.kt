package com.example.myapplication.data.repository

import com.example.myapplication.data.model.BarcodeLookupResult
import com.example.myapplication.data.model.ChatMessage
import com.example.myapplication.data.model.ChatSender
import com.example.myapplication.data.model.ChatThread
import com.example.myapplication.data.model.CustomerUrlInfo
import com.example.myapplication.data.model.Order
import com.example.myapplication.data.model.OrderItem
import com.example.myapplication.data.model.OrderStatus
import com.example.myapplication.data.model.PasskeyInfo
import com.example.myapplication.data.model.Product
import com.example.myapplication.data.model.RefundRecord
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.random.Random

/**
 * サーバー接続前に全画面・全操作を実機／エミュレーターで確認できるインメモリモック実装。
 */
class MockStaffRepository : StaffRepository {

    private val timeFmt = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm")
    private fun nowStr(): String = LocalDateTime.now().format(timeFmt)

    private val _session = MutableStateFlow<StaffSession?>(
        StaffSession(email = "owner@example.com", role = StaffRole.ADMIN)
    )
    override val sessionFlow: Flow<StaffSession?> = _session

    private val _stores = MutableStateFlow(
        listOf(
            Store(
                id = "shop-001",
                name = "渋谷カフェ本店",
                invoiceNumber = "T1234567890123",
                qrRotateMinutes = 0,
                lat = 35.659482,
                lng = 139.700559,
                geofenceM = 200,
                lineLinked = true
            ),
            Store(
                id = "shop-002",
                name = "代官山スタンド",
                invoiceNumber = "T1234567890123",
                qrRotateMinutes = 15,
                lat = 35.648094,
                lng = 139.703198,
                geofenceM = 250,
                lineLinked = false
            )
        )
    )
    override val storesFlow: Flow<List<Store>> = _stores

    private val _products = MutableStateFlow(
        listOf(
            Product(
                id = "prod-001",
                storeId = "shop-001",
                name = "ドリップコーヒー（ホット）",
                price = 450,
                taxRate = 8,
                barcode = null,
                available = true,
                ageRestricted = false,
                sort = 1
            ),
            Product(
                id = "prod-002",
                storeId = "shop-001",
                name = "クラフトビール IPA 350ml",
                price = 680,
                taxRate = 10,
                barcode = "4901777289123",
                available = true,
                ageRestricted = true,
                sort = 2
            ),
            Product(
                id = "prod-003",
                storeId = "shop-001",
                name = "自家製バタークロワッサン",
                price = 320,
                taxRate = 8,
                barcode = null,
                available = true,
                ageRestricted = false,
                sort = 3
            ),
            Product(
                id = "prod-004",
                storeId = "shop-001",
                name = "天然水 500ml",
                price = 150,
                taxRate = 8,
                barcode = "4901777018686",
                available = true,
                ageRestricted = false,
                sort = 4
            ),
            Product(
                id = "prod-005",
                storeId = "shop-001",
                name = "季節のチーズケーキ（品切れ）",
                price = 540,
                taxRate = 8,
                barcode = null,
                available = false,
                ageRestricted = false,
                sort = 5
            ),
            Product(
                id = "prod-101",
                storeId = "shop-002",
                name = "アイスカフェラテ",
                price = 520,
                taxRate = 8,
                barcode = null,
                available = true,
                ageRestricted = false,
                sort = 1
            ),
            Product(
                id = "prod-102",
                storeId = "shop-002",
                name = "オーガニックオレンジジュース",
                price = 420,
                taxRate = 8,
                barcode = "4902102072618",
                available = true,
                ageRestricted = false,
                sort = 2
            )
        )
    )

    private val _orders = MutableStateFlow(
        listOf(
            Order(
                id = "ord-004",
                storeId = "shop-001",
                pickupNo = 4,
                status = OrderStatus.PAID,
                total = 1000,
                refundedAmount = 0,
                items = listOf(
                    OrderItem("prod-002", "クラフトビール IPA 350ml", 680, 10, 1, ageRestricted = true),
                    OrderItem("prod-003", "自家製バタークロワッサン", 320, 8, 1, ageRestricted = false)
                ),
                ageCheckRequired = true,
                ageCheckedAt = null,
                ageCheckedBy = null,
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
                refundedAmount = 0,
                items = listOf(
                    OrderItem("prod-001", "ドリップコーヒー（ホット）", 450, 8, 2)
                ),
                ageCheckRequired = false,
                geoDistanceM = 11,
                geoAccuracyM = 15,
                paidAt = "2026/11/27 10:48"
            ),
            Order(
                id = "ord-002",
                storeId = "shop-001",
                pickupNo = 2,
                status = OrderStatus.PARTIALLY_REFUNDED,
                total = 770,
                refundedAmount = 320,
                items = listOf(
                    OrderItem("prod-001", "ドリップコーヒー（ホット）", 450, 8, 1),
                    OrderItem("prod-003", "自家製バタークロワッサン", 320, 8, 1)
                ),
                ageCheckRequired = false,
                geoDistanceM = 24,
                geoAccuracyM = 20,
                paidAt = "2026/11/27 10:12",
                refunds = listOf(
                    RefundRecord("ref-001", 320, "owner@example.com", "2026/11/27 10:25")
                )
            ),
            Order(
                id = "ord-001",
                storeId = "shop-001",
                pickupNo = 1,
                status = OrderStatus.PAID,
                total = 830,
                refundedAmount = 0,
                items = listOf(
                    OrderItem("prod-002", "クラフトビール IPA 350ml", 680, 10, 1, ageRestricted = true),
                    OrderItem("prod-004", "天然水 500ml", 150, 8, 1)
                ),
                ageCheckRequired = true,
                ageCheckedAt = "2026/11/27 09:42",
                ageCheckedBy = "staff1@example.com",
                geoDistanceM = 9,
                geoAccuracyM = 10,
                paidAt = "2026/11/27 09:40"
            ),
            Order(
                id = "ord-201",
                storeId = "shop-002",
                pickupNo = 1,
                status = OrderStatus.PAID,
                total = 940,
                refundedAmount = 0,
                items = listOf(
                    OrderItem("prod-101", "アイスカフェラテ", 520, 8, 1),
                    OrderItem("prod-102", "オーガニックオレンジジュース", 420, 8, 1)
                ),
                ageCheckRequired = false,
                geoDistanceM = 35,
                geoAccuracyM = 18,
                paidAt = "2026/11/27 10:30"
            )
        )
    )

    private val _returns = MutableStateFlow(
        listOf(
            ReturnRequest(
                id = "ret-001",
                orderId = "ord-003",
                orderPickupNo = 3,
                storeId = "shop-001",
                code = "R7KQ",
                reason = ReturnReason.DEFECT,
                detail = "カップの蓋が割れていて中身が漏れていました。交換または返金をお願いしたいです。",
                wanted = ReturnWanted.REFUND,
                status = ReturnStatus.SUBMITTED,
                createdAt = "2026/11/27 10:55",
                geoDistanceM = 45,
                geoAccuracyM = 16,
                items = listOf(
                    ReturnItem("prod-001", "ドリップコーヒー（ホット）", 450, 1)
                ),
                photos = listOf(
                    ReturnPhoto(1, ReturnPhotoKind.WHOLE, "商品全体の写真（撮影済・EXIF除去済）"),
                    ReturnPhoto(2, ReturnPhotoKind.DEFECT, "蓋の破損箇所のアップ写真")
                )
            ),
            ReturnRequest(
                id = "ret-002",
                orderId = "ord-001",
                orderPickupNo = 1,
                storeId = "shop-001",
                code = "R4MX",
                reason = ReturnReason.WRONG_ITEM,
                detail = "別のフレーバーの商品が入っていました。",
                wanted = ReturnWanted.EXCHANGE,
                status = ReturnStatus.APPROVED,
                resolution = ReturnWanted.EXCHANGE,
                staffNote = "申し訳ございません。商品と購入の証明をカウンターへお持ちください。",
                decidedBy = "owner@example.com",
                decidedAt = "2026/11/27 10:05",
                createdAt = "2026/11/27 09:58",
                geoDistanceM = 32,
                geoAccuracyM = 14,
                items = listOf(
                    ReturnItem("prod-004", "天然水 500ml", 150, 1)
                ),
                photos = listOf(
                    ReturnPhoto(3, ReturnPhotoKind.WHOLE, "商品全体の写真"),
                    ReturnPhoto(4, ReturnPhotoKind.LABEL, "商品ラベルの写真")
                )
            )
        )
    )

    private val _chatThreads = MutableStateFlow(
        listOf(
            ChatThread(
                id = "chat-001",
                storeId = "shop-001",
                code = "K7QD",
                orderId = "ord-003",
                orderPickupNo = 3,
                createdAt = "2026/11/27 10:52",
                lastMessageAt = "2026/11/27 10:52",
                messages = listOf(
                    ChatMessage(
                        id = 1,
                        sender = ChatSender.CUSTOMER,
                        body = "注文 #003 の領収書メールがまだ届いていないようです。確認できますか？",
                        createdAt = "2026/11/27 10:52"
                    )
                )
            ),
            ChatThread(
                id = "chat-002",
                storeId = "shop-001",
                code = "M2NP",
                orderId = "ord-001",
                orderPickupNo = 1,
                createdAt = "2026/11/27 09:45",
                lastMessageAt = "2026/11/27 09:48",
                messages = listOf(
                    ChatMessage(
                        id = 2,
                        sender = ChatSender.CUSTOMER,
                        body = "紙袋はもらえますか？",
                        createdAt = "2026/11/27 09:45"
                    ),
                    ChatMessage(
                        id = 3,
                        sender = ChatSender.STORE,
                        body = "はい、カウンターにてお渡ししておりますのでお気軽にお声がけください。",
                        createdAt = "2026/11/27 09:48"
                    )
                )
            )
        )
    )

    private val _accounts = MutableStateFlow(
        listOf(
            StaffAccount(
                id = "acc-001",
                email = "owner@example.com",
                role = StaffRole.ADMIN,
                active = true,
                setupAllowed = false,
                passkeyCount = 2,
                createdAt = "2026/10/01 09:00"
            ),
            StaffAccount(
                id = "acc-002",
                email = "staff1@example.com",
                role = StaffRole.STAFF,
                active = true,
                setupAllowed = false,
                passkeyCount = 1,
                createdAt = "2026/10/15 12:30"
            ),
            StaffAccount(
                id = "acc-003",
                email = "new-staff@example.com",
                role = StaffRole.STAFF,
                active = true,
                setupAllowed = true,
                passkeyCount = 0,
                createdAt = "2026/11/26 18:00"
            )
        )
    )
    override val accountsFlow: Flow<List<StaffAccount>> = _accounts

    private val _passkeys = MutableStateFlow(
        listOf(
            PasskeyInfo(
                id = "pk-001",
                label = "Pixel 9（この端末）",
                createdAt = "2026/10/01 09:05",
                lastUsedAt = "2026/11/27 09:00"
            ),
            PasskeyInfo(
                id = "pk-002",
                label = "店舗レジ PC（Chrome）",
                createdAt = "2026/10/02 10:15",
                lastUsedAt = "2026/11/26 20:10"
            )
        )
    )
    override val passkeysFlow: Flow<List<PasskeyInfo>> = _passkeys

    private var pendingSetupCode: String? = null

    private val mockBarcodeCatalog = mapOf(
        "4901777018686" to ("天然水 500ml" to "Open Food Facts"),
        "4901777289123" to ("クラフトビール IPA 350ml" to "Open Food Facts"),
        "4902102072618" to ("オーガニックオレンジジュース" to "Open Food Facts"),
        "4901085618448" to ("お〜いお茶 緑茶 600ml" to "Open Food Facts"),
        "4902102141116" to ("コカ・コーラ ゼロ 500ml" to "Open Food Facts"),
        "4901301334701" to ("ヘルシア緑茶 350ml" to "Yahoo!ショッピング"),
        "4549131999999" to ("オリジナルマグカップ" to "Yahoo!ショッピング")
    )

    override fun ordersFlow(storeId: String): Flow<List<Order>> =
        _orders.map { list -> list.filter { it.storeId == storeId } }

    override fun productsFlow(storeId: String): Flow<List<Product>> =
        _products.map { list -> list.filter { it.storeId == storeId }.sortedBy { it.sort } }

    override fun returnsFlow(storeId: String): Flow<List<ReturnRequest>> =
        _returns.map { list -> list.filter { it.storeId == storeId } }

    override fun chatThreadsFlow(storeId: String): Flow<List<ChatThread>> =
        _chatThreads.map { list -> list.filter { it.storeId == storeId } }

    override suspend fun loginWithPasskey(role: StaffRole): Result<StaffSession> {
        val email = if (role == StaffRole.ADMIN) "owner@example.com" else "staff1@example.com"
        val session = StaffSession(email = email, role = role)
        _session.value = session
        return Result.success(session)
    }

    override suspend fun requestSetupCode(email: String): Result<String> {
        val trimmed = email.trim().lowercase()
        val acc = _accounts.value.find { it.email.equals(trimmed, ignoreCase = true) && it.setupAllowed && it.active }
            ?: return Result.failure(IllegalArgumentException("セットアップ可能なメールアドレスが見つかりません（モックでは new-staff@example.com 等を使用してください）"))
        val code = Random.nextInt(100000, 999999).toString()
        pendingSetupCode = code
        return Result.success("確認コードを発行しました（デモ用コード: $code）: ${acc.email}")
    }

    override suspend fun verifySetupCode(email: String, code: String): Result<Unit> {
        if (code.trim() != pendingSetupCode && code.trim() != "123456") {
            return Result.failure(IllegalArgumentException("確認コードが一致しません"))
        }
        return Result.success(Unit)
    }

    override suspend fun finishSetupPasskey(email: String, deviceLabel: String): Result<StaffSession> {
        val trimmed = email.trim().lowercase()
        val acc = _accounts.value.find { it.email.equals(trimmed, ignoreCase = true) }
        val role = acc?.role ?: StaffRole.STAFF
        _accounts.update { list ->
            list.map {
                if (it.email.equals(trimmed, ignoreCase = true)) {
                    it.copy(setupAllowed = false, passkeyCount = it.passkeyCount + 1)
                } else it
            }
        }
        _passkeys.update { list ->
            list + PasskeyInfo(
                id = "pk-${UUID.randomUUID().toString().take(6)}",
                label = deviceLabel.ifBlank { "この Android 端末" },
                createdAt = nowStr(),
                lastUsedAt = nowStr()
            )
        }
        val session = StaffSession(email = trimmed, role = role)
        _session.value = session
        pendingSetupCode = null
        return Result.success(session)
    }

    override suspend fun logout() {
        _session.value = null
    }

    override suspend fun switchDemoRole(role: StaffRole) {
        val email = if (role == StaffRole.ADMIN) "owner@example.com" else "staff1@example.com"
        _session.value = StaffSession(email = email, role = role)
    }

    override suspend fun confirmAgeCheck(orderId: String): Result<Unit> {
        val staffEmail = _session.value?.email ?: return Result.failure(IllegalStateException("未ログインです"))
        _orders.update { list ->
            list.map {
                if (it.id == orderId) {
                    it.copy(ageCheckedAt = nowStr(), ageCheckedBy = staffEmail)
                } else it
            }
        }
        return Result.success(Unit)
    }

    override suspend fun refundOrder(orderId: String, amount: Int): Result<Unit> {
        val session = _session.value ?: return Result.failure(IllegalStateException("未ログインです"))
        if (!session.isAdmin) return Result.failure(IllegalStateException("返金は管理者のみ実行できます"))
        val target = _orders.value.find { it.id == orderId }
            ?: return Result.failure(IllegalArgumentException("注文が見つかりません"))
        if (amount <= 0 || amount > target.refundableAmount) {
            return Result.failure(IllegalArgumentException("返金額は 1〜${target.refundableAmount} 円の範囲で入力してください"))
        }
        val newRefunded = target.refundedAmount + amount
        val newStatus = if (newRefunded >= target.total) OrderStatus.REFUNDED else OrderStatus.PARTIALLY_REFUNDED
        val record = RefundRecord(
            id = "ref-${UUID.randomUUID().toString().take(6)}",
            amount = amount,
            staffEmail = session.email,
            createdAt = nowStr()
        )
        _orders.update { list ->
            list.map {
                if (it.id == orderId) {
                    it.copy(
                        refundedAmount = newRefunded,
                        status = newStatus,
                        refunds = listOf(record) + it.refunds
                    )
                } else it
            }
        }
        return Result.success(Unit)
    }

    override suspend fun createProduct(
        storeId: String,
        name: String,
        price: Int,
        taxRate: Int,
        barcode: String?,
        ageRestricted: Boolean
    ): Result<Product> {
        if (_session.value?.isAdmin != true) return Result.failure(IllegalStateException("商品登録は管理者のみ可能です"))
        val cleanName = name.trim()
        val cleanBarcode = barcode?.trim()?.ifEmpty { null }
        if (cleanName.isEmpty()) return Result.failure(IllegalArgumentException("商品名を入力してください"))
        if (price < 50) return Result.failure(IllegalArgumentException("価格は 50 円以上で設定してください"))
        if (cleanBarcode != null && _products.value.any { it.storeId == storeId && it.barcode == cleanBarcode }) {
            return Result.failure(IllegalArgumentException("同じバーコードの商品がすでに登録されています"))
        }
        val nextSort = (_products.value.filter { it.storeId == storeId }.maxOfOrNull { it.sort } ?: 0) + 1
        val product = Product(
            id = "prod-${UUID.randomUUID().toString().take(8)}",
            storeId = storeId,
            name = cleanName,
            price = price,
            taxRate = taxRate,
            barcode = cleanBarcode,
            available = true,
            ageRestricted = ageRestricted,
            sort = nextSort
        )
        _products.update { it + product }
        return Result.success(product)
    }

    override suspend fun updateProduct(
        productId: String,
        name: String,
        price: Int,
        taxRate: Int,
        barcode: String?,
        ageRestricted: Boolean
    ): Result<Product> {
        if (_session.value?.isAdmin != true) return Result.failure(IllegalStateException("商品編集は管理者のみ可能です"))
        val cleanName = name.trim()
        val cleanBarcode = barcode?.trim()?.ifEmpty { null }
        if (cleanName.isEmpty()) return Result.failure(IllegalArgumentException("商品名を入力してください"))
        if (price < 50) return Result.failure(IllegalArgumentException("価格は 50 円以上で設定してください"))
        val current = _products.value.find { it.id == productId }
            ?: return Result.failure(IllegalArgumentException("商品が見つかりません"))
        if (cleanBarcode != null && _products.value.any {
                it.storeId == current.storeId && it.id != productId && it.barcode == cleanBarcode
            }
        ) {
            return Result.failure(IllegalArgumentException("同じバーコードの商品がすでに登録されています"))
        }
        val updated = current.copy(
            name = cleanName,
            price = price,
            taxRate = taxRate,
            barcode = cleanBarcode,
            ageRestricted = ageRestricted
        )
        _products.update { list -> list.map { if (it.id == productId) updated else it } }
        return Result.success(updated)
    }

    override suspend fun deleteProduct(productId: String): Result<Unit> {
        if (_session.value?.isAdmin != true) return Result.failure(IllegalStateException("商品削除は管理者のみ可能です"))
        _products.update { list -> list.filterNot { it.id == productId } }
        return Result.success(Unit)
    }

    override suspend fun setProductAvailability(productId: String, available: Boolean): Result<Unit> {
        _products.update { list ->
            list.map { if (it.id == productId) it.copy(available = available) else it }
        }
        return Result.success(Unit)
    }

    override suspend fun lookupBarcode(storeId: String, barcode: String): BarcodeLookupResult {
        val clean = barcode.trim()
        val existing = _products.value.find { it.storeId == storeId && it.barcode == clean }
        val catalogHit = mockBarcodeCatalog[clean]
        return BarcodeLookupResult(
            barcode = clean,
            suggestedName = catalogHit?.first,
            source = catalogHit?.second,
            existingProduct = existing
        )
    }

    override suspend fun decideReturn(
        returnId: String,
        approve: Boolean,
        resolution: ReturnWanted?,
        staffNote: String
    ): Result<Unit> {
        val session = _session.value ?: return Result.failure(IllegalStateException("未ログインです"))
        if (!session.isAdmin) return Result.failure(IllegalStateException("承認・却下は管理者のみ可能です"))
        val cleanNote = staffNote.trim()
        if (!approve && cleanNote.isEmpty()) {
            return Result.failure(IllegalArgumentException("却下する場合はお客様への理由を入力してください"))
        }
        _returns.update { list ->
            list.map {
                if (it.id == returnId) {
                    it.copy(
                        status = if (approve) ReturnStatus.APPROVED else ReturnStatus.REJECTED,
                        resolution = if (approve) (resolution ?: it.wanted) else null,
                        staffNote = cleanNote.ifEmpty { null },
                        decidedBy = session.email,
                        decidedAt = nowStr()
                    )
                } else it
            }
        }
        return Result.success(Unit)
    }

    override suspend fun completeReturn(returnId: String, refundAmount: Int?): Result<Unit> {
        val session = _session.value ?: return Result.failure(IllegalStateException("未ログインです"))
        if (!session.isAdmin) return Result.failure(IllegalStateException("完了処理は管理者のみ可能です"))
        val req = _returns.value.find { it.id == returnId }
            ?: return Result.failure(IllegalArgumentException("申請が見つかりません"))
        val actualRefund = if (req.resolution == ReturnWanted.REFUND) {
            val amount = refundAmount ?: req.defaultRefundAmount
            val refundResult = refundOrder(req.orderId, amount)
            if (refundResult.isFailure) return refundResult
            amount
        } else 0
        _returns.update { list ->
            list.map {
                if (it.id == returnId) {
                    it.copy(
                        status = ReturnStatus.COMPLETED,
                        refundAmount = actualRefund,
                        completedAt = nowStr()
                    )
                } else it
            }
        }
        return Result.success(Unit)
    }

    override suspend fun sendChatReply(threadId: String, body: String): Result<Unit> {
        val clean = body.trim()
        if (clean.isEmpty()) return Result.failure(IllegalArgumentException("返信内容を入力してください"))
        val now = nowStr()
        _chatThreads.update { list ->
            list.map { thread ->
                if (thread.id == threadId) {
                    val nextId = (thread.messages.maxOfOrNull { it.id } ?: 0) + 1
                    thread.copy(
                        lastMessageAt = now,
                        messages = thread.messages + ChatMessage(
                            id = nextId,
                            sender = ChatSender.STORE,
                            body = clean,
                            createdAt = now
                        )
                    )
                } else thread
            }
        }
        return Result.success(Unit)
    }

    override suspend fun issueLineLinkCode(storeId: String): Result<String> {
        if (_session.value?.isAdmin != true) return Result.failure(IllegalStateException("管理者のみ実行できます"))
        val code = Random.nextInt(100000, 999999).toString()
        val expires = LocalDateTime.now().plusMinutes(15).format(timeFmt)
        _stores.update { list ->
            list.map {
                if (it.id == storeId) it.copy(lineLinkCode = code, lineLinkExpiresAt = expires) else it
            }
        }
        return Result.success(code)
    }

    override suspend fun unlinkLine(storeId: String): Result<Unit> {
        if (_session.value?.isAdmin != true) return Result.failure(IllegalStateException("管理者のみ実行できます"))
        _stores.update { list ->
            list.map {
                if (it.id == storeId) it.copy(lineLinked = false, lineLinkCode = null, lineLinkExpiresAt = null) else it
            }
        }
        return Result.success(Unit)
    }

    override suspend fun simulateLineLinked(storeId: String): Result<Unit> {
        _stores.update { list ->
            list.map {
                if (it.id == storeId) it.copy(lineLinked = true, lineLinkCode = null, lineLinkExpiresAt = null) else it
            }
        }
        return Result.success(Unit)
    }

    override fun buildCustomerUrl(store: Store, epochSeconds: Long): CustomerUrlInfo {
        if (store.qrRotateMinutes <= 0) {
            return CustomerUrlInfo(
                url = "${store.baseHost}/s/${store.id}",
                rotationMinutes = 0,
                tokenSuffix = null
            )
        }
        val bucket = epochSeconds / (store.qrRotateMinutes * 60L)
        val token = ((store.id.hashCode().toLong() * 31 + bucket) and 0xFFFFFFFL)
            .toString(36)
            .padStart(6, '0')
        return CustomerUrlInfo(
            url = "${store.baseHost}/s/${store.id}?k=$token",
            rotationMinutes = store.qrRotateMinutes,
            tokenSuffix = token
        )
    }

    override fun buildProductQrUrl(store: Store, product: Product): String =
        "${store.baseHost}/p/${product.id}"

    override fun buildEslImageUrl(store: Store, product: Product): String =
        "${store.baseHost}/esl/${product.id}.jpg"

    override suspend fun updateQrRotation(storeId: String, minutes: Int): Result<Unit> {
        if (_session.value?.isAdmin != true) return Result.failure(IllegalStateException("管理者のみ変更できます"))
        _stores.update { list ->
            list.map { if (it.id == storeId) it.copy(qrRotateMinutes = minutes) else it }
        }
        return Result.success(Unit)
    }

    override suspend fun updateStoreLocation(
        storeId: String,
        lat: Double?,
        lng: Double?,
        geofenceM: Int
    ): Result<Unit> {
        if (_session.value?.isAdmin != true) return Result.failure(IllegalStateException("管理者のみ変更できます"))
        _stores.update { list ->
            list.map {
                if (it.id == storeId) it.copy(lat = lat, lng = lng, geofenceM = geofenceM.coerceIn(50, 1000)) else it
            }
        }
        return Result.success(Unit)
    }

    override suspend fun addStaffAccount(email: String, role: StaffRole): Result<StaffAccount> {
        if (_session.value?.isAdmin != true) return Result.failure(IllegalStateException("管理者のみ追加できます"))
        val clean = email.trim().lowercase()
        if (!clean.contains("@") || !clean.contains(".")) {
            return Result.failure(IllegalArgumentException("有効なメールアドレスを入力してください"))
        }
        if (_accounts.value.any { it.email.equals(clean, ignoreCase = true) }) {
            return Result.failure(IllegalArgumentException("そのメールアドレスはすでに登録されています"))
        }
        val account = StaffAccount(
            id = "acc-${UUID.randomUUID().toString().take(6)}",
            email = clean,
            role = role,
            active = true,
            setupAllowed = true,
            passkeyCount = 0,
            createdAt = nowStr()
        )
        _accounts.update { it + account }
        return Result.success(account)
    }

    override suspend fun setStaffAccountActive(accountId: String, active: Boolean): Result<Unit> {
        if (_session.value?.isAdmin != true) return Result.failure(IllegalStateException("管理者のみ操作できます"))
        val target = _accounts.value.find { it.id == accountId }
            ?: return Result.failure(IllegalArgumentException("アカウントが見つかりません"))
        if (!active && target.role == StaffRole.ADMIN) {
            val activeAdmins = _accounts.value.count { it.role == StaffRole.ADMIN && it.active }
            if (activeAdmins <= 1) {
                return Result.failure(IllegalStateException("最後の管理者は停止できません"))
            }
        }
        _accounts.update { list ->
            list.map { if (it.id == accountId) it.copy(active = active) else it }
        }
        return Result.success(Unit)
    }

    override suspend fun resetStaffAccount(accountId: String): Result<Unit> {
        if (_session.value?.isAdmin != true) return Result.failure(IllegalStateException("管理者のみ操作できます"))
        _accounts.update { list ->
            list.map {
                if (it.id == accountId) it.copy(setupAllowed = true, passkeyCount = 0) else it
            }
        }
        return Result.success(Unit)
    }

    override suspend fun deleteStaffAccount(accountId: String): Result<Unit> {
        if (_session.value?.isAdmin != true) return Result.failure(IllegalStateException("管理者のみ操作できます"))
        val target = _accounts.value.find { it.id == accountId }
            ?: return Result.failure(IllegalArgumentException("アカウントが見つかりません"))
        if (target.role == StaffRole.ADMIN) {
            val activeAdmins = _accounts.value.count { it.role == StaffRole.ADMIN && it.active }
            if (activeAdmins <= 1) {
                return Result.failure(IllegalStateException("最後の管理者は削除できません"))
            }
        }
        _accounts.update { list -> list.filterNot { it.id == accountId } }
        return Result.success(Unit)
    }

    override suspend fun registerPasskey(label: String): Result<PasskeyInfo> {
        val pk = PasskeyInfo(
            id = "pk-${UUID.randomUUID().toString().take(6)}",
            label = label.trim().ifEmpty { "この Android 端末" },
            createdAt = nowStr(),
            lastUsedAt = nowStr()
        )
        _passkeys.update { it + pk }
        return Result.success(pk)
    }

    override suspend fun deletePasskey(passkeyId: String): Result<Unit> {
        if (_passkeys.value.size <= 1) {
            return Result.failure(IllegalStateException("最後のパスキーは削除できません（ログインできなくなります）"))
        }
        _passkeys.update { list -> list.filterNot { it.id == passkeyId } }
        return Result.success(Unit)
    }
}
