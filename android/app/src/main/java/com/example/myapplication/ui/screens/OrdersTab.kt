package com.example.myapplication.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.myapplication.data.model.Order
import com.example.myapplication.data.model.OrderStatus
import com.example.myapplication.ui.components.NoticeBanner
import com.example.myapplication.ui.components.NumpadAmountDialog
import com.example.myapplication.ui.components.SectionHeader
import com.example.myapplication.ui.components.StaffCard
import com.example.myapplication.ui.components.StatusPill
import com.example.myapplication.ui.components.formatYen

@Composable
fun OrdersTab(
    orders: List<Order>,
    isAdmin: Boolean,
    onConfirmAgeCheck: (String) -> Unit,
    onRefundOrder: (String, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var refundTarget by remember { mutableStateOf<Order?>(null) }

    val agePendingOrders = remember(orders) { orders.filter { it.needsAgeCheck } }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (agePendingOrders.isNotEmpty()) {
            item(key = "age_header") {
                SectionHeader(
                    title = "年齢確認が必要な注文",
                    badgeCount = agePendingOrders.size,
                    subtitle = "年齢確認商品を含む注文です。お客様の年齢を身分証などで確認してから「年齢を確認した」を押してください。確認できない場合は返金してください。"
                )
            }
            items(agePendingOrders, key = { "age_${it.id}" }) { order ->
                AgeCheckOrderCard(
                    order = order,
                    isAdmin = isAdmin,
                    onConfirmAgeCheck = { onConfirmAgeCheck(order.id) },
                    onRefundClick = { refundTarget = order }
                )
            }
            item(key = "age_spacer") { Spacer(Modifier.height(4.dp)) }
        }

        item(key = "paid_header") {
            SectionHeader(
                title = "支払いが済んだ注文",
                badgeCount = orders.size,
                subtitle = "お支払いが完了した注文の一覧です（新しい順）。商品の受け渡し操作はありません。注文番号は毎日 001 から始まります。"
            )
        }

        if (orders.isEmpty()) {
            item(key = "empty_orders") {
                StaffCard {
                    Text(
                        text = "支払いが済んだ注文はまだありません。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            items(orders, key = { it.id }) { order ->
                PaidOrderCard(
                    order = order,
                    isAdmin = isAdmin,
                    onRefundClick = { refundTarget = order }
                )
            }
        }
        item(key = "bottom_pad") { Spacer(Modifier.height(24.dp)) }
    }

    refundTarget?.let { order ->
        NumpadAmountDialog(
            title = "返金（注文 #${order.formattedPickupNo}）",
            subtitle = "返金は取り消せません。決済に使われた支払方法へ返金されます。\n返金可能額: ${formatYen(order.refundableAmount)}",
            initialAmount = order.refundableAmount,
            minAmount = 1,
            maxAmount = order.refundableAmount,
            confirmLabel = "返金する",
            isDanger = true,
            onConfirm = { amount -> onRefundOrder(order.id, amount) },
            onDismiss = { refundTarget = null }
        )
    }
}

@Composable
private fun AgeCheckOrderCard(
    order: Order,
    isAdmin: Boolean,
    onConfirmAgeCheck: () -> Unit,
    onRefundClick: () -> Unit
) {
    StaffCard(highlightBorderColor = MaterialTheme.colorScheme.error) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "#${order.formattedPickupNo}",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.width(10.dp))
                StatusPill(
                    text = "年齢確認待ち",
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            Text(
                text = order.paidAt,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        OrderItemsBlock(order = order)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onConfirmAgeCheck,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.VerifiedUser, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("年齢を確認した", fontWeight = FontWeight.Bold)
            }
            if (isAdmin && order.refundableAmount > 0) {
                OutlinedButton(onClick = onRefundClick) {
                    Text("返金", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun PaidOrderCard(
    order: Order,
    isAdmin: Boolean,
    onRefundClick: () -> Unit
) {
    val statusColors = when (order.status) {
        OrderStatus.PAID -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        OrderStatus.PARTIALLY_REFUNDED -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        OrderStatus.REFUNDED -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }

    StaffCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "#${order.formattedPickupNo}",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(10.dp))
                StatusPill(
                    text = order.status.labelJa,
                    containerColor = statusColors.first,
                    contentColor = statusColors.second
                )
                if (order.ageCheckRequired && order.ageCheckedAt != null) {
                    Spacer(Modifier.width(6.dp))
                    StatusPill(
                        text = "年齢確認済",
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
            Text(
                text = order.paidAt,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        OrderItemsBlock(order = order)

        if (order.geoDistanceM != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.LocationOn,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "店舗から約 ${order.geoDistanceM}m（測位誤差 ±${order.geoAccuracyM ?: 0}m）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (order.ageCheckedAt != null && order.ageCheckedBy != null) {
            Text(
                text = "年齢確認: ${order.ageCheckedAt}（${order.ageCheckedBy}）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (order.refunds.isNotEmpty()) {
            NoticeBanner(
                text = buildString {
                    append("返金済み合計: ${formatYen(order.refundedAmount)}")
                    order.refunds.forEach { r ->
                        append("\n・${r.createdAt}  ${formatYen(r.amount)}（${r.staffEmail}）")
                    }
                },
                isWarning = true
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "合計 ${formatYen(order.total)}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold
                )
                if (order.refundedAmount > 0 && order.refundableAmount > 0) {
                    Text(
                        text = "残り ${formatYen(order.refundableAmount)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (isAdmin && order.refundableAmount > 0) {
                OutlinedButton(onClick = onRefundClick) {
                    Text("返金", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun OrderItemsBlock(order: Order) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        order.items.forEach { item ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = buildString {
                        append(item.name)
                        if (item.ageRestricted) append(" [20+]")
                        append(" × ${item.qty}")
                        if (item.unitPrice > 0) append("（${item.taxRate}%）")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                if (item.unitPrice > 0) {
                    Text(
                        text = formatYen(item.unitPrice * item.qty),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
