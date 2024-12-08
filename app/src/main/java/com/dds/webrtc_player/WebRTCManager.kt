package com.dds.webrtc_player

import android.util.Log
import org.webrtc.*


class WebRTCManager(
    private val signalingClient: WebSocketClient
) {
    private val peerConnectionFactory: PeerConnectionFactory = PeerConnectionFactory.builder().createPeerConnectionFactory()
    private var peerConnection: PeerConnection? = null

    fun initializePeerConnection(iceServers: List<PeerConnection.IceServer>) {
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers)
        peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                signalingClient.send(candidate.toString())
            }

            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {
//                TODO("Not yet implemented")
                Log.d("WebRTCManager", "Ice candidate removed")
            }

            override fun onAddStream(mediaStream: MediaStream) {
                // Handle the remote stream
                Log.d("WebRTCManager-onAddStream", mediaStream.videoTracks.firstOrNull().toString())
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                // Handle connection state changes
            }

            override fun onDataChannel(channel: DataChannel) {}
            override fun onRemoveStream(mediaStream: MediaStream) {}
            override fun onRenegotiationNeeded() {}
            override fun onSignalingChange(newState: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) {}
        })
    }

    fun setRemoteDescription(sdp: SessionDescription) {
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {
                Log.d("WebRTCManager", "Sdp observer created successfully: $p0")
            }

            override fun onSetSuccess() {}
            override fun onCreateFailure(p0: String?) {
                Log.d("WebRTCManager", "Sdp observer creation failed: $p0")
            }

            override fun onSetFailure(error: String) {}
        }, sdp)
    }

    fun createAnswer() {
        peerConnection?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peerConnection?.setLocalDescription(this, sdp)
                signalingClient.send(sdp.toString())
            }

            override fun onSetSuccess() {
                Log.d("WebRTCManager", "sdp set success")
            }

            override fun onCreateFailure(error: String) {}
            override fun onSetFailure(p0: String?) {
                Log.d("WebRTCManager", "sdp set failure")
            }
        }, MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("offerToReceiveAudio", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("offerToReceiveAudio", "true"))
        })
    }
}
