package dev.cmux.android

import android.content.Context
import android.util.Base64
import computer.iroh.BiStream
import computer.iroh.Connection
import computer.iroh.Endpoint
import computer.iroh.EndpointAddr
import computer.iroh.EndpointId
import computer.iroh.EndpointOptions
import computer.iroh.IrohAndroid
import computer.iroh.RecvStream
import computer.iroh.RelayConfig
import computer.iroh.RelayMap
import computer.iroh.RelayMode
import computer.iroh.SecretKey
import computer.iroh.SendStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

internal class IrohWireConnection private constructor(
    private val endpoint: Endpoint,
    private val connection: Connection,
    private val stream: BiStream,
    private val receive: RecvStream,
    private val send: SendStream,
) : WireConnection {
    override fun input(): InputStream = object : InputStream() {
        private var buffered = ByteArrayInputStream(ByteArray(0))

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 0xff
        }

        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            require(offset >= 0 && length >= 0 && offset + length <= target.size)
            if (length == 0) return 0
            val ready = buffered.read(target, offset, length)
            if (ready > 0) return ready
            val bytes = runBlocking(Dispatchers.IO) { receive.read(length.toUInt()) }
            if (bytes.isEmpty()) return -1
            buffered = ByteArrayInputStream(bytes)
            return buffered.read(target, offset, length)
        }
    }

    override fun output(): OutputStream = object : OutputStream() {
        override fun write(value: Int) = write(byteArrayOf(value.toByte()))

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            require(offset >= 0 && length >= 0 && offset + length <= bytes.size)
            if (length == 0) return
            val copy = bytes.copyOfRange(offset, offset + length)
            runBlocking(Dispatchers.IO) { send.writeAll(copy) }
        }
    }

    override fun close() {
        try { connection.close(0, "client_closed".toByteArray()) } catch (_: Exception) {}
        runBlocking(Dispatchers.IO) {
            try { endpoint.shutdown() } catch (_: Exception) {}
        }
        try { send.close() } catch (_: Exception) {}
        try { receive.close() } catch (_: Exception) {}
        try { stream.close() } catch (_: Exception) {}
        try { connection.close() } catch (_: Exception) {}
        try { endpoint.close() } catch (_: Exception) {}
    }

    companion object {
        private val alpn = "cmux/mobile/1".toByteArray(StandardCharsets.UTF_8)

        @JvmStatic
        fun connect(context: Context, auth: StackAuthClient, remoteEndpointId: String): IrohWireConnection =
            runBlocking(Dispatchers.IO) {
                IrohAndroid.installAndroidContext(context.applicationContext)
                val key = localKey(context, auth)
                val endpointId = key.public().toString()
                require(endpointId.matches(Regex("[0-9a-f]{64}"))) { "Invalid local Iroh identity" }

                val broker = Broker(context, auth)
                val registration = broker.register(key, endpointId)
                val discovery = broker.discover()
                val local = discovery.bindings.firstOrNull { it.endpointId == endpointId }
                    ?: throw IllegalStateException("This phone is not registered with cmux")
                require(local.bindingId == registration.bindingId) { "cmux registration changed unexpectedly" }
                val target = discovery.bindings.firstOrNull {
                    it.endpointId == remoteEndpointId && it.platform == "mac" && it.pairingEnabled
                } ?: throw IllegalArgumentException("That Mac is not available on this cmux account")
                val grant = broker.pairGrant(local.bindingId, target.bindingId)

                val relayCredentials = registration.relayCredentials.ifEmpty {
                    broker.relayCredentials(endpointId)
                }
                val relayMap = RelayMap.empty()
                relayCredentials.forEach {
                    relayMap.insert(RelayConfig(it.url, null, it.token))
                }
                if (relayCredentials.isEmpty()) {
                    discovery.relayFleet.forEach { relayMap.insert(RelayConfig(it, null, null)) }
                }
                val endpoint = Endpoint.bind(EndpointOptions(
                    secretKey = key.toBytes(),
                    alpns = listOf(alpn),
                    relayMode = RelayMode.custom(relayMap),
                ))
                try {
                val relay = target.pathHints.firstOrNull {
                        it.usable && it.kind == "relay_url" && it.privacyScope == "public_internet"
                    }?.value
                    val direct = target.pathHints.filter {
                        it.usable && it.kind == "direct_address"
                            && it.privacyScope == "public_internet"
                    }.map { it.value }
                    val remoteId = EndpointId.fromString(remoteEndpointId)
                    val connection = endpoint.connect(EndpointAddr(remoteId, relay, direct), alpn)
                    try {
                        require(connection.remoteId().toString() == remoteEndpointId) { "Iroh peer identity mismatch" }
                        connection.setMaxConcurrentBiStreams(0UL)
                        connection.setMaxConcurrentUniStreams(0UL)
                        val stream = connection.openBi()
                        val send = stream.send()
                        val receive = stream.recv()
                        send.writeAll(controlHeader(grant))
                        val accepted = admission(receive.readExact(8U))
                        if (accepted == 0) connection.authorizeNatTraversal()
                        send.writeAll(admissionFrame(2))
                        require(admission(receive.readExact(8U)) == 3) { "cmux did not finish Iroh admission" }
                        return@runBlocking IrohWireConnection(endpoint, connection, stream, receive, send)
                    } catch (error: Throwable) {
                        try { connection.close(1, "admission_failed".toByteArray()) } catch (_: Exception) {}
                        try { connection.close() } catch (_: Exception) {}
                        throw error
                    }
                } catch (error: Throwable) {
                    try { endpoint.shutdown() } catch (_: Exception) {}
                    try { endpoint.close() } catch (_: Exception) {}
                    throw error
                }
            }

        @JvmStatic
        fun discoverMacs(context: Context, auth: StackAuthClient): JSONArray =
            runBlocking(Dispatchers.IO) {
                IrohAndroid.installAndroidContext(context.applicationContext)
                val key = localKey(context, auth)
                val endpointId = key.public().toString()
                val broker = Broker(context, auth)
                broker.register(key, endpointId)
                val result = JSONArray()
                broker.discover().bindings.filter {
                    it.platform == "mac" && it.pairingEnabled
                }.sortedByDescending { it.lastSeenAt }.forEach {
                    result.put(JSONObject().put("endpoint_id", it.endpointId)
                        .put("device_id", it.deviceId).put("tag", it.tag)
                        .put("display_name", it.displayName).put("last_seen_at", it.lastSeenAt))
                }
                result
            }

        private fun localKey(context: Context, auth: StackAuthClient): SecretKey {
            val store = SecureTokenStore(context)
            val account = auth.accountFingerprint()
            val stored = store.irohSecret(account)
            val key = if (stored == null) SecretKey.generate() else SecretKey.fromBytes(stored)
            if (stored == null) store.saveIrohSecret(key.toBytes(), account)
            require(key.public().toString().matches(Regex("[0-9a-f]{64}"))) {
                "Invalid local Iroh identity"
            }
            return key
        }

        internal fun controlHeader(grant: String): ByteArray {
            val token = grant.toByteArray(StandardCharsets.UTF_8)
            require(token.isNotEmpty() && token.size <= 65_535) { "Invalid cmux pair grant" }
            val payload = ByteBuffer.allocate(2 + token.size).putShort(token.size.toShort()).put(token).array()
            return ByteBuffer.allocate(16 + payload.size)
                .put("CMUXIRH1".toByteArray()).put(1).put(1).put(0).put(1)
                .putInt(payload.size).put(payload).array()
        }

        internal fun admission(frame: ByteArray): Int {
            require(frame.size == 8 && frame.copyOfRange(0, 4).contentEquals("CMXA".toByteArray())
                && frame[4].toInt() == 1) { "Invalid cmux admission frame" }
            val status = frame[5].toInt() and 0xff
            val code = ByteBuffer.wrap(frame, 6, 2).short.toInt() and 0xffff
            if (status == 1) throw IllegalStateException("cmux denied Iroh admission ($code)")
            require(code == 0 && status in listOf(0, 3, 4)) { "Invalid cmux admission frame" }
            return status
        }

        private fun admissionFrame(status: Int) =
            byteArrayOf('C'.code.toByte(), 'M'.code.toByte(), 'X'.code.toByte(), 'A'.code.toByte(), 1, status.toByte(), 0, 0)
    }
}

