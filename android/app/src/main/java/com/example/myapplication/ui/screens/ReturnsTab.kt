package com.example.myapplication.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.myapplication.data.model.ReturnPhoto
import com.example.myapplication.data.model.ReturnRequest
import com.example.myapplication.data.model.ReturnStatus
import com.example.myapplication.data.model.ReturnWanted
import com.example.myapplication.ui.components.NoticeBanner
import com.example.myapplication.ui.components.NumpadAmountDialog
import com.example.myapplication.ui.components.SectionHeader
import com.example.myapplication.ui.components.StaffCard
import com.example.myapplication.ui.components.StatusPill
import com.example.myapplication.ui.components.formatYen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ReturnsTab(
    returns: List<ReturnRequest>,
    isAdmin: Boolean,
    loadReturnPhotoBytes: suspend (returnId: String, photoId: Int) -> ByteArray?,
    onDecideReturn: (returnId: String, approve: Boolean, resolution: ReturnWanted?, staffNote: String) -> Unit,
    onCompleteReturn: (returnId: String, refundAmount: Int?) -> Unit,
    modifier: Modifier = Modifier
) {
    var previewPhoto by remember { mutableStateOf<Pair<ReturnRequest, ReturnPhoto>?>(null) }
    var decisionDialog by remember { mutableStateOf<ReturnDecisionTarget?>(null) }
    var completeRefundTarget by remember { mutableStateOf<ReturnRequest?>(null) }
    var completeExchangeTarget by remember { mutableStateOf<ReturnRequest?>(null) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "returns_header") {
            SectionHeader(
                title = "返品・交換の申請",
                badgeCount = returns.count { it.status == ReturnStatus.SUBMITTED },
                subtitle = "写真と説明を確認し、管理者が承認・却下を決めます。承認後、お客様が商品を持参した時点で「商品を回収して完了」を押します。"
            )
        }

        if (returns.isEmpty()) {
            item(key = "empty_returns") {
                StaffCard {
                    Text(
                        text = "現在、返品・交換の申請はありません。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            items(returns, key = { it.id }) { req ->
                ReturnRequestCard(
                    request = req,
                    isAdmin = isAdmin,
                    onPhotoClick = { photo -> previewPhoto = req to photo },
                    onApproveRefund = {
                        decisionDialog = ReturnDecisionTarget(req, approve = true, resolution = ReturnWanted.REFUND)
                    },
                    onApproveExchange = {
                        decisionDialog = ReturnDecisionTarget(req, approve = true, resolution = ReturnWanted.EXCHANGE)
                    },
                    onReject = {
                        decisionDialog = ReturnDecisionTarget(req, approve = false, resolution = null)
                    },
                    onComplete = {
                        if (req.resolution == ReturnWanted.REFUND) {
                            completeRefundTarget = req
                        } else {
                            completeExchangeTarget = req
                        }
                    }
                )
            }
        }
        item(key = "bottom_pad") { Spacer(Modifier.height(24.dp)) }
    }

    previewPhoto?.let { (request, photo) ->
        var loading by remember(request.id, photo.id) { mutableStateOf(true) }
        var imageBitmap by remember(request.id, photo.id) { mutableStateOf<ImageBitmap?>(null) }

        LaunchedEffect(request.id, photo.id) {
            loading = true
            val bytes = loadReturnPhotoBytes(request.id, photo.id)
            imageBitmap = if (bytes != null && bytes.isNotEmpty()) {
                withContext(Dispatchers.Default) {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                }
            } else null
            loading = false
        }

        AlertDialog(
            onDismissRequest = { previewPhoto = null },
            title = { Text("添付写真: ${photo.kind.labelJa}", fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(260.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        when {
                            loading -> CircularProgressIndicator()
                            imageBitmap != null -> Image(
                                bitmap = imageBitmap!!,
                                contentDescription = photo.kind.labelJa,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize()
                            )
                            else -> Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PhotoCamera,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = photo.caption,
                                    textAlign = TextAlign.Center,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { previewPhoto = null }) { Text("閉じる") }
            }
        )
    }

    decisionDialog?.let { target ->
        var note by remember(target) {
            mutableStateOf(
                if (target.approve) {
                    "ご不便をおかけし申し訳ございません。対象商品と購入の証明を店頭へお持ちください。"
                } else ""
            )
        }
        val title = when {
            !target.approve -> "申請 #${target.request.code} を却下"
            target.resolution == ReturnWanted.EXCHANGE -> "申請 #${target.request.code} を承認（交換）"
            else -> "申請 #${target.request.code} を承認（返金）"
        }
        AlertDialog(
            onDismissRequest = { decisionDialog = null },
            title = { Text(title, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = if (target.approve) {
                            "お客様の画面に案内メッセージが表示されます。"
                        } else {
                            "却下理由の入力は必須です。お客様の画面に表示されます。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        label = { Text(if (target.approve) "お客様へのメッセージ（任意）" else "却下理由（必須）") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDecideReturn(target.request.id, target.approve, target.resolution, note)
                        decisionDialog = null
                    },
                    enabled = target.approve || note.isNotBlank()
                ) {
                    Text(if (target.approve) "承認する" else "却下する", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { decisionDialog = null }) { Text("キャンセル") }
            }
        )
    }

    completeRefundTarget?.let { req ->
        NumpadAmountDialog(
            title = "回収して返金完了（#${req.code}）",
            subtitle = "お客様が商品を持参したことを確認してから実行してください。返金は取り消せません。",
            initialAmount = req.defaultRefundAmount,
            minAmount = 1,
            maxAmount = req.defaultRefundAmount,
            confirmLabel = "返金して完了",
            isDanger = true,
            onConfirm = { amount -> onCompleteReturn(req.id, amount) },
            onDismiss = { completeRefundTarget = null }
        )
    }

    completeExchangeTarget?.let { req ->
        AlertDialog(
            onDismissRequest = { completeExchangeTarget = null },
            title = { Text("交換を完了しますか？（#${req.code}）", fontWeight = FontWeight.Bold) },
            text = { Text("お客様から商品を回収し、交換品の受け渡しが完了したことを確認してください。") },
            confirmButton = {
                Button(
                    onClick = {
                        onCompleteReturn(req.id, null)
                        completeExchangeTarget = null
                    }
                ) {
                    Text("商品を回収して完了", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { completeExchangeTarget = null }) { Text("キャンセル") }
            }
        )
    }
}

private data class ReturnDecisionTarget(
    val request: ReturnRequest,
    val approve: Boolean,
    val resolution: ReturnWanted?
)

@Composable
private fun ReturnRequestCard(
    request: ReturnRequest,
    isAdmin: Boolean,
    onPhotoClick: (ReturnPhoto) -> Unit,
    onApproveRefund: () -> Unit,
    onApproveExchange: () -> Unit,
    onReject: () -> Unit,
    onComplete: () -> Unit
) {
    val pillColors = when (request.status) {
        ReturnStatus.SUBMITTED -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        ReturnStatus.APPROVED -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        ReturnStatus.COMPLETED -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        ReturnStatus.REJECTED -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }

    StaffCard(
        highlightBorderColor = if (request.status == ReturnStatus.SUBMITTED) MaterialTheme.colorScheme.error else null
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "#${request.code}",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                StatusPill(
                    text = request.status.labelJa,
                    containerColor = pillColors.first,
                    contentColor = pillColors.second
                )
            }
            Text(
                text = "注文 #${request.orderPickupNo.toString().padStart(3, '0')}",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusPill(
                text = request.reason.labelJa,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
            StatusPill(
                text = request.wanted.labelJa,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            request.items.forEach { item ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("${item.name} × ${item.qty}", style = MaterialTheme.typography.bodyMedium)
                    Text(formatYen(item.unitPrice * item.qty), fontWeight = FontWeight.SemiBold)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }

        Text(
            text = request.detail,
            style = MaterialTheme.typography.bodyMedium
        )

        if (request.photos.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(request.photos, key = { it.id }) { photo ->
                    Box(
                        modifier = Modifier
                            .size(width = 112.dp, height = 84.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { onPhotoClick(photo) }
                            .padding(8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.PhotoCamera,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = photo.kind.labelJa,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }

        Text(
            text = buildString {
                append("申請日時: ${request.createdAt}")
                if (request.geoDistanceM != null) {
                    append("  /  距離 約${request.geoDistanceM}m (±${request.geoAccuracyM ?: 0}m)")
                }
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (!request.staffNote.isNullOrBlank()) {
            NoticeBanner(
                text = "店舗メッセージ: ${request.staffNote}" +
                    if (request.decidedBy != null) "\n決定: ${request.decidedBy} (${request.decidedAt.orEmpty()})" else ""
            )
        }

        if (request.status == ReturnStatus.COMPLETED) {
            NoticeBanner(
                text = buildString {
                    append("完了日時: ${request.completedAt.orEmpty()}")
                    if (request.refundAmount > 0) {
                        append("  /  返金額: ${formatYen(request.refundAmount)}")
                    } else {
                        append("  /  交換完了")
                    }
                }
            )
        }

        if (isAdmin) {
            when (request.status) {
                ReturnStatus.SUBMITTED -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onApproveRefund,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("承認(返金)", fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = onApproveExchange,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("承認(交換)")
                        }
                    }
                    OutlinedButton(
                        onClick = onReject,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("却下する", color = MaterialTheme.colorScheme.error)
                    }
                }

                ReturnStatus.APPROVED -> {
                    Button(
                        onClick = onComplete,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val suffix = if (request.resolution == ReturnWanted.REFUND) "（返金）" else "（交換）"
                        Text("商品を回収して完了$suffix", fontWeight = FontWeight.Bold)
                    }
                }

                else -> Unit
            }
        }
    }
}
