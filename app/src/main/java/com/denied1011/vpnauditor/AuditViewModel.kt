package com.denied1011.vpnauditor // <--- ЭТО САМОЕ ВАЖНОЕ!

import android.util.Base64
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.regex.Pattern
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

// Модель данных узла
data class Node(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val host: String,
    var status: String = "Ожидание...",
    var color: Color = Color.Gray
)

class AuditViewModel : ViewModel() {
    private val _nodes = MutableStateFlow<List<Node>>(emptyList())
    val nodes = _nodes.asStateFlow()

    private val _isChecking = MutableStateFlow(false)
    val isChecking = _isChecking.asStateFlow()

    private val _internetStatus = MutableStateFlow("Нажмите Старт")
    val internetStatus = _internetStatus.asStateFlow()

    private val _internetColor = MutableStateFlow(Color.Gray)
    val internetColor = _internetColor.asStateFlow()

    private var currentSessionID = UUID.randomUUID().toString()
    private var scanJob: Job? = null

    private val client: OkHttpClient by lazy {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        val sslContext = SSLContext.getInstance("SSL")
        sslContext.init(null, trustAllCerts, SecureRandom())

        OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .callTimeout(java.time.Duration.ofSeconds(15))
            .build()
    }

    fun clearAll() {
        scanJob?.cancel()
        currentSessionID = UUID.randomUUID().toString()
        _nodes.value = emptyList()
        _isChecking.value = false
        _internetStatus.value = "Готов к тесту"
        _internetColor.value = Color.Gray
    }

    private fun checkConnectivity() {
        viewModelScope.launch(Dispatchers.IO) {
            _internetStatus.value = "Пингуем сеть..."
            _internetColor.value = Color.Blue

            val yaAlive = ping("https://ya.ru")
            val googleAlive = ping("https://www.google.com")

            if (yaAlive && googleAlive) {
                _internetStatus.value = "Интернет есть 🌐"
                _internetColor.value = Color(0xFF4CAF50)
            } else if (yaAlive && !googleAlive) {
                _internetStatus.value = "Белые списки (RU only) ⚠️"
                _internetColor.value = Color(0xFFFF9800)
            } else if (!yaAlive && !googleAlive) {
                _internetStatus.value = "Интернета нет ❌"
                _internetColor.value = Color.Red
            } else {
                _internetStatus.value = "Частичный доступ 🟡"
                _internetColor.value = Color(0xFFFF9800)
            }
        }
    }

    private fun ping(url: String): Boolean {
        return try {
            val request = Request.Builder().url(url).head().build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) { false }
    }

    fun parseAndAudit(url: String) {
        val sessionID = UUID.randomUUID().toString()
        currentSessionID = sessionID

        scanJob = viewModelScope.launch(Dispatchers.IO) {
            _isChecking.value = true
            _nodes.value = emptyList()
            checkConnectivity()

            if (url.contains("github.com") && url.contains("/tree/")) {
                parseGitHubFolder(url, sessionID)
            } else {
                fetchAndParseContent(url, sessionID)
            }

            if (currentSessionID == sessionID) {
                _isChecking.value = false
                if (_nodes.value.isEmpty()) {
                    _internetStatus.value = "Ничего не найдено 🤷‍♂️"
                    _internetColor.value = Color(0xFFFF9800)
                }
            }
        }
    }

