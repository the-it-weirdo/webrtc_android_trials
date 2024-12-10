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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okio.ByteString
import org.webrtc.PeerConnection.RTCConfiguration
import org.webrtc.PeerConnectionFactory.InitializationOptions
import java.util.concurrent.ConcurrentSkipListSet


class WebRTCPlayer(val context: Context, val url:String, val videoSink: VideoSink): WebSocketListener() {

    private fun sendSocketMessage(s:String) {
        webSocketClient.send(s)
    }

    fun open() {
        if (::webSocketClient.isInitialized)
            webSocketClient.cancel()

        webSocketClient = OkHttpClient.Builder().build().newWebSocket(
            Request.Builder().url(url).build(), this)
    }

    private val TAG = "WssDataStreamCollector"

    private lateinit var  webSocketClient: WebSocket

    var pc: PeerConnection? = null;
    var pcF: PeerConnectionFactory? = null
    var state = -1;
    var audioCodec = "";
    var videoCodec = "H264";
    var firstAttempt = true;
    var connOK = false;
    val iceCandidates = mutableListOf<IceCandidate>()

    var firstResponse: String = ""

    private val wssData = ConcurrentSkipListSet<ByteString>()

    private val sdpObserver = object :SdpObserver {
        override fun onCreateSuccess(p0: SessionDescription?) {
            Log.d( TAG, "onCreateSuccess: ${p0?.type} ${p0?.description}")
            onCreateOfferSuccess(p0)
        }

        override fun onSetSuccess() {
            Log.d( TAG, "SdpObserver set success")
        }

        override fun onCreateFailure(p0: String?) {
            Log.d( TAG, "SdpObserver create failure: $p0")
        }

        override fun onSetFailure(p0: String?) {
            Log.d( TAG, "SdpObserver set failure: $p0")
        }

    }

    init {
        // Initialize PeerConnectionFactory globals.
        val initializationOptions =
            InitializationOptions.builder(context)
                .setEnableInternalTracer(true)
                .setInjectableLogger({ message, severity, tag ->
                    Log.d( "RTCPeerConnection", "[$severity] $tag: $message")
                }, Logging.Severity.LS_VERBOSE)
                .createInitializationOptions()
        PeerConnectionFactory.initialize(initializationOptions)

        // Create a new PeerConnectionFactory instance.
        val options = PeerConnectionFactory.Options()
        pcF = PeerConnectionFactory.builder()
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(EglBase.create().eglBaseContext))
            .setOptions(options)
            .createPeerConnectionFactory()

