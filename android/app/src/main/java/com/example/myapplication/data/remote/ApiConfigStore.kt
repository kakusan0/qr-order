package com.example.myapplication.data.remote

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.example.myapplication.data.repository.QrOrderApiEndpoints
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class ApiConfig(
    val baseUrl: String = QrOrderApiEndpoints.DEFAULT_BASE_URL,
    val useMockMode: Boolean = false
)

class ApiConfigStore(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("qr_order_staff_prefs", Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(
        ApiConfig(
            baseUrl = normalizeBaseUrl(prefs.getString(KEY_BASE_URL, QrOrderApiEndpoints.DEFAULT_BASE_URL).orEmpty()),
            useMockMode = prefs.getBoolean(KEY_USE_MOCK, false)
        )
    )
    val configFlow: StateFlow<ApiConfig> = _config.asStateFlow()

    val baseUrl: String get() = _config.value.baseUrl
    val useMockMode: Boolean get() = _config.value.useMockMode

    var sessionCookie: String?
        get() = prefs.getString(KEY_SESSION_COOKIE, null)?.takeIf { it.isNotBlank() }
        set(value) {
            prefs.edit {
                if (value.isNullOrBlank()) remove(KEY_SESSION_COOKIE)
                else putString(KEY_SESSION_COOKIE, value.trim())
            }
        }

    fun updateConfig(baseUrl: String, useMockMode: Boolean) {
        val normalized = normalizeBaseUrl(baseUrl)
        val urlChanged = normalized != _config.value.baseUrl
        prefs.edit {
            putString(KEY_BASE_URL, normalized)
            putBoolean(KEY_USE_MOCK, useMockMode)
            if (urlChanged) remove(KEY_SESSION_COOKIE)
        }
        _config.value = ApiConfig(baseUrl = normalized, useMockMode = useMockMode)
    }

    companion object {
        const val SESSION_COOKIE_NAME = "staff_session"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_USE_MOCK = "use_mock"
        private const val KEY_SESSION_COOKIE = "staff_session_cookie"

        fun normalizeBaseUrl(raw: String): String {
            val trimmed = raw.trim().trimEnd('/')
            if (trimmed.isEmpty()) return QrOrderApiEndpoints.DEFAULT_BASE_URL
            val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                trimmed
            } else {
                "https://$trimmed"
            }
            return withScheme.toHttpUrlOrNull()?.let { url ->
                val portPart = if ((url.scheme == "https" && url.port == 443) || (url.scheme == "http" && url.port == 80)) {
                    ""
                } else {
                    ":${url.port}"
                }
                "${url.scheme}://${url.host}$portPart"
            } ?: QrOrderApiEndpoints.DEFAULT_BASE_URL
        }
    }
}

/**
 * staff_session クッキーを SharedPreferences と同期する OkHttp CookieJar。
 */
class StaffSessionCookieJar(
    private val configStore: ApiConfigStore
) : CookieJar {
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val session = cookies.find { it.name == ApiConfigStore.SESSION_COOKIE_NAME } ?: return
        if (session.value.isBlank() || session.expiresAt <= System.currentTimeMillis()) {
            configStore.sessionCookie = null
        } else {
            configStore.sessionCookie = session.value
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val token = configStore.sessionCookie ?: return emptyList()
        val configuredHost = configStore.baseUrl.toHttpUrlOrNull()?.host ?: return emptyList()
        if (!url.host.equals(configuredHost, ignoreCase = true)) return emptyList()
        val cookie = Cookie.Builder()
            .name(ApiConfigStore.SESSION_COOKIE_NAME)
            .value(token)
            .domain(url.host)
            .path("/")
            .apply { if (url.isHttps) secure() }
            .httpOnly()
            .build()
        return listOf(cookie)
    }
}
