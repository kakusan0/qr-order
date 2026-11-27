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
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.example.myapplication.data.model.StaffAccount
import com.example.myapplication.data.model.StaffRole
import com.example.myapplication.ui.components.ConfirmActionDialog
import com.example.myapplication.ui.components.SectionHeader
import com.example.myapplication.ui.components.StaffCard
import com.example.myapplication.ui.components.StatusPill

@Composable
fun StaffAccountsTab(
    accounts: List<StaffAccount>,
    currentUserEmail: String,
    onAddAccount: (String, StaffRole, () -> Unit) -> Unit,
    onSetActive: (String, Boolean) -> Unit,
    onResetAccount: (String) -> Unit,
    onDeleteAccount: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var newEmail by remember { mutableStateOf("") }
    var newRole by remember { mutableStateOf(StaffRole.STAFF) }
    var resetTarget by remember { mutableStateOf<StaffAccount?>(null) }
    var deleteTarget by remember { mutableStateOf<StaffAccount?>(null) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "add_staff_header") {
            SectionHeader(
                title = "スタッフを追加する",
                subtitle = "追加されたスタッフは、セットアップ画面でメールアドレスを入力し、届いた確認コードで本人確認をしてからパスキーを登録します。"
            )
        }

        item(key = "add_staff_form") {
            StaffCard {
                OutlinedTextField(
                    value = newEmail,
                    onValueChange = { newEmail = it },
                    label = { Text("メールアドレス") },
                    placeholder = { Text("staff@example.com") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("権限:", fontWeight = FontWeight.SemiBold)
                    FilterChip(
                        selected = newRole == StaffRole.STAFF,
                        onClick = { newRole = StaffRole.STAFF },
                        label = { Text("スタッフ") }
                    )
                    FilterChip(
                        selected = newRole == StaffRole.ADMIN,
                        onClick = { newRole = StaffRole.ADMIN },
                        label = { Text("管理者") }
                    )
                }
                Button(
                    onClick = {
                        onAddAccount(newEmail, newRole) {
                            newEmail = ""
                            newRole = StaffRole.STAFF
                        }
                    },
                    enabled = newEmail.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("追加する", fontWeight = FontWeight.Bold)
                }
            }
        }

        item(key = "staff_list_header") {
            SectionHeader(
                title = "スタッフ一覧",
                badgeCount = accounts.size,
                subtitle = "端末をなくした・替えた場合は「再セットアップ」を押してください。最後の管理者は停止・削除できません。"
            )
        }

        items(accounts, key = { it.id }) { account ->
            StaffCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = account.email,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "登録日: ${account.createdAt}  /  パスキー ${account.passkeyCount} 件",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusPill(
                        text = account.role.labelJa,
                        containerColor = if (account.role == StaffRole.ADMIN) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.secondaryContainer
                        },
                        contentColor = if (account.role == StaffRole.ADMIN) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        }
                    )
                    StatusPill(
                        text = when {
                            !account.active -> "停止中"
                            account.setupAllowed -> "セットアップ待ち"
                            else -> "有効"
                        },
                        containerColor = if (!account.active) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        contentColor = if (!account.active) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    if (account.email.equals(currentUserEmail, ignoreCase = true)) {
                        StatusPill(
                            text = "自分",
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { onSetActive(account.id, !account.active) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (account.active) "停止する" else "再開する")
                    }
                    OutlinedButton(
                        onClick = { resetTarget = account },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("再セットアップ")
                    }
                    Spacer(Modifier.width(2.dp))
                    OutlinedButton(onClick = { deleteTarget = account }) {
                        Text("削除", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        item(key = "bottom_pad") { Spacer(Modifier.height(24.dp)) }
    }

    resetTarget?.let { acc ->
        ConfirmActionDialog(
            title = "再セットアップを許可しますか？",
            message = "${acc.email} の登録済みパスキーをすべて無効にし、メールの確認コードから再登録できるようにします。",
            confirmLabel = "再セットアップ",
            onConfirm = { onResetAccount(acc.id) },
            onDismiss = { resetTarget = null }
        )
    }

    deleteTarget?.let { acc ->
        ConfirmActionDialog(
            title = "スタッフを削除しますか？",
            message = "${acc.email} のアカウントとパスキーを削除します。この操作は元に戻せません。",
            confirmLabel = "削除する",
            isDanger = true,
            onConfirm = { onDeleteAccount(acc.id) },
            onDismiss = { deleteTarget = null }
        )
    }
}
