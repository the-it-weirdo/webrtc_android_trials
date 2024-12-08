package com.dds.webrtc_player

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.webrtc.*

class WebRTCPlayer(
    private val context: Context,
    private val surfaceView: SurfaceViewRenderer,
    private val wsUrl: String
) {
    private val eglBase = EglBase.create()
    private lateinit var peerConnectionFactory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private var webSocket: WebSocket? = null

    private var state = -1  // Track connection state
    private var videoCodec = ""  // Store codec info from server

    init {
        initializePeerConnection()
    }

    private fun initializePeerConnection() {
        // Initialize WebRTC components
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(true)
            .setInjectableLogger({ message, severity, tag ->
                Log.d("WebRTC", "[$severity] $tag: $message")
            }, Logging.Severity.LS_VERBOSE)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)

        // Create peer connection factory
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .createPeerConnectionFactory()

        // Initialize surface view
        surfaceView.init(eglBase.eglBaseContext, null)
        surfaceView.setEnableHardwareScaler(true)
    }

    fun start() {
        // Create websocket connection
        val client = OkHttpClient()
        val request = Request.Builder()
            .url(wsUrl)
            .build()

        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d("WebRTCPlayer-onMessage", text)
                handleSocketMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("WebRTCPlayer", "WebSocket error: ${t.message}")
            }
        }

        webSocket = client.newWebSocket(request, listener)
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

    fun extractPayloadType(sdpLine: String, pattern: Regex): String? {
        val result = pattern.find(sdpLine)
        return result?.groupValues?.getOrNull(1)
    }

    fun ensureSupportedProfile(codec: String, sdpLines: List<String>, mLineIndex: Int, codecPayload: String): Boolean {
        if (codec != "H264") return true

        // Server can send any profile/level H264, but SDP has to specify supported one
        for (i in mLineIndex until sdpLines.size) {
            if (sdpLines[i].startsWith("a=fmtp:$codecPayload") && sdpLines[i].contains("profile-level-id")) {
                return true
            }
        }
        return false
    }

    fun setDefaultCodec(mLine: String, payload: String): String {
        val elements = mLine.split(" ")
        val newLine = mutableListOf<String>()
        var index = 0

        for (element in elements) {
            if (index == 3) {
                newLine.add(payload)
                break
            }
            if (element != payload) {
                newLine.add(element)
                index++
            }
        }

        return newLine.joinToString(" ")
    }

    fun setCodec(sdp: String, type: String, codec: String, clockRate: Int): String {
        Log.d("setCodec-sdp default sdp received:", sdp)
        val sdpLines = sdp.split("\r\n").toMutableList()
        var mLineIndex: Int? = null

        // Find m= line index
        for (i in sdpLines.indices) {
            if (sdpLines[i].contains("m=$type")) {
                mLineIndex = i
                break
            }
        }

        if (mLineIndex == null) return sdp

        var codecPayload: String? = null
        val regex = Regex(":(\\d+) $codec/$clockRate")

        // Find codec payload
        for (i in mLineIndex until sdpLines.size) {
            if (sdpLines[i].contains("$codec/$clockRate")) {
                codecPayload = extractPayloadType(sdpLines[i], regex)
                if (codecPayload != null && ensureSupportedProfile(codec, sdpLines, mLineIndex, codecPayload)) {
                    sdpLines[mLineIndex] = setDefaultCodec(sdpLines[mLineIndex], codecPayload)
                    break
                } else {
                    Log.d("WebRtcPlayer", "$codecPayload, ${codecPayload?.let { {ensureSupportedProfile(codec, sdpLines, mLineIndex, it)} }}")
                }
            }
        }

        if (codecPayload == null) return sdp

        val rtmpmap = "a=rtpmap:"
        val rtcp = "a=rtcp-fb:"
        val fmptp = "a=fmtp:"
        val rtmpmapThis = "a=rtpmap:$codecPayload"
        val rtcpThis = "a=rtcp-fb:$codecPayload"
        val fmptpThis = "a=fmtp:$codecPayload"
        var bAddAll = false
        val resSDPLines = mutableListOf<String>()

        for (i in sdpLines.indices) {
            if (i <= mLineIndex) {
                resSDPLines.add(sdpLines[i])
            } else {
                if (sdpLines[i].startsWith("m=")) {
                    bAddAll = true
                }

                val bNotToAdd = (sdpLines[i].startsWith(rtmpmap) && !sdpLines[i].startsWith(rtmpmapThis)) ||
                        (sdpLines[i].startsWith(rtcp) && !sdpLines[i].startsWith(rtcpThis)) ||
                        (sdpLines[i].startsWith(fmptp) && !sdpLines[i].startsWith(fmptpThis))

                if (bAddAll || !bNotToAdd) {
                    resSDPLines.add(sdpLines[i])
                }
            }
        }

        val resSdp = resSDPLines.joinToString("\r\n")
        Log.d("modified sdp from setcodec: ", resSdp)
        return resSdp
    }

    private fun createAndSendOffer() {
        Log.d("WebRTCPlayer", "creating and sending offer")
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
        }

        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                var finalSdp = sdp.description

                // Replace H264 profile if needed
                if (videoCodec == "H264") {
                    finalSdp = setCodec(sdp.description, "video", videoCodec, 90000)
                }

                val modifiedSdp = SessionDescription(sdp.type, finalSdp)
                peerConnection?.setLocalDescription(this, modifiedSdp)


                val sdpJson = "\"{\\\"sdp\\\":\\\"${finalSdp.replace("\r\n", "\\\\r\\\\n")}\\\",\\\"type\\\":\\\"${sdp.type.canonicalForm()}\\\"}\""

                Log.d("WebRTC", "Sending SDP: $sdpJson")
                webSocket?.send(sdpJson)
            }

            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String) {}
            override fun onSetFailure(error: String) {}
        }, constraints)
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

    fun stop() {
        peerConnection?.dispose()
        peerConnection = null
        webSocket?.close(1000, null)
        webSocket = null
        surfaceView.release()
    }
}