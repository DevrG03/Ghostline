package app.ghostline

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoSink
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

enum class CallState { IDLE, OUTGOING, INCOMING, ACTIVE }

// Called on the main thread.
interface SessionUi {
    fun onStatus(text: String, kind: String) // kind: "", "ok" or "bad"
    fun onSystem(text: String)
    fun onMessage(text: String, mine: Boolean, ttl: Int)
    fun onClear()
    fun onSafety(code: String?)
    fun onReady(ready: Boolean)
    fun onCall(state: CallState)
    fun onPeerVideo(on: Boolean)
}

// One room. Speaks the same protocol as public/app.js: encrypted signaling through the
// server, "perfect negotiation" WebRTC, chat and call control over a data channel.
// All state is touched only on one background thread (exec), in order, like the web
// app's promise queue.
class Session(
    context: Context,
    val invite: Invite,
    private val ui: SessionUi,
    private val audio: CallAudio,
    egl: EglBase,
    private val remoteSink: VideoSink,
) {
    private val keys = RoomKeys(invite.secret)
    private val main = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor()
    private val http = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()

    private val adm = JavaAudioDeviceModule.builder(context.applicationContext)
        .setUseHardwareAcousticEchoCanceler(true)
        .setUseHardwareNoiseSuppressor(true)
        .setAudioRecordErrorCallback(object : JavaAudioDeviceModule.AudioRecordErrorCallback {
            override fun onWebRtcAudioRecordInitError(msg: String) = system("Microphone error: $msg")
            override fun onWebRtcAudioRecordStartError(
                code: JavaAudioDeviceModule.AudioRecordStartErrorCode, msg: String,
            ) = system("Microphone error: $msg")
            override fun onWebRtcAudioRecordError(msg: String) = system("Microphone error: $msg")
        })
        .createAudioDeviceModule()
    // Video codecs are needed even though this app doesn't send video: without them,
    // WebRTC aborts when the other side turns its camera on mid-call.
    private val factory = PeerConnectionFactory.builder()
        .setAudioDeviceModule(adm)
        .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
        .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
        .createPeerConnectionFactory()
    private var remoteVideo: VideoTrack? = null

    private var ws: WebSocket? = null
    private var pc: PeerConnection? = null
    private var dc: DataChannel? = null
    private val pendingCandidates = mutableListOf<IceCandidate>()
    private var iceServers = listOf<PeerConnection.IceServer>()
    private var relayOnly = false
    private var closed = false

    // perfect negotiation
    private var polite = false
    private var makingOffer = false
    private var ignoreOffer = false

    // replay protection for signaling: fresh id per peer session + increasing counter
    private var me = Crypto.newId()
    private var seq = 0L
    private var peerId: String? = null
    private var peerSeq = 0L

    private var call = CallState.IDLE
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var code: String? = null

    // ---------- public API (any thread) ----------

    fun start() = post {
        loadConfig()
        connectSignaling()
    }

    fun sendMessage(text: String, ttl: Int) = post {
        if (sendPeer(JSONObject().put("t", "msg").put("text", text).put("ttl", ttl))) {
            ui { onMessage(text, true, ttl) }
        }
    }

    fun clearChat() = post { sendPeer(JSONObject().put("t", "clear")) }
    fun startCall() = post { startCallNow() }
    fun acceptCall() = post { acceptCallNow() }
    fun declineCall() = post { declineCallNow() }
    fun hangUp() = post { endCall(true) }

    fun setMuted(muted: Boolean) = post { audioTrack?.setEnabled(!muted) }

    fun close() {
        post {
            teardownPeer()
            ws?.close(1000, null)
            ws = null
            closed = true
            factory.dispose()
            adm.release()
        }
        exec.shutdown()
        http.dispatcher.executorService.shutdown()
    }

    // ---------- plumbing ----------

    private fun post(block: () -> Unit) {
        if (exec.isShutdown) return
        exec.execute {
            if (closed) return@execute
            try {
                block()
            } catch (e: Exception) {
                Log.e(TAG, "session error", e)
            }
        }
    }

    private fun ui(block: SessionUi.() -> Unit) {
        main.post { if (!closed) ui.block() }
    }
    private fun status(text: String, kind: String = "") = ui { onStatus(text, kind) }
    private fun system(text: String) = ui { onSystem(text) }

    // Run an SDP operation and wait for it on our thread.
    private fun sdp(op: (SdpObserver) -> Unit) {
        val done = CountDownLatch(1)
        var error: String? = null
        op(object : SdpObserver {
            override fun onCreateSuccess(d: SessionDescription?) {}
            override fun onSetSuccess() = done.countDown()
            override fun onCreateFailure(e: String?) { error = e; done.countDown() }
            override fun onSetFailure(e: String?) { error = e; done.countDown() }
        })
        if (!done.await(10, TimeUnit.SECONDS)) error = "timed out"
        error?.let { throw IllegalStateException(it) }
    }

    // ---------- signaling ----------

    private fun loadConfig() {
        try {
            http.newCall(Request.Builder().url("${invite.origin}/config").build()).execute().use { res ->
                val j = JSONObject(res.body!!.string())
                val arr = j.optJSONArray("iceServers") ?: JSONArray()
                iceServers = (0 until arr.length()).map { i ->
                    val s = arr.getJSONObject(i)
                    val urls = when (val u = s.opt("urls")) {
                        is JSONArray -> (0 until u.length()).map { u.getString(it) }
                        else -> listOf(u.toString())
                    }
                    PeerConnection.IceServer.builder(urls).apply {
                        if (s.has("username")) setUsername(s.getString("username"))
                        if (s.has("credential")) setPassword(s.getString("credential"))
                    }.createIceServer()
                }
                relayOnly = j.optBoolean("relayOnly")
            }
        } catch (e: Exception) {
            Log.w(TAG, "no ICE config", e) // direct connections only
        }
    }

    private fun connectSignaling() {
        val url = invite.origin.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/ws"
        ws = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(JSONObject().put("type", "join").put("room", keys.roomId).toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) = post {
                if (ws === webSocket) onServerMessage(JSONObject(text))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = lost(webSocket)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = lost(webSocket)

            private fun lost(webSocket: WebSocket) = post {
                if (ws === webSocket && !dcOpen()) {
                    status("Lost connection to the server. Open the invite link again to rejoin.", "bad")
                }
            }
        })
    }

    private fun resetSession() {
        me = Crypto.newId()
        seq = 0
        peerId = null
        peerSeq = 0
    }

    private fun onServerMessage(m: JSONObject) {
        when (m.optString("type")) {
            "joined" -> {
                resetSession()
                polite = m.optInt("peers") > 0
                if (polite) {
                    status("Connecting to the other person…")
                    createPeer()
                } else {
                    status("Waiting for the other person to open the link…")
                }
            }
            "peer-joined" -> {
                resetSession()
                teardownPeer()
                polite = false
                status("Connecting to the other person…")
                val p = createPeer()
                setupDc(p.createDataChannel("chat", DataChannel.Init().apply { ordered = true }))
            }
            "peer-left" -> {
                resetSession()
                teardownPeer()
                polite = false
                system("The other person left.")
                status("Waiting for the other person to open the link…")
            }
            "full" -> status("This room already has two people.", "bad")
            "signal" -> {
                val plain = keys.unseal(m.optString("data")) ?: return
                val d = try { JSONObject(plain) } catch (e: Exception) { return }
                val from = d.optString("from")
                if (from.isEmpty() || from == me) return
                // Lock onto the first sender of this session and drop replayed/reordered messages.
                if (peerId != null && from != peerId) return
                val s = d.optLong("seq", -1)
                if (s <= peerSeq) return
                peerId = from
                peerSeq = s
                onSignal(d)
            }
        }
    }

    private fun sendSignal(obj: JSONObject) {
        val w = ws ?: return
        obj.put("from", me).put("seq", ++seq)
        w.send(JSONObject().put("type", "signal").put("data", keys.seal(obj.toString())).toString())
    }

    private fun sendDescription(d: SessionDescription) = sendSignal(
        JSONObject().put("description", JSONObject().put("type", d.type.canonicalForm()).put("sdp", d.description)),
    )

    // ---------- WebRTC (perfect negotiation) ----------

    private fun createPeer(): PeerConnection {
        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            iceTransportsType = if (relayOnly) PeerConnection.IceTransportsType.RELAY else PeerConnection.IceTransportsType.ALL
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        val observer = PeerObserver()
        val p = factory.createPeerConnection(config, observer)!!
        observer.self = p
        pc = p
        return p
    }

    private inner class PeerObserver : PeerConnection.Observer {
        var self: PeerConnection? = null

        // Only react to events from the current connection, on our thread.
        private fun mine(block: (PeerConnection) -> Unit) = post {
            val p = self
            if (p != null && p === pc) block(p)
        }

        override fun onIceCandidate(c: IceCandidate) = mine {
            sendSignal(
                JSONObject().put(
                    "candidate",
                    JSONObject().put("candidate", c.sdp).put("sdpMid", c.sdpMid).put("sdpMLineIndex", c.sdpMLineIndex),
                ),
            )
        }

        override fun onRenegotiationNeeded() = mine { p ->
            if (p.signalingState() != PeerConnection.SignalingState.STABLE) return@mine
            try {
                makingOffer = true
                sdp { p.setLocalDescription(it) }
                sendDescription(p.localDescription)
            } catch (e: Exception) {
                Log.e(TAG, "offer failed", e)
            } finally {
                makingOffer = false
            }
        }

        override fun onDataChannel(ch: DataChannel) = mine { setupDc(ch) }

        override fun onTrack(transceiver: RtpTransceiver) = mine {
            val track = transceiver.receiver.track()
            if (track is VideoTrack && track !== remoteVideo) {
                remoteVideo?.removeSink(remoteSink)
                remoteVideo = track
                track.addSink(remoteSink)
            }
        }

        override fun onSignalingChange(s: PeerConnection.SignalingState) {
            if (s == PeerConnection.SignalingState.STABLE) mine { updateSafetyCode(it) }
        }

        override fun onConnectionChange(s: PeerConnection.PeerConnectionState) = mine { p ->
            when (s) {
                PeerConnection.PeerConnectionState.DISCONNECTED -> status("Connection interrupted…", "bad")
                PeerConnection.PeerConnectionState.CONNECTED -> if (dcOpen()) status(CONNECTED, "ok")
                PeerConnection.PeerConnectionState.FAILED -> {
                    status("Direct connection failed. The network may block it (a TURN server would help).", "bad")
                    p.restartIce()
                }
                else -> {}
            }
        }

        override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {}
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {}
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
        override fun onAddStream(stream: MediaStream) {}
        override fun onRemoveStream(stream: MediaStream) {}
        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {}
    }

    private fun onSignal(d: JSONObject) {
        val p = pc ?: createPeer()
        val desc = d.optJSONObject("description")
        if (desc != null) {
            val type = SessionDescription.Type.fromCanonicalForm(desc.getString("type"))
            val offer = type == SessionDescription.Type.OFFER
            val collision = offer && (makingOffer || p.signalingState() != PeerConnection.SignalingState.STABLE)
            ignoreOffer = !polite && collision
            if (ignoreOffer) return
            if (collision) sdp { p.setLocalDescription(it, SessionDescription(SessionDescription.Type.ROLLBACK, "")) }
            sdp { p.setRemoteDescription(it, SessionDescription(type, desc.getString("sdp"))) }
            pendingCandidates.forEach { p.addIceCandidate(it) }
            pendingCandidates.clear()
            if (offer) {
                sdp { p.setLocalDescription(it) }
                sendDescription(p.localDescription)
            }
            updateSafetyCode(p)
            return
        }
        val c = d.optJSONObject("candidate") ?: return
        val sdpLine = c.optString("candidate")
        if (sdpLine.isEmpty()) return // end-of-candidates marker
        val mid = if (c.isNull("sdpMid")) "0" else c.getString("sdpMid")
        val candidate = IceCandidate(mid, c.optInt("sdpMLineIndex"), sdpLine)
        if (p.remoteDescription == null) {
            pendingCandidates.add(candidate)
        } else if (!p.addIceCandidate(candidate) && !ignoreOffer) {
            Log.w(TAG, "could not add candidate")
        }
    }

    private fun teardownPeer() {
        endCall(false)
        val ch = dc
        dc = null
        ch?.unregisterObserver()
        ch?.close()
        ch?.dispose()
        remoteVideo?.removeSink(remoteSink)
        remoteVideo = null
        val p = pc
        pc = null
        p?.dispose()
        pendingCandidates.clear()
        code = null
        ui {
            onSafety(null)
            onReady(false)
        }
    }

    private fun updateSafetyCode(p: PeerConnection) {
        val c = safetyCode(p.localDescription?.description, p.remoteDescription?.description) ?: return
        if (c == code) return
        code = c
        ui { onSafety(c) }
    }

    // ---------- data channel: chat + call control ----------

    private fun dcOpen() = dc?.state() == DataChannel.State.OPEN

    private fun setupDc(ch: DataChannel) {
        dc = ch
        ch.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previous: Long) {}

            override fun onStateChange() = post {
                if (dc !== ch) return@post
                when (ch.state()) {
                    DataChannel.State.OPEN -> {
                        status(CONNECTED, "ok")
                        system("Connected. Nothing here is saved anywhere.")
                        ui { onReady(true) }
                    }
                    DataChannel.State.CLOSED -> {
                        status("Connection lost.", "bad")
                        ui { onReady(false) }
                    }
                    else -> {}
                }
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                val bytes = ByteArray(buffer.data.remaining()).also { buffer.data.get(it) }
                val text = String(bytes, Charsets.UTF_8)
                post { if (dc === ch) onPeerMessage(JSONObject(text)) }
            }
        })
    }

    // Pad to a multiple of PAD_BLOCK bytes, like the web app, so sizes don't reveal lengths.
    private fun sendPeer(obj: JSONObject): Boolean {
        val ch = dc ?: return false
        if (ch.state() != DataChannel.State.OPEN) return false
        val len = obj.toString().toByteArray().size + 7 // + ,"p":""
        obj.put("p", ".".repeat((len + PAD_BLOCK - 1) / PAD_BLOCK * PAD_BLOCK - len))
        return ch.send(DataChannel.Buffer(ByteBuffer.wrap(obj.toString().toByteArray()), false))
    }

    private fun onPeerMessage(m: JSONObject) {
        when (m.optString("t")) {
            "msg" -> {
                val ttl = m.optInt("ttl").coerceIn(0, 86400)
                val text = m.optString("text").take(4000)
                ui { onMessage(text, false, ttl) }
            }
            "clear" -> ui {
                onClear()
                onSystem("Chat cleared by the other person.")
            }
            "ring" -> {
                if (call != CallState.IDLE) {
                    sendPeer(JSONObject().put("t", "busy"))
                    return
                }
                setCall(CallState.INCOMING)
                peerVideo(m.optBoolean("video"))
            }
            "accept" -> {
                if (call != CallState.OUTGOING) return
                setCall(CallState.ACTIVE)
                peerVideo(m.optBoolean("video"))
                attachTracks()
            }
            "decline", "busy" -> {
                if (call != CallState.OUTGOING) return
                endCall(false)
                system(if (m.optString("t") == "busy") "The other person is busy." else "Call declined.")
            }
            "cancel", "end" -> {
                if (call == CallState.IDLE) return
                endCall(false)
                system("Call ended.")
            }
            "video" -> peerVideo(m.optBoolean("on"))
        }
    }

    // ---------- calls (voice) ----------

    private fun peerVideo(on: Boolean) = ui { onPeerVideo(on) }

    private fun setCall(state: CallState) {
        call = state
        ui { onCall(state) }
    }

    private fun ensureMic() {
        if (audioTrack != null) return
        audioSource = factory.createAudioSource(MediaConstraints())
        audioTrack = factory.createAudioTrack("ghostline-audio", audioSource)
    }

    // Reuse existing transceivers so repeated calls don't grow the connection (like the web app).
    private fun attachTracks() {
        val p = pc ?: return
        val track = audioTrack ?: return
        val free = p.transceivers.firstOrNull {
            it.mediaType == org.webrtc.MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO &&
                it.sender.track() == null && !it.isStopped
        }
        if (free != null) {
            free.sender.setTrack(track, false)
            free.sender.setStreams(listOf("ghostline"))
            if (free.direction != RtpTransceiver.RtpTransceiverDirection.SEND_RECV) {
                free.direction = RtpTransceiver.RtpTransceiverDirection.SEND_RECV
            }
        } else if (p.senders.none { it.track() === track }) {
            p.addTrack(track, listOf("ghostline"))
        }
    }

    private fun startCallNow() {
        if (call != CallState.IDLE || !dcOpen()) return
        ensureMic()
        main.post { audio.start() }
        setCall(CallState.OUTGOING)
        sendPeer(JSONObject().put("t", "ring").put("video", false))
    }

    private fun acceptCallNow() {
        if (call != CallState.INCOMING) return
        ensureMic()
        main.post { audio.start() }
        setCall(CallState.ACTIVE)
        attachTracks()
        sendPeer(JSONObject().put("t", "accept").put("video", false))
    }

    private fun declineCallNow() {
        sendPeer(JSONObject().put("t", "decline"))
        endCall(false)
    }

    private fun endCall(notify: Boolean) {
        if (notify && call != CallState.IDLE) {
            sendPeer(JSONObject().put("t", if (call == CallState.OUTGOING) "cancel" else "end"))
        }
        pc?.senders?.forEach { if (it.track() != null) it.setTrack(null, false) }
        audioTrack?.dispose()
        audioSource?.dispose()
        audioTrack = null
        audioSource = null
        if (call != CallState.IDLE) {
            main.post { audio.stop() }
            setCall(CallState.IDLE)
            peerVideo(false)
        }
    }

    companion object {
        private const val TAG = "Ghostline"
        private const val PAD_BLOCK = 256
        const val CONNECTED = "Connected · end-to-end encrypted"

        @Volatile private var initialized = false

        fun init(context: Context) {
            if (initialized) return
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                    .createInitializationOptions(),
            )
            initialized = true
        }
    }
}
