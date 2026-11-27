package com.example.myapplication.ui.screens

import android.app.Activity
import android.os.Build
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
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.myapplication.data.model.PasskeyInfo
import com.example.myapplication.data.model.StaffRole
import com.example.myapplication.data.model.StaffSession
import com.example.myapplication.ui.components.ConfirmActionDialog
import com.example.myapplication.ui.components.NoticeBanner
import com.example.myapplication.ui.components.SectionHeader
import com.example.myapplication.ui.components.StaffCard

@Composable
fun PasskeysTab(
    session: StaffSession,
    passkeys: List<PasskeyInfo>,
    useMockMode: Boolean,
    baseUrl: String,
    onRegisterPasskey: (String) -> Unit,
    onRegisterNativePasskey: (Activity, String) -> Unit,
    onDeletePasskey: (String) -> Unit,
    onSwitchDemoRole: (StaffRole) -> Unit,
    onOpenConnectionSettings: () -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showAddDialog by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<PasskeyInfo?>(null) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "passkeys_header") {
            SectionHeader(
                title = "自分のパスキー",
                badgeCount = passkeys.size,
                subtitle = "ログインに使う端末の一覧です。スマホとパソコンなど 2 台以上を登録しておくと、1 台をなくしてもログインできます。"
            )
        }

        item(key = "add_passkey_card") {
            StaffCard {
                Button(
                    onClick = { showAddDialog = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Key, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("この端末を追加", fontWeight = FontWeight.Bold)
                }
                if (!useMockMode) {
                    Text(
                        text = "※ Android Credential Manager 経由で /api/staff/passkeys/options・verify と連携します（サーバー側で Android Origin 未対応の場合は Web 管理画面から追加してください）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        items(passkeys, key = { it.id }) { pk ->
            StaffCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = pk.label,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "登録: ${pk.createdAt}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (pk.lastUsedAt != null) {
                            Text(
                                text = "最終利用: ${pk.lastUsedAt}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    OutlinedButton(onClick = { deleteTarget = pk }) {
                        Text("削除", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        item(key = "session_header") {
            SectionHeader(
                title = if (useMockMode) "現在のログイン・モック設定" else "現在のログイン・接続先",
                subtitle = if (useMockMode) {
                    "モック動作中に「管理者」と「スタッフ」の権限差（返金・商品登録・スタッフタブの表示等）をすぐに確認できます。"
                } else {
                    "接続先の変更やモックモードとの切り替えができます。"
                }
            )
        }

        item(key = "session_card") {
            StaffCard {
                NoticeBanner(
                    text = buildString {
                        append("ログイン中: ${session.email}（${session.role.labelJa}）")
                        if (!useMockMode) append("\n接続先: $baseUrl")
                    }
                )
                if (useMockMode) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("権限切替:", fontWeight = FontWeight.SemiBold)
                        FilterChip(
                            selected = session.role == StaffRole.ADMIN,
                            onClick = { onSwitchDemoRole(StaffRole.ADMIN) },
                            label = { Text("管理者") }
                        )
                        FilterChip(
                            selected = session.role == StaffRole.STAFF,
                            onClick = { onSwitchDemoRole(StaffRole.STAFF) },
                            label = { Text("スタッフ") }
                        )
                    }
                }
                OutlinedButton(
                    onClick = onOpenConnectionSettings,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Settings, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("接続先・動作モード設定")
                }
                OutlinedButton(
                    onClick = onLogout,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("ログアウト")
                }
            }
        }
        item(key = "bottom_pad") { Spacer(Modifier.height(24.dp)) }
    }

    if (showAddDialog) {
        var label by remember { mutableStateOf("${Build.MODEL}（この端末）") }
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("この端末をパスキーに追加", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "一覧で見分けやすい端末名を入力してください。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = label,
                        onValueChange = { label = it },
                        label = { Text("端末名") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (!useMockMode) {
                            context.findActivity()?.let { onRegisterNativePasskey(it, label) }
                        } else {
                            onRegisterPasskey(label)
                        }
                        showAddDialog = false
                    }
                ) {
                    Text("登録する", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("キャンセル") }
            }
        )
    }

    deleteTarget?.let { pk ->
        ConfirmActionDialog(
            title = "パスキーを削除しますか？",
            message = "「${pk.label}」を削除します。この端末からはログインできなくなります。",
            confirmLabel = "削除する",
            isDanger = true,
            onConfirm = { onDeletePasskey(pk.id) },
            onDismiss = { deleteTarget = null }
        )
    }
}
