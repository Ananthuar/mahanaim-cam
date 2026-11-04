
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

// --------------------------------------------------
// SIGNALING STATES
// --------------------------------------------------

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

// --------------------------------------------------
// SIGNALING CLIENT
// --------------------------------------------------

class SignalingClient(
    context: Context,

    // Receives WebRTC messages from the desktop.
    private val onMessageReceived: (JSONObject) -> Unit = {},

    // Keep this last for compatibility with trailing lambdas.
    private val onStateChanged: (SignalingState) -> Unit
) {

    private val appContext = context.applicationContext

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var sessionId: String? = null

    // --------------------------------------------------
    // DEVICE UUID
    // --------------------------------------------------

    private fun getDeviceUuid(): String {

        val preferences = appContext.getSharedPreferences(
            "mahanaim_cam",
            Context.MODE_PRIVATE
        )

        val existingUuid = preferences.getString(
            "device_uuid",
            null
        )

        if (existingUuid != null) {
            return existingUuid
        }

        val newUuid = UUID.randomUUID().toString()

        preferences.edit()
            .putString("device_uuid", newUuid)
            .apply()

        return newUuid
    }

    // --------------------------------------------------
    // CONNECT
    // --------------------------------------------------

    @Synchronized
    fun connect(url: String) {

        if (
            !url.startsWith("ws://") ||
            url.length > 256
        ) {
            onStateChanged(
                SignalingState.Failed(
                    "Invalid desktop connection address."
                )
            )
            return
        }

        disconnect()

        onStateChanged(SignalingState.Connecting)

        val request = try {
            Request.Builder()
                .url(url)
                .build()
        } catch (exception: Exception) {

            onStateChanged(
                SignalingState.Failed(
                    exception.message ?: "Invalid connection address."
                )
            )

            return
        }

        val socket = client.newWebSocket(
            request,
            object : WebSocketListener() {

                // ------------------------------------------
                // CONNECTION OPENED
                // ------------------------------------------

                override fun onOpen(
                    webSocket: WebSocket,
                    response: Response
                ) {

                    if (this@SignalingClient.webSocket !== webSocket) {
                        webSocket.close(1000, "Obsolete connection")
                        return
                    }

                    Log.d(TAG, "WebSocket connected")

                    onStateChanged(
                        SignalingState.AwaitingApproval
                    )

                    val pairingRequest = JSONObject().apply {

                        put("type", "PAIR_REQUEST")

                        put(
                            "deviceName",
                            Build.MODEL ?: "Android Device"
                        )

                        put(
                            "uuid",
                            getDeviceUuid()
                        )

                        put(
                            "platformVersion",
                            Build.VERSION.SDK_INT.toString()
                        )
                    }

                    val sent = webSocket.send(
                        pairingRequest.toString()
                    )

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

                // ------------------------------------------
                // RECEIVE SERVER MESSAGE
                // ------------------------------------------

                override fun onMessage(
                    webSocket: WebSocket,
                    text: String
                ) {

                    if (this@SignalingClient.webSocket !== webSocket) {
                        return
                    }

                    try {

                        val message = JSONObject(text)

                        when (message.optString("type")) {

                            "PAIR_ACCEPT" -> {

                                val receivedSessionId =
                                    message.optString("sessionId")

                                if (receivedSessionId.isNotBlank()) {

                                    sessionId = receivedSessionId

                                    Log.d(
                                        TAG,
                                        "Pairing accepted: $receivedSessionId"
                                    )

                                    onStateChanged(
                                        SignalingState.Paired(
                                            receivedSessionId
                                        )
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

                                Log.d(
                                    TAG,
                                    "Heartbeat acknowledged"
                                )
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

                            "SDP_OFFER",
                            "ICE_CANDIDATE",
                            "SESSION_END" -> {

                                val receivedSessionId =
                                    message.optString("sessionId")

                                if (
                                    receivedSessionId.isBlank() ||
                                    receivedSessionId != sessionId
                                ) {

                                    Log.w(
                                        TAG,
                                        "Ignored message for invalid session"
                                    )

                                    return
                                }

                                Log.d(
                                    TAG,
                                    "WebRTC message received: ${message.optString("type")}"
                                )

                                onMessageReceived(message)
                            }

                            else -> {

                                Log.d(
                                    TAG,
                                    "Unhandled server message: $text"
                                )
                            }
                        }

                    } catch (exception: Exception) {

                        Log.e(
                            TAG,
                            "Invalid server message",
                            exception
                        )
                    }
                }

                // ------------------------------------------
                // CONNECTION FAILURE
                // ------------------------------------------

                override fun onFailure(
                    webSocket: WebSocket,
                    t: Throwable,
                    response: Response?
                ) {

                    if (this@SignalingClient.webSocket !== webSocket) {
                        return
                    }

                    Log.e(
                        TAG,
                        "WebSocket connection failed",
                        t
                    )

                    this@SignalingClient.webSocket = null
                    sessionId = null

                    onStateChanged(
                        SignalingState.Failed(
                            t.message ?: "Unable to connect to desktop."
                        )
                    )
                }

                // ------------------------------------------
                // CONNECTION CLOSED
                // ------------------------------------------

                override fun onClosed(
                    webSocket: WebSocket,
                    code: Int,
                    reason: String
                ) {

                    if (this@SignalingClient.webSocket !== webSocket) {
                        return
                    }

                    Log.d(
                        TAG,
                        "WebSocket closed: $reason"
                    )

                    this@SignalingClient.webSocket = null
                    sessionId = null

                    onStateChanged(
                        SignalingState.Disconnected
                    )
                }
            }
        )

        webSocket = socket
    }

    // --------------------------------------------------
    // SEND SIGNALING MESSAGE
    // --------------------------------------------------

    fun sendSignalingMessage(
        type: String,
        payload: JSONObject = JSONObject()
    ): Boolean {

        val socket = webSocket ?: return false
        val activeSessionId = sessionId ?: return false

        val allowedTypes = setOf(
            "SDP_ANSWER",
            "ICE_CANDIDATE",
            "SESSION_END"
        )

        if (type !in allowedTypes) {

            Log.w(
                TAG,
                "Unsupported outgoing message type: $type"
            )

            return false
        }

        val message = JSONObject(payload.toString()).apply {
            put("type", type)
            put("sessionId", activeSessionId)
        }

        if (
            socket !== webSocket ||
            !socket.send(message.toString())
        ) {

            Log.e(
                TAG,
                "Failed to send signaling message: $type"
            )

            return false
        }

        Log.d(
            TAG,
            "Sent signaling message: $type"
        )

        return true
    }

    // --------------------------------------------------
    // DISCONNECT
    // --------------------------------------------------

    @Synchronized
    fun disconnect() {

        val socket = webSocket

        webSocket = null
        sessionId = null

        socket?.close(
            1000,
            "Disconnected by user"
        )

        Log.d(
            TAG,
            "Disconnected from desktop"
        )
    }

    // --------------------------------------------------
    // GET SESSION ID
    // --------------------------------------------------

    fun getSessionId(): String? {
        return sessionId
    }
}
