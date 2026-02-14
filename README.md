# VPN Auditor v1.0

A lightweight and powerful network diagnostic tool designed for enthusiasts and engineers to analyze proxy server availability and detect DPI (Deep Packet Inspection) filtering patterns.

---

## 🚀 Key Features

* **Multi-Protocol Parsing**: Automatically extract **VLESS, VMess, Trojan, and Shadowsocks** configurations from raw text, links, or subscription URLs.
* **GitHub Repository Scanner**: Advanced folder scanning (format `.../tree/main/...`) to discover and audit configuration files within GitHub repositories.
* **White-List Detector**: Checks internet connectivity by comparing access to local and global resources (Yandex vs. Google).
* **DPI Stress Test**: Simulates real user traffic using **Safari/iPhone User-Agent masking** to trigger and identify active blocking during data transmission.

---

## 🛠 Audit Methodology

VPN Auditor goes beyond simple pings by analyzing the behavior of the connection under payload stress:

1. **Handshake Check**: Establishes a primary TLS connection. Failure here indicates a **Protocol/SSL Block**.
2. **Payload Stress Test**: Initiates a full `GET` request. This is crucial as modern DPI systems often allow initial packets and drop traffic only after identifying encryption patterns.
3. **Reset Analysis**: Precisely identifies the moment of session termination (errors like `-1005`, `SocketTimeout`, or `Connection Reset`), marking it as a **DPI CUT**.

---

## 📊 Status Interpretation

| Status | Technical Verdict |
| :--- | :--- |
| **Alive (Ping)** | The node is fully functional; data transfer is successful. |
| **DPI CUT (Reset)** | The connection opens but is forcibly closed by the ISP during traffic analysis. |
| **SSL Block** | The connection is blocked at the encryption establishment stage. |
| **Alive (Low Data)** | The IP is reachable, but the mask-server returned an empty or restricted response (typical for VLESS/Reality). |
| **Banned / Unavailable** | Complete node unavailability or IP-address blacklisting. |

---

## 📥 Compatibility

* **Android**: Supports devices running **Android 10 (API 29)** and above.
* **iOS**: Optimized for iPhone running **iOS 16.0+**.

---

## 🇷🇺 Описание на русском (Russian Version)

**VPN Auditor** — это инструмент для технического аудита прокси-серверов. 

### Основные функции:
* **Парсинг конфигов**: Поддержка VLESS, VMess, Trojan, Shadowsocks.
* **Сканер GitHub**: Автоматический поиск файлов конфигураций внутри папок репозиториев.
* **DPI Стресс-тест**: Проверка устойчивости соединения к анализу трафика системами ТСПУ.
* **Детектор "Чебурнета"**: Быстрая проверка режима "белых списков" (доступность только RU-ресурсов).

### Значение статусов:
* **Живой**: Полная проходимость трафика.
* **DPI CUT**: Соединение обрывается провайдером после начала передачи данных.
* **SSL Block**: Блокировка протокола на этапе рукопожатия.
* **Бан / Ошибка**: Узел полностью недоступен.

---
Developed by **denied1011**
