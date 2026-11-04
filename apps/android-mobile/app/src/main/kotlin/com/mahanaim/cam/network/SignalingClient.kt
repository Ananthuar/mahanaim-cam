
package com.mahanaim.cam.network

import android.content.Context
import android.os.Build
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

private const val TAG = "MahanaimCAM/Signaling"

sealed class SignalingState {
    object Disconnected : SignalingState()
    object Connecting : SignalingState()
    object AwaitingApproval : SignalingState()

    data class Paired(
        val sessionId: String
    ) : SignalingState()

    data class Rejected(
        val reason: String
    ) : SignalingState()

    data class Failed(
        val reason: String
    ) : SignalingState()
}

class SignalingClient(
    context: Context,
    private val onStateChanged: (SignalingState) -> Unit
) {
    private val appContext = context.applicationContext

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    @Volatile
    private var webSocket: WebSocket? = null

    private fun getDeviceUuid(): String {
        val preferences = appContext.getSharedPreferences(
            "mahanaim_cam",
            Context.MODE_PRIVATE
        )

        val existingUuid = preferences.getString("device_uuid", null)

        if (existingUuid != null) {
            return existingUuid
        }

        val newUuid = UUID.randomUUID().toString()

        preferences.edit()
            .putString("device_uuid", newUuid)
            .apply()

        return newUuid
    }

    fun connect(url: String) {
        if (!url.startsWith("ws://") || url.length > 256) {
            onStateChanged(
                SignalingState.Failed("Invalid desktop connection address.")
            )
            return
        }

        disconnect()

        onStateChanged(SignalingState.Connecting)

        val request = Request.Builder()
            .url(url)
            .build()

        webSocket = client.newWebSocket(
            request,
            object : WebSocketListener() {

                override fun onOpen(
                    webSocket: WebSocket,
                    response: Response
                ) {
                    Log.d(TAG, "WebSocket connected")

                    onStateChanged(SignalingState.AwaitingApproval)

                    val pairingRequest = JSONObject().apply {
                        put("type", "PAIR_REQUEST")
                        put("deviceName", Build.MODEL ?: "Android Device")
                        put("uuid", getDeviceUuid())
                        put("platformVersion", Build.VERSION.SDK_INT.toString())
                    }

                    val sent = webSocket.send(pairingRequest.toString())

                    if (!sent) {
                        onStateChanged(
                            SignalingState.Failed(
                                "Unable to send pairing request."
                            )
                        )
                    } else {
                        Log.d(TAG, "Pairing request sent")
                    }
                }

                override fun onMessage(
                    webSocket: WebSocket,
                    text: String
                ) {
                    try {
                        val message = JSONObject(text)

                        when (message.optString("type")) {
                            "PAIR_ACCEPT" -> {
                                val sessionId = message.optString("sessionId")

                                if (sessionId.isNotBlank()) {
                                    Log.d(TAG, "Pairing accepted")

                                    onStateChanged(
                                        SignalingState.Paired(sessionId)
                                    )
                                } else {
                                    onStateChanged(
                                        SignalingState.Failed(
                                            "Desktop returned an invalid session."
                                        )
                                    )
                                }
                            }

                            "PAIR_REJECT" -> {
                                val reason = message.optString(
                                    "reason",
                                    "Pairing rejected by desktop."
                                )

                                Log.w(TAG, reason)

                                onStateChanged(
                                    SignalingState.Rejected(reason)
                                )
                            }

                            "HEARTBEAT_ACK" -> {
                                Log.d(TAG, "Heartbeat acknowledged")
                            }

                            "ERROR" -> {
                                onStateChanged(
                                    SignalingState.Failed(
                                        message.optString(
                                            "message",
                                            "Desktop reported an error."
                                        )
                                    )
                                )
                            }

                            else -> {
                                Log.d(TAG, "Received: $text")
                            }
                        }
                    } catch (exception: Exception) {
                        Log.e(TAG, "Invalid server message", exception)
                    }
                }

                override fun onFailure(
                    webSocket: WebSocket,
                    t: Throwable,
                    response: Response?
                ) {
                    Log.e(TAG, "WebSocket connection failed", t)

                    this@SignalingClient.webSocket = null

                    onStateChanged(
                        SignalingState.Failed(
                            t.message ?: "Unable to connect to desktop."
                        )
                    )
                }

                override fun onClosed(
                    webSocket: WebSocket,
                    code: Int,
                    reason: String
                ) {
                    Log.d(TAG, "WebSocket closed: $reason")

                    this@SignalingClient.webSocket = null

                    onStateChanged(SignalingState.Disconnected)
                }
            }
        )
    }

    fun disconnect() {
        webSocket?.close(1000, "Disconnected by user")
        webSocket = null
    }
}
