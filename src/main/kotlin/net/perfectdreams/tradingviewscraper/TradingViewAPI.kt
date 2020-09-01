package net.perfectdreams.tradingviewscraper

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.features.websocket.ClientWebSocketSession
import io.ktor.client.features.websocket.WebSockets
import io.ktor.client.features.websocket.wss
import io.ktor.client.features.websocket.wssRaw
import io.ktor.client.request.header
import io.ktor.http.cio.websocket.Frame
import io.ktor.http.cio.websocket.close
import io.ktor.http.cio.websocket.readText
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import mu.KotlinLogging
import kotlin.coroutines.Continuation
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
    var ready = false
    var isShuttingDown = false
    var connectionTries = 1

    fun connect() {
        isShuttingDown = false
        connectionTries++

        GlobalScope.launch(Dispatchers.IO) {
            try {
                client.wssRaw(
                        host = "data.tradingview.com",
                        path = "/socket.io/websocket",
                        request = {
                            this.header("Origin", "https://br.tradingview.com")
                            this.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:79.0) Gecko/20100101 Firefox/79.0")
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
                                    return@wssRaw
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

        while (!ready)
            Thread.sleep(25)
    }

    suspend fun shutdownSession() {
        isShuttingDown = true
        session.close()
    }

    suspend fun shutdown() {
        shutdownSession()
        subscribedTickers.clear()
        tickerCallbacks.clear()
    }

    suspend fun getTicker(tickerId: String): JsonObject? = subscribedTickers[tickerId]
    suspend fun getOrRetrieveTicker(tickerId: String, requiredFields: List<String> = defaultTickerFields): JsonObject {
        while (!ready)
            delay(25)

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
        sendMessage(
                createAuthenticatedMessage("quote_add_symbols") {
                    + tickerId
                    + json {
                        "flags" to jsonArray {
                            + "force_permission"
                        }
                    }
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
            val json = Json.parseJson(payload)
            logger.debug { "As json: ${json}" }

            if (json.contains("session_id")) {
                sendAuthToken(authToken)
                createQuoteSession()
                setQuoteFields(*defaultTickerFields.toTypedArray())
                logger.debug { "Session is ready!" }
                connectionTries = 1
                ready = true
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
            } else if (json.contains("m")) {
                val method = json.jsonObject["m"]!!.content

                logger.debug { "Method: $method" }

                if (method == "qsd") {
                    val data = json.jsonObject["p"]!!.jsonArray[1].jsonObject
                    val s = data["s"]!!.content
                    val n = data["n"]!!.content
                    val v = data["v"]!!.jsonObject

                    if (s == "error") {
                        tickerCallbacks[n]?.invoke(json {}, InvalidTickerException())
                        return
                    }

                    val currentTicket = subscribedTickers[n]

                    val newTicket = json {
                        currentTicket?.forEach { k, value ->
                            if (!v.containsKey(k))
                                k to value
                        }

                        v.forEach { k, v ->
                            k to v
                        }
                    }

                    logger.debug { n }
                    logger.debug { tickerCallbacks[n] }

                    subscribedTickers[n] = newTicket
                    logger.debug { "New Ticket: $newTicket" }

                    tickerCallbacks[n]?.invoke(newTicket, null)
                }
            }
        }
    }

    private suspend fun sendAuthToken(tokenType: String) {
        val json = json {
            "m" to "set_auth_token"
            "p" to jsonArray {
                + tokenType
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
                + field
        }

        sendMessage(json)
    }

    private fun createAuthenticatedMessage(method: String, data: JsonArrayBuilder.() -> (Unit)) = json {
        "m" to method
        "p" to jsonArray {
            + sessionId
            data.invoke(this)
        }
    }

    private suspend fun sendMessage(json: JsonObject) = sendMessage(json.toString())
    private suspend fun sendMessage(rawContent: String) = sendRawMessage("~m~${rawContent.length}~m~$rawContent")
    private suspend fun sendRawMessage(rawContent: String) {
        logger.debug { "^ $rawContent" }
        session.send(Frame.Text(rawContent))
    }
}