        play()
    }

    fun play() {
        state = 0
        connOK = false
        open()
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        super.onMessage(webSocket, text)
        Log.d( "DDS-WssDataStreamCollector:onMessageString", "Message: $text,  ${webSocket.queueSize()} ")
        firstResponse = text
        if (text != "Alias of the live source is not recognized by Media Server.")
            openWebRtc(text)
        else
            Log.e(TAG, "message received: $text")
    }

    private fun openWebRtc(text: String) {
        Log.d( TAG, "openWebRtc: $text")
        val strAttr = text.split("|-|-|")

        connOK = true

        if (state == 0) {
            state = 1

            if (strAttr.size == 1) {
                terminate()
                Log.e( TAG, text)
            } else {
                videoCodec = strAttr[0]
                audioCodec = strAttr[1]


                val rtcConfig = RTCConfiguration(
                    arrayListOf(),
                ).apply {
                    // it's very important to use new unified sdp semantics PLAN_B is deprecated
                    sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                }

                pc = pcF?.createPeerConnection(rtcConfig, getPeerConnectionListener())

                val offerOptions = createOfferOptions(videoCodec, audioCodec)

                pc?.createOffer(sdpObserver, offerOptions)
            }
        } else {
            Log.d( TAG, "In else block of openwebrtc $text")
            if (text == "H264|-|-|") {
                state = 0
                openWebRtc(text)
            }
            if (strAttr.size == 1) {
                terminate()

                if (firstAttempt && text == "Error: Initialization of peer connection failed") {
                    firstAttempt = false
                    CoroutineScope(Dispatchers.Main).launch {
                        delay(500)
//                        openWebRtc("H264|-|-|")
//                        retry()
                        open()
                    }
                } else {
                    Log.e( TAG, "Message received was: $text")
                }
            } else {
                var serverSDP = JSONObject(strAttr[0])
                var serverEndPoint = JSONObject(strAttr[1])

                Log.d( TAG, "Server SDP: $serverSDP , \nserverEndpoint: $serverEndPoint\n${serverEndPoint.getInt("sdpMLineIndex")}")

                serverEndPoint.put("candidate", ensureValidCandidate(serverEndPoint.getString("candidate")))

                Log.d( TAG, "Validated candidate: $serverEndPoint")
                val remoteDescription = SessionDescription(SessionDescription.Type.valueOf(serverSDP.getString("type").toUpperCase()), serverSDP.getString("sdp"))
                Log.d( TAG, "Remote description: $remoteDescription")
                pc?.setRemoteDescription(object :SdpObserver {
                    override fun onCreateSuccess(p0: SessionDescription?) {
                        Log.d( TAG, "remote sdp create success $p0")
                    }

                    override fun onSetSuccess() {
                        Log.d( TAG, "remote sdp set success")
                        val candidate = IceCandidate(serverEndPoint.getString("sdpMid"), serverEndPoint.getInt("sdpMLineIndex"), serverEndPoint.getString("candidate"))
                        Log.d( TAG, "ICE candidate: $candidate")
                        pc?.addIceCandidate(candidate)
                    }

                    override fun onCreateFailure(p0: String?) {
                        Log.d( TAG, "remote sdp create failure $p0")
                    }

                    override fun onSetFailure(p0: String?) {
                        Log.d( TAG, "remote sdp set failure $p0")
                    }

                }, remoteDescription)
            }
        }


    }

    private fun getPeerConnectionListener(): PeerConnection.Observer {
        return object :PeerConnection.Observer {

            override fun onTrack(transceiver: RtpTransceiver?) {
                super.onTrack(transceiver)
                Log.d( TAG, "onTrack ${transceiver?.mediaType}, $transceiver")

            }

            override fun onSignalingChange(p0: PeerConnection.SignalingState?) {
                Log.d( TAG, "onSignalingChange: $p0")
            }

            override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {
                Log.d( TAG, "onIceConnectionChange: $p0")
            }

            override fun onIceConnectionReceivingChange(p0: Boolean) {
                Log.d( TAG, "onIceConnectionReceivingChange: $p0")
            }

            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {
                Log.d( TAG, "onIceGatheringChange: $p0")
                if (p0 == PeerConnection.IceGatheringState.COMPLETE) {
                    sendIceCandidates()
                }
            }

            override fun onIceCandidate(p0: IceCandidate?) {
                Log.d( TAG, "onIceCandidate: $p0 , ${p0?.serverUrl}")
                p0?.let { iceCandidates.add(it) }
//                p0?.let {iceCandidates.add(IceCandidate(it.sdpMid, it.sdpMLineIndex, ensureValidCandidate(it.sdp)))}
//                p0?.let { candidate ->
//                    val candidateObj = JSONObject().apply {
//                        put("type", "candidate")
//                        put("sdpMid", candidate.sdpMid)
//                        put("sdpMLineIndex", candidate.sdpMLineIndex)
//                        put("candidate", candidate.sdp)
//                    }
//                    sendSocketMessage(candidateObj.toString())
//                }
//                val candidate = ensureValidCandidate(p0?.sdp?:"")
////                pc?.addIceCandidate(IceCandidate(p0?.sdpMid, p0?.sdpMLineIndex ?: 0, candidate))
////                pc?.addIceCandidate(IceCandidate(p0?.sdpMid, p0?.sdpMLineIndex?:0, candidate))
////                pc?.addIceCandidate(p0)
//                val obj = JSONObject().apply {
//                    put("candidate", p0?.sdp)
//                    put("sdpMid", p0?.sdpMid)
//                    put("sdpMLineIndex", p0?.sdpMLineIndex)
//                    put("type", "candidate")
//                }
//                sendSocketMessage(obj.toString())
//                sendSocketMessage("3 ${p0?.sdpMid}:${p0?.sdpMLineIndex}:${candidate}")
//                pc?.setRemoteDescription(sdpObserver, SessionDescription(SessionDescription.Type.valueOf(serverSDP.getString("type").toUpperCase()), serverSDP.getString("sdp")))
            }

            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {
                Log.d( TAG, "onIceCandidatesRemoved: $p0")
            }

            override fun onAddStream(p0: MediaStream?) {
                Log.d( TAG, "onAddStream: $p0")
                p0?.videoTracks?.firstOrNull()?.addSink(videoSink)
            }

            override fun onRemoveStream(p0: MediaStream?) {
                Log.d( TAG, "onRemoveStream: $p0")
            }

            override fun onDataChannel(p0: DataChannel?) {
                Log.d( TAG, "onDataChannel: $p0")
            }

            override fun onRenegotiationNeeded() {
                Log.d( TAG, "onRenegotiationNeeded")
            }

            override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {
                Log.d( TAG, "onAddTrack: $p0 , $p1")
                p1?.firstOrNull()?.videoTracks?.firstOrNull()?.addSink(videoSink)
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                Log.d(TAG, " new Peer connection state $newState")
            }

            override fun onIceCandidateError(event: IceCandidateErrorEvent?) {
                super.onIceCandidateError(event)
                Log.d(TAG, "Ice candidate error ${event?.url} ${event?.errorText}")
            }

        }
    }

    private fun createOfferOptions(videoCodec: String, audioCodec: String): MediaConstraints {
        Log.d( TAG, "createOfferOptions: $videoCodec, $audioCodec")
        return when {
            videoCodec.isNotEmpty() && audioCodec.isNotEmpty() -> {
                MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
                }
            }
            videoCodec.isNotEmpty() -> {
                MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
                }
            }
            audioCodec.isNotEmpty() -> {
                MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
                }
            }
            else -> throw IllegalArgumentException("Invalid codec configuration")
        }
    }

    private fun validateIPAddress(ipAddr: String): Boolean {
        val regex = """^(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\.(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\.(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\.(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$"""
        return ipAddr.matches(Regex(regex))
    }


    private fun ensureValidCandidate(candidate: String): String {
        Log.d( TAG, "Candidate validation: $candidate")
        val ipAddress = "uhlsd01.securecomwireless.com"

        if (candidate.contains(ipAddress) || ipAddress == "127.0.0.1") {
            return candidate
        }

        val candLines = candidate.split(" ").toMutableList()
        var ipIndex = 4;
        for (i in 0..candLines.size) {
            if (candLines[i] == "typ") {
                ipIndex = i - 2
                break
            }
        }

        candLines[ipIndex] = ipAddress
        candLines[ipIndex-2] = "tcp"
        return candLines.joinToString(" ")
    }

    private fun terminate() {
        state = -1

        if (pc != null) {
            pc?.close()
            pc = null
        }
    }

    private fun onCreateOfferSuccess(description: SessionDescription?) {
        var desc = description?.description ?: ""
        var lines = description?.description?.split("\r\n")?.joinToString("\n") ?: ""
        Log.d( TAG, "onCreateOfferSuccess: $lines")
//        var audioRate = 8000;
//        if (audioCodec == "opus")
//            audioRate = 48000
//
//        if (audioCodec.isNotEmpty()) {
//            desc = setCodec(desc, "audio", audioCodec, audioRate.toString())
//        }
        if(videoCodec.isNotEmpty()) {
            lines = setCodec(lines, "video", videoCodec, "90000")
        }

        lines = lines.replace("a=sendrecv", "a=recvonly")
        lines = lines.replace("a=sendrecv", "a=recvonly")

        lines = lines.replace("a=extmap-allow-mixed\\r\\n", "")
        lines = lines.replace("a=extmap-allow-mixed", "")

        lines = lines.replace("a=ice-options:trickle renomination", "a=ice-options:trickle")

//        lines = lines.replace("a=rtpmap:100 H264/90000", "a=rtpmap:102 H264/90000")
//        lines = lines.replace("a=rtcp-fb:100 goog-remb", "a=rtcp-fb:102 goog-remb")
//        lines = lines.replace("a=rtcp-fb:100 transport-cc", "a=rtcp-fb:102 transport-cc")
//        lines = lines.replace("a=rtcp-fb:100 ccm fir", "a=rtcp-fb:102 ccm fir")
//        lines = lines.replace("a=rtcp-fb:100 nack", "a=rtcp-fb:102 nack")
//        lines = lines.replace("a=rtcp-fb:100 nack pli", "a=rtcp-fb:102 nack pli")
//        lines = lines.replace("a=fmtp:100 level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=42e01f", "a=fmtp:102 level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=42001f")

//        val sdpJson = "\"{\\\"sdp\\\":\\\"${desc.replace("\r\n", "\\\\r\\\\n")}\\\",\\\"type\\\":\\\"${description?.type?.canonicalForm()}\\\"}\""

        Log.d( TAG, "Before $lines")
        val sdpJson = desc.replace("\\r", "")
//        Log.d( TAG, "After $sdpJson")

        val modifiedSdp = SessionDescription(SessionDescription.Type.OFFER, lines.replace("\r\n\r\n", "\r\n"))

        val message = JSONObject()
        message.put("sdp", modifiedSdp.description)
        message.put("type", modifiedSdp.type.canonicalForm())
        sendSocketMessage(message.toString())

        Log.d( TAG, "Trying to set local description with $modifiedSdp")
        pc?.setLocalDescription(object :SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {
                Log.d( TAG, "local offer description created $p0")
            }

            override fun onSetSuccess() {
                Log.d( TAG, "local offer description set")

            }

            override fun onCreateFailure(p0: String?) {
                Log.d( TAG, "local offer create failure $p0")
            }

            override fun onSetFailure(p0: String?) {
                Log.d( TAG, "local offer set failure $p0")
            }

        }, modifiedSdp)
    }

    private fun setCodec(sdp:String, type: String, codec: String, clockRate:String): String {
        val sdpLines = sdp.split("\n").toMutableList()


        var mLineIndex = -1
        for(i in 0..<sdpLines.size) {
            if (sdpLines[i].contains("m=$type")) {
                mLineIndex = i
            }
        }

        var codecPayload: String? = null
        val re = Regex(":(\\d+) $codec/$clockRate")

        for (i in mLineIndex until sdpLines.size) {
            if (sdpLines[i].contains("$codec/$clockRate")) {
                codecPayload = extractPayloadType(sdpLines[i], re)
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

        val retSdp = resSDPLines.joinToString("\r\n")
        return retSdp

    }

    private fun extractPayloadType(sdpLine: String, pattern: Regex): String? {
        val result = pattern.find(sdpLine)
        return if (result != null && result.groupValues.size == 2) result.groupValues[1] else null
    }

    private fun ensureSupportedProfile(codec: String, sdpLines: List<String>, mLineIndex: Int, codecPayload: String): Boolean {
        if (codec != "H264") return true

        // Server can send any profile/level H264, but SDP has to specify the supported one
        for (i in mLineIndex until sdpLines.size) {
            if (sdpLines[i].startsWith("a=fmtp:$codecPayload") && sdpLines[i].contains("profile-level-id=42")) {
                return true
            }
        }

        return false
    }

    private fun setDefaultCodec(mLine: String, payload: String): String {
        val elements = mLine.split(" ")
        val newLine = mutableListOf<String>()
        var index = 0

        for (element in elements) {
            if (index == 3) {
                newLine.add(payload)
                break
            }
            if (element != payload) newLine.add(element)
            index++
        }

        return newLine.joinToString(" ")
    }

    fun sendIceCandidates() {
        Log.d( TAG, "Sending ICE candidates")
        iceCandidates.forEach { candidate ->
            val candidateObj = JSONObject().apply {
                put("type", "candidate")
                put("sdpMid", candidate.sdpMid)
                put("sdpMLineIndex", candidate.sdpMLineIndex)
                put("candidate", candidate.sdp)
            }
            sendSocketMessage(candidateObj.toString())
        }
    }

}