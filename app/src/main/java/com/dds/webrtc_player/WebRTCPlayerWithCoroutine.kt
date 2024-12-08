package com.dds.webrtc_player

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.webrtc.*
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class WebRTCPlayerWithCoroutine(
    private val context: Context,
    private val surfaceView: SurfaceViewRenderer,
    private val wsUrl: String,
    private val scope: CoroutineScope = MainScope()
) {
    private val eglBase = EglBase.create()
    private lateinit var peerConnectionFactory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private var webSocket: WebSocket? = null

    private var state = -1  // Track connection state
    private var videoCodec = ""  // Store codec info from server

    private val messageHandlers = mutableListOf<(String) -> Unit>()

    init {
        initializePeerConnection()
    }

    private fun initializePeerConnection() {
        // Initialize WebRTC components
        val initializationOptions = PeerConnectionFactory.InitializationOptions.builder(context)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initializationOptions)

        // Create peer connection factory
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .createPeerConnectionFactory()

        // Initialize surface view
        surfaceView.init(eglBase.eglBaseContext, null)
        surfaceView.setEnableHardwareScaler(true)
    }

    fun start() = scope.launch {
        try {
            // Set a timeout for the whole connection process
            withTimeout(30_0000) {
                connectWebSocket()
                // Wait for initial codec message
                val codec = waitForCodec()
                if (codec == "H264") {
                    createPeerConnection()
                    createAndSendOffer()
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.e("WebRTC", "Connection timed out")
        }
    }

    private fun createPeerConnection() {
        val rtcConfig = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }

        val observer = object : PeerConnection.Observer {
            override fun onAddStream(stream: MediaStream?) {
                stream?.videoTracks?.firstOrNull()?.addSink(surfaceView)
            }
            // Implement other required methods...
            override fun onIceCandidate(candidate: IceCandidate?) {}
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {

            }

            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                when(state) {
                    PeerConnection.IceConnectionState.CONNECTED -> Log.d("WebRTCPlayer", "PeerConnection connected")
                    PeerConnection.IceConnectionState.NEW -> Log.d("WebRTCPlayer", "PeerConnection New")
                    PeerConnection.IceConnectionState.CLOSED -> Log.d("WebRTCPlayer", "PeerConnection Closed")
                    PeerConnection.IceConnectionState.COMPLETED -> Log.d("WebRTCPlayer", "PeerConnection Completed")
                    PeerConnection.IceConnectionState.CHECKING -> Log.d("WebRTCPlayer", "PeerConnection Checking")
                    PeerConnection.IceConnectionState.DISCONNECTED -> Log.d("WebRTCPlayer", "PeerConnection disconnected")
                    PeerConnection.IceConnectionState.FAILED -> Log.d("WebRTCPlayer", "PeerConnection failed")
                    null -> Log.d("WebRTCPlayer", "PeerConnection null")
                }
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(channel: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
        }

        peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, observer)
    }

    private suspend fun connectWebSocket() = suspendCancellableCoroutine<Unit> { continuation ->
        val client = OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder()
            .url(wsUrl)
            .build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                continuation.resume(Unit)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (continuation.isActive) {
                    continuation.resumeWithException(t)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleSocketMessage(text)
//                messageHandlers.forEach { handler -> handler(text) }
            }
        }

        webSocket = client.newWebSocket(request, listener)

        continuation.invokeOnCancellation {
            webSocket?.close(1000, null)
        }
    }

    private fun handleSocketMessage(message: String) {
        val parts = message.split("|-|-|")

        when (state) {
            -1 -> {
                // Initial message with codec info
                if (parts[0] == "H264") {
                    videoCodec = "H264"
                    state = 0
                    createPeerConnection()
                    createAndSendOffer()
                }
            }
            0 -> {
                // Handle server response with SDP and candidate
                if (parts.size == 2) {
                    val serverSdp = JSONObject(parts[0])
                    val serverCandidate = JSONObject(parts[1])
                    handleServerResponse(serverSdp, serverCandidate)
                }
            }
        }
    }

    private fun handleServerResponse(serverSdp: JSONObject, serverCandidate: JSONObject) {
        val remoteSdp = SessionDescription(
            SessionDescription.Type.ANSWER,
            serverSdp.getString("sdp")
        )

        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                val candidate = IceCandidate(
                    serverCandidate.getString("sdpMid"),
                    serverCandidate.getInt("sdpMLineIndex"),
                    serverCandidate.getString("candidate")
                )
                peerConnection?.addIceCandidate(candidate)
            }
            override fun onSetFailure(error: String) {}
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(error: String) {}
        }, remoteSdp)
    }

    private suspend fun waitForCodec() = suspendCancellableCoroutine<String> { continuation ->
        val messageHandler = { message: String ->
            val parts = message.split("|-|-|")
            if (parts[0] == "H264") {
                continuation.resume("H264")
            } else {
                continuation.resumeWithException(Exception("Unsupported codec"))
            }
        }

        // Store the handler to remove it later
        messageHandlers.add(messageHandler)

        continuation.invokeOnCancellation {
            messageHandlers.remove(messageHandler)
        }
    }

    // The offer creation can stay callback-based since it's a WebRTC native pattern
    private fun createAndSendOffer() {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("offerToReceiveVideo", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("offerToReceiveAudio", "false"))
        }

        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
//                val modifiedSdp = modifySdp(sdp)
                peerConnection?.setLocalDescription(this, sdp)

                val sdpJson = "{\"sdp\":\"${sdp.description.replace("\n", "\\r\\n")}\",\"type\":\"${sdp.type.canonicalForm()}\"}"
                webSocket?.send(sdpJson)
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String) {}
            override fun onSetFailure(error: String) {}
        }, constraints)
    }

    fun stop() {
//        scope.cancel() // Cancel any ongoing coroutines
        peerConnection?.dispose()
        peerConnection = null
        webSocket?.close(1000, null)
        webSocket = null
        surfaceView.release()
    }
}