private class Broker(context: Context, private val auth: StackAuthClient) {
    data class Binding(
        val bindingId: String,
        val deviceId: String,
        val appInstanceId: String,
        val tag: String,
        val displayName: String?,
        val endpointId: String,
        val platform: String,
        val pairingEnabled: Boolean,
        val lastSeenAt: String,
        val pathHints: List<PathHint>,
    )
    data class PathHint(
        val kind: String,
        val value: String,
        val privacyScope: String,
        val usable: Boolean,
    )
    data class RelayCredential(val url: String, val token: String)
    data class Registration(val bindingId: String, val relayCredentials: List<RelayCredential>)
    data class Discovery(val bindings: List<Binding>, val relayFleet: List<String>)

    private val install = context.getSharedPreferences("iroh-install", Context.MODE_PRIVATE)

    fun register(key: SecretKey, endpointId: String): Registration {
        val deviceId = stableUuid("device-id")
        val appInstanceId = stableUuid("app-instance-id")
        val payload = "{" +
            "\"appInstanceId\":${JSONObject.quote(appInstanceId)}," +
            "\"capabilities\":[\"mobile-rpc-v1\",\"multistream-v1\"]," +
            "\"deviceId\":${JSONObject.quote(deviceId)}," +
            "\"endpointId\":${JSONObject.quote(endpointId)}," +
            "\"identityGeneration\":1," +
            "\"pairingEnabled\":false," +
            "\"pathHints\":[]," +
            "\"platform\":\"ios\"," +
            "\"route_contract_version\":1," +
            "\"tag\":\"android\"}"
        val payloadBytes = payload.toByteArray(StandardCharsets.UTF_8)
        val hash = MessageDigest.getInstance("SHA-256").digest(payloadBytes).hex()
        val challenge = request("api/devices/iroh/challenge", "POST", JSONObject()
            .put("deviceId", deviceId).put("appInstanceId", appInstanceId).put("tag", "android")
            .put("endpointId", endpointId).put("identityGeneration", 1).put("payloadSha256", hash))
        val challengeId = challenge.getString("challenge_id")
        val nonce = challenge.getString("nonce")
        val decodedNonce = runCatching { decode64(nonce) }.getOrNull()
        require(canonicalUuid(challengeId) && decodedNonce?.size == 32
            && encode64(decodedNonce).equals(nonce)) {
            "Invalid cmux registration challenge"
        }
        val transcript = "cmux/iroh/device-registration/v1\n${challengeId.lowercase()}\n$nonce\n$hash"
            .toByteArray(StandardCharsets.UTF_8)
        val signature = encode64(key.sign(transcript).toBytes())
        val response = request("api/devices/iroh/register", "POST", JSONObject()
            .put("challengeId", challengeId.lowercase()).put("nonce", nonce)
            .put("payload", encode64(payloadBytes)).put("signature", signature))
        val binding = binding(response.getJSONObject("binding"))
        require(binding.endpointId == endpointId && binding.deviceId == deviceId
            && binding.appInstanceId == appInstanceId && binding.tag == "android"
            && binding.platform == "ios" && !binding.pairingEnabled) {
            "cmux registered a different identity"
        }
        val relay = response.optJSONObject("relay")
        val credentials = when {
            relay == null || relay.optString("status") != "issued" -> emptyList()
            relay.has("relay_credentials") -> relay.getJSONArray("relay_credentials").relayCredentials()
            else -> legacyRelayCredentials(relay, Instant.now()).map { RelayCredential(it.first, it.second) }
        }
        return Registration(binding.bindingId, credentials)
    }

