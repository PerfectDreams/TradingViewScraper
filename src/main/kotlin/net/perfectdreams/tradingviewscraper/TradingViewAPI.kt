package net.perfectdreams.tradingviewscraper

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.*
import io.ktor.client.plugins.websocket.cio.*
import io.ktor.client.request.header
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import mu.KotlinLogging
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class TradingViewAPI(
    val authToken: String = "unauthorized_user_token",
    val pingInterval: Int = 20_000
) {
    companion object {
        private val client = HttpClient(CIO) {
            install(WebSockets)
        }
        private val packetPrefix = Regex("~m~(\\d+)~m~")
        private val logger = KotlinLogging.logger {}
    }

    private var _session: ClientWebSocketSession? = null
    val session: ClientWebSocketSession
        get() = _session ?: throw RuntimeException("Session isn't connected yet!")
    var lastPingReceivedAt = 0L
    // Generates a random 12 length string for the Session ID
    private val sessionId = "qs_${(('a'..'z').map { it.toString() }).shuffled().take(12).joinToString("")}"
    private val subscribedTickers = mutableMapOf<String, JsonObject>()
    private val tickerCallbacks = mutableMapOf<String, (JsonObject, Throwable?) -> (Unit)>()
    private val defaultTickerFields = listOf(
        "ch",
        "chp",
        "current_session",
        "description",
        // "local_description",
        "language",
        "exchange",
        "fractional",
        "is_tradable",
        "lp",
        "minmov",
        "minmove2",
        "original_name",
        "pricescale",
        "pro_name",
        "short_name",
        "type",
        "update_mode",
        "volume",
        "ask",
        "bid",
        // "fundamentals",
        "high_price",
        "is_tradable",
        "low_price",
        "open_price",
        "prev_close_price",
        "rch",
        "rchp",
        "rtc",
        // "status",
        "basic_eps_net_income",
        "beta_1_year",
        "earnings_per_share_basic_ttm",
        "industry",
        "market_cap_basic",
        // "price_earnings_ttm",
        "sector",
        "volume",
        "dividends_yield"
    )
    var ready = MutableStateFlow(false)
    var isShuttingDown = false
    var connectionTries = 1

    fun connect() {
        isShuttingDown = false
        connectionTries++

        GlobalScope.launch(Dispatchers.IO) {
            try {
                client.webSocket(
                    "wss://data.tradingview.com/socket.io/websocket",
                    {
                        this.header("Origin", "https://br.tradingview.com")
                        this.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:101.0) Gecko/20100101 Firefox/101.0")
                    }
                ) {
                    _session = this

                    try {
                        for (frame in incoming) {
                            when (frame) {
                                is Frame.Text -> {
                                    val rawContent = frame.readText()
                                    logger.debug { rawContent }
                                    val cont = splitAndProcessMessage(rawContent)
                                }
                                is Frame.Close -> {
                                    logger.info { "Received shutdown frame! $frame" }
                                    logger.info { "Shutting down the session and reconnecting..." }
                                    shutdownSession()
                                    return@webSocket
                                }
                                else -> {
                                    logger.info { "Received strange frame! $frame" }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        logger.warn(e) { "Exception while reading frames" }
                    }
                }
            } catch (e: Exception) {
                logger.warn(e) { "Exception while connecting to the WebSocket" }
            }

            val delay = (Math.pow(2.0, connectionTries.toDouble()) * 1_000).toLong()

            logger.warn { "WebSocket disconnected for some reason...? Reconnecting after ${delay}ms!" }
            shutdownSession()
            delay(delay)
            connect()
        }

        runBlocking {
            awaitReady()
        }
    }

    suspend fun shutdownSession() {
        isShuttingDown = true
        ready.value = false
        if (session.isActive)
            try {
                session.close()
            } catch (e: Exception) {
                logger.warn(e) { "Failed to close session! Is it already closed?" }
            }
    }

    suspend fun shutdown() {
        shutdownSession()
        subscribedTickers.clear()
        tickerCallbacks.clear()
    }

    suspend fun getTicker(tickerId: String): JsonObject? = subscribedTickers[tickerId]
    suspend fun getOrRetrieveTicker(tickerId: String, requiredFields: List<String> = defaultTickerFields): JsonObject {
        awaitReady()

        val ticker = getTicker(tickerId)
        if (ticker != null)
            return ticker

        registerTicker(tickerId)

        return suspendCoroutine { continuation ->
            logger.debug { "Inside suspended coroutine" }
            tickerCallbacks[tickerId] = { it, throwable ->
                logger.debug { "Called back!" }
                if (throwable != null)
                    throw throwable

                val anyKeyMissing = requiredFields.any { field -> !it.containsKey(field) }

                if (!anyKeyMissing) {
                    tickerCallbacks.remove(tickerId)
                    continuation.resume(it)
                } else {
                    val missingFields = requiredFields.filter { field -> !it.containsKey(field) }
                    logger.debug { "Missing fields: $missingFields" }
                }
            }
        }
    }

    fun onTickerUpdate(tickerId: String, callback: (JsonObject) -> (Unit)) {
        tickerCallbacks[tickerId] = { it, throwable ->
            if (throwable != null)
                throw throwable

            callback.invoke(it)
        }
    }

    suspend fun registerTicker(tickerId: String) {
        awaitReady()

        sendMessage(
            createAuthenticatedMessage("quote_add_symbols") {
                add(tickerId)
                // 06/02/2024 update - Doesn't seem to be used anymore, using these flags causes the client to bork out with "{"m":"critical_error","p":["qs_rfcyvedtgukx","invalid_parameters","quote_add_symbols"]}"
                /* addJsonObject {
                    putJsonArray("flags") {
                        add("force_permission")
                    }
                } */
            }
        )
    }

    private suspend fun splitAndProcessMessage(rawContent: String) {
        // Packet Format:
        // ~m~$payloadLength~m~$payload
        // Example:
        // ~m~52~m~{"m":"quote_create_session","p":["qs_dnsa2OmMSwkH"]}
        // Example (Ping):
        // ~m~5~m~~h~16
        logger.debug { rawContent }

        val messages = mutableListOf<String>()

        // Sometimes TradingView may send multiple messages in the same payload (separated by ~m~#payloadLength~m~)
        // So we need to find them all and split if possible
        val matches = packetPrefix.findAll(rawContent)

        for (match in matches) {
            val fullMatch = match.groups[0]!!
            val payloadLength = match.groups[1]!!

            messages += rawContent.substring(fullMatch.range.last + 1, fullMatch.range.last + 1 + payloadLength.value.toInt())
        }

        messages.forEach {
            logger.debug { "Detected message: $it" }
        }

        for (message in messages)
            processMessage(message)
    }

    private suspend fun processMessage(payload: String) {
        if (payload.startsWith("~h~")) {
            // Ping request
            logger.debug { "Pong!" }
            lastPingReceivedAt = System.currentTimeMillis()
            sendMessage(payload) // Just reply with the original content
        } else if (payload.startsWith("{") && payload.endsWith("}")) {
            val json = Json.parseToJsonElement(payload)
            logger.debug { "As json: ${json}" }

            if (json is JsonObject) {
                if (json.containsKey("session_id")) {
                    sendAuthToken(authToken)
                    createQuoteSession()
                    setQuoteFields(*defaultTickerFields.toTypedArray())
                    logger.debug { "Session is ready!" }
                    connectionTries = 1
                    ready.value = true
                    lastPingReceivedAt = System.currentTimeMillis()

                    logger.debug { "Creating ticker subscriptions for previously created ${subscribedTickers.size} tickers" }

                    for ((tickerId, _) in subscribedTickers) {
                        registerTicker(tickerId)
                    }

                    GlobalScope.launch(Dispatchers.IO) {
                        while (!isShuttingDown) {
                            val diff = System.currentTimeMillis() - lastPingReceivedAt

                            if (diff > pingInterval) {
                                logger.warn { "Last ping was received more than ${pingInterval}ms ago! Shutting down connection and reconnecting..." }
                                shutdownSession()
                                return@launch
                            }

                            logger.info { "Last ping was received ${diff}ms ago" }

                            delay(5_000)
                        }
                    }
                } else if (json.containsKey("m")) {
                    val method = json.jsonObject["m"]!!.jsonPrimitive.content

                    logger.debug { "Method: $method" }

                    if (method == "qsd") {
                        val data = json.jsonObject["p"]!!.jsonArray[1].jsonObject
                        val s = data["s"]!!.jsonPrimitive.content
                        val n = data["n"]!!.jsonPrimitive.content
                        val v = data["v"]!!.jsonObject

                        if (s == "error") {
                            tickerCallbacks[n]?.invoke(buildJsonObject {}, InvalidTickerException())
                            return
                        }

                        val currentTicker = subscribedTickers[n]

                        // Clones the ticker object
                        val newTicket = buildJsonObject {
                            currentTicker?.forEach { k, value ->
                                if (!v.containsKey(k))
                                    put(k, value)
                            }

                            v.forEach { k, v ->
                                put(k, v)
                            }
                        }

                        logger.debug { n }
                        logger.debug { tickerCallbacks[n] }

                        subscribedTickers[n] = newTicket
                        logger.debug { "New Ticker: $newTicket" }

                        tickerCallbacks[n]?.invoke(newTicket, null)
                    }
                }
            } else {
                logger.warn { "Received JsonElement is not a JsonObject!" }
            }
        }
    }

    private suspend fun sendAuthToken(tokenType: String) {
        val json = buildJsonObject {
            put("m", "set_auth_token")
            putJsonArray("p") {
                add(tokenType)
            }
        }

        sendMessage(json)
    }

    private suspend fun createQuoteSession() {
        val json = createAuthenticatedMessage("quote_create_session") {}

        sendMessage(json)
    }

    private suspend fun setQuoteFields(vararg fields: String) {
        val json = createAuthenticatedMessage("quote_set_fields") {
            for (field in fields)
                add(field)
        }

        sendMessage(json)
    }

    private fun createAuthenticatedMessage(method: String, data: JsonArrayBuilder.() -> (Unit)) = buildJsonObject {
        put("m", method)
        putJsonArray("p") {
            add(sessionId)
            data.invoke(this)
        }
    }

    private suspend fun sendMessage(json: JsonObject) = sendMessage(json.toString())
    private suspend fun sendMessage(rawContent: String) = sendRawMessage("~m~${rawContent.length}~m~$rawContent")
    private suspend fun sendRawMessage(rawContent: String) {
        logger.debug { "^ $rawContent" }
        session.send(Frame.Text(rawContent))
    }

    // Suspends while ready != true
    // https://stackoverflow.com/a/66332000/7271796
    private suspend fun awaitReady() {
        if (ready.value) {
            return
        } else {
            ready.first { it }
        }
    }
}