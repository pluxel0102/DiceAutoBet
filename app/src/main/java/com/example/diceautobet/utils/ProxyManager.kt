package com.example.diceautobet.utils

import android.content.Context
import android.util.Log
import okhttp3.*
import okhttp3.Credentials
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Менеджер для работы с прокси-сервером
 * Настраивает OkHttp клиент для работы через прокси с аутентификацией
 * Поддерживает отключение прокси для использования VPN
 */
object ProxyManager {
    private const val TAG = "ProxyManager"
    
    // Настройки прокси-сервера по умолчанию
    private const val DEFAULT_PROXY_HOST = "200.10.39.135"
    private const val DEFAULT_PROXY_PORT = 8000
    private const val DEFAULT_PROXY_USERNAME = "tr6NAW"
    private const val DEFAULT_PROXY_PASSWORD = "Kjohrt"
    
    // 🔄 РЕЗЕРВНЫЕ НАСТРОЙКИ HTTP ПРОКСИ (если SOCKS5 не работает)
    private const val HTTP_FALLBACK_HOST = "138.219.172.121"
    private const val HTTP_FALLBACK_PORT = 8000
    private const val HTTP_FALLBACK_USER = "ZpUR2q"
    private const val HTTP_FALLBACK_PASS = "Hd1foV"
    
    // Текущие настройки прокси (загружаются из SharedPreferences)
    private var _currentProxyHost = DEFAULT_PROXY_HOST
    private var _currentProxyPort = DEFAULT_PROXY_PORT
    private var _currentProxyUsername = DEFAULT_PROXY_USERNAME
    private var _currentProxyPassword = DEFAULT_PROXY_PASSWORD
    private var _currentProxyType = ProxyType.SOCKS5
    
    // Кэшированные клиенты и статус аутентификации
    private var _proxyHttpClient: OkHttpClient? = null
    private var _directHttpClient: OkHttpClient? = null
    private var _isAuthenticated = false
    private var _authenticatedConnection: Call? = null
    
    // Настройка использования прокси (можно переключать в настройках)
    private var _isProxyEnabled = true
    
    /**
     * Типы прокси-серверов
     */
    enum class ProxyType {
        SOCKS5, HTTP
    }
    
    /**
     * Данные конфигурации прокси
     */
    data class ProxyConfig(
        val host: String,
        val port: Int,
        val username: String,
        val password: String,
        val type: ProxyType
    ) {
        fun isValid(): Boolean {
            return host.isNotBlank() && 
                   port in 1..65535 && 
                   username.isNotBlank() && 
                   password.isNotBlank()
        }
    }
    
    /**
     * Устанавливает режим использования прокси
     */
    fun setProxyEnabled(enabled: Boolean, context: Context? = null) {
        if (_isProxyEnabled != enabled) {
            Log.d(TAG, "🔄 Переключение режима прокси: ${if (enabled) "включен" else "отключен (VPN/прямое)"}")
            _isProxyEnabled = enabled
            
            // Сохраняем настройку в SharedPreferences, если передан контекст
            context?.let {
                val prefs = it.getSharedPreferences("proxy_settings", Context.MODE_PRIVATE)
                prefs.edit().putBoolean("proxy_enabled", enabled).apply()
            }
            
            // Сбрасываем кэшированные клиенты
            resetClient()
            _isAuthenticated = false
            
            // 🚀 АВТОМАТИЧЕСКИЙ ПРОГРЕВ при смене настроек
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    Log.d(TAG, "🔥 Автоматический прогрев после смены настроек...")
                    warmUpConnection()
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Ошибка автоматического прогрева: ${e.message}")
                }
            }
        }
    }
    
    /**
     * Получает текущий статус прокси
     */
    fun isProxyEnabled(): Boolean = _isProxyEnabled
    
    /**
     * Инициализирует настройки прокси из SharedPreferences
     */
    fun initFromPreferences(context: Context) {
        val prefs = context.getSharedPreferences("proxy_settings", Context.MODE_PRIVATE)
        _isProxyEnabled = prefs.getBoolean("proxy_enabled", true) // по умолчанию включен
        
        // Загружаем пользовательские настройки прокси
        _currentProxyHost = prefs.getString("proxy_host", DEFAULT_PROXY_HOST) ?: DEFAULT_PROXY_HOST
        _currentProxyPort = prefs.getInt("proxy_port", DEFAULT_PROXY_PORT)
        _currentProxyUsername = prefs.getString("proxy_username", DEFAULT_PROXY_USERNAME) ?: DEFAULT_PROXY_USERNAME
        _currentProxyPassword = prefs.getString("proxy_password", DEFAULT_PROXY_PASSWORD) ?: DEFAULT_PROXY_PASSWORD
        
        val typeString = prefs.getString("proxy_type", ProxyType.SOCKS5.name) ?: ProxyType.SOCKS5.name
        _currentProxyType = try {
            ProxyType.valueOf(typeString)
        } catch (e: Exception) {
            ProxyType.SOCKS5 // по умолчанию SOCKS5
        }
        
        Log.d(TAG, "📱 Настройки прокси загружены: ${if (_isProxyEnabled) "включен" else "отключен"}")
        Log.d(TAG, "📱 Конфигурация: ${_currentProxyType.name} ${_currentProxyUsername}@${_currentProxyHost}:${_currentProxyPort}")
    }
    
    /**
     * Сохраняет пользовательские настройки прокси
     */
    fun saveProxyConfig(context: Context, config: ProxyConfig): Boolean {
        if (!config.isValid()) {
            Log.e(TAG, "❌ Неверная конфигурация прокси: $config")
            return false
        }
        
        val prefs = context.getSharedPreferences("proxy_settings", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString("proxy_host", config.host)
            putInt("proxy_port", config.port)
            putString("proxy_username", config.username)
            putString("proxy_password", config.password)
            putString("proxy_type", config.type.name)
        }.apply()
        
        // Обновляем текущие настройки
        _currentProxyHost = config.host
        _currentProxyPort = config.port
        _currentProxyUsername = config.username
        _currentProxyPassword = config.password
        _currentProxyType = config.type
        
        // Сбрасываем кэшированные клиенты для пересоздания с новыми настройками
        resetClient()
        
        Log.d(TAG, "💾 Настройки прокси сохранены: ${config.type.name} ${config.username}@${config.host}:${config.port}")
        return true
    }
    
    /**
     * Получает текущую конфигурацию прокси
     */
    fun getCurrentProxyConfig(): ProxyConfig {
        return ProxyConfig(
            host = _currentProxyHost,
            port = _currentProxyPort,
            username = _currentProxyUsername,
            password = _currentProxyPassword,
            type = _currentProxyType
        )
    }
    
    /**
     * Сбрасывает настройки прокси к значениям по умолчанию
     */
    fun resetProxyConfigToDefaults(context: Context) {
        val defaultConfig = ProxyConfig(
            host = DEFAULT_PROXY_HOST,
            port = DEFAULT_PROXY_PORT,
            username = DEFAULT_PROXY_USERNAME,
            password = DEFAULT_PROXY_PASSWORD,
            type = ProxyType.SOCKS5
        )
        
        saveProxyConfig(context, defaultConfig)
        Log.d(TAG, "🔄 Настройки прокси сброшены к значениям по умолчанию")
    }
    
    /**
     * Валидирует настройки прокси
     */
    fun validateProxyConfig(host: String, port: String, username: String, password: String): String? {
        if (host.isBlank()) {
            return "Введите адрес хоста"
        }
        
        if (!isValidHost(host)) {
            return "Неверный формат хоста"
        }
        
        if (port.isBlank()) {
            return "Введите порт"
        }
        
        val portNum = port.toIntOrNull()
        if (portNum == null || portNum !in 1..65535) {
            return "Порт должен быть от 1 до 65535"
        }
        
        if (username.isBlank() || password.isBlank()) {
            return "Введите логин и пароль"
        }
        
        return null // Валидация прошла успешно
    }
    
    /**
     * Проверяет валидность хоста (IP или домен)
     */
    private fun isValidHost(host: String): Boolean {
        // Проверяем IP адрес
        val ipPattern = "^([0-9]{1,3}\\.){3}[0-9]{1,3}$".toRegex()
        if (ipPattern.matches(host)) {
            return host.split(".").all { part ->
                val num = part.toIntOrNull()
                num != null && num in 0..255
            }
        }
        
        // Проверяем доменное имя
        val domainPattern = "^[a-zA-Z0-9]([a-zA-Z0-9\\-]{0,61}[a-zA-Z0-9])?(\\.[a-zA-Z0-9]([a-zA-Z0-9\\-]{0,61}[a-zA-Z0-9])?)*$".toRegex()
        return domainPattern.matches(host)
    }
    
    /**
     * Получает настроенный OkHttpClient (с прокси или без)
     */
    fun getHttpClient(): OkHttpClient {
        return if (_isProxyEnabled) {
            _proxyHttpClient ?: createProxyHttpClient().also { _proxyHttpClient = it }
        } else {
            _directHttpClient ?: createDirectHttpClient().also { _directHttpClient = it }
        }
    }
    
    /**
     * Создает новый OkHttpClient с настройками прокси
     */
    private fun createProxyHttpClient(): OkHttpClient {
        Log.d(TAG, "🔧 Создаем OkHttp клиент с прокси ${_currentProxyHost}:${_currentProxyPort} (${_currentProxyType.name})")
        
        // Используем текущие пользовательские настройки
        val host = _currentProxyHost
        val port = _currentProxyPort
        val username = _currentProxyUsername
        val password = _currentProxyPassword
        val proxyType = _currentProxyType
        
        Log.d(TAG, "🌐 Используем ${proxyType.name}: ${username}@${host}:${port}")
        
        val clientBuilder = OkHttpClient.Builder()
        
        when (proxyType) {
            ProxyType.SOCKS5 -> {
                val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(host, port))
                clientBuilder.proxy(proxy)
                
                // SOCKS5 авторизация - системные свойства + Authenticator
                System.setProperty("java.net.socks.username", username)
                System.setProperty("java.net.socks.password", password)
                System.setProperty("socksProxyHost", host)
                System.setProperty("socksProxyPort", port.toString())
                
                // Дополнительно добавляем общий Authenticator для SOCKS
                val socksAuthenticator = object : java.net.Authenticator() {
                    override fun getPasswordAuthentication(): java.net.PasswordAuthentication {
                        return java.net.PasswordAuthentication(username, password.toCharArray())
                    }
                }
                java.net.Authenticator.setDefault(socksAuthenticator)
                
                Log.d(TAG, "🧦 SOCKS5 настроен: ${username}@${host}:${port}")
            }
            
            ProxyType.HTTP -> {
                val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port))
                clientBuilder.proxy(proxy)
                
                // HTTP прокси авторизация через ProxyAuthenticator
                clientBuilder.proxyAuthenticator { _, response ->
                    val credential = Credentials.basic(username, password)
                    response.request.newBuilder()
                        .header("Proxy-Authorization", credential)
                        .build()
                }
                
                Log.d(TAG, "🌐 HTTP прокси настроен: ${username}@${host}:${port}")
            }
        }
        
        return clientBuilder
            // � УВЕЛИЧЕННЫЕ ТАЙМАУТЫ ДЛЯ НЕСТАБИЛЬНОГО ПРОКСИ
            .connectTimeout(5, TimeUnit.SECONDS)   // 5 секунд на подключение (было 1)
            .readTimeout(8, TimeUnit.SECONDS)      // 8 секунд на чтение (было 2) 
            .writeTimeout(3, TimeUnit.SECONDS)     // 3 секунды на отправку (было 1)
            .callTimeout(12, TimeUnit.SECONDS)     // 12 секунд на весь запрос (было 3)
            // 🚀 ЭКСТРЕМАЛЬНОЕ ПЕРЕИСПОЛЬЗОВАНИЕ СОЕДИНЕНИЙ
            .connectionPool(ConnectionPool(3, 300, TimeUnit.SECONDS))  // Увеличиваем время жизни соединений
            .retryOnConnectionFailure(true)        // ВКЛЮЧАЕМ повторы для нестабильного прокси
            .followRedirects(false)                // Никаких редиректов
            .followSslRedirects(false)             // Никаких SSL редиректов
            .pingInterval(5, TimeUnit.SECONDS)     // Частые keep-alive пинги
            .addInterceptor { chain ->
                // 🚀 ОПТИМИЗИРОВАННЫЕ ЗАГОЛОВКИ (prоxyAuthenticator делает авторизацию)
                val request = chain.request().newBuilder()
                    .header("Connection", "keep-alive") // Переиспользование соединений
                    .header("Accept", "application/json") // Ожидаем JSON
                    .header("User-Agent", "DiceAutoBet-Fast/1.0") // Короткий User-Agent
                    .build()
                    
                Log.d(TAG, "🌐 Отправляем запрос через прокси: ${request.method} ${request.url}")
                val startTime = System.currentTimeMillis()
                try {
                    val response = chain.proceed(request)
                    val duration = System.currentTimeMillis() - startTime
                    Log.d(TAG, "📨 Получен ответ через прокси: ${response.code} ${response.message} (${duration}мс)")
                    response
                } catch (e: Exception) {
                    val duration = System.currentTimeMillis() - startTime
                    Log.e(TAG, "❌ Ошибка запроса через прокси (${duration}мс): ${e.message}")
                    throw e
                }
            }
            .build()
    }
    
    /**
     * Создает новый OkHttpClient без прокси (для VPN/прямого подключения)
     */
    private fun createDirectHttpClient(): OkHttpClient {
        Log.d(TAG, "🌐 Создаем OkHttp клиент БЕЗ прокси (VPN/прямое подключение)")
        
        // Очищаем системные настройки прокси
        System.clearProperty("java.net.socks.username")
        System.clearProperty("java.net.socks.password")
        System.clearProperty("socksProxyHost")
        System.clearProperty("socksProxyPort")
        java.net.Authenticator.setDefault(null)
        
        return OkHttpClient.Builder()
            // Быстрые таймауты для прямого подключения
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(2, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(3, 60, TimeUnit.SECONDS))
            .retryOnConnectionFailure(true)
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("Connection", "keep-alive")
                    .header("Accept", "application/json")
                    .header("User-Agent", "DiceAutoBet-Direct/1.0")
                    .build()
                    
                Log.d(TAG, "🌐 Отправляем запрос БЕЗ прокси: ${request.method} ${request.url}")
                val startTime = System.currentTimeMillis()
                try {
                    val response = chain.proceed(request)
                    val duration = System.currentTimeMillis() - startTime
                    Log.d(TAG, "📨 Получен ответ БЕЗ прокси: ${response.code} ${response.message} (${duration}мс)")
                    response
                } catch (e: Exception) {
                    val duration = System.currentTimeMillis() - startTime
                    Log.e(TAG, "❌ Ошибка запроса БЕЗ прокси (${duration}мс): ${e.message}")
                    throw e
                }
            }
            .build()
    }
    
    /**
     * Тестирует подключение к серверу (через прокси или напрямую)
     */
    suspend fun testConnection(): ProxyTestResult = withContext(Dispatchers.IO) {
        return@withContext try {
            Log.d(TAG, "🧪 Начинаем тест соединения (${if (_isProxyEnabled) "через прокси" else "напрямую"})...")
            
            val client = getHttpClient()
            
            // Попробуем несколько тестовых URL для большей надежности
            val testUrls = listOf(
                "https://httpbin.org/ip",
                "https://ipinfo.io/json",
                "https://api.ipify.org?format=json"
            )
            
            var lastError: String? = null
            
            for (testUrl in testUrls) {
                try {
                    Log.d(TAG, "🌐 Тестируем URL: $testUrl")
                    
                    val request = Request.Builder()
                        .url(testUrl)
                        .get()
                        .addHeader("User-Agent", "DiceAutoBet/1.0")
                        .build()
                    
                    val startTime = System.currentTimeMillis()
                    client.newCall(request).execute().use { response ->
                        val duration = System.currentTimeMillis() - startTime
                        
                        if (response.isSuccessful) {
                            val responseBody = response.body?.string() ?: ""
                            val connectionType = if (_isProxyEnabled) "через прокси" else "БЕЗ прокси"
                            Log.d(TAG, "✅ Тест соединения успешен $connectionType через $testUrl (${duration}мс)")
                            Log.d(TAG, "📊 Полный ответ: $responseBody")
                            
                            // 🔍 ДЕТАЛЬНЫЙ АНАЛИЗ IP АДРЕСА
                            if (_isProxyEnabled) {
                                if (responseBody.contains("200.10.39.135")) {
                                    Log.d(TAG, "🎯 SOCKS5 прокси работает правильно! IP: 200.10.39.135")
                                } else if (responseBody.contains("138.219.172.121")) {
                                    Log.w(TAG, "⚠️ Используется HTTP fallback прокси! IP: 138.219.172.121")
                                } else {
                                    Log.w(TAG, "🤔 Неожиданный IP в ответе при использовании прокси!")
                                }
                            } else {
                                Log.d(TAG, "🌐 Прямое подключение работает, IP: ${responseBody.take(50)}...")
                            }
                            
                            return@withContext ProxyTestResult.Success(duration, responseBody)
                        } else {
                            val errorMsg = "HTTP ${response.code}: ${response.message}"
                            Log.w(TAG, "⚠️ Неуспешный ответ от $testUrl: $errorMsg")
                            lastError = errorMsg
                        }
                    }
                } catch (e: Exception) {
                    val errorMsg = "Ошибка при подключении к $testUrl: ${e.message}"
                    Log.w(TAG, "⚠️ $errorMsg")
                    lastError = errorMsg
                }
            }
            
            // Если все URL не удались
            Log.e(TAG, "❌ Все тестовые URL не удались")
            ProxyTestResult.Error(lastError ?: "Не удалось подключиться ни к одному тестовому серверу")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Критическая ошибка тестирования соединения: ${e.message}", e)
            ProxyTestResult.Error("Критическая ошибка: ${e.message}")
        }
    }
    
    /**
     * 🚀 ПРЕДВАРИТЕЛЬНАЯ АУТЕНТИФИКАЦИЯ И WARM-UP СОЕДИНЕНИЯ
     * Вызывается при запуске приложения для подготовки быстрых запросов
     */
    suspend fun warmUpConnection(): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            Log.d(TAG, "🔥 Начинаем предварительную аутентификацию и warm-up соединения...")
            
            val client = getHttpClient()
            
            // 🚀 ТЕСТИРУЕМ ПРЯМО С GEMINI API
            val warmUpRequest = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models") // Тестируем целевой API
                .get()
                .header("Accept", "application/json")
                .build()
            
            val startTime = System.currentTimeMillis()
            client.newCall(warmUpRequest).execute().use { response ->
                val duration = System.currentTimeMillis() - startTime
                
                Log.d(TAG, "🔍 Gemini API тест: код ${response.code}, сообщение: ${response.message}")
                
                // Для Gemini API даже 401/403 означает успешное подключение к прокси
                if (response.isSuccessful || response.code == 401 || response.code == 403 || response.code == 400) {
                    _isAuthenticated = true
                    Log.d(TAG, "✅ SOCKS5 прокси работает с Gemini API (${duration}мс, код: ${response.code})")
                    true
                } else {
                    Log.w(TAG, "⚠️ Gemini API недоступен через SOCKS5: ${response.code}")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка warm-up соединения: ${e.message}")
            
            // 🚀 ДОПОЛНИТЕЛЬНАЯ ДИАГНОСТИКА
            if (e.message?.contains("SOCKS") == true) {
                Log.e(TAG, "💡 SOCKS5 ошибка - возможные причины:")
                Log.e(TAG, "   1. Неправильные credentials: ${_currentProxyUsername}")
                Log.e(TAG, "   2. Прокси блокирует домен: generativelanguage.googleapis.com")
                Log.e(TAG, "   3. Прокси не поддерживает HTTPS")
                Log.e(TAG, "   4. Неправильный IP/порт: ${_currentProxyHost}:${_currentProxyPort}")
            }
            false
        }
    }
    
    /**
     * 🚀 БЫСТРЫЙ КЛИЕНТ ДЛЯ ИГРОВОГО РЕЖИМА  
     * Использует уже аутентифицированное и прогретое соединение
     */
    fun getFastGameClient(): OkHttpClient {
        if (!_isAuthenticated) {
            Log.w(TAG, "⚠️ Соединение не прогрето! Первый запрос может быть медленнее")
            // 🚀 АВТОМАТИЧЕСКИЙ ПРОГРЕВ при первом использовании
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    Log.d(TAG, "🔥 Автоматический прогрев соединения...")
                    warmUpConnection()
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Ошибка автоматического прогрева: ${e.message}")
                }
            }
        }
        return getHttpClient()
    }

    /**
     * Получает информацию о текущем соединении для отображения
     */
    fun getCurrentConnectionInfo(): String {
        return if (_isProxyEnabled) {
            "${_currentProxyHost}:${_currentProxyPort} (${_currentProxyType.name})"
        } else {
            "VPN/прямое подключение"
        }
    }
    
    /**
     * Очищает кэшированные клиенты (для пересоздания с новыми настройками)
     */
    fun resetClient() {
        Log.d(TAG, "🔄 Сброс HTTP клиентов")
        _proxyHttpClient = null
        _directHttpClient = null
        _isAuthenticated = false
        _authenticatedConnection?.cancel()
        _authenticatedConnection = null
    }
    
    /**
     * 🔥 ПРОГРЕВ СОЕДИНЕНИЯ при запуске приложения
     * Выполняет фоновый запрос для установления соединения с серверами
     */
    suspend fun warmupConnection(): WarmupResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        
        try {
            Log.d(TAG, "🔥 Начинаем прогрев соединения...")
            
            val client = getHttpClient()
            val requests = mutableListOf<String>()
            val errors = mutableListOf<String>()
            
            // 1. Прогрев соединения с Google API
            try {
                Log.d(TAG, "🧠 Прогреваем соединение с Google API...")
                val googleUrl = "https://www.googleapis.com"
                val googleRequest = Request.Builder()
                    .url(googleUrl)
                    .head() // HEAD запрос - быстрее и не требует ключа
                    .addHeader("User-Agent", "DiceAutoBet/1.0")
                    .build()
                
                client.newCall(googleRequest).execute().use { response ->
                    // Принимаем любой ответ как успешный прогрев соединения
                    // 200=OK, 404=Not Found, 405=Method Not Allowed - все означает что соединение установлено
                    if (response.code in 200..499) {
                        requests.add("Google API: ${response.code}")
                        Log.d(TAG, "✅ Google API соединение прогрето успешно (${response.code})")
                    } else {
                        errors.add("Google API: ${response.code}")
                        Log.w(TAG, "⚠️ Google API прогрев с ошибкой: ${response.code}")
                    }
                }
            } catch (e: Exception) {
                errors.add("Google API: ${e.message}")
                Log.w(TAG, "⚠️ Ошибка прогрева Google API: ${e.message}")
            }
            
            // 2. Прогрев BetBoom API
            try {
                Log.d(TAG, "🎰 Прогреваем BetBoom API...")
                val betboomUrl = "https://betboom.ru"
                val betboomRequest = Request.Builder()
                    .url(betboomUrl)
                    .head() // HEAD запрос быстрее
                    .addHeader("User-Agent", "Mozilla/5.0 (Android)")
                    .build()
                
                client.newCall(betboomRequest).execute().use { response ->
                    // Принимаем любой ответ 2xx, 3xx, 4xx как успешный прогрев
                    if (response.code in 200..499) {
                        requests.add("BetBoom: ${response.code}")
                        Log.d(TAG, "✅ BetBoom прогрет успешно (${response.code})")
                    } else {
                        errors.add("BetBoom: ${response.code}")
                        Log.w(TAG, "⚠️ BetBoom прогрев с ошибкой: ${response.code}")
                    }
                }
            } catch (e: Exception) {
                errors.add("BetBoom: ${e.message}")
                Log.w(TAG, "⚠️ Ошибка прогрева BetBoom: ${e.message}")
            }
            
            // 3. Отмечаем соединение как прогретое
            _isAuthenticated = true
            
            val totalTime = System.currentTimeMillis() - startTime
            val connectionType = if (_isProxyEnabled) "прокси" else "прямое"
            
            Log.d(TAG, "🔥 Прогрев завершен за ${totalTime}мс через $connectionType соединение")
            
            WarmupResult.Success(
                duration = totalTime,
                connectionType = connectionType,
                successfulRequests = requests,
                errors = errors
            )
            
        } catch (e: Exception) {
            val totalTime = System.currentTimeMillis() - startTime
            Log.e(TAG, "❌ Ошибка прогрева соединения (${totalTime}мс): ${e.message}", e)
            
            WarmupResult.Error(
                duration = totalTime,
                error = e.message ?: "Неизвестная ошибка"
            )
        }
    }

    /**
     * Результат тестирования соединения
     */
    sealed class ProxyTestResult {
        data class Success(val duration: Long, val response: String) : ProxyTestResult()
        data class Error(val message: String) : ProxyTestResult()
    }
    
    /**
     * Результат прогрева соединения
     */
    sealed class WarmupResult {
        data class Success(
            val duration: Long,
            val connectionType: String,
            val successfulRequests: List<String>,
            val errors: List<String>
        ) : WarmupResult()
        
        data class Error(
            val duration: Long,
            val error: String
        ) : WarmupResult()
    }
}