    fun discover(): Discovery {
        val bindings = ArrayList<Binding>()
        var cursor: String? = null
        var relayFleet = emptyList<String>()
        do {
            val suffix = if (cursor == null) "?page_size=128" else
                "?page_size=128&cursor=" + URLEncoder.encode(cursor, "UTF-8")
            val page = request("api/devices/iroh$suffix", "GET", null)
            val discovery = page.optJSONObject("discovery") ?: page
            relayFleet = discovery.getJSONArray("relay_fleet").strings()
            require(relayFleet.isNotEmpty() && relayFleet.size <= 16
                && relayFleet.distinct().size == relayFleet.size
                && relayFleet.all(::canonicalRelayUrl)) { "Invalid cmux relay fleet" }
            val values = discovery.getJSONArray("bindings")
            for (i in 0 until values.length()) bindings += binding(values.getJSONObject(i))
            cursor = page.optString("next_cursor").ifBlank { null }
        } while (cursor != null)
        return Discovery(bindings, relayFleet)
    }

    fun pairGrant(initiator: String, acceptor: String): String =
        request("api/devices/iroh/pair-grants", "POST", JSONObject()
            .put("initiatorBindingId", initiator).put("acceptorBindingId", acceptor)).getString("grant")

    fun relayCredentials(endpointId: String): List<RelayCredential> {
        val response = request("api/relay/token", "POST", JSONObject().put("endpointId", endpointId))
        response.optJSONArray("relay_credentials")?.let { return it.relayCredentials() }
        val token = response.optString("token")
        val relays = response.optJSONArray("relays") ?: response.optJSONArray("relay_fleet")
        return if (token.isBlank() || relays == null) emptyList()
            else relays.strings().map { RelayCredential(it, token) }
    }

    private fun request(path: String, method: String, body: JSONObject?): JSONObject {
        val credentials = auth.credentials()
        val url = URL("https://cmux.com/$path")
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", "Bearer ${credentials.accessToken()}")
            connection.setRequestProperty("X-Stack-Refresh-Token", credentials.refreshToken())
            connection.setRequestProperty("Accept", "application/json")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }
            }
            val status = connection.responseCode
            require(connection.url.toURI() == URI(url.toString())) { "cmux broker redirected unexpectedly" }
            val input = if (status in 200..299) connection.inputStream else connection.errorStream
            val bytes = input?.use { limitedRead(it, 2 * 1024 * 1024) } ?: ByteArray(0)
            val json = if (bytes.isEmpty()) JSONObject() else JSONObject(String(bytes, StandardCharsets.UTF_8))
            if (status !in 200..299) throw IllegalStateException(json.optString("error", "cmux broker error $status"))
            return json
        } finally {
            connection.disconnect()
        }
    }

    private fun stableUuid(key: String): String {
        install.getString(key, null)?.let { return it }
        val value = UUID.randomUUID().toString().lowercase()
        check(install.edit().putString(key, value).commit()) { "Could not save cmux device identity" }
        return value
    }

    private fun binding(value: JSONObject): Binding {
        val hints = value.getJSONArray("path_hints")
        require(hints.length() <= 16) { "Invalid cmux path hints" }
        val parsed = ArrayList<PathHint>()
        val distinctHints = HashSet<String>()
        var relayCount = 0
        val now = Instant.now()
        for (i in 0 until hints.length()) {
            val hint = hints.getJSONObject(i)
            val kind = hint.getString("kind")
            val path = hint.getString("value")
            val privacy = hint.getString("privacy_scope")
            val source = hint.getString("source")
            require(kind == "direct_address" || kind == "relay_url") { "Invalid cmux path hint" }
            require(privacy in setOf("public_internet", "private_network", "local_network")) {
                "Invalid cmux path privacy"
            }
            require(path.length in 3..512 && path.none { it.isISOControl() || it.isWhitespace() }) {
                "Invalid cmux path"
            }
            require((source == "native" && privacy == "public_internet")
                || (source == "lan" && privacy == "local_network")
                || (source in setOf("tailscale", "custom_vpn") && privacy == "private_network")) {
                "Invalid cmux path provenance"
            }
            if (kind == "relay_url") {
                relayCount++
                require(source == "native" && privacy == "public_internet"
                    && canonicalRelayUrl(path)) { "Invalid cmux relay URL" }
            }
            val observed = runCatching { Instant.parse(hint.getString("observed_at")) }.getOrNull()
            val expires = runCatching { Instant.parse(hint.getString("expires_at")) }.getOrNull()
            require(observed != null && expires != null && expires.isAfter(observed)
                && expires.epochSecond - observed.epochSecond <= 3600) { "Invalid cmux path lifetime" }
            val profile = hint.optJSONObject("network_profile")
            if (privacy == "public_internet") {
                require(profile == null) { "Invalid public cmux path profile" }
            } else {
                require(profile != null && profile.getString("source") == source
                    && profile.getString("profile_id").matches(Regex("[0-9a-f]{64}"))) {
                    "Invalid private cmux path profile"
                }
            }
            val key = listOf(kind, path, privacy, source, observed.toString(), expires.toString(),
                profile?.optString("source") ?: "", profile?.optString("profile_id") ?: "")
                .joinToString("\u0000")
            require(distinctHints.add(key)) { "Duplicate cmux path hint" }
            val usable = !observed.isAfter(now.plusSeconds(300)) && expires.isAfter(now)
            parsed += PathHint(kind, path, privacy, usable)
        }
        require(relayCount <= 2) { "Invalid cmux relay hints" }
        val bindingId = value.getString("binding_id")
        val deviceId = value.getString("device_id")
        val appInstanceId = value.getString("app_instance_id")
        val tag = value.getString("tag")
        val displayName = value.optString("display_name").ifBlank { null }
        val endpointId = value.getString("endpoint_id")
        val platform = value.getString("platform")
        val lastSeenAt = value.getString("last_seen_at")
        val identityGeneration = value.getInt("identity_generation")
        val capabilities = value.getJSONArray("capabilities").strings()
        require(listOf(bindingId, deviceId, appInstanceId).all(::canonicalUuid)
            && endpointId.matches(Regex("[0-9a-f]{64}"))
            && tag.matches(Regex("[A-Za-z0-9._:-]{1,64}"))
            && platform in setOf("mac", "ios")
            && identityGeneration in 1..Int.MAX_VALUE
            && capabilities.size <= 32 && capabilities.distinct().size == capabilities.size
            && capabilities.all { it.matches(Regex("[A-Za-z0-9._:-]{1,64}")) }
            && (displayName == null || displayName.length <= 128
                && displayName.none { it.isISOControl() })
            && runCatching { Instant.parse(lastSeenAt) }.isSuccess) {
            "Invalid cmux device binding"
        }
        return Binding(bindingId, deviceId, appInstanceId, tag, displayName, endpointId,
            platform, value.getBoolean("pairing_enabled"), lastSeenAt, parsed)
    }

    private fun canonicalUuid(value: String) = runCatching {
        UUID.fromString(value).toString() == value
    }.getOrDefault(false)

}

