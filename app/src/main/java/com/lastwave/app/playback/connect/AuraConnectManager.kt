package com.lastwave.app.playback.connect

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.lastwave.app.playback.MusicPlayer
import com.lastwave.app.playback.PlayableTrack
import dagger.hilt.android.qualifiers.ApplicationContext
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Spotify Connect-style background service for seamless device continuity.
 * Automatically discovers devices on the local mesh, reports active audio output route
 * (e.g. "Bluetooth: JBL Flip 6", "Phone speaker"), and executes remote playback commands.
 */
@Singleton
class AuraConnectManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val musicPlayer: MusicPlayer,
) {
    companion object {
        private const val TAG = "AuraConnect"
        const val CONNECT_HTTP_PORT = 8765
        const val CONNECT_UDP_PORT = 8766
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var httpServer: ConnectHttpServer? = null
    private var udpListenJob: Job? = null
    private var heartbeatJob: Job? = null

    private val deviceId: String by lazy {
        try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                ?: Build.MODEL.replace(" ", "-").lowercase()
        } catch (_: Exception) {
            Build.MODEL.replace(" ", "-").lowercase()
        }
    }

    private val deviceName: String
        get() = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}".trim()

    fun start() {
        startHttpServer()
        startUdpListener()
        startHeartbeatLoop()
    }

    fun stop() {
        httpServer?.stop()
        httpServer = null
        udpListenJob?.cancel()
        udpListenJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private fun startHttpServer() {
        if (httpServer != null) return
        try {
            val server = ConnectHttpServer(CONNECT_HTTP_PORT)
            server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            httpServer = server
            Log.i(TAG, "Aura Connect HTTP server listening on port $CONNECT_HTTP_PORT")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Aura Connect HTTP server: ${e.message}", e)
        }
    }

    private fun startUdpListener() {
        udpListenJob?.cancel()
        udpListenJob = scope.launch {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(CONNECT_UDP_PORT))
                    broadcast = true
                }
                val buffer = ByteArray(2048)
                while (isActive) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                        val text = String(packet.data, 0, packet.length, StandardCharsets.UTF_8).trim()
                        if (text.contains("aura_discover") || text.contains("aura_ping")) {
                            // Respond directly with presence
                            val stateJson = buildStateJson().toString()
                            val responseBytes = stateJson.toByteArray(StandardCharsets.UTF_8)
                            val replyPacket = DatagramPacket(
                                responseBytes,
                                responseBytes.size,
                                packet.address,
                                packet.port,
                            )
                            socket.send(replyPacket)
                        }
                    } catch (recvEx: Exception) {
                        if (!isActive) break
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "UDP discovery listener error: ${e.message}")
            } finally {
                socket?.close()
            }
        }
    }

    private fun startHeartbeatLoop() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            val socket = try {
                DatagramSocket().apply { broadcast = true }
            } catch (_: Exception) {
                null
            } ?: return@launch

            try {
                val broadcastAddress = InetAddress.getByName("255.255.255.255")
                while (isActive) {
                    // Emit beacon every 3 seconds while active
                    val state = musicPlayer.state.value
                    if (state.isPlaying || state.current != null) {
                        try {
                            val stateJson = buildStateJson().toString()
                            val data = stateJson.toByteArray(StandardCharsets.UTF_8)
                            val packet = DatagramPacket(data, data.size, broadcastAddress, CONNECT_UDP_PORT)
                            socket.send(packet)
                        } catch (_: Exception) {}
                    }
                    delay(3000L)
                }
            } finally {
                socket.close()
            }
        }
    }

    private fun detectAudioOutputRoute(): String {
        val audioManager = context.getSystemService(AudioManager::class.java) ?: return "Phone speaker"
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        fun rank(type: Int): Int = when (type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER -> 0
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY -> 1
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> 2
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> 3
            else -> 9
        }
        val best = devices.filter { rank(it.type) < 9 }.minByOrNull { rank(it.type) } ?: return "Phone speaker"
        val prodName = best.productName?.toString()?.trim().orEmpty()
        return when (rank(best.type)) {
            0 -> if (prodName.isNotBlank()) "Bluetooth: $prodName" else "Bluetooth Speaker"
            1 -> if (prodName.isNotBlank()) "USB: $prodName" else "USB Audio"
            2 -> if (prodName.isNotBlank()) prodName else "Wired Headphones"
            else -> "Phone speaker"
        }
    }

    private fun getVolumeFraction(): Double {
        return try {
            val audioManager = context.getSystemService(AudioManager::class.java) ?: return 1.0
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val curr = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (max > 0) curr.toDouble() / max.toDouble() else 1.0
        } catch (_: Exception) {
            1.0
        }
    }

    private fun setVolumeFraction(fraction: Double) {
        try {
            val audioManager = context.getSystemService(AudioManager::class.java) ?: return
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val target = (fraction.coerceIn(0.0, 1.0) * max).toInt()
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set volume: ${e.message}")
        }
    }

    fun buildStateJson(): JSONObject {
        val state = musicPlayer.state.value
        val track = state.current
        val outputRoute = detectAudioOutputRoute()
        val volume = getVolumeFraction()
        val pos = musicPlayer.currentPositionMs()

        val json = JSONObject()
        json.put("type", "aura_state")
        json.put("status", "ok")

        val deviceObj = JSONObject()
        deviceObj.put("id", deviceId)
        deviceObj.put("name", deviceName)
        deviceObj.put("type", "phone")
        deviceObj.put("outputRoute", outputRoute)
        deviceObj.put("httpPort", CONNECT_HTTP_PORT)
        json.put("device", deviceObj)

        val playbackObj = JSONObject()
        playbackObj.put("isPlaying", state.isPlaying)
        playbackObj.put("positionMs", pos)
        playbackObj.put("durationMs", state.durationMs ?: 0L)
        playbackObj.put("volume", volume)

        if (track != null) {
            val trackObj = JSONObject()
            trackObj.put("title", track.title)
            trackObj.put("artist", track.artist)
            trackObj.put("album", track.album ?: "")
            trackObj.put("artworkUrl", track.artworkUrl ?: "")
            trackObj.put("videoId", track.videoId ?: "")
            trackObj.put("durationMs", track.durationMs ?: (state.durationMs ?: 0L))
            playbackObj.put("track", trackObj)
        } else {
            playbackObj.put("track", JSONObject.NULL)
        }
        json.put("playback", playbackObj)

        return json
    }

    private inner class ConnectHttpServer(port: Int) : NanoHTTPD("0.0.0.0", port) {
        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri
            val method = session.method

            if (method == Method.OPTIONS) {
                val resp = newFixedLengthResponse("")
                addCorsHeaders(resp)
                return resp
            }

            if (uri == "/aura-connect/state" && method == Method.GET) {
                val resp = newFixedLengthResponse(
                    Response.Status.OK,
                    "application/json",
                    buildStateJson().toString(),
                )
                addCorsHeaders(resp)
                return resp
            }

            if (uri == "/aura-connect/action" && (method == Method.POST || method == Method.GET)) {
                val bodyMap = HashMap<String, String>()
                try {
                    session.parseBody(bodyMap)
                } catch (e: Exception) {
                    Log.w(TAG, "NanoHTTPD parseBody failed: ${e.message}")
                }
                val postData = bodyMap["postData"] ?: ""
                val actionJson = if (postData.isNotBlank()) {
                    try { JSONObject(postData) } catch (_: Exception) { JSONObject() }
                } else JSONObject()

                val action = (if (actionJson.has("action")) actionJson.optString("action") else null)
                    ?: session.parms["action"]
                    ?: ""

                Log.d(TAG, "AuraConnect action received: $action (parms=${session.parms})")

                scope.launch(Dispatchers.Main) {
                    when (action) {
                        "play" -> musicPlayer.resume()
                        "pause" -> musicPlayer.pause()
                        "toggle" -> musicPlayer.togglePlayPause()
                        "seek" -> {
                            val pos = if (actionJson.has("positionMs")) {
                                actionJson.optLong("positionMs", 0L)
                            } else {
                                session.parms["positionMs"]?.toLongOrNull() ?: 0L
                            }
                            musicPlayer.seekTo(pos)
                        }
                        "skip_next", "next" -> musicPlayer.next()
                        "skip_previous", "previous" -> musicPlayer.previous()
                        "volume", "set_volume" -> {
                            val vol = if (actionJson.has("value")) {
                                actionJson.optDouble("value", 1.0)
                            } else {
                                session.parms["value"]?.toDoubleOrNull() ?: 1.0
                            }
                            setVolumeFraction(vol)
                        }
                        "play_track" -> {
                            val tObj = actionJson.optJSONObject("track") ?: actionJson
                            val title = tObj.optString("title", session.parms["title"] ?: "")
                            if (title.isNotBlank()) {
                                val newTrack = PlayableTrack(
                                    title = title,
                                    artist = tObj.optString("artist", session.parms["artist"] ?: "Unknown"),
                                    album = tObj.optString("album", session.parms["album"] ?: "").takeIf { it.isNotBlank() },
                                    artworkUrl = tObj.optString("artworkUrl", session.parms["artworkUrl"] ?: "").takeIf { it.isNotBlank() },
                                    videoId = tObj.optString("videoId", session.parms["videoId"] ?: "").takeIf { it.isNotBlank() },
                                    durationMs = tObj.optLong("durationMs", session.parms["durationMs"]?.toLongOrNull() ?: 0L).takeIf { it > 0 },
                                )
                                musicPlayer.play(track = newTrack, sourceLabel = "Aura Connect")
                            }
                        }
                    }
                }

                val resp = newFixedLengthResponse(
                    Response.Status.OK,
                    "application/json",
                    JSONObject().put("status", "accepted").put("action", action).toString(),
                )
                addCorsHeaders(resp)
                return resp
            }

            val notFound = newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
            addCorsHeaders(notFound)
            return notFound
        }

        private fun addCorsHeaders(response: Response) {
            response.addHeader("Access-Control-Allow-Origin", "*")
            response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
            response.addHeader("Access-Control-Allow-Headers", "Content-Type, Authorization")
        }
    }
}
