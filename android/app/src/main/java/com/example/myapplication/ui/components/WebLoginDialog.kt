package com.example.myapplication.ui.components

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.myapplication.data.remote.ApiConfigStore

/**
 * サーバーの /staff/login または /staff/setup をアプリ内 WebView で開き、
 * パスキー認証完了後に発行された staff_session クッキーをネイティブ API クライアントへ引き継ぐダイアログ。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebLoginDialog(
    baseUrl: String,
    initialPath: String = "/staff/login",
    onSessionCaptured: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val startUrl = "${baseUrl.trimEnd('/')}$initialPath"
    var captured by remember { mutableStateOf(false) }

    fun tryExtractSessionCookie(url: String?): Boolean {
        if (captured || url.isNullOrBlank()) return false
        val rawCookies = CookieManager.getInstance().getCookie(baseUrl)
            ?: CookieManager.getInstance().getCookie(url)
            ?: return false
        val sessionValue = rawCookies.split(";")
            .map { it.trim() }
            .firstOrNull { it.startsWith("${ApiConfigStore.SESSION_COOKIE_NAME}=") }
            ?.substringAfter("=")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        if (sessionValue != null) {
            captured = true
            onSessionCaptured(sessionValue)
            return true
        }
        return false
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (initialPath.contains("setup")) "初回セットアップ（Web 認証）" else "パスキーログイン（Web 認証）",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = startUrl,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "閉じる")
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    factory = { context ->
                        CookieManager.getInstance().setAcceptCookie(true)
                        WebView(context).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            webChromeClient = WebChromeClient()
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): Boolean {
                                    val target = request?.url?.toString()
                                    if (target != null && target.startsWith(baseUrl) && target.contains("/staff") &&
                                        !target.contains("/staff/login") && !target.contains("/staff/setup")
                                    ) {
                                        if (tryExtractSessionCookie(target)) return true
                                    }
                                    return false
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    if (url != null && url.contains("/staff") &&
                                        !url.contains("/staff/login") && !url.contains("/staff/setup")
                                    ) {
                                        tryExtractSessionCookie(url)
                                    }
                                }
                            }
                            loadUrl(startUrl)
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun ConnectionSettingsDialog(
    currentBaseUrl: String,
    currentUseMock: Boolean,
    onSave: (baseUrl: String, useMock: Boolean) -> Unit,
    onImportManualCookie: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var baseUrl by remember(currentBaseUrl) { mutableStateOf(currentBaseUrl) }
    var useMock by remember(currentUseMock) { mutableStateOf(currentUseMock) }
    var manualCookie by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("接続先・動作モード設定", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("モックモード（オフラインデモ）", fontWeight = FontWeight.SemiBold)
                        Text(
                            text = if (useMock) "ローカルのサンプルデータで動作します" else "実サーバー API とリアルタイムに通信します",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = useMock, onCheckedChange = { useMock = it })
                }

                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("サーバー URL") },
                    placeholder = { Text("https://qr-order.infoapp.org") },
                    singleLine = true,
                    enabled = !useMock,
                    modifier = Modifier.fillMaxWidth()
                )

                if (!useMock) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text(
                        text = "セッション Cookie 直接設定（任意・開発用）",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    OutlinedTextField(
                        value = manualCookie,
                        onValueChange = { manualCookie = it },
                        label = { Text("staff_session の値") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedButton(
                        onClick = {
                            onSave(baseUrl, false)
                            onImportManualCookie(manualCookie)
                            onDismiss()
                        },
                        enabled = manualCookie.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("このセッションで接続する")
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(baseUrl, useMock)
                    onDismiss()
                }
            ) {
                Text("保存", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        }
    )
}