    private fun parseGitHubFolder(folderUrl: String, sessionID: String) {
        try {
            val request = Request.Builder()
                .url(folderUrl)
                .header("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1")
                .build()

            val html = client.newCall(request).execute().use { it.body?.string() } ?: return

            val pattern = Pattern.compile("href=\"(/[^\"]+/blob/[^\"]+\\.(txt|yaml|yml|json|conf))\"", Pattern.CASE_INSENSITIVE)
            val matcher = pattern.matcher(html)

            val foundLinks = mutableSetOf<String>()

            while (matcher.find()) {
                val relativePath = matcher.group(1) ?: continue
                val rawLink = "https://raw.githubusercontent.com" + relativePath.replace("/blob/", "/")
                foundLinks.add(rawLink)
            }

            foundLinks.forEach { link ->
                if (currentSessionID != sessionID) return
                fetchAndParseContent(link, sessionID)
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun fetchAndParseContent(url: String, sessionID: String) {
        var rawContent = ""
        val userAgents = listOf(
            "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1",
            "Clash.Meta"
        )

        for (ua in userAgents) {
            try {
                val req = Request.Builder().url(url).header("User-Agent", ua).build()
                client.newCall(req).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        if (!body.isNullOrEmpty()) {
                            rawContent = body
                        }
                    }
                }
                if (rawContent.isNotEmpty()) break
            } catch (e: Exception) { continue }
        }

        if (rawContent.isEmpty()) rawContent = url

        if (!rawContent.contains("://")) {
            safeBase64Decode(rawContent)?.let { rawContent = it }
        }

        val pattern = Pattern.compile("(vless|vmess|trojan|ss)://[^\"'<> \\n]+")
        val matcher = pattern.matcher(rawContent)

        while (matcher.find()) {
            if (currentSessionID != sessionID) return
            val link = matcher.group()
            processLink(link, sessionID)
        }
    }

    private fun processLink(link: String, sessionID: String) {
        var host = ""
        var name = "Node"

        try {
            if (link.startsWith("vless://") || link.startsWith("trojan://") || link.startsWith("ss://")) {
                val parts = link.split("#")
                if (parts.size > 1) name = java.net.URLDecoder.decode(parts[1], "UTF-8")
                val uri = java.net.URI(parts[0])
                host = uri.host ?: ""
            } else if (link.startsWith("vmess://")) {
                val b64 = link.replace("vmess://", "")
                safeBase64Decode(b64)?.let { jsonStr ->
                    val json = JSONObject(jsonStr)
                    name = json.optString("ps", "VMess Node")
                    host = json.optString("add", "")
                }
            }
        } catch (e: Exception) { return }

        if (host.isNotEmpty()) {
            val node = Node(name = cleanName(name), host = host)

            if (currentSessionID == sessionID) {
                val newList = _nodes.value.toMutableList()
                newList.add(node)
                _nodes.value = newList

                viewModelScope.launch(Dispatchers.IO) {
                    Thread.sleep(50)
                    runDeepStressTest(host, sessionID)
                }
            }
        }
    }

    private fun runDeepStressTest(host: String, sessionID: String) {
        if (currentSessionID != sessionID) return

        val url = "https://$host"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1")
            .build()

        try {
            val startTime = System.currentTimeMillis()
            client.newCall(request).execute().use { response ->
                val duration = System.currentTimeMillis() - startTime
                val msg = if (response.body?.contentLength() ?: 0 > 100) "Живой (${duration}ms)" else "Живой (Low Data)"
                updateNodeStatus(host, msg, Color(0xFF4CAF50), sessionID)
            }
        } catch (e: Exception) {
            val err = e.toString()
            if (e is SSLHandshakeException) {
                updateNodeStatus(host, "SSL Block", Color(0xFFFF9800), sessionID)
            } else if (err.contains("Timeout") || err.contains("Reset") || err.contains("Socket")) {
                updateNodeStatus(host, "DPI CUT (Разрыв)", Color.Red, sessionID)
            } else {
                updateNodeStatus(host, "Бан / Ошибка", Color.Red, sessionID)
            }
        }
    }

    private fun updateNodeStatus(host: String, status: String, color: Color, sessionID: String) {
        if (currentSessionID != sessionID) return

        val currentList = _nodes.value.toMutableList()
        val index = currentList.indexOfFirst { it.host == host && it.status == "Ожидание..." }

        if (index != -1) {
            currentList[index] = currentList[index].copy(status = status, color = color)
            _nodes.value = currentList
        }
    }

    private fun cleanName(name: String): String {
        return name.filter { it.isLetterOrDigit() || it.isWhitespace() || ".-_".contains(it) }
    }

    private fun safeBase64Decode(str: String): String? {
        return try {
            var base64 = str.replace("-", "+").replace("_", "/").trim()
            val remainder = base64.length % 4
            if (remainder > 0) base64 += "=".repeat(4 - remainder)
            String(Base64.decode(base64, Base64.DEFAULT), Charsets.UTF_8)
        } catch (e: Exception) { null }
    }
}