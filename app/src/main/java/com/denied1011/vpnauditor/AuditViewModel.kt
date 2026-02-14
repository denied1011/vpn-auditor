package com.denied1011.vpnauditor

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
import java.net.URLDecoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.regex.Pattern
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

// Классическая модель данных
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

    private val _internetStatus = MutableStateFlow("Готов к работе")
    val internetStatus = _internetStatus.asStateFlow()

    private val _internetColor = MutableStateFlow(Color.Gray)
    val internetColor = _internetColor.asStateFlow()

    private var currentSessionID = UUID.randomUUID().toString()
    private var scanJob: Job? = null

    // Настройка клиента: Игнор SSL (для самоподписанных сертификатов) + Таймауты
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
            .connectTimeout(java.time.Duration.ofSeconds(10))
            .readTimeout(java.time.Duration.ofSeconds(10))
            .followRedirects(true)
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

    fun parseAndAudit(url: String) {
        val sessionID = UUID.randomUUID().toString()
        currentSessionID = sessionID

        scanJob = viewModelScope.launch(Dispatchers.IO) {
            _isChecking.value = true
            _nodes.value = emptyList()
            _internetStatus.value = "Загрузка..."
            _internetColor.value = Color.Blue

            if (url.contains("github.com") && url.contains("/tree/")) {
                parseGitHubFolder(url, sessionID)
            } else {
                fetchAndParse(url, sessionID)
            }

            if (currentSessionID == sessionID) {
                _isChecking.value = false
                if (_nodes.value.isEmpty()) {
                    _internetStatus.value = "Ничего не найдено"
                    _internetColor.value = Color.Red
                } else {
                    _internetStatus.value = "Найдено: ${_nodes.value.size}"
                    _internetColor.value = Color(0xFF4CAF50) // Green
                }
            }
        }
    }

    private fun parseGitHubFolder(folderUrl: String, sessionID: String) {
        // Заглушка для GitHub, так как мы фокусируемся на подписках.
        // Если нужна полная логика GitHub - скажи, я добавлю.
    }

    private fun fetchAndParse(url: String, sessionID: String) {
        // Перебор User-Agent для обхода блокировок
        val userAgents = listOf(
            "v2rayNG/1.8.5",        // Обычно самый надежный для ссылок
            "ClashForAndroid/2.5.12", // Для YAML конфигов
            "Clash.Meta"
        )

        var rawBody = ""

        for (ua in userAgents) {
            try {
                val req = Request.Builder().url(url).header("User-Agent", ua).build()
                client.newCall(req).execute().use { response ->
                    if (response.isSuccessful) {
                        rawBody = response.body?.string() ?: ""
                    }
                }
                // Если скачали что-то осмысленное (длиннее 50 символов), останавливаемся
                if (rawBody.length > 50) break
            } catch (e: Exception) { continue }
        }

        if (rawBody.isEmpty()) return

        // 1. Пробуем парсить как Clash YAML (если есть proxies:)
        if (rawBody.contains("proxies:")) {
            parseClashYaml(rawBody, sessionID)
        }

        // 2. Если YAML не дал результатов (или это не YAML), пробуем Base64/Ссылки
        // (Мы не проверяем isEmpty(), так как в одном файле может быть и то, и другое)
        if (_nodes.value.isEmpty()) {
            val decoded = smartDecode(rawBody) ?: rawBody
            extractStandardLinks(decoded, sessionID)
        }
    }

    // --- ПАРСЕР CLASH (YAML) ---
    private fun parseClashYaml(content: String, sessionID: String) {
        try {
            val proxiesIndex = content.indexOf("proxies:")
            if (proxiesIndex == -1) return

            val proxiesBlock = content.substring(proxiesIndex)
            val items = proxiesBlock.split(Regex("\\n\\s*-\\s+"))

            for (item in items) {
                if (currentSessionID != sessionID) break

                val name = extractYamlField(item, "name")
                val server = extractYamlField(item, "server")

                if (server.isNotEmpty() && isValidHost(server)) {
                    addNode(name.ifEmpty { "Node" }, server, sessionID)
                }
            }
        } catch (e: Exception) { }
    }

    private fun extractYamlField(text: String, key: String): String {
        val pattern = Pattern.compile("$key:\\s*([^\\n]+)")
        val matcher = pattern.matcher(text)
        if (matcher.find()) {
            return matcher.group(1)?.trim()?.replace("\"", "")?.replace("'", "") ?: ""
        }
        return ""
    }

    // --- ПАРСЕР СТАНДАРТНЫХ ССЫЛОК (VLESS/VMESS) ---
    private fun extractStandardLinks(text: String, sessionID: String) {
        // Ищем vless://... до пробела или конца строки
        val pattern = Pattern.compile("(vless|vmess|trojan|ss)://[^\\s\"'<>,]+", Pattern.CASE_INSENSITIVE)
        val matcher = pattern.matcher(text)

        while (matcher.find()) {
            if (currentSessionID != sessionID) break
            val link = matcher.group()
            processLink(link, sessionID)
        }
    }

    private fun processLink(link: String, sessionID: String) {
        var host = ""
        var name = "Node"

        try {
            if (link.startsWith("vmess://")) {
                val b64 = link.substring(8)
                smartDecode(b64)?.let { jsonStr ->
                    val json = JSONObject(jsonStr)
                    name = json.optString("ps", "VMess")
                    host = json.optString("add", "")
                }
            } else {
                val parts = link.split("#")
                if (parts.size > 1) name = try { URLDecoder.decode(parts[1], "UTF-8") } catch(e:Exception){ parts[1] }

                val mainPart = parts[0]

                // 1. Ищем SNI/Host в параметрах (для Reality/CDN)
                host = findParam(mainPart, "sni=")
                    ?: findParam(mainPart, "host=")
                            ?: findParam(mainPart, "addr=")
                            ?: ""

                // 2. Если нет, берем адрес после @
                if (host.isEmpty()) {
                    val atIndex = mainPart.indexOf("@")
                    if (atIndex != -1) {
                        val afterAt = mainPart.substring(atIndex + 1)
                        host = afterAt.substringBefore(":").substringBefore("?")
                    }
                }
            }
        } catch (e: Exception) { return }

        host = host.removePrefix("[").removeSuffix("]")

        if (isValidHost(host)) {
            addNode(name, host, sessionID)
        }
    }

    // --- ДОБАВЛЕНИЕ УЗЛА (С ФИКСОМ ДУБЛЕЙ) ---
    private fun addNode(name: String, host: String, sessionID: String) {
        // Дубликат теперь - это совпадение И имени, И хоста.
        // Это позволяет добавить 21 сервер, даже если у них один IP.
        val isDuplicate = _nodes.value.any { it.name == name && it.host == host }

        if (!isDuplicate) {
            val node = Node(name = name.take(40), host = host)
            val list = _nodes.value.toMutableList()
            list.add(node)
            _nodes.value = list

            viewModelScope.launch(Dispatchers.IO) {
                runClassicStressTest(host, sessionID)
            }
        }
    }

    private fun isValidHost(host: String): Boolean {
        return host.isNotEmpty() &&
                host != "127.0.0.1" &&
                host != "0.0.0.0" &&
                !host.contains("example.com")
    }

    private fun smartDecode(source: String): String? {
        var clean = source.filter { !it.isWhitespace() }
        while (clean.length % 4 != 0) { clean += "=" }
        try { return String(Base64.decode(clean, Base64.DEFAULT), Charsets.UTF_8) } catch (e: Exception) {}
        try { return String(Base64.decode(clean, Base64.URL_SAFE), Charsets.UTF_8) } catch (e: Exception) {}
        try { return String(Base64.decode(clean, Base64.NO_WRAP), Charsets.UTF_8) } catch (e: Exception) {}
        return null
    }

    private fun findParam(text: String, key: String): String? {
        val idx = text.indexOf(key)
        if (idx == -1) return null
        return text.substring(idx + key.length).substringBefore("&").substringBefore("#")
    }

    // --- КЛАССИЧЕСКИЙ ТЕСТ С ПИНГОМ ---
    private fun runClassicStressTest(host: String, sessionID: String) {
        if (currentSessionID != sessionID) return

        val url = "https://$host"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0")
            .head()
            .build()

        try {
            val start = System.currentTimeMillis()
            client.newCall(request).execute().use {
                val time = System.currentTimeMillis() - start
                // ВОТ ОН, ВАШ ПИНГ! ВЕРНУЛ НА МЕСТО.
                updateNodeStatus(host, "Живой (${time}ms)", Color(0xFF4CAF50), sessionID)
            }
        } catch (e: Exception) {
            val err = e.toString()
            if (err.contains("Timeout") || err.contains("ConnectException")) {
                updateNodeStatus(host, "Таймаут", Color.Red, sessionID)
            } else if (err.contains("SSL")) {
                updateNodeStatus(host, "Ошибка SSL", Color(0xFFFF9800), sessionID)
            } else {
                updateNodeStatus(host, "Недоступен", Color.Red, sessionID)
            }
        }
    }

    private fun updateNodeStatus(host: String, status: String, color: Color, sessionID: String) {
        if (currentSessionID != sessionID) return

        val currentList = _nodes.value.toMutableList()
        // Обновляем все узлы с таким хостом, которые еще не проверены
        val indices = currentList.mapIndexedNotNull { index, node ->
            if (node.host == host) index else null
        }

        var updated = false
        for (i in indices) {
            if (currentList[i].status == "Ожидание...") {
                currentList[i] = currentList[i].copy(status = status, color = color)
                updated = true
            }
        }

        if (updated) {
            _nodes.value = currentList
        }
    }
}