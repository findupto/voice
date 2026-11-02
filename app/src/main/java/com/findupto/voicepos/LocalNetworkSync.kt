package com.findupto.voicepos

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.security.SecureRandom
import java.util.Collections

object LocalNetworkSync {
    private const val PORT = 8765
    private const val PREFS = "voice_local_network"
    @Volatile private var server: PosLanServer? = null

    fun token(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString("access_token", null) ?: ByteArray(24).also {
            SecureRandom().nextBytes(it)
        }.joinToString("") { "%02x".format(it) }.also {
            prefs.edit().putString("access_token", it).apply()
        }
    }

    @Synchronized fun start(context: Context, store: Store): Boolean {
        if (server?.isAlive == true) return true
        return runCatching {
            PosLanServer(store, token(context)).also { it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false); server = it }
            true
        }.getOrDefault(false)
    }

    @Synchronized fun stop() {
        server?.stop()
        server = null
    }

    fun isRunning() = server?.isAlive == true
    fun ipAddress(): String {
        return runCatching {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            interfaces.asSequence().filter { it.isUp && !it.isLoopback }.flatMap { Collections.list(it.inetAddresses).asSequence() }
                .filterIsInstance<Inet4Address>().firstOrNull { it.isSiteLocalAddress }?.hostAddress ?: "IP unavailable"
        }.getOrDefault("IP unavailable")
    }

    fun sync(serverIp: String, accessToken: String, store: Store): String {
        val ip = serverIp.trim().removePrefix("http://").removeSuffix("/")
        require(ip.matches(Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}(:[0-9]{1,5})?"))) { "Enter the server's local IPv4 address." }
        val endpoint = if (ip.contains(":")) "http://$ip/sync" else "http://$ip:$PORT/sync"
        val connection = (URL(endpoint).openConnection() as HttpURLConnection)
        connection.connectTimeout = 3500
        connection.readTimeout = 10000
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.setRequestProperty("X-POS-Token", accessToken.trim())
        connection.outputStream.use { it.write(store.exportBackup().toByteArray(Charsets.UTF_8)) }
        val status = connection.responseCode
        val response = (if (status in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()
        if (status !in 200..299) throw IllegalStateException("Server rejected sync ($status): ${runCatching { JSONObject(response).optString("error") }.getOrDefault("Connection failed")}")
        store.importBackup(response)
        return "Synchronized with main device."
    }

    private class PosLanServer(private val store: Store, private val secret: String) : NanoHTTPD("0.0.0.0", PORT) {
        override fun serve(session: IHTTPSession): Response {
            if (session.uri == "/health" && session.method == Method.GET) {
                return newFixedLengthResponse(Response.Status.OK, "application/json", JSONObject().put("service", "Voice POS LAN").put("version", 1).toString())
            }
            if (session.uri != "/sync" || session.method != Method.POST) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", """{"error":"Not found"}""")
            }
            if (session.headers["x-pos-token"] != secret) {
                return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "application/json", """{"error":"Invalid server access code"}""")
            }
            return try {
                val files = HashMap<String, String>()
                session.parseBody(files)
                val raw = files["postData"] ?: throw IllegalArgumentException("Empty sync request")
                val merged = store.mergeBackup(raw)
                newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", merged)
            } catch (error: Exception) {
                newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", JSONObject().put("error", error.message ?: "Sync failed").toString())
            }
        }
    }
}
