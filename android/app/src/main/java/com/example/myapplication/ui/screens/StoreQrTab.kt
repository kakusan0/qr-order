package com.example.myapplication.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.example.myapplication.data.model.CustomerUrlInfo
import com.example.myapplication.data.model.Store
import com.example.myapplication.ui.components.NoticeBanner
import com.example.myapplication.ui.components.SectionHeader
import com.example.myapplication.ui.components.StaffCard
import com.example.myapplication.ui.components.copyToClipboard
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds

// kakusan0/qr-order サーバー側 ROTATE_CHOICES = [0, 1, 5, 15, 60, 1440] と完全一致
private val qrRotationOptions = listOf(
    0 to "なし（固定）",
    1 to "1 分",
    5 to "5 分",
    15 to "15 分",
    60 to "1 時間",
    1440 to "1 日"
)

// kakusan0/qr-order サーバー側 許可半径 [50, 100, 200, 300, 500, 1000] と完全一致
private val geofenceRadiusOptions = listOf(50, 100, 200, 300, 500, 1000)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StoreQrTab(
    store: Store?,
    isAdmin: Boolean,
    buildCustomerUrl: (Store, Long) -> CustomerUrlInfo,
    fetchCustomerUrl: suspend (Store) -> CustomerUrlInfo,
    onOpenDisplayQr: () -> Unit,
    onUpdateQrRotation: (Int) -> Unit,
    onUpdateStoreLocation: (Double?, Double?, Int) -> Unit,
    onResolveMapUrl: (String, (Double, Double) -> Unit) -> Unit,
    onShowMessage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (store == null) return
    val context = LocalContext.current

    var customerUrlInfo by remember(store.id, store.qrRotateMinutes) {
        mutableStateOf(buildCustomerUrl(store, System.currentTimeMillis() / 1000))
    }
    LaunchedEffect(store.id, store.qrRotateMinutes) {
        while (true) {
            customerUrlInfo = fetchCustomerUrl(store)
            val intervalSec = if (store.qrRotateMinutes > 0) 15 else 60
            delay(intervalSec.seconds)
        }
    }

    var mapLinkInput by remember(store.id) { mutableStateOf("") }
    var latText by remember(store.id, store.lat) { mutableStateOf(store.lat?.let { "%.6f".format(it) }.orEmpty()) }
    var lngText by remember(store.id, store.lng) { mutableStateOf(store.lng?.let { "%.6f".format(it) }.orEmpty()) }
    var selectedRadiusM by remember(store.id, store.geofenceM) {
        val closest = geofenceRadiusOptions.minByOrNull { kotlin.math.abs(it - store.geofenceM) } ?: 300
        mutableIntStateOf(closest)
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "qr_entrance_header") {
            SectionHeader(
                title = "店舗の QR コード（入口用）",
                subtitle = "お客様が入口の QR を読み取ると注文画面（カート）が開きます。入口のタブレットなどには、表示専用の画面で映してください。"
            )
        }

        item(key = "qr_entrance_card") {
            StaffCard {
                Button(
                    onClick = onOpenDisplayQr,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Fullscreen, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("表示専用の画面を開く（入口タブレット用）", fontWeight = FontWeight.Bold)
                }

                Text(
                    text = "お客様画面（カート）の URL",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                NoticeBanner(text = customerUrlInfo.url)
                OutlinedButton(
                    onClick = {
                        copyToClipboard(context, "お客様画面 URL", customerUrlInfo.url)
                        onShowMessage("お客様画面の URL をコピーしました")
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("URL をコピー")
                }

                if (isAdmin) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "QR コードの定期更新（管理者）",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "有効にすると URL に時間で変わる合言葉が入り、古い QR の写真や保存したリンクからの注文を拒否します。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        qrRotationOptions.forEach { (minutes, label) ->
                            FilterChip(
                                selected = store.qrRotateMinutes == minutes,
                                onClick = { onUpdateQrRotation(minutes) },
                                label = { Text(label) }
                            )
                        }
                    }
                }
            }
        }

        item(key = "esl_header") {
            SectionHeader(
                title = "電子棚札（ST-GR29000）",
                subtitle = "OpenEPaperLink の基地局で「7 - Image URL」を選び、各商品の画像 URL（/esl/<商品ID>.jpg）を設定します。"
            )
        }

        item(key = "esl_card") {
            StaffCard {
                Text(
                    text = "棚札には商品の QR コード・商品名・価格（税込）が表示されます。管理画面で商品名や価格を変更すると、次の更新タイミングで自動反映されます。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (store.qrRotateMinutes > 0) {
                    NoticeBanner(
                        text = "注意：QR コードの定期更新が有効なため、棚札の QR コードからは注文できません（期限切れの案内が出ます）。棚札を使う場合は更新間隔を「なし」にしてください。",
                        isWarning = true
                    )
                }
            }
        }

        item(key = "geo_header") {
            SectionHeader(
                title = "店舗の位置（購入時の位置情報の確認）",
                subtitle = "位置を登録すると、円の内側（＋お客様の測位誤差）以外からの注文・返品申請を拒否します。屋内は誤差が大きいため 200m 以上がおすすめです。"
            )
        }

        item(key = "geo_card") {
            StaffCard {
                if (isAdmin) {
                    OutlinedTextField(
                        value = mapLinkInput,
                        onValueChange = { mapLinkInput = it },
                        label = { Text("地図共有リンク または 座標（例: 35.6595, 139.7006）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                onResolveMapUrl(mapLinkInput) { lat, lng ->
                                    latText = "%.6f".format(lat)
                                    lngText = "%.6f".format(lng)
                                }
                            },
                            enabled = mapLinkInput.isNotBlank(),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("リンク／座標を反映")
                        }
                        OutlinedButton(
                            onClick = {
                                latText = "35.659482"
                                lngText = "139.700559"
                                onShowMessage("サンプル座標をセットしました")
                            }
                        ) {
                            Icon(Icons.Default.MyLocation, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("サンプル")
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = latText,
                            onValueChange = { latText = it },
                            label = { Text("緯度 (lat)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = lngText,
                            onValueChange = { lngText = it },
                            label = { Text("経度 (lng)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("受け付ける範囲", fontWeight = FontWeight.SemiBold)
                        Text(
                            text = "${selectedRadiusM}m",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        geofenceRadiusOptions.forEach { radius ->
                            FilterChip(
                                selected = selectedRadiusM == radius,
                                onClick = { selectedRadiusM = radius },
                                label = { Text("${radius}m") }
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                val lat = latText.trim().toDoubleOrNull()
                                val lng = lngText.trim().toDoubleOrNull()
                                if (lat == null || lng == null || lat !in -90.0..90.0 || lng !in -180.0..180.0) {
                                    onShowMessage("有効な緯度・経度を入力してください")
                                } else {
                                    onUpdateStoreLocation(lat, lng, selectedRadiusM)
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("この位置で保存", fontWeight = FontWeight.Bold)
                        }
                        if (store.lat != null && store.lng != null) {
                            OutlinedButton(
                                onClick = {
                                    latText = ""
                                    lngText = ""
                                    onUpdateStoreLocation(null, null, selectedRadiusM)
                                }
                            ) {
                                Text("解除", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                } else {
                    val summary = if (store.lat != null && store.lng != null) {
                        "登録済み: %.5f, %.5f（範囲 ${store.geofenceM}m）".format(store.lat, store.lng)
                    } else {
                        "位置情報は未設定です"
                    }
                    NoticeBanner(text = "$summary\n※ 店舗位置の変更は管理者権限で行います。")
                }
            }
        }
        item(key = "bottom_pad") { Spacer(Modifier.height(24.dp)) }
    }
}
