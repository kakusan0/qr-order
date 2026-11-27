package com.example.myapplication.ui.screens

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.myapplication.data.model.ChatSender
import com.example.myapplication.data.model.ChatThread
import com.example.myapplication.data.model.Store
import com.example.myapplication.ui.components.NoticeBanner
import com.example.myapplication.ui.components.SectionHeader
import com.example.myapplication.ui.components.StaffCard
import com.example.myapplication.ui.components.StatusPill
import com.example.myapplication.ui.components.copyToClipboard

@Composable
fun ChatTab(
    store: Store?,
    threads: List<ChatThread>,
    isAdmin: Boolean,
    useMockMode: Boolean,
    onLoadThreadMessages: (String) -> Unit,
    onSendReply: (threadId: String, body: String, onSuccess: () -> Unit) -> Unit,
    onIssueLineLinkCode: () -> Unit,
    onUnlinkLine: () -> Unit,
    onSimulateLineLinked: () -> Unit,
    onShowMessage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var openThreadId by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "line_header") {
            SectionHeader(
                title = "LINE で受け取る",
                subtitle = "LINE 公式アカウントとのトーク（またはスタッフのグループ）にお問い合わせや返品申請を通知し、リプライで返信できます。"
            )
        }

        item(key = "line_card") {
            StaffCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("通知先ステータス", fontWeight = FontWeight.Bold)
                    StatusPill(
                        text = if (store?.lineLinked == true) "連携済み" else "未連携",
                        containerColor = if (store?.lineLinked == true) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        contentColor = if (store?.lineLinked == true) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }

                Text(
                    text = "LINE ではお客様のメッセージを長押しして「リプライ」で返信するか、先頭に「#番号 」を付けて送るとお客様の画面に届きます。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                val code = store?.lineLinkCode
                if (!code.isNullOrBlank()) {
                    val command = "連携 $code"
                    NoticeBanner(
                        text = "LINE のトークで次のとおり送信してください（有効期限: ${store.lineLinkExpiresAt.orEmpty()}）:\n$command"
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                copyToClipboard(context, "LINE 連携コード", command)
                                onShowMessage("「$command」をコピーしました")
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("コピー")
                        }
                        OutlinedButton(
                            onClick = onSimulateLineLinked,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (useMockMode) "連携完了をテスト" else "連携状態を再確認")
                        }
                    }
                }

                if (isAdmin) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onIssueLineLinkCode,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("連携コードを発行する", fontWeight = FontWeight.Bold)
                        }
                        if (store?.lineLinked == true) {
                            OutlinedButton(onClick = onUnlinkLine) {
                                Text("連携解除", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }

        item(key = "chat_header") {
            SectionHeader(
                title = "お問い合わせ一覧",
                badgeCount = threads.count { it.waitingForReply },
                subtitle = "新しい順に並びます。押すとやり取りの確認と返信ができます（LINE を使えないとき用）。90 日で自動で消えます。"
            )
        }

        if (threads.isEmpty()) {
            item(key = "empty_chat") {
                StaffCard {
                    Text(
                        text = "お問い合わせはまだありません。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            items(threads, key = { it.id }) { thread ->
                StaffCard(
                    modifier = Modifier.clickable { openThreadId = thread.id },
                    highlightBorderColor = if (thread.waitingForReply) MaterialTheme.colorScheme.primary else null
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "#${thread.code}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Black,
                                color = MaterialTheme.colorScheme.primary
                            )
                            if (thread.orderPickupNo != null) {
                                Spacer(Modifier.width(8.dp))
                                StatusPill(
                                    text = "注文 #${thread.orderPickupNo.toString().padStart(3, '0')}",
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (thread.waitingForReply) {
                                Spacer(Modifier.width(8.dp))
                                StatusPill(
                                    text = "返信待ち",
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                        Text(
                            text = thread.lastMessageAt,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = thread.lastMessagePreview,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2
                    )
                }
            }
        }
        item(key = "bottom_pad") { Spacer(Modifier.height(24.dp)) }
    }

    val activeThread = threads.find { it.id == openThreadId }
    if (activeThread != null) {
        ChatThreadDialog(
            thread = activeThread,
            onLoadMessages = { onLoadThreadMessages(activeThread.id) },
            onSendReply = { body, onSuccess -> onSendReply(activeThread.id, body, onSuccess) },
            onDismiss = { openThreadId = null }
        )
    }
}

@Composable
private fun ChatThreadDialog(
    thread: ChatThread,
    onLoadMessages: () -> Unit,
    onSendReply: (String, () -> Unit) -> Unit,
    onDismiss: () -> Unit
) {
    var draft by remember(thread.id) { mutableStateOf("") }

    LaunchedEffect(thread.id) {
        onLoadMessages()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "お問い合わせ #${thread.code}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold
                        )
                        if (thread.orderPickupNo != null) {
                            Text(
                                text = "対象注文: #${thread.orderPickupNo.toString().padStart(3, '0')}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "閉じる")
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 180.dp, max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(thread.messages, key = { it.id }) { msg ->
                        val isStore = msg.sender == ChatSender.STORE
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = if (isStore) Alignment.End else Alignment.Start
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(
                                        if (isStore) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.surfaceVariant
                                    )
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = msg.body,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (isStore) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Text(
                                text = "${if (isStore) "店舗" else "お客様"}  ${msg.createdAt}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp, start = 4.dp, end = 4.dp)
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        placeholder = { Text("お客様への返信を入力…") },
                        maxLines = 3,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            onSendReply(draft) { draft = "" }
                        },
                        enabled = draft.isNotBlank()
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "送信")
                    }
                }
            }
        }
    }
}
