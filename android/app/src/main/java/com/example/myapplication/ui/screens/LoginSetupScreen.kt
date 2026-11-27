package com.example.myapplication.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.myapplication.data.model.StaffRole
import com.example.myapplication.ui.components.NoticeBanner
import com.example.myapplication.ui.components.StaffCard
import com.example.myapplication.ui.components.StatusPill

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * qr-order の /staff/login と /staff/setup に相当するログイン／初回セットアップ画面。
 */
@Composable
fun LoginSetupScreen(
    baseUrl: String,
    useMockMode: Boolean,
    connectionError: String?,
    onOpenConnectionSettings: () -> Unit,
    onOpenWebLogin: (String) -> Unit,
    onNativePasskeyLogin: (Activity) -> Unit,
    onPasskeyLogin: (StaffRole) -> Unit,
    onRequestSetupCode: (String, (String) -> Unit) -> Unit,
    onVerifySetupCode: (String, String, () -> Unit) -> Unit,
    onFinishSetupPasskey: (String, String) -> Unit,
    onFinishSetupWithNativePasskey: (Activity, String) -> Unit
) {
    val context = LocalContext.current
    var setupMode by remember { mutableStateOf(false) }
    var setupStep by remember { mutableIntStateOf(1) }
    var email by remember(useMockMode) { mutableStateOf(if (useMockMode) "new-staff@example.com" else "") }
    var code by remember { mutableStateOf("") }
    var codeHint by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "店舗管理",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = if (setupMode) "はじめて使うとき（初回セットアップ）" else "スタッフログイン",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(14.dp))

        // 接続先・モード表示バー
        StaffCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    StatusPill(
                        text = if (useMockMode) "MOCK モード" else "実 API 接続",
                        containerColor = if (useMockMode) {
                            MaterialTheme.colorScheme.surfaceVariant
                        } else {
                            MaterialTheme.colorScheme.primaryContainer
                        },
                        contentColor = if (useMockMode) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        }
                    )
                    Text(
                        text = if (useMockMode) "ローカルのサンプルデータを使用中" else baseUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(onClick = onOpenConnectionSettings) {
                    Icon(Icons.Default.Settings, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("接続設定")
                }
            }
            if (!connectionError.isNullOrBlank() && !useMockMode) {
                NoticeBanner(text = connectionError, isWarning = true)
            }
        }

        Spacer(Modifier.height(16.dp))

        if (!setupMode) {
            StaffCard {
                Text(
                    text = "パスキー（指紋・顔・画面ロック）でログインします。パスワードは使いません。",
                    style = MaterialTheme.typography.bodyMedium
                )
                if (!useMockMode) {
                    Button(
                        onClick = {
                            context.findActivity()?.let { onNativePasskeyLogin(it) }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Fingerprint, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("パスキーでログイン（推奨）", fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = { onOpenWebLogin("/staff/login") },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Language, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("ブラウザ認証でログイン")
                    }
                    Text(
                        text = "※ 端末に保存されたパスキー、または他のスマホのパスキー（QR 認証）でログインできます。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Button(
                        onClick = { onPasskeyLogin(StaffRole.ADMIN) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Fingerprint, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("パスキーでログイン（管理者デモ）", fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = { onPasskeyLogin(StaffRole.STAFF) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("パスキーでログイン（スタッフデモ）")
                    }
                }
                TextButton(
                    onClick = {
                        setupMode = true
                        setupStep = 1
                    },
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Text("はじめての方・端末を変えた方（セットアップ）")
                }
            }
        } else {
            StaffCard {
                if (!useMockMode) {
                    Button(
                        onClick = { onOpenWebLogin("/staff/setup") },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Language, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("ブラウザ認証でセットアップ（推奨）", fontWeight = FontWeight.Bold)
                    }
                    Text(
                        text = "または下のフォームからネイティブでセットアップします：",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                when (setupStep) {
                    1 -> {
                        Text(
                            text = "1. 登録されているメールアドレスを入力してください。",
                            fontWeight = FontWeight.Bold
                        )
                        OutlinedTextField(
                            value = email,
                            onValueChange = { email = it },
                            label = { Text("メールアドレス") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            onClick = {
                                onRequestSetupCode(email) { msg ->
                                    codeHint = msg
                                    setupStep = 2
                                }
                            },
                            enabled = email.isNotBlank(),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("確認コードを送る", fontWeight = FontWeight.Bold)
                        }
                    }

                    2 -> {
                        Text(
                            text = "2. メールで届いた 6 桁の確認コードを入力してください。",
                            fontWeight = FontWeight.Bold
                        )
                        codeHint?.let { NoticeBanner(text = it) }
                        OutlinedTextField(
                            value = code,
                            onValueChange = { code = it },
                            label = { Text("6 桁の確認コード") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            onClick = {
                                onVerifySetupCode(email, code) {
                                    setupStep = 3
                                }
                            },
                            enabled = code.length >= 6,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("確認する", fontWeight = FontWeight.Bold)
                        }
                    }

                    else -> {
                        Text(
                            text = "3. この端末にパスキーを登録します。",
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "登録が完了するとそのまま管理画面に入ります。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            onClick = {
                                if (!useMockMode) {
                                    context.findActivity()?.let { onFinishSetupWithNativePasskey(it, email) }
                                } else {
                                    onFinishSetupPasskey(email, "${Build.MODEL}（この端末）")
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Fingerprint, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("この端末にパスキーを登録する", fontWeight = FontWeight.Bold)
                        }
                    }
                }

                TextButton(
                    onClick = {
                        setupMode = false
                        setupStep = 1
                        code = ""
                        codeHint = null
                    },
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Text("ログイン画面へ戻る")
                }
            }
        }
    }
}
