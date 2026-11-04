
package com.mahanaim.cam.network

import android.content.Context
import android.util.Log

import org.json.JSONObject
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

private const val TAG = "MahanaimCAM/WebRTC"

class WebRTCManager(
    context: Context,
    private val signalingClient: SignalingClient,
    private val onStatusChanged: (String) -> Unit = {}
) {

    private val appContext = context.applicationContext

    private var eglBase: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null

    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var cameraCapturer: CameraVideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null

    private var remoteDescriptionSet = false

    private val pendingIceCandidates =
        mutableListOf<IceCandidate>()

    @Volatile
    private var disposed = false

    // --------------------------------------------------
    // INITIALIZE WEBRTC
    // --------------------------------------------------

    @Synchronized
    fun initialize(): Boolean {
        if (factory != null) return true

        return try {
            Log.d(TAG, "Initializing WebRTC")

            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions
                    .builder(appContext)
                    .createInitializationOptions()
            )

            eglBase = EglBase.create()

            val encoderFactory = DefaultVideoEncoderFactory(
                eglBase!!.eglBaseContext,
                true,
                true
            )

            val decoderFactory = DefaultVideoDecoderFactory(
                eglBase!!.eglBaseContext
            )

            factory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
                .createPeerConnectionFactory()

            Log.d(TAG, "WebRTC initialized successfully")

            true
        } catch (exception: Exception) {
            Log.e(TAG, "WebRTC initialization failed", exception)

            onStatusChanged("WebRTC initialization failed")

            release()

            false
        }
    }

    // --------------------------------------------------
    // HANDLE DESKTOP SIGNALING MESSAGES
    // --------------------------------------------------

    fun handleSignalingMessage(message: JSONObject) {
        if (disposed) return

        when (message.optString("type")) {

            "SDP_OFFER" -> {
                val sdp = message.optString("sdp")

                if (sdp.isBlank()) {
                    Log.e(TAG, "Received empty SDP offer")
                    onStatusChanged("Invalid desktop offer")
                    return
                }

                handleOffer(sdp)
            }

            "ICE_CANDIDATE" -> {
                handleRemoteIceCandidate(message)
            }

            "SESSION_END" -> {
                Log.d(TAG, "Desktop ended the session")
                stop()
            }
        }
    }

    // --------------------------------------------------
    // CREATE CAMERA AND PEER CONNECTION
    // --------------------------------------------------

    private fun handleOffer(sdp: String) {
        if (disposed) return

        if (!initialize()) return

        if (peerConnection != null) {
            Log.w(TAG, "Ignoring duplicate SDP offer")
            return
        }

        onStatusChanged("Preparing camera...")

        if (!startCamera()) {
            onStatusChanged("Unable to start camera")
            return
        }

        val peerFactory = factory ?: return

        val configuration = PeerConnection.RTCConfiguration(
            emptyList()
        ).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            iceTransportsType = PeerConnection.IceTransportsType.ALL
        }

        val observer = createPeerConnectionObserver()

        peerConnection = peerFactory.createPeerConnection(
            configuration,
            observer
        )

        val connection = peerConnection

        if (connection == null) {
            Log.e(TAG, "Unable to create PeerConnection")
            onStatusChanged("Unable to create video connection")
            return
        }

        val track = videoTrack

        if (track == null) {
            onStatusChanged("Camera video track unavailable")
            return
        }

        val sender = connection.addTrack(
            track,
            listOf("mahanaim-stream")
        )

        if (sender == null) {
            Log.e(TAG, "Unable to add video track")
            onStatusChanged("Unable to add camera video track")
            return
        }

        // Configure video bitrate.
        try {
            val parameters = sender.parameters

            parameters.encodings.forEach {
                it.maxBitrateBps = 2_000_000
                it.maxFramerate = 30
            }

            sender.parameters = parameters
        } catch (exception: Exception) {
            Log.w(TAG, "Unable to configure video bitrate", exception)
        }

        onStatusChanged("Processing desktop offer...")

        val remoteOffer = SessionDescription(
            SessionDescription.Type.OFFER,
            sdp
        )

        connection.setRemoteDescription(
            object : SdpObserver {

                override fun onCreateSuccess(description: SessionDescription?) {}

                override fun onSetSuccess() {
                    Log.d(TAG, "Remote SDP offer applied")

                    if (disposed) return

                    remoteDescriptionSet = true

                    applyPendingIceCandidates()

                    createAnswer(connection)
                }

                override fun onCreateFailure(error: String?) {
                    Log.e(TAG, "Remote SDP operation failed: $error")
                    onStatusChanged("Failed to process desktop offer")
                }

                override fun onSetFailure(error: String?) {
                    Log.e(TAG, "Failed to set remote SDP: $error")
                    onStatusChanged("Failed to apply desktop offer")
                }
            },
            remoteOffer
        )
    }

    // --------------------------------------------------
    // CREATE SDP ANSWER
    // --------------------------------------------------

    private fun createAnswer(connection: PeerConnection) {
        if (disposed) return

        val constraints = MediaConstraints()

        connection.createAnswer(
            object : SdpObserver {

                override fun onCreateSuccess(description: SessionDescription?) {
                    if (disposed || description == null) return

                    Log.d(TAG, "SDP answer created")

                    connection.setLocalDescription(
                        object : SdpObserver {

                            override fun onCreateSuccess(description: SessionDescription?) {}

                            override fun onSetSuccess() {
                                if (disposed) return

                                Log.d(TAG, "Local SDP description applied")

                                val payload = JSONObject().apply {
                                    put("sdp", descriptionOrEmpty(connection))
                                }

                                val sent = signalingClient.sendSignalingMessage(
                                    "SDP_ANSWER",
                                    payload
                                )

                                if (sent) {
                                    onStatusChanged("Video stream starting...")
                                } else {
                                    onStatusChanged("Unable to send SDP answer")
                                }
                            }

                            override fun onCreateFailure(error: String?) {
                                Log.e(TAG, "Local SDP creation failed: $error")
                            }

                            override fun onSetFailure(error: String?) {
                                Log.e(TAG, "Unable to set local SDP: $error")
                                onStatusChanged("Failed to configure video connection")
                            }
                        },
                        description
                    )
                }

                override fun onSetSuccess() {}

                override fun onCreateFailure(error: String?) {
                    Log.e(TAG, "Unable to create SDP answer: $error")
                    onStatusChanged("Failed to create video response")
                }

                override fun onSetFailure(error: String?) {}
            },
            constraints
        )
    }

    private fun descriptionOrEmpty(connection: PeerConnection): String {
        return connection.localDescription?.description ?: ""
    }

    // --------------------------------------------------
    // START CAMERA CAPTURE
    // --------------------------------------------------

    private fun startCamera(): Boolean {
        return try {
            val peerFactory = factory ?: return false
            val eglContext = eglBase?.eglBaseContext ?: return false

            val enumerator = Camera2Enumerator(appContext)

            val cameraName = enumerator.deviceNames.firstOrNull {
                enumerator.isBackFacing(it)
            } ?: enumerator.deviceNames.firstOrNull {
                enumerator.isFrontFacing(it)
            } ?: run {
                Log.e(TAG, "No available camera found")
                return false
            }

            val capturer = enumerator.createCapturer(
                cameraName,
                null
            ) ?: run {
                Log.e(TAG, "Unable to create camera capturer")
                return false
            }

            cameraCapturer = capturer

            surfaceTextureHelper = SurfaceTextureHelper.create(
                "MahanaimCameraThread",
                eglContext
            )

            videoSource = peerFactory.createVideoSource(false)

            capturer.initialize(
                surfaceTextureHelper,
                appContext,
                videoSource!!.capturerObserver
            )

            capturer.startCapture(
                1280,
                720,
                30
            )

            videoTrack = peerFactory.createVideoTrack(
                "mahanaim-camera-track",
                videoSource
            )

            Log.d(
                TAG,
                "Camera started: $cameraName at 1280x720, 30 FPS"
            )

            onStatusChanged("Camera active")

            true
        } catch (exception: Exception) {
            Log.e(TAG, "Camera startup failed", exception)
            false
        }
    }

    // --------------------------------------------------
    // PEER CONNECTION OBSERVER
    // --------------------------------------------------

    private fun createPeerConnectionObserver(): PeerConnection.Observer {

        return object : PeerConnection.Observer {

            override fun onSignalingChange(
                state: PeerConnection.SignalingState
            ) {
                Log.d(TAG, "Signaling state: $state")
            }

            override fun onIceConnectionChange(
                state: PeerConnection.IceConnectionState
            ) {
                Log.d(TAG, "ICE connection state: $state")

                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> {
                        onStatusChanged("Video connected")
                    }

                    PeerConnection.IceConnectionState.FAILED -> {
                        onStatusChanged("Video connection failed")
                    }

                    PeerConnection.IceConnectionState.DISCONNECTED -> {
                        onStatusChanged("Video connection interrupted")
                    }

                    else -> Unit
                }
            }

            override fun onIceConnectionReceivingChange(
                receiving: Boolean
            ) {
                Log.d(TAG, "ICE receiving: $receiving")
            }

            override fun onIceGatheringChange(
                state: PeerConnection.IceGatheringState
            ) {
                Log.d(TAG, "ICE gathering state: $state")
            }

            override fun onIceCandidate(candidate: IceCandidate) {
                if (disposed) return

                val payload = JSONObject().apply {
                    put("candidate", candidate.sdp)
                    put("sdpMid", candidate.sdpMid ?: "")
                    put("sdpMLineIndex", candidate.sdpMLineIndex)
                }

                val sent = signalingClient.sendSignalingMessage(
                    "ICE_CANDIDATE",
                    payload
                )

                if (!sent) {
                    Log.w(TAG, "Unable to send ICE candidate")
                }
            }

            override fun onIceCandidatesRemoved(
                candidates: Array<out IceCandidate>
            ) {
                Log.d(TAG, "ICE candidates removed")
            }

            override fun onAddStream(stream: org.webrtc.MediaStream) {}

            override fun onRemoveStream(stream: org.webrtc.MediaStream) {}

            override fun onDataChannel(channel: org.webrtc.DataChannel) {}

            override fun onRenegotiationNeeded() {
                Log.d(TAG, "Renegotiation requested")
            }

            override fun onAddTrack(
                receiver: org.webrtc.RtpReceiver,
                streams: Array<out org.webrtc.MediaStream>
            ) {}

            override fun onTrack(
                transceiver: org.webrtc.RtpTransceiver
            ) {}
        }
    }

    // --------------------------------------------------
    // RECEIVE REMOTE ICE CANDIDATES
    // --------------------------------------------------

    private fun handleRemoteIceCandidate(message: JSONObject) {

        val candidateText = message.optString("candidate")

        if (candidateText.isBlank()) return

        val candidate = IceCandidate(
            message.optString("sdpMid"),
            message.optInt("sdpMLineIndex"),
            candidateText
        )

        val connection = peerConnection

        if (!remoteDescriptionSet || connection == null) {
            synchronized(pendingIceCandidates) {
                pendingIceCandidates.add(candidate)
            }

            Log.d(TAG, "ICE candidate queued")

            return
        }

        val added = connection.addIceCandidate(candidate)

        if (!added) {
            Log.w(TAG, "Unable to add remote ICE candidate")
        }
    }

    private fun applyPendingIceCandidates() {

        val connection = peerConnection ?: return

        val candidates = synchronized(pendingIceCandidates) {
            val copy = pendingIceCandidates.toList()
            pendingIceCandidates.clear()
            copy
        }

        candidates.forEach { candidate ->
            val added = connection.addIceCandidate(candidate)

            if (!added) {
                Log.w(TAG, "Unable to apply queued ICE candidate")
            }
        }

        Log.d(TAG, "Applied ${candidates.size} queued ICE candidates")
    }

    // --------------------------------------------------
    // STOP STREAMING
    // --------------------------------------------------

    @Synchronized
    fun stop() {
        if (disposed) return

        Log.d(TAG, "Stopping WebRTC")

        release()

        onStatusChanged("Video streaming stopped")
    }

    // --------------------------------------------------
    // RELEASE RESOURCES
    // --------------------------------------------------

    @Synchronized
    fun release() {
        disposed = true

        try {
            cameraCapturer?.stopCapture()
        } catch (exception: Exception) {
            Log.w(TAG, "Camera stop error", exception)
        }

        cameraCapturer?.dispose()
        cameraCapturer = null

        peerConnection?.close()
        peerConnection?.dispose()
        peerConnection = null

        videoTrack?.dispose()
        videoTrack = null

        videoSource?.dispose()
        videoSource = null

        surfaceTextureHelper?.dispose()
        surfaceTextureHelper = null

        factory?.dispose()
        factory = null

        eglBase?.release()
        eglBase = null

        remoteDescriptionSet = false

        synchronized(pendingIceCandidates) {
            pendingIceCandidates.clear()
        }

        Log.d(TAG, "WebRTC resources released")
    }
}
