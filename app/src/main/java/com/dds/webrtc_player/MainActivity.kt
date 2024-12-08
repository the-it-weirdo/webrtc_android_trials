package com.dds.webrtc_player

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.webrtc.*

class MainActivity : AppCompatActivity() {
    private lateinit var surfaceView: SurfaceViewRenderer
    private val eglBase = EglBase.create()
    private lateinit var peerConnectionFactory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private var webSocket: WebSocket? = null

    private var state = -1
    private var audioCodec = ""
    private var videoCodec = ""
    private var latestStopTime = 0L
    private var connOK = false
    private var firstAttempt = true

    // Configuration properties // https://unrealstreaming.net:8443/UnrealWebRTCPublishingDemo/publish.aspx
    private val ipAddress: String = "unrealstreaming.net"
    private val port: Int = 444
    private val alias: String = "322c4988-ada5-4344-a9de-245d399c7c22"
    private val sid: String = ""  // Optional session ID
    private val useSecureWebsocket: Boolean = true
    private val useSingleWebRTCPort: Boolean = true
    private val webRTCProtocol: String = "webrtc-v1"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize SurfaceViewRenderer
        surfaceView = findViewById(R.id.surface_view_renderer)
        surfaceView.init(eglBase.eglBaseContext, null)
        surfaceView.setMirror(false)
        surfaceView.setEnableHardwareScaler(true)

        startActivity(Intent(this, WebViewRTC::class.java))

//        initializePeerConnectionFactory()

