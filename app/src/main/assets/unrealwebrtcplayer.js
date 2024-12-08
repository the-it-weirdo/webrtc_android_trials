// UnrealWebRTCPlayer - Copyright 2017-2021 Unreal Streaming Technologies
// Version 1.5 - May 10 2019. Added support for overlayed Play button.
// Version 2.0 - September 7 2020. Temporary fix for Androids until Google fixes https://bugs.chromium.org/p/chromium/issues/detail?id=1047994
// Version 2.5 - January 7 2021. Fix for a=extmap-allow-mixed that Chrome introduced in SDP in Chrome 89 https://bugs.chromium.org/p/chromium/issues/detail?id=908377

function UnrealWebRTCPlayer(videoID, alias, sid, ipAddress, port, useSecureWebsocket, useSingleWebRTCPort, WebRTCProtocol) {
    "use strict";

    var pc = null;
    var ws = null;
    var state = -1;
    var audioCodec = "";
    var videoCodec = "";
    var latestStopTime = 0;
    var connOK = false;
    var remoteVideo = document.getElementById(videoID);
    var showControls = remoteVideo.controls;
    var firstAttempt = true;
    var that = this;

    this.Play = function Play() {
        var nowTime = new Date().getTime();

        if ((state == -1) && (nowTime - latestStopTime > 1000)) {
            remoteVideo.srcObject = null;
            state = 0;
            connOK = false;
            ToggleOverlay(false);
            remoteVideo.setAttribute('style', 'background: black url(loader.gif) center no-repeat;');
            showControls = remoteVideo.controls;
            remoteVideo.controls = false;
            DoSignaling();
        }

        if (nowTime - latestStopTime <= 1000)
            remoteVideo.pause();
    }

    this.Stop = function Stop() {
        showControls = remoteVideo.controls;
        Terminate();
    }

    remoteVideo.onplay = this.Play;
    remoteVideo.onpause = this.Stop;

    function Terminate() {
        latestStopTime = new Date().getTime();

        state = -1;
        ToggleOverlay(true);
        remoteVideo.setAttribute('style', 'background-color:black');

        if (showControls)
            remoteVideo.controls = true;

        if (pc != null) {
            pc.onconnectionstatechange = null;
            pc.close();
            pc = null;
        }

        if (ws != null) {
            ws.onerror = null;
            ws.close();
            ws = null;
        }

        remoteVideo.pause();
    }

    function onCreateSessionDescriptionError(error) {
        Terminate();
        alert("Failed to create session description: " + error.toString());
    }

    function onCreateOfferSuccess(desc) {
        var audioRate = 8000;
        if (audioCodec === "opus")
            audioRate = 48000;

        if (audioCodec != "")
            desc.sdp = setCodec(desc.sdp, "audio", audioCodec, audioRate);
        if (videoCodec != "")
            desc.sdp = setCodec(desc.sdp, "video", videoCodec, 90000);

        //Fix for some browsers and/or adapter incorrect behavior
        desc.sdp = desc.sdp.replace("a=sendrecv", "a=recvonly");
        desc.sdp = desc.sdp.replace("a=sendrecv", "a=recvonly");

        //Fix for a=extmap-allow-mixed - Unreal Media Server doesn't support it in SDP
        desc.sdp = desc.sdp.replace("a=extmap-allow-mixed\r\n", "");
        desc.sdp = desc.sdp.replace("a=extmap-allow-mixed", "");

        //Signal the SDP to the server
        var msgString = JSON.stringify(desc);
        ws.send(msgString);

        pc.setLocalDescription(desc);
    }

    function onIceCandidate(event) {
        //Do nothing! We only need one endpoint from server; browser is going to connect to it
    }

    function gotRemoteStream(e) {
        remoteVideo.srcObject = e.streams[0];

        if (videoCodec == "")
            remoteVideo.setAttribute('style', 'background-color:black');

        if (showControls)
            remoteVideo.controls = true;
    }

    function onConnStateChange(event) {
        if (pc.connectionState === "failed") {
            Terminate();
            alert("Connection failed; playback stopped");
        }
    }

    function DoSignaling() {
        var centralWebRTCPort = useSingleWebRTCPort ? "singleport/" : "randomport/";
        var URL = useSecureWebsocket ? "wss://" : "ws://";
        URL += ipAddress + ":" + port + "/webrtc_playnow/" + centralWebRTCPort + WebRTCProtocol + "/" + alias;
        if (sid != "")
            URL += "/sid:" + sid;

        console.log(URL);
        try {
            ws = new WebSocket(URL);
        }
        catch (error) {
            Terminate();
            alert("Error creating websocket: " + error);
        }

        ws.onmessage = function(evt) {
            var response = evt.data;
            console.log(response);
            var strArr = response.split("|-|-|");

            connOK = true;

            if (state == 0) {
                state = 1;

                if (strArr.length == 1) {
                    Terminate();
                    alert(response);
                }
                else {
                    var servers = null;
                    var offerOptions = null;

                    videoCodec = strArr[0];
                    audioCodec = strArr[1];

                    if ((videoCodec != "") && (audioCodec != ""))
                        offerOptions = { offerToReceiveAudio: 1, offerToReceiveVideo: 1 };
                    else if (videoCodec != "")
                        offerOptions = { offerToReceiveAudio: 0, offerToReceiveVideo: 1 };
                    else if (audioCodec != "")
                        offerOptions = { offerToReceiveAudio: 1, offerToReceiveVideo: 0 };

                    pc = new RTCPeerConnection(servers);
                    pc.onicecandidate = onIceCandidate;
                    pc.onconnectionstatechange = onConnStateChange;
                    pc.ontrack = gotRemoteStream;

                    pc.createOffer(offerOptions).then(
                      onCreateOfferSuccess,
                      onCreateSessionDescriptionError
                    );
                }
            }
            else {
                if (strArr.length == 1) {
                    Terminate();

                    //Temporary fix for androids until Google fixes https://bugs.chromium.org/p/chromium/issues/detail?id=1047994
                    if ((firstAttempt) && (response === "Error: Initialization of peer connection failed") && (navigator.userAgent.toLowerCase().indexOf("android") > -1)) {
                        firstAttempt = false;
                        latestStopTime = 0;
                        setTimeout(function () { that.Play(); }, 500);
                    }
                    else
                        alert(response);
                }
                else {
                    var serverSDP = JSON.parse(strArr[0]);
                    var serverEndpoint = JSON.parse(strArr[1]);

                    serverEndpoint.candidate = EnsureValidCandidate(serverEndpoint.candidate);

                    pc.setRemoteDescription(new RTCSessionDescription(serverSDP));
                    var candidate = new RTCIceCandidate({ sdpMLineIndex: serverEndpoint.sdpMLineIndex, candidate: serverEndpoint.candidate });
                    pc.addIceCandidate(candidate);

                    ws.close();
                    ws = null;
                }
            }
        }

        ws.onerror = function(evt) {
            console.log(JSON.stringify(evt));
            if (!connOK) {
                Terminate();
                alert("Error connecting to Unreal Media Server");
            }
            console.log("Calling play")
            DoSignaling();
        }
    }

    function EnsureValidCandidate(candidate) {
        if ((candidate.search(ipAddress) !== -1) || !useSingleWebRTCPort || (ipAddress == "127.0.0.1")) {
            return candidate;
        }

        //In case the server is behind the NAT router, replace private IP with public IP in the candidate
        var candLines = candidate.split(" ");
        var ipIndex = 4;
        for (var i = 0; i < candLines.length; i++) {
            if (candLines[i] === "typ") {
                ipIndex = i - 2;
                break;
            }
        }

        candLines[ipIndex] = ipAddress;
        candidate = candLines.join(" ");
        return candidate;
    }

    function ValidateIPaddress(ipaddr) {
        if (/^(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\.(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\.(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\.(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$/.test(ipaddr)) {
            return true;
        }

        return false;
    }

    function setCodec(sdp, type, codec, clockRate) {
        var sdpLines = sdp.split("\r\n");

        for (var i = 0; i < sdpLines.length; i++) {
            if (sdpLines[i].search("m=" + type) !== -1) {
                var mLineIndex = i;
                break;
            }
        }

        if (mLineIndex === null) return sdp;

        var codecPayload = null;
        var re = new RegExp(":(\\d+) " + codec + "\/" + clockRate);

        for (var i = mLineIndex; i < sdpLines.length; i++) {
            if (sdpLines[i].search(codec + "/" + clockRate) !== -1) {
                codecPayload = extractPayloadType(sdpLines[i], re);
                if (codecPayload && (EnsureSupportedProfile(codec, sdpLines, mLineIndex, codecPayload))) {
                    sdpLines[mLineIndex] = setDefaultCodec(sdpLines[mLineIndex], codecPayload);
                    break;
                }
            }
        }

        if (codecPayload === null) return sdp;

        var rtmpmap = "a=rtpmap:";
        var rtcp = "a=rtcp-fb:";
        var fmptp = "a=fmtp:";
        var rtmpmapThis = "a=rtpmap:" + codecPayload;
        var rtcpThis = "a=rtcp-fb:" + codecPayload;
        var fmptpThis = "a=fmtp:" + codecPayload;
        var bAddAll = false;
        var resSDPLines = new Array();

        for (var i = 0; i < sdpLines.length; i++) {
            if (i <= mLineIndex) {
                resSDPLines.push(sdpLines[i]);
            }
            else {
                if (sdpLines[i].search("m=") === 0)
                    bAddAll = true;

                var bNotToAdd = ((sdpLines[i].search(rtmpmap) === 0) && (sdpLines[i].search(rtmpmapThis) !== 0)) || ((sdpLines[i].search(rtcp) === 0) && (sdpLines[i].search(rtcpThis) !== 0)) || ((sdpLines[i].search(fmptp) === 0) && (sdpLines[i].search(fmptpThis) !== 0));

                if (bAddAll || !bNotToAdd)
                    resSDPLines.push(sdpLines[i]);
            }

        }

        sdp = resSDPLines.join("\r\n");
        return sdp;
    };

    function extractPayloadType(sdpLine, pattern) {
        var result = sdpLine.match(pattern);
        return (result && result.length == 2) ? result[1] : null;
    };

    function EnsureSupportedProfile(codec, sdpLines, mLineIndex, codecPayload) {
        if (codec != "H264")
            return true;

        //Server can send any profile/level H264, but SDP has to specify supported one
        for (var i = mLineIndex; i < sdpLines.length; i++) {
            if ((sdpLines[i].search("a=fmtp:" + codecPayload) === 0) && (sdpLines[i].search("profile-level-id=42") !== -1))
                return true;
        }

        return false;
    };

    function setDefaultCodec(mLine, payload) {
        var elements = mLine.split(" ");
        var newLine = new Array();
        var index = 0;
        for (var i = 0; i < elements.length; i++) {
            if (index === 3) {
                newLine[index++] = payload;
                break;
            }
            if (elements[i] !== payload) newLine[index++] = elements[i];
        }
        return newLine.join(" ");
    };

    function ToggleOverlay(show) {
        var Overlay = document.getElementById(videoID + "_Overlay");
        if (Overlay != null) {
            if(show)
                Overlay.style.display = "inline";
            else
                Overlay.style.display = "none";
        }
    }

};