private fun JSONArray?.relayCredentials(): List<Broker.RelayCredential> {
    if (this == null) return emptyList()
    return (0 until length()).map {
        val value = getJSONObject(it)
        parseRelayCredential(value, Instant.now()).let { Broker.RelayCredential(it.first, it.second) }
    }
}

internal fun legacyRelayCredentials(value: JSONObject, now: Instant): List<Pair<String, String>> {
    val token = value.getString("token")
    val expires = runCatching { Instant.parse(value.getString("expires_at")) }.getOrNull()
    val refresh = runCatching { Instant.parse(value.getString("refresh_after")) }.getOrNull()
    val fleet = value.getJSONArray("relay_fleet").strings()
    require(expires != null && refresh != null && now.isBefore(refresh) && refresh.isBefore(expires)
        && token.toByteArray(StandardCharsets.UTF_8).size in 1..8_192
        && validRelayTokenShape(token) && fleet.size in 1..16
        && fleet.distinct().size == fleet.size && fleet.all(::canonicalRelayUrl)) {
        "Invalid cmux relay credential"
    }
    return fleet.map { it to token }
}

internal fun parseRelayCredential(value: JSONObject, now: Instant): Pair<String, String> {
    val url = value.getString("relay_url")
    val token = value.getString("token")
    val expires = value.getLong("expires_at")
    val refresh = value.getLong("refresh_after")
    val ttl = value.getLong("ttl_seconds")
    require(canonicalRelayUrl(url) && token.toByteArray(StandardCharsets.UTF_8).size in 1..8_192
        && validRelayTokenShape(token) && ttl in 30..86_400 && expires > refresh
        && refresh >= expires - ttl && now.epochSecond < refresh) {
        "Invalid cmux relay credential"
    }
    return url to token
}

private fun validRelayTokenShape(value: String): Boolean {
    val jwt = value.split('.', limit = 4)
    if (jwt.size == 3) return jwt.all { part ->
        part.isNotEmpty() && part.all { it.isLetterOrDigit() || it == '-' || it == '_' }
    }
    return value.all { it in 'a'..'z' || it in '2'..'7' }
}

private fun JSONArray.strings() = (0 until length()).map { getString(it) }
internal fun canonicalRelayUrl(value: String): Boolean = runCatching {
    val uri = URI(value)
    uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.host == uri.host.lowercase()
        && uri.userInfo == null && uri.port == -1 && uri.query == null && uri.fragment == null
}.getOrDefault(false)
private fun ByteArray.hex() = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
private fun encode64(value: ByteArray) = Base64.encodeToString(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
private fun decode64(value: String) = Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
private fun limitedRead(input: InputStream, limit: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(16_384)
    while (true) {
        val count = input.read(buffer)
        if (count == -1) break
        require(output.size() + count <= limit) { "cmux broker response is too large" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
