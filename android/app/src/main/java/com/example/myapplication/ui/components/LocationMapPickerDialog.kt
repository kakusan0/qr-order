package com.example.myapplication.ui.components

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.myapplication.data.remote.DeviceLocationProvider
import kotlinx.coroutines.launch
import java.util.Locale

private const val TAG = "LocationMapPicker"
private const val DEFAULT_TOKYO_LAT = 35.681236
private const val DEFAULT_TOKYO_LNG = 139.767125

class LocationMapJsBridge(
    private val onLocationPicked: (Double, Double) -> Unit,
    private val onReady: () -> Unit,
    private val onError: (String) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun onLocationSelected(lat: Double, lng: Double) {
        if (!lat.isFinite() || !lng.isFinite() || lat !in -90.0..90.0 || lng !in -180.0..180.0) {
            return
        }
        mainHandler.post {
            onLocationPicked(lat, lng)
        }
    }

    @JavascriptInterface
    fun onMapReady() {
        mainHandler.post {
            onReady()
        }
    }

    @JavascriptInterface
    fun onMapError(message: String) {
        mainHandler.post {
            onError(message)
        }
    }
}

/**
 * 地図をタップまたはピンをドラッグして店舗の位置を選択するフルスクリーンダイアログ。
 * 国土地理院タイル / OpenStreetMap を切り替えでき、受付範囲（半径）の円もリアルタイム表示します。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LocationMapPickerDialog(
    initialLat: Double?,
    initialLng: Double?,
    radiusM: Int,
    onConfirm: (Double, Double) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedLat by remember { mutableDoubleStateOf(initialLat ?: DEFAULT_TOKYO_LAT) }
    var selectedLng by remember { mutableDoubleStateOf(initialLng ?: DEFAULT_TOKYO_LNG) }
    var isFetchingGps by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var isMapReady by remember { mutableStateOf(false) }

    fun moveMapPin(lat: Double, lng: Double, recenter: Boolean = true) {
        selectedLat = lat
        selectedLng = lng
        val js = String.format(
            Locale.US,
            "window.setPin && window.setPin(%.6f, %.6f, %d, %s);",
            lat,
            lng,
            radiusM,
            if (recenter) "true" else "false"
        )
        webViewRef?.evaluateJavascript(js, null)
    }

    fun fetchGpsAndMove() {
        if (isFetchingGps) return
        scope.launch {
            isFetchingGps = true
            statusMessage = "GPS で現在地を取得中..."
            DeviceLocationProvider.getCurrentLocation(context)
                .onSuccess { loc ->
                    moveMapPin(loc.lat, loc.lng, recenter = true)
                    val acc = loc.accuracyM?.let { "（精度 約${it}m）" }.orEmpty()
                    statusMessage = if (loc.isLastKnown) {
                        "直近の位置へ移動しました$acc"
                    } else {
                        "現在地へ移動しました$acc"
                    }
                }
                .onFailure { err ->
                    statusMessage = err.message ?: "現在地を取得できませんでした"
                }
            isFetchingGps = false
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result.values.any { it }
        if (granted) {
            fetchGpsAndMove()
        } else {
            statusMessage = "位置情報の権限が許可されていません。地図をタップして位置を選んでください。"
        }
    }

    // 初期座標が未設定で、すでに位置情報権限がある場合は自動で現在地へ移動
    LaunchedEffect(isMapReady) {
        if (isMapReady && (initialLat == null || initialLng == null) &&
            DeviceLocationProvider.hasLocationPermission(context)
        ) {
            fetchGpsAndMove()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.destroy()
            webViewRef = null
        }
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
                            text = "地図上で店舗の位置を選択",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "タップまたはピンをドラッグして調整できます",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "閉じる")
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            val bridge = LocationMapJsBridge(
                                onLocationPicked = { lat, lng ->
                                    selectedLat = lat
                                    selectedLng = lng
                                },
                                onReady = {
                                    isMapReady = true
                                },
                                onError = { msg ->
                                    isMapReady = true
                                    statusMessage = msg
                                }
                            )
                            WebView(ctx).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.cacheMode = WebSettings.LOAD_DEFAULT
                                settings.allowFileAccess = false
                                settings.allowContentAccess = false
                                addJavascriptInterface(bridge, "AndroidMapBridge")
                                webChromeClient = object : WebChromeClient() {
                                    override fun onConsoleMessage(cm: ConsoleMessage?): Boolean {
                                        if (cm != null) {
                                            Log.d(TAG, "JS [${cm.messageLevel()}]: ${cm.message()} (${cm.lineNumber()})")
                                        }
                                        return true
                                    }
                                }
                                webViewClient = object : WebViewClient() {
                                    override fun onReceivedError(
                                        view: WebView?,
                                        request: WebResourceRequest?,
                                        error: WebResourceError?
                                    ) {
                                        Log.w(TAG, "WebView error url=${request?.url} err=${error?.description}")
                                    }
                                }
                                loadDataWithBaseURL(
                                    "https://qr-order.infoapp.org/",
                                    buildMapPickerHtml(
                                        initialLat = selectedLat,
                                        initialLng = selectedLng,
                                        radiusM = radiusM
                                    ),
                                    "text/html",
                                    "UTF-8",
                                    null
                                )
                                webViewRef = this
                            }
                        }
                    )
                    if (!isMapReady) {
                        CircularProgressIndicator()
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = String.format(
                            Locale.US,
                            "選択中: %.6f, %.6f（受付範囲 ${radiusM}m）",
                            selectedLat,
                            selectedLng
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    statusMessage?.let { msg ->
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                if (DeviceLocationProvider.hasLocationPermission(context)) {
                                    fetchGpsAndMove()
                                } else {
                                    permissionLauncher.launch(DeviceLocationProvider.REQUIRED_PERMISSIONS)
                                }
                            },
                            enabled = !isFetchingGps
                        ) {
                            if (isFetchingGps) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(Icons.Default.MyLocation, contentDescription = null)
                            }
                            Spacer(Modifier.width(4.dp))
                            Text("現在地")
                        }
                        Button(
                            onClick = { onConfirm(selectedLat, selectedLng) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("この位置に決定", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

private fun buildMapPickerHtml(
    initialLat: Double,
    initialLng: Double,
    radiusM: Int
): String {
    val latStr = String.format(Locale.US, "%.6f", initialLat)
    val lngStr = String.format(Locale.US, "%.6f", initialLng)
    return """
<!DOCTYPE html>
<html lang="ja">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
  <link id="leaflet-css" rel="stylesheet" href="https://cdn.jsdelivr.net/npm/leaflet@1.9.4/dist/leaflet.css" />
  <style>
    * { box-sizing: border-box; }
    html, body { margin: 0; padding: 0; width: 100%; height: 100%; overflow: hidden; background: #eef1ef; }
    #map { position: fixed; inset: 0; width: 100vw; height: 100vh; }
    .store-pin {
      width: 34px;
      height: 44px;
      display: flex;
      align-items: center;
      justify-content: center;
      filter: drop-shadow(0 2px 4px rgba(0, 0, 0, 0.35));
    }
    #fallback-msg {
      display: none; position: fixed; inset: 0; align-items: center; justify-content: center;
      padding: 24px; text-align: center; font-family: sans-serif; color: #333; background: #eef1ef;
      z-index: 9999;
    }
  </style>
</head>
<body>
  <div id="map"></div>
  <div id="fallback-msg">地図の読み込みに失敗しました。通信状況をご確認のうえ再度お試しください。</div>
  <script>
    function showError(msg) {
      var el = document.getElementById('fallback-msg');
      el.textContent = msg;
      el.style.display = 'flex';
      if (window.AndroidMapBridge && window.AndroidMapBridge.onMapError) {
        window.AndroidMapBridge.onMapError(msg);
      }
    }

    function loadCssFallback() {
      var link = document.createElement('link');
      link.rel = 'stylesheet';
      link.href = 'https://unpkg.com/leaflet@1.9.4/dist/leaflet.css';
      document.head.appendChild(link);
    }

    var cssEl = document.getElementById('leaflet-css');
    if (cssEl) cssEl.onerror = loadCssFallback;

    function loadScript(src, onOk, onErr) {
      var s = document.createElement('script');
      s.src = src;
      s.onload = onOk;
      s.onerror = onErr;
      document.head.appendChild(s);
    }

    function initMap() {
      try {
        if (!window.L) {
          showError('地図ライブラリの読み込みに失敗しました。');
          return;
        }
        var initLat = $latStr;
        var initLng = $lngStr;
        var radiusM = $radiusM;

        var map = L.map('map', { zoomControl: true }).setView([initLat, initLng], 16);

        var gsiStd = L.tileLayer('https://cyberjapandata.gsi.go.jp/xyz/std/{z}/{x}/{y}.png', {
          maxZoom: 18,
          attribution: '&copy; 国土地理院'
        });
        var osm = L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
          maxZoom: 19,
          attribution: '&copy; OpenStreetMap contributors'
        });

        gsiStd.addTo(map);
        L.control.layers({ '地理院地図': gsiStd, 'OpenStreetMap': osm }, null, { position: 'topright' }).addTo(map);

        var pinIcon = L.divIcon({
          className: '',
          iconSize: [34, 44],
          iconAnchor: [17, 42],
          html: '<div class="store-pin"><svg viewBox="0 0 34 44" width="34" height="44" xmlns="http://www.w3.org/2000/svg"><path d="M17 1C8.2 1 1 8.2 1 17c0 11.2 16 25 16 25s16-13.8 16-25C33 8.2 25.8 1 17 1z" fill="#0a6b50" stroke="#ffffff" stroke-width="2.5"/><circle cx="17" cy="17" r="6" fill="#ffffff"/></svg></div>'
        });

        var marker = L.marker([initLat, initLng], { draggable: true, icon: pinIcon }).addTo(map);
        var circle = L.circle([initLat, initLng], {
          radius: radiusM,
          color: '#0a6b50',
          fillColor: '#0a6b50',
          fillOpacity: 0.16,
          weight: 2
        }).addTo(map);

        function notify(lat, lng) {
          if (window.AndroidMapBridge && window.AndroidMapBridge.onLocationSelected) {
            window.AndroidMapBridge.onLocationSelected(lat, lng);
          }
        }

        function updatePosition(lat, lng) {
          marker.setLatLng([lat, lng]);
          circle.setLatLng([lat, lng]);
          notify(lat, lng);
        }

        map.on('click', function(e) {
          updatePosition(e.latlng.lat, e.latlng.lng);
        });

        marker.on('dragend', function() {
          var pos = marker.getLatLng();
          circle.setLatLng(pos);
          notify(pos.lat, pos.lng);
        });

        window.setPin = function(lat, lng, newRadius, recenter) {
          marker.setLatLng([lat, lng]);
          circle.setLatLng([lat, lng]);
          if (typeof newRadius === 'number' && newRadius > 0) {
            circle.setRadius(newRadius);
          }
          if (recenter) {
            map.setView([lat, lng], Math.max(map.getZoom(), 16));
          }
          map.invalidateSize();
        };

        if (window.ResizeObserver) {
          new ResizeObserver(function() { map.invalidateSize(); }).observe(document.getElementById('map'));
        }
        window.addEventListener('resize', function() { map.invalidateSize(); });
        setTimeout(function() { map.invalidateSize(); }, 150);
        setTimeout(function() { map.invalidateSize(); }, 500);

        if (window.AndroidMapBridge && window.AndroidMapBridge.onMapReady) {
          window.AndroidMapBridge.onMapReady();
        }
      } catch (err) {
        showError('地図の初期化エラー: ' + (err && err.message ? err.message : err));
      }
    }

    loadScript('https://cdn.jsdelivr.net/npm/leaflet@1.9.4/dist/leaflet.js', initMap, function() {
      loadScript('https://unpkg.com/leaflet@1.9.4/dist/leaflet.js', initMap, function() {
        showError('地図の読み込みに失敗しました。通信状況をご確認のうえ再度お試しください。');
      });
    });
  </script>
</body>
</html>
    """.trimIndent()
}