        // Start playing when everything is initialized
//        play()
    }

    private fun initializePeerConnectionFactory() {
        val options = PeerConnectionFactory.InitializationOptions.builder(applicationContext)
            .setEnableInternalTracer(true)
            .setInjectableLogger({ message, severity, tag ->
                Log.d("WebRTC", "[$severity] $tag: $message")
            }, Logging.Severity.LS_VERBOSE)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)



        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext).apply {
                this.supportedCodecs.find { it.name.contains("H264") }?.let {
//                    this.setFallbackCodec(it)  // Set H264 as fallback codec
                    Log.d("codec from video decoder factory:", this.supportedCodecs.joinToString(", "))
                }
            })
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .createPeerConnectionFactory()

        val rtcConfig = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
        }

        peerConnection = peerConnectionFactory.createPeerConnection(
            rtcConfig,
            createPeerConnectionObserver()
        )
    }

    fun play() {
        val nowTime = System.currentTimeMillis()

        if (state == -1) {
            surfaceView.clearImage()
            state = 0
            connOK = false
            doSignaling()
        }
    }

    fun stop() {
        terminate()
    }

    private fun terminate() {
        latestStopTime = System.currentTimeMillis()
        state = -1

        peerConnection?.apply {
            dispose()
            peerConnection = null
        }

        webSocket?.apply {
            cancel()
            webSocket = null
        }
    }

    private fun doSignaling() {
        val wsUrl = buildWebSocketUrl()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                super.onOpen(webSocket, response)
//                connOK = true
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d("DDS-onSocketMessage", text)
                handleSignalingMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                showError("Error connecting to Media Server: ${response?.message}")
                if (!connOK) {
                    terminate()

                }
            }
        }

        // Initialize WebSocket connection using OkHttp
        val client = okhttp3.OkHttpClient()
        val request = okhttp3.Request.Builder()
            .url(wsUrl)
            .build()

        webSocket = client.newWebSocket(request, listener)
    }

    private fun handleSignalingMessage(message: String) {
        val strArr = message.split("|-|-|")

        connOK = true

        when (state) {
            0 -> handleInitialState(strArr)
            else -> handleConnectionState(strArr)
        }
    }

    private fun handleInitialState(strArr: List<String>) {
        state = 1

        if (strArr.size == 1) {
            terminate()
            showError(strArr[0])
            return
        }

        videoCodec = strArr[0]
        audioCodec = strArr[1]

        createOffer()
    }

    private fun createOffer() {
        val constraints = MediaConstraints().apply {
            when {
                videoCodec.isNotEmpty() && audioCodec.isNotEmpty() -> {
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
                }
                videoCodec.isNotEmpty() -> {
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
                }
                audioCodec.isNotEmpty() -> {
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
                }
            }
        }


        peerConnection?.createOffer(createSdpObserver(), constraints)
    }

    private fun createPeerConnectionObserver() = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            // Do nothing as per JS implementation
            Log.d("onIceCandidate", "$candidate")
        }

        override fun onAddStream(mediaStream: MediaStream) {
            mediaStream.videoTracks.firstOrNull()?.addSink(surfaceView)
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            if (newState == PeerConnection.PeerConnectionState.FAILED) {
                terminate()
                showError("Connection failed; playback stopped")
            }
        }

        // Implement other required methods
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
        override fun onSignalingChange(state: PeerConnection.SignalingState) {}
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
        override fun onRemoveStream(stream: MediaStream?) {}
        override fun onDataChannel(dataChannel: DataChannel?) {}
        override fun onRenegotiationNeeded() {}
    }

    private fun createSdpObserver() = object : SdpObserver {
        override fun onCreateSuccess(sessionDescription: SessionDescription) {
            var sdp = sessionDescription.description

            // Apply codec preferences
            if (audioCodec.isNotEmpty()) {
                sdp = setCodec(sdp, "audio", audioCodec, if (audioCodec == "opus") 48000 else 8000)
            }
            if (videoCodec.isNotEmpty()) {
                sdp = setCodec(sdp, "video", videoCodec, 90000)
//                sdp = sdp.replace("profile-level-id=42e01f", "profile-level-id=42001f")
            }

            // Fix for recvonly
            sdp = sdp.replace("a=sendrecv", "a=recvonly")

            // Remove extmap-allow-mixed
            sdp = sdp.replace("a=extmap-allow-mixed\r\n", "")
            sdp = sdp.replace("a=extmap-allow-mixed", "")

            val modifiedDesc = SessionDescription(sessionDescription.type, sdp)
            peerConnection?.setLocalDescription(this, modifiedDesc)


            // Send to server
            val msgString = createSdpJson(modifiedDesc.description, modifiedDesc.type.canonicalForm())
            Log.d("DDS-sending sdp", msgString)
            webSocket?.send(msgString)




//            webSocket?.send(JSONObject().apply {
//                put("type", modifiedDesc.type.canonicalForm())
//                put("sdp", modifiedDesc.description)
//            }.toString())
//            webSocket?.send(jsonStringify(modifiedDesc.type.canonicalForm(), modifiedDesc.description))
        }

        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String) {
            terminate()
            showError("Failed to create session description: $error")
        }
        override fun onSetFailure(error: String) {}
    }

    fun createSdpJson(sdp: String, type: String): String {
        return "{\"sdp\":\"${sdp.replace("\n", "\\r\\n")}\",\"type\":\"$type\"}"
    }

    private fun handleConnectionState(strArr: List<String>) {
        if (strArr.size == 1) {
            terminate()

            // Handle Android-specific retry logic
            if (firstAttempt && strArr[0] == "Error: Initialization of peer connection failed") {
                firstAttempt = false
                latestStopTime = 0
                play()
            } else {
                showError(strArr[0])
            }
            return
        }

        val serverSdp = JSONObject(strArr[0])
        val serverEndpoint = JSONObject(strArr[1])

        val remoteSdp = SessionDescription(
            SessionDescription.Type.fromCanonicalForm(serverSdp.getString("type")),
            serverSdp.getString("sdp")
        )

        val candidate = IceCandidate(
            serverEndpoint.getString("sdpMid"),
            serverEndpoint.getInt("sdpMLineIndex"),
            ensureValidCandidate(serverEndpoint.getString("candidate"))
        )

        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                peerConnection?.addIceCandidate(candidate)
            }
            override fun onSetFailure(error: String) {}
            override fun onCreateSuccess(desc: SessionDescription?) {}
            override fun onCreateFailure(error: String) {}
        }, remoteSdp)

        webSocket?.close(1000, null)
        webSocket = null
    }

    fun ensureValidCandidate(candidate: String): String {
        if (candidate.contains(ipAddress) || !useSingleWebRTCPort || ipAddress == "127.0.0.1") {
            return candidate
        }

        // In case the server is behind the NAT router, replace private IP with public IP in the candidate
        val candLines = candidate.split(" ").toMutableList()
        var ipIndex = 4

        for (i in candLines.indices) {
            if (candLines[i] == "typ") {
                ipIndex = i - 2
                break
            }
        }

        candLines[ipIndex] = ipAddress
        return candLines.joinToString(" ")
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

    fun extractPayloadType(sdpLine: String, pattern: Regex): String? {
        val result = pattern.find(sdpLine)
        return result?.groupValues?.getOrNull(1)
    }

    fun ensureSupportedProfile(codec: String, sdpLines: List<String>, mLineIndex: Int, codecPayload: String): Boolean {
        if (codec != "H264") return true

        // Server can send any profile/level H264, but SDP has to specify supported one
        for (i in mLineIndex until sdpLines.size) {
            if (sdpLines[i].startsWith("a=fmtp:$codecPayload") && sdpLines[i].contains("profile-level-id=42")) {
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

    private fun buildWebSocketUrl(): String {
        val centralWebRTCPort = if (useSingleWebRTCPort) "singleport/" else "randomport/"
        val protocol = if (useSecureWebsocket) "wss://" else "ws://"

        var url = "$protocol$ipAddress:$port/webrtc_playnow/$centralWebRTCPort$webRTCProtocol/$alias"
        if (sid.isNotEmpty()) {
            url += "/sid:$sid"
        }
        return "wss://unrealstreaming.net:444/webrtc_playnow/singleport/tcp/0f7cb3d8-a9dd-4b85-b427-0c8d561d580b"
    }

    private fun showError(message: String) {
        Log.e("WebRTCPlayer", message)
        // Implement your error handling UI logic here
    }

    override fun onResume() {
        super.onResume()
//        play()
    }

    override fun onDestroy() {
        super.onDestroy()
//        terminate()
        surfaceView.release()
        eglBase.release()
//        peerConnectionFactory.dispose()
    }
}