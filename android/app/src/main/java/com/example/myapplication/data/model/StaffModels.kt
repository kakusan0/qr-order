package com.example.myapplication.data.model

enum class StaffRole(val apiValue: String, val labelJa: String) {
    ADMIN("admin", "管理者"),
    STAFF("staff", "スタッフ")
}

enum class ThemeMode(val labelJa: String) {
    AUTO("自動"),
    LIGHT("ライト"),
    DARK("ダーク")
}

enum class StaffTab(val labelJa: String, val adminOnly: Boolean = false) {
    ORDERS("注文"),
    PRODUCTS("商品"),
    RETURNS("返品"),
    CHAT("問い合わせ"),
    STORE_QR("店舗 QR"),
    STAFF("スタッフ", adminOnly = true),
    PASSKEYS("パスキー")
}

data class StaffSession(
    val email: String,
    val role: StaffRole
) {
    val isAdmin: Boolean get() = role == StaffRole.ADMIN
}

data class Store(
    val id: String,
    val name: String,
    val invoiceNumber: String? = null,
    val qrRotateMinutes: Int = 0,
    val lat: Double? = null,
    val lng: Double? = null,
    val geofenceM: Int = 200,
    val lineLinked: Boolean = false,
    val lineLinkCode: String? = null,
    val lineLinkExpiresAt: String? = null,
    val baseHost: String = "https://qr-order.gmakihiro.workers.dev"
)

enum class OrderStatus(val labelJa: String) {
    PAID("支払済"),
    PARTIALLY_REFUNDED("一部返金"),
    REFUNDED("返金済")
}

data class OrderItem(
    val productId: String,
    val name: String,
    val unitPrice: Int,
    val taxRate: Int,
    val qty: Int,
    val ageRestricted: Boolean = false
)

data class RefundRecord(
    val id: String,
    val amount: Int,
    val staffEmail: String,
    val createdAt: String
)

data class Order(
    val id: String,
    val storeId: String,
    val pickupNo: Int,
    val status: OrderStatus,
    val total: Int,
    val refundedAmount: Int = 0,
    val items: List<OrderItem>,
    val ageCheckRequired: Boolean = false,
    val ageCheckedAt: String? = null,
    val ageCheckedBy: String? = null,
    val geoDistanceM: Int? = null,
    val geoAccuracyM: Int? = null,
    val paidAt: String,
    val refunds: List<RefundRecord> = emptyList()
) {
    val refundableAmount: Int get() = (total - refundedAmount).coerceAtLeast(0)
    val needsAgeCheck: Boolean get() = ageCheckRequired && ageCheckedAt == null && status != OrderStatus.REFUNDED
    val formattedPickupNo: String get() = pickupNo.toString().padStart(3, '0')
}

data class Product(
    val id: String,
    val storeId: String,
    val name: String,
    val price: Int,
    val taxRate: Int, // 8 or 10
    val barcode: String? = null,
    val available: Boolean = true,
    val ageRestricted: Boolean = false,
    val sort: Int = 0
)

data class BarcodeLookupResult(
    val barcode: String,
    val suggestedName: String?,
    val source: String?,
    val existingProduct: Product?
)

enum class ReturnReason(val apiValue: String, val labelJa: String) {
    DEFECT("defect", "商品の不良・破損"),
    WRONG_ITEM("wrong_item", "注文と異なる商品"),
    EXPIRED("expired", "賞味・消費期限切れ"),
    OTHER("other", "その他")
}

enum class ReturnWanted(val apiValue: String, val labelJa: String) {
    REFUND("refund", "返金希望"),
    EXCHANGE("exchange", "交換希望")
}

enum class ReturnStatus(val apiValue: String, val labelJa: String) {
    SUBMITTED("submitted", "確認待ち"),
    APPROVED("approved", "承認済（持参待ち）"),
    COMPLETED("completed", "完了"),
    REJECTED("rejected", "却下")
}

enum class ReturnPhotoKind(val labelJa: String) {
    WHOLE("商品の全体"),
    DEFECT("不具合の箇所"),
    LABEL("ラベル・期限"),
    OTHER("その他")
}

data class ReturnPhoto(
    val id: Int,
    val kind: ReturnPhotoKind,
    val caption: String,
    val imageUrl: String? = null
)

data class ReturnItem(
    val productId: String,
    val name: String,
    val unitPrice: Int,
    val qty: Int
)

data class ReturnRequest(
    val id: String,
    val orderId: String,
    val orderPickupNo: Int,
    val storeId: String,
    val code: String,
    val reason: ReturnReason,
    val detail: String,
    val wanted: ReturnWanted,
    val status: ReturnStatus,
    val resolution: ReturnWanted? = null,
    val refundAmount: Int = 0,
    val staffNote: String? = null,
    val decidedBy: String? = null,
    val decidedAt: String? = null,
    val completedAt: String? = null,
    val createdAt: String,
    val geoDistanceM: Int? = null,
    val geoAccuracyM: Int? = null,
    val items: List<ReturnItem>,
    val photos: List<ReturnPhoto>
) {
    val defaultRefundAmount: Int get() = items.sumOf { it.unitPrice * it.qty }
}

enum class ChatSender { CUSTOMER, STORE }

data class ChatMessage(
    val id: Int,
    val sender: ChatSender,
    val body: String,
    val createdAt: String
)

data class ChatThread(
    val id: String,
    val storeId: String,
    val code: String,
    val orderId: String? = null,
    val orderPickupNo: Int? = null,
    val createdAt: String,
    val lastMessageAt: String,
    val messages: List<ChatMessage>
) {
    val waitingForReply: Boolean get() = messages.lastOrNull()?.sender == ChatSender.CUSTOMER
    val lastMessagePreview: String get() = messages.lastOrNull()?.body.orEmpty()
}

data class StaffAccount(
    val id: String,
    val email: String,
    val role: StaffRole,
    val active: Boolean,
    val setupAllowed: Boolean,
    val passkeyCount: Int,
    val createdAt: String
)

data class PasskeyInfo(
    val id: String,
    val label: String,
    val createdAt: String,
    val lastUsedAt: String?
)

data class CustomerUrlInfo(
    val url: String,
    val rotationMinutes: Int,
    val tokenSuffix: String?
)
