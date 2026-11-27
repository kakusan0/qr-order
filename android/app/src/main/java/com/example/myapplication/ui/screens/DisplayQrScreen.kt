package com.example.myapplication.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.myapplication.data.model.CustomerUrlInfo
import com.example.myapplication.data.model.Store
import com.example.myapplication.ui.components.QrCodeImage
import com.example.myapplication.ui.components.StatusPill
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds

/**
 * 入口タブレット等に大きく映す表示専用画面（qr-order の /staff/display 相当）。
 */
@Composable
fun DisplayQrScreen(
    store: Store,
    buildCustomerUrl: (Store, Long) -> CustomerUrlInfo,
    fetchCustomerUrl: suspend (Store) -> CustomerUrlInfo,
    onClose: () -> Unit
) {
    var nowEpochSec by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    var urlInfo by remember(store.id, store.qrRotateMinutes) {
        mutableStateOf(buildCustomerUrl(store, System.currentTimeMillis() / 1000))
    }

    LaunchedEffect(store.id, store.qrRotateMinutes) {
        while (true) {
            nowEpochSec = System.currentTimeMillis() / 1000
            delay(1.seconds)
        }
    }

    val currentBucket = if (store.qrRotateMinutes > 0) {
        nowEpochSec / (store.qrRotateMinutes * 60L)
    } else 0L

    LaunchedEffect(store.id, store.qrRotateMinutes, currentBucket) {
        urlInfo = fetchCustomerUrl(store)
    }

    val remainingSec = if (store.qrRotateMinutes > 0) {
        val period = store.qrRotateMinutes * 60L
        period - (nowEpochSec % period)
    } else null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        IconButton(
            onClick = onClose,
            modifier = Modifier.align(Alignment.End)
        ) {
            Icon(Icons.Default.Close, contentDescription = "閉じる")
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = store.name,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center
            )
            Text(
                text = "スマホのカメラで QR コードを読み取ってご注文ください",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            QrCodeImage(
                content = urlInfo.url,
                size = 280.dp,
                contentDescription = "${store.name} 入口 QR コード"
            )
            if (remainingSec != null) {
                val min = remainingSec / 60
                val sec = remainingSec % 60
                StatusPill(
                    text = "自動更新まであと %d:%02d（%d分間隔）".format(min, sec, store.qrRotateMinutes)
                )
            } else {
                StatusPill(
                    text = "固定 QR コード",
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = urlInfo.url,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }

        Spacer(Modifier.height(16.dp))
    }
}
