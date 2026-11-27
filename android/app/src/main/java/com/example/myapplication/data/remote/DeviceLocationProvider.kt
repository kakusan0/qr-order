package com.example.myapplication.data.remote

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.math.roundToInt

data class DeviceLocationResult(
    val lat: Double,
    val lng: Double,
    val accuracyM: Int?,
    val isLastKnown: Boolean = false
)

/**
 * 端末の GPS / ネットワーク測位を使って現在地を取得するヘルパー。
 * Android 12 (API 31) 以上では FUSED_PROVIDER + QUALITY_HIGH_ACCURACY を優先し、
 * 屋内・屋外を問わず高精度かつ高速に取得します。
 */
object DeviceLocationProvider {

    val REQUIRED_PERMISSIONS: Array<String> = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    fun hasLocationPermission(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    @SuppressLint("MissingPermission")
    suspend fun getCurrentLocation(
        context: Context,
        timeoutMs: Long = 15_000L
    ): Result<DeviceLocationResult> {
        val appContext = context.applicationContext
        val hasFine = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasFine && !hasCoarse) {
            return Result.failure(
                IllegalStateException("位置情報の権限が許可されていません。端末の設定から位置情報を許可してください。")
            )
        }

        val locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return Result.failure(IllegalStateException("位置情報サービスを利用できません。"))

        if (!LocationManagerCompat.isLocationEnabled(locationManager)) {
            return Result.failure(
                IllegalStateException("端末の位置情報（GPS）がオフになっています。設定から位置情報をオンにしてください。")
            )
        }

        val providers = buildProviderPriorityList(locationManager, hasFine)
        if (providers.isEmpty()) {
            return Result.failure(
                IllegalStateException("利用可能な位置情報プロバイダーが見つかりません。GPS 設定を確認してください。")
            )
        }

        val executor = ContextCompat.getMainExecutor(appContext)
        val perProviderTimeout = (timeoutMs / providers.size.coerceAtLeast(1)).coerceAtLeast(5_000L)

        for (provider in providers) {
            val location = requestSingleLocation(
                locationManager = locationManager,
                provider = provider,
                hasFine = hasFine,
                executor = executor,
                timeoutMs = perProviderTimeout
            )
            if (location != null) {
                return Result.success(location.toDeviceLocationResult(isLastKnown = false))
            }
        }

        val lastKnown = providers
            .mapNotNull { provider ->
                runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
            }
            .maxByOrNull { it.time }

        if (lastKnown != null) {
            return Result.success(lastKnown.toDeviceLocationResult(isLastKnown = true))
        }

        return Result.failure(
            IllegalStateException("現在地を取得できませんでした。窓際や屋外など電波の届きやすい場所で再度お試しください。")
        )
    }

    private fun buildProviderPriorityList(
        locationManager: LocationManager,
        hasFine: Boolean
    ): List<String> {
        val all = locationManager.allProviders
        val result = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            all.contains(LocationManager.FUSED_PROVIDER) &&
            locationManager.isProviderEnabled(LocationManager.FUSED_PROVIDER)
        ) {
            result += LocationManager.FUSED_PROVIDER
        }
        if (hasFine &&
            all.contains(LocationManager.GPS_PROVIDER) &&
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        ) {
            result += LocationManager.GPS_PROVIDER
        }
        if (all.contains(LocationManager.NETWORK_PROVIDER) &&
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        ) {
            result += LocationManager.NETWORK_PROVIDER
        }
        return result
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestSingleLocation(
        locationManager: LocationManager,
        provider: String,
        hasFine: Boolean,
        executor: Executor,
        timeoutMs: Long
    ): Location? = withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val request = LocationRequest.Builder(0L)
                        .setQuality(
                            if (hasFine) LocationRequest.QUALITY_HIGH_ACCURACY
                            else LocationRequest.QUALITY_BALANCED_POWER_ACCURACY
                        )
                        .setDurationMillis(timeoutMs)
                        .setMaxUpdates(1)
                        .build()
                    locationManager.getCurrentLocation(
                        provider,
                        request,
                        signal,
                        executor
                    ) { location ->
                        if (continuation.isActive) {
                            continuation.resume(location)
                        }
                    }
                } else {
                    LocationManagerCompat.getCurrentLocation(
                        locationManager,
                        provider,
                        signal,
                        executor
                    ) { location ->
                        if (continuation.isActive) {
                            continuation.resume(location)
                        }
                    }
                }
            } catch (_: Throwable) {
                if (continuation.isActive) {
                    continuation.resume(null)
                }
            }
        }
    }

    private fun Location.toDeviceLocationResult(isLastKnown: Boolean): DeviceLocationResult =
        DeviceLocationResult(
            lat = latitude,
            lng = longitude,
            accuracyM = if (hasAccuracy()) accuracy.roundToInt() else null,
            isLastKnown = isLastKnown
        )
}
