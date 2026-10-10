package app.plainnotes

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
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtcCertificatePem
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer
import java.security.KeyPair
import java.security.interfaces.ECPublicKey
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.crypto.SecretKey

enum class CallState { IDLE, OUTGOING, INCOMING, ACTIVE }

// ---------- what the screen shows (immutable snapshots, built on the hub thread) ----------

data class MsgView(val id: String, val mine: Boolean, val sys: Boolean, val text: String, val ts: Long, val ttl: Int, val status: String)

data class RoomView(
    val id: String, val name: String, val status: String, val kind: String, val ready: Boolean,
    val ended: Boolean, val unread: Int, val preview: String, val lastTs: Long,
    val code: String?, val verified: Boolean, val codeChanged: Boolean, val owner: Boolean,
    val msgs: List<MsgView>, val msgsKey: String,
)

data class PairView(val step: String?, val code: String?, val expiresAt: Long, val status: String, val bad: Boolean)
data class CallView(val roomId: String?, val state: CallState, val peerVideo: Boolean, val myVideo: Boolean, val frontCamera: Boolean)
data class HubView(val connected: Boolean, val rooms: List<RoomView>, val pair: PairView, val call: CallView)

interface HubUi {
    fun onView(view: HubView) // main thread
    fun onNotice(text: String) // a one-off message, e.g. "the owner removed you"
}

// All rooms, pairing and calls. Speaks the same protocol as public/app.js. All state lives on
// one background thread (exec), touched in order, like the web app's promise queue.
class Hub(
    context: Context,
    private val origin: String,
    private val ui: HubUi,
    private val audio: CallAudio,
    private val egl: EglBase,
    private val remoteSink: VideoSink,
    private val localSink: VideoSink,
    dataKey: SecretKey? = null, // with the Notes disguise on, the key the PIN unwrapped
) {
    private val appContext = context.applicationContext
    private val store = Store(appContext, dataKey)
    private val closedLatch = CountDownLatch(1)
    private val main = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadScheduledExecutor()
    private val http = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()

    private val adm = JavaAudioDeviceModule.builder(appContext)
        .setUseHardwareAcousticEchoCanceler(true)
        .setUseHardwareNoiseSuppressor(true)
        .createAudioDeviceModule()
    // Video codecs are needed even for voice: the other side may turn its camera on mid-call.
    private val factory = PeerConnectionFactory.builder()
        .setAudioDeviceModule(adm)
        .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
        .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
        .createPeerConnectionFactory()

    private val rooms = LinkedHashMap<String, Room>()
    private var ws: WebSocket? = null
    private var wsOpen = false
    private var backoffMs = 1000L
    private var closed = false
    private var iceServers = listOf<PeerConnection.IceServer>()
    private var relayOnly = false
    private var openRoomId: String? = null
    private var foreground = true

    // ---------- public API (any thread) ----------

    fun start() = post {
        loadRooms()
        loadConfig()
        connect()
        publish()
    }

    fun setOpenRoom(id: String?) = post {
        openRoomId = id
        rooms[id]?.let { if (it.rec.unread != 0) { it.rec.unread = 0; saveRooms() } }
        publish()
    }

    fun setForeground(on: Boolean) = post {
        foreground = on
        if (on) rooms[openRoomId]?.let { it.rec.unread = 0; saveRooms(); publish() }
    }

    fun send(roomId: String, text: String, ttl: Int) = post { rooms[roomId]?.send(text, ttl) }
    fun clearChat(roomId: String) = post {
        val r = rooms[roomId] ?: return@post
        r.msgs.clear()
        r.saveMsgs()
        r.sendPeer(JSONObject().put("t", "clear"))
        publish()
    }
    fun rename(roomId: String, name: String) = post { rooms[roomId]?.let { it.rec.name = name.take(40); saveRooms(); publish() } }
    fun verify(roomId: String) = post {
        val r = rooms[roomId] ?: return@post
        r.rec.verifiedCode = r.code
        saveRooms()
        r.addSys("Safety code marked as verified. You will be warned if it changes.")
    }
    fun leave(roomId: String) = post { leaveNow(roomId) }
    fun endRoom(roomId: String) = post { endRoomForBothNow(roomId) }
    fun removeParticipant(roomId: String) = post { removeParticipantNow(roomId) }

    fun pairCreate() = post { pairing.create() }
    fun pairJoin(code: String) = post { pairing.join(code) }
    fun pairCancel() = post { pairing.cancel() }
    fun pairSave(name: String, then: (String) -> Unit) = post {
        val id = pairing.save(name) ?: return@post
        main.post { then(id) }
    }

    fun startCall(roomId: String, video: Boolean) = post { startCallNow(roomId, video) }
    fun acceptCall(video: Boolean) = post { acceptCallNow(video) }
    fun declineCall() = post {
        call.room?.sendPeer(JSONObject().put("t", "decline"))
        endCall(false)
    }
    fun hangUp() = post { endCall(true) }
    fun setMuted(muted: Boolean) = post { audioTrack?.setEnabled(!muted) }
    fun setCamera(on: Boolean) = post { setCameraNow(on) }
    fun flipCamera() = post {
        val cam = camera ?: return@post
        cam.switchCamera(null)
        frontCamera = !frontCamera
        publish()
    }

    fun close() {
        post {
            endCall(true)
            for (r in rooms.values) r.teardown()
            ws?.close(1000, null)
            ws = null
            closed = true
            factory.dispose()
            adm.release()
            closedLatch.countDown()
        }
        exec.shutdown()
        http.dispatcher.executorService.shutdown()
    }

    // Blocks until close() has finished, including any saves queued before it.
    fun awaitClosed() = closedLatch.await(10, TimeUnit.SECONDS)

    // ---------- plumbing ----------

    private fun post(block: () -> Unit) {
        if (exec.isShutdown) return
        exec.execute {
            if (closed) return@execute
            try {
                block()
            } catch (e: Exception) {
                Log.e(TAG, "hub error", e)
            }
        }
    }

    private fun later(ms: Long, block: () -> Unit): ScheduledFuture<*>? =
        if (exec.isShutdown) null else exec.schedule({ if (!closed) try { block() } catch (e: Exception) { Log.e(TAG, "hub error", e) } }, ms, TimeUnit.MILLISECONDS)

    private var publishPending = false
    private fun publish() {
        if (publishPending) return
        publishPending = true
        later(30) {
            publishPending = false
            val v = view()
            main.post { if (!closed) ui.onView(v) }
        }
    }

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

    // ---------- storage ----------

    private fun loadRooms() {
        val arr = store.read(ROOMS)?.let { JSONArray(it) } ?: JSONArray()
        for (i in 0 until arr.length()) {
            try {
                val rec = RoomRec.from(arr.getJSONObject(i))
                val msgs = store.read("$MSGS${rec.id}")?.let { JSONArray(it) } ?: JSONArray()
                val cert = store.read("$CERT${rec.id}")?.let { JSONObject(it) }
                    ?.let { RtcCertificatePem(it.getString("key"), it.getString("cert")) }
                val room = Room(rec, cert)
                for (j in 0 until msgs.length()) Msg.from(msgs.getJSONObject(j))?.let { room.msgs.add(it) }
                room.msgs.removeAll { it.expires in 1..System.currentTimeMillis() }
                room.msgs.forEach { room.scheduleExpiry(it) }
                rooms[rec.id] = room
            } catch (e: Exception) {
                Log.e(TAG, "could not load a room", e)
            }
        }
    }

    private fun saveRooms() {
        store.write(ROOMS, JSONArray(rooms.values.map { it.rec.json() }).toString())
    }

    // ---------- server connection (one WebSocket for every room) ----------

    private fun loadConfig() {
        try {
            http.newCall(Request.Builder().url("$origin/config").build()).execute().use { res ->
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
            Log.w(TAG, "no ICE config", e)
        }
    }

    private fun connect() {
        val url = origin.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/ws"
        ws = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = post {
                if (ws !== webSocket) return@post
                backoffMs = 1000
                wsOpen = true
                for (r in rooms.values) r.join()
                publish()
            }

            override fun onMessage(webSocket: WebSocket, text: String) = post {
                if (ws === webSocket) onServerMessage(JSONObject(text))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = lost(webSocket)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = lost(webSocket)

            private fun lost(webSocket: WebSocket) = post {
                if (ws !== webSocket) return@post
                ws = null
                wsOpen = false
                for (r in rooms.values) r.serverPeer = false
                pairing.onDisconnect()
                publish()
                later(backoffMs) { if (ws == null) connect() }
                backoffMs = (backoffMs * 2).coerceAtMost(30_000)
            }
        })
    }

    private fun connected() = wsOpen

    private fun sendServer(obj: JSONObject): Boolean = wsOpen && (ws?.send(obj.toString()) ?: false)

    private fun onServerMessage(m: JSONObject) {
        val roomId = m.optString("room")
        if (roomId.isNotEmpty()) rooms[roomId]?.onServer(m) else pairing.onServer(m)
        publish()
    }

    // ---------- a room ----------

    class RoomRec(
        val id: String, val secret: String, var name: String, val created: Long,
        var verifiedCode: String?, var unread: Int, var ended: Boolean,
        val role: String, // "owner" (created it) or "participant" (joined with the code)
    ) {
        fun json(): JSONObject = JSONObject().put("id", id).put("secret", secret).put("name", name).put("created", created)
            .put("verifiedCode", verifiedCode ?: JSONObject.NULL).put("unread", unread).put("ended", ended).put("role", role)

        companion object {
            fun from(j: JSONObject) = RoomRec(
                j.getString("id"), j.getString("secret"), j.optString("name", "Room"), j.optLong("created"),
                if (j.isNull("verifiedCode")) null else j.optString("verifiedCode"), j.optInt("unread"), j.optBoolean("ended"),
                j.optString("role", "owner"),
            )
        }
    }

    class Msg(
        val id: String, val mine: Boolean, val sys: Boolean, val text: String, val ts: Long,
        val ttl: Int, val expires: Long, var status: String,
    ) {
        fun json(): JSONObject = JSONObject().put("id", id).put("mine", mine).put("sys", sys).put("text", text)
            .put("ts", ts).put("ttl", ttl).put("expires", expires).put("status", status)

        fun view() = MsgView(id, mine, sys, text, ts, ttl, status)

        companion object {
            fun from(j: JSONObject): Msg? = try {
                Msg(
                    j.getString("id"), j.optBoolean("mine"), j.optBoolean("sys"), j.getString("text"),
                    j.optLong("ts"), j.optInt("ttl"), j.optLong("expires"), j.optString("status"),
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    private inner class Room(val rec: RoomRec, val cert: RtcCertificatePem?) {
        val id = rec.id
        val keys = RoomKeys(rec.secret)
        val msgs = mutableListOf<Msg>()
        var pc: PeerConnection? = null
        var dc: DataChannel? = null
        val pending = mutableListOf<IceCandidate>()
        var polite = false
        var makingOffer = false
        var ignoreOffer = false
        var serverPeer = false
        var full = false
        var code: String? = null
        var remoteVideo: VideoTrack? = null
        private val expiry = HashMap<String, ScheduledFuture<*>?>()
        // replay protection for signaling: fresh id per peer session + increasing counter
        var me = Crypto.newId()
        var seq = 0L
        var peerId: String? = null
        var peerSeq = 0L

        fun saveMsgs() = store.write("$MSGS$id", JSONArray(msgs.map { it.json() }).toString())
        fun join() { if (!rec.ended) sendServer(JSONObject().put("type", "join").put("room", id)) }
        fun dcOpen() = dc?.state() == DataChannel.State.OPEN

        fun resetSession() {
            me = Crypto.newId(); seq = 0; peerId = null; peerSeq = 0
        }

        fun onServer(m: JSONObject) {
            when (m.optString("type")) {
                "joined" -> {
                    full = false
                    resetSession()
                    serverPeer = m.optInt("peers") > 0
                    if (serverPeer) {
                        teardown()
                        polite = true
                        createPeer()
                    }
                }
                "peer-joined" -> {
                    resetSession()
                    teardown()
                    serverPeer = true
                    polite = false
                    setupDc(createPeer().createDataChannel("chat", DataChannel.Init().apply { ordered = true }))
                }
                "peer-left" -> {
                    serverPeer = false
                    if (!dcOpen()) teardown() // the direct connection may outlive their server link
                }
                "full" -> full = true
                "signal" -> {
                    val plain = keys.unseal(m.optString("data")) ?: return
                    val d = try { JSONObject(plain) } catch (e: Exception) { return }
                    val from = d.optString("from")
                    if (from.isEmpty() || from == me) return
                    if (peerId != null && from != peerId) return
                    val s = d.optLong("seq", -1)
                    if (s <= peerSeq) return
                    peerId = from
                    peerSeq = s
                    onSignal(d)
                }
            }
        }

        fun sendSignal(obj: JSONObject) {
            obj.put("from", me).put("seq", ++seq)
            sendServer(JSONObject().put("type", "signal").put("room", id).put("data", keys.seal(obj.toString())))
        }

        fun sendDescription(d: SessionDescription) = sendSignal(
            JSONObject().put("description", JSONObject().put("type", d.type.canonicalForm()).put("sdp", d.description)),
        )

        fun createPeer(): PeerConnection {
            val config = PeerConnection.RTCConfiguration(iceServers).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                iceTransportsType = if (relayOnly) PeerConnection.IceTransportsType.RELAY else PeerConnection.IceTransportsType.ALL
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
                // the same DTLS certificate every time, so the safety code stays the same
                this@Room.cert?.let { certificate = it }
            }
            val observer = Observer()
            val p = factory.createPeerConnection(config, observer)!!
            observer.self = p
            pc = p
            return p
        }

        inner class Observer : PeerConnection.Observer {
            var self: PeerConnection? = null

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
                    if (call.room === this@Room) remoteVideo?.removeSink(remoteSink)
                    remoteVideo = track
                    if (call.room === this@Room) track.addSink(remoteSink)
                }
            }

            override fun onSignalingChange(s: PeerConnection.SignalingState) {
                if (s == PeerConnection.SignalingState.STABLE) mine { updateSafetyCode(it) }
            }

            override fun onConnectionChange(s: PeerConnection.PeerConnectionState) = mine { p ->
                if (s == PeerConnection.PeerConnectionState.FAILED) p.restartIce()
                publish()
            }

            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onAddStream(stream: MediaStream) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {}
        }

        fun onSignal(d: JSONObject) {
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
                pending.forEach { p.addIceCandidate(it) }
                pending.clear()
                if (offer) {
                    sdp { p.setLocalDescription(it) }
                    sendDescription(p.localDescription)
                }
                updateSafetyCode(p)
                return
            }
            val c = d.optJSONObject("candidate") ?: return
            val line = c.optString("candidate")
            if (line.isEmpty()) return
            val candidate = IceCandidate(if (c.isNull("sdpMid")) "0" else c.getString("sdpMid"), c.optInt("sdpMLineIndex"), line)
            if (p.remoteDescription == null) pending.add(candidate) else p.addIceCandidate(candidate)
        }

        fun teardown() {
            if (call.room === this) endCall(false)
            val ch = dc
            dc = null
            ch?.unregisterObserver()
            ch?.close()
            ch?.dispose()
            remoteVideo = null
            val p = pc
            pc = null
            p?.dispose()
            pending.clear()
            code = null
        }

        fun updateSafetyCode(p: PeerConnection) {
            val c = safetyCode(p.localDescription?.description, p.remoteDescription?.description) ?: return
            if (c == code) return
            code = c
            val verified = rec.verifiedCode
            if (verified != null && c != verified) {
                addSys("⚠ The safety code changed. This may be a different device or someone in the middle. Verify it again before saying anything sensitive.")
            }
            publish()
        }

        fun setupDc(ch: DataChannel) {
            dc = ch
            ch.registerObserver(object : DataChannel.Observer {
                override fun onBufferedAmountChange(previous: Long) {}

                override fun onStateChange() = post {
                    if (dc !== ch) return@post
                    when (ch.state()) {
                        DataChannel.State.OPEN -> flushOutbox()
                        DataChannel.State.CLOSED -> if (call.room === this@Room) endCall(false)
                        else -> {}
                    }
                    publish()
                }

                override fun onMessage(buffer: DataChannel.Buffer) {
                    val bytes = ByteArray(buffer.data.remaining()).also { buffer.data.get(it) }
                    val text = String(bytes, Charsets.UTF_8)
                    post {
                        if (dc === ch) onPeerMessage(JSONObject(text))
                        publish()
                    }
                }
            })
        }

        // Pad to a multiple of PAD_BLOCK bytes, like the web app, so sizes don't reveal lengths.
        fun sendPeer(obj: JSONObject): Boolean {
            val ch = dc ?: return false
            if (ch.state() != DataChannel.State.OPEN) return false
            val len = obj.toString().toByteArray().size + 7 // + ,"p":""
            obj.put("p", ".".repeat((len + PAD_BLOCK - 1) / PAD_BLOCK * PAD_BLOCK - len))
            return ch.send(DataChannel.Buffer(ByteBuffer.wrap(obj.toString().toByteArray()), false))
        }

        fun msgJson(m: Msg) = JSONObject().put("t", "msg").put("id", m.id).put("text", m.text).put("ttl", m.ttl).put("ts", m.ts)

        fun flushOutbox() {
            var changed = false
            for (m in msgs) {
                // Resend anything not yet acknowledged: a message "sent" into a connection that had
                // silently died is never delivered otherwise. The receiver ignores duplicates by id.
                if (m.mine && m.status != "delivered" && sendPeer(msgJson(m)) && m.status == "pending") {
                    m.status = "sent"
                    changed = true
                }
            }
            if (changed) saveMsgs()
        }

        fun onPeerMessage(m: JSONObject) {
            when (m.optString("t")) {
                "msg" -> {
                    val mid = m.optString("id").ifEmpty { Crypto.newId() }.take(32)
                    sendPeer(JSONObject().put("t", "ack").put("id", mid))
                    if (msgs.any { it.id == mid && !it.mine }) return
                    val ttl = m.optInt("ttl").coerceIn(0, 86400)
                    val now = System.currentTimeMillis()
                    val msg = Msg(mid, false, false, m.optString("text").take(4000), now, ttl, if (ttl > 0) now + ttl * 1000L else 0, "")
                    msgs.add(msg)
                    scheduleExpiry(msg)
                    if (openRoomId != id || !foreground) rec.unread++
                    saveMsgs()
                    saveRooms()
                }
                "ack" -> msgs.firstOrNull { it.mine && it.id == m.optString("id") }?.let {
                    if (it.status != "delivered") { it.status = "delivered"; saveMsgs() }
                }
                "clear" -> {
                    msgs.clear()
                    addSys("The other person cleared the chat.")
                }
                "bye" -> {
                    val who = if (rec.role == "owner") "The participant" else "The other person"
                    rec.ended = true
                    saveRooms()
                    sendServer(JSONObject().put("type", "leave").put("room", id))
                    addSys("$who left this room. You can delete it from the ⋯ menu.")
                    teardown()
                }
                // Only the owner may end a room or remove the participant; a participant honours it.
                "end-room" -> if (rec.role != "owner") wipeRoom(id, notice = "The owner ended this room, so it was deleted here too.")
                "removed" -> if (rec.role != "owner") wipeRoom(id, notice = "The owner removed you from this room.")
                else -> onCallMessage(this, m)
            }
        }

        fun addSys(text: String) {
            msgs.add(Msg(Crypto.newId(), false, true, text, System.currentTimeMillis(), 0, 0, ""))
            saveMsgs()
            publish()
        }

        fun send(text: String, ttl: Int) {
            if (rec.ended) return
            val now = System.currentTimeMillis()
            val msg = Msg(Crypto.newId(), true, false, text.take(4000), now, ttl, if (ttl > 0) now + ttl * 1000L else 0, "pending")
            msgs.add(msg)
            scheduleExpiry(msg)
            if (sendPeer(msgJson(msg))) msg.status = "sent"
            saveMsgs()
            publish()
        }

        fun scheduleExpiry(m: Msg) {
            if (m.expires <= 0 || expiry.containsKey(m.id)) return
            expiry[m.id] = later((m.expires - System.currentTimeMillis()).coerceAtLeast(0)) {
                msgs.remove(m)
                expiry.remove(m.id)
                saveMsgs()
                publish()
            }
        }

        fun cancelTimers() = expiry.values.forEach { it?.cancel(false) }

        fun status(): Pair<String, String> = when {
            rec.ended -> "The other person left" to "bad"
            full -> "Open on another device" to "bad"
            dcOpen() -> ("Online · end-to-end encrypted" + if (code != null && code == rec.verifiedCode) " · verified" else "") to "ok"
            serverPeer -> "Connecting…" to ""
            !connected() -> "Reconnecting to the server…" to ""
            else -> "Offline · messages will be sent when they're back" to "off"
        }

        fun view(): RoomView {
            val (text, kind) = status()
            val last = msgs.lastOrNull { !it.sys }
            val preview = if (rec.ended) "The other person left" else last?.let { (if (it.mine) "You: " else "") + it.text } ?: "No messages yet"
            val views = msgs.map { it.view() }
            return RoomView(
                id, rec.name, text, kind, dcOpen(), rec.ended, rec.unread, preview,
                msgs.lastOrNull()?.ts ?: rec.created, code, code != null && code == rec.verifiedCode,
                rec.verifiedCode != null && code != null && code != rec.verifiedCode, rec.role == "owner", views,
                views.joinToString(",") { it.id + it.status },
            )
        }
    }

    private fun addRoom(secret: String, name: String, role: String): Room {
        val keys = RoomKeys(secret)
        rooms[keys.roomId]?.let { return it }
        val cert = try {
            RtcCertificatePem.generateCertificate(PeerConnection.KeyType.ECDSA, 10L * 365 * 24 * 3600).also {
                store.write("$CERT${keys.roomId}", JSONObject().put("key", it.privateKey).put("cert", it.certificate).toString())
            }
        } catch (e: Exception) {
            Log.w(TAG, "no saved certificate", e)
            null
        }
        val rec = RoomRec(keys.roomId, secret, name.ifBlank { "Room" }.take(40), System.currentTimeMillis(), null, 0, false, role)
        val room = Room(rec, cert)
        rooms[rec.id] = room
        room.addSys("Paired. Compare the safety code with the other person once: tap Check above.")
        saveRooms()
        room.join()
        return room
    }

    // Remove the room and everything it stores from this device.
    private fun wipeRoom(roomId: String, notice: String? = null) {
        val room = rooms[roomId] ?: return
        if (call.room === room) endCall(true)
        room.teardown()
        room.cancelTimers()
        rooms.remove(roomId)
        saveRooms()
        sendServer(JSONObject().put("type", "leave").put("room", roomId))
        store.delete("$MSGS$roomId")
        store.delete("$CERT$roomId")
        notice?.let { n -> main.post { if (!closed) ui.onNotice(n) } }
        publish()
    }

    // Participant leaves, or anyone deletes a finished room: gone here, the other side keeps an archive.
    private fun leaveNow(roomId: String) {
        val room = rooms[roomId] ?: return
        if (!room.rec.ended) room.sendPeer(JSONObject().put("t", "bye"))
        later(150) { wipeRoom(roomId) } // let the goodbye go out before the channel closes
    }

    // Owner: end the room for both people, history wiped on both sides.
    private fun endRoomForBothNow(roomId: String) {
        rooms[roomId]?.sendPeer(JSONObject().put("t", "end-room"))
        later(150) { wipeRoom(roomId) }
    }

    // Owner: remove the participant. They're kicked; the owner keeps the history as an archive.
    private fun removeParticipantNow(roomId: String) {
        val room = rooms[roomId] ?: return
        room.sendPeer(JSONObject().put("t", "removed"))
        later(150) {
            if (call.room === room) endCall(true)
            room.rec.ended = true
            room.addSys("You removed the participant. To talk again, start a new room.")
            saveRooms()
            sendServer(JSONObject().put("type", "leave").put("room", roomId))
            room.teardown()
            publish()
        }
    }

    // ---------- pairing: a 6-letter code -> a saved room ----------

    private val pairing = Pairer()

    private inner class Pairer {
        var step: String? = null // "create" | "join" | "name"
        var mode: String? = null
        var code: String? = null
        var keys: KeyPair? = null
        var secret: String? = null
        var expiresAt = 0L
        var status = ""
        var bad = false

        fun reset() {
            step = null; mode = null; code = null; keys = null; secret = null; status = ""; bad = false
        }

        fun say(text: String, isBad: Boolean = false) {
            status = text
            bad = isBad
            publish()
        }

        fun create() {
            reset()
            step = "create"
            mode = "create"
            if (!sendServer(JSONObject().put("type", "pair-create"))) {
                mode = null
                say("Not connected to the server yet. Try again in a moment.", true)
            } else {
                say("Getting a code…")
            }
        }

        fun join(text: String) {
            val c = Pairing.normalizeCode(text)
            step = "join"
            if (c.length != Pairing.CODE_LENGTH) return say("Enter all 6 letters of the code.", true)
            mode = "join"
            code = c
            if (!sendServer(JSONObject().put("type", "pair-join").put("code", c))) {
                mode = null
                say("Not connected to the server yet. Try again in a moment.", true)
            } else {
                say("Connecting…")
            }
        }

        fun cancel() {
            if (mode != null && secret == null) sendServer(JSONObject().put("type", "pair-end").put("reason", "cancelled"))
            reset()
            publish()
        }

        fun onDisconnect() {
            if (mode != null && secret == null) {
                mode = null
                say("Lost connection to the server. Please start again.", true)
            }
        }

        fun save(name: String): String? {
            val s = secret ?: return null
            val room = addRoom(s, name, if (mode == "create") "owner" else "participant")
            reset()
            publish()
            return room.id
        }

        fun onServer(m: JSONObject) {
            if (mode == null) return
            when (m.optString("type")) {
                "pair-created" -> {
                    code = m.optString("code")
                    expiresAt = System.currentTimeMillis() + m.optLong("expiresIn", 600_000)
                    say("Waiting for the other person to enter the code…")
                }
                "pair-peer" -> {
                    // Both sides send a fresh ECDH public key; the shared secret never crosses the network.
                    val kp = Pairing.newKeyPair()
                    keys = kp
                    val pub = Crypto.b64u(Pairing.rawPublic(kp.public as ECPublicKey))
                    sendServer(JSONObject().put("type", "pair-msg").put("data", JSONObject().put("pub", pub).toString()))
                    say("Connecting securely…")
                }
                "pair-msg" -> {
                    val kp = keys ?: return
                    if (secret != null) return
                    val pub = try { JSONObject(m.optString("data")).optString("pub") } catch (e: Exception) { "" }
                    if (pub.isEmpty()) return
                    try {
                        secret = Pairing.roomSecret(kp.private, kp.public as ECPublicKey, Crypto.unb64u(pub))
                    } catch (e: Exception) {
                        sendServer(JSONObject().put("type", "pair-end").put("reason", "failed"))
                        mode = null
                        return say("Pairing failed. Please get a new code.", true)
                    }
                    sendServer(JSONObject().put("type", "pair-end").put("reason", "done"))
                    step = "name"
                    say("")
                }
                "pair-ended" -> {
                    if (secret != null) return
                    val text = when (m.optString("reason")) {
                        "no-such-code" -> "No room is waiting for that code. Check the letters, or ask for a new code."
                        "too-many-attempts" -> "Too many attempts from this network. Wait 10 minutes and try again."
                        "expired" -> "This code expired. Get a new one."
                        "peer-gone" -> "The other person closed the pairing screen."
                        "cancelled" -> "The other person cancelled."
                        "failed" -> "Pairing failed. Please get a new code."
                        "busy" -> "The server is busy. Try again in a minute."
                        else -> "Pairing ended."
                    }
                    if (mode == "create") code = null
                    mode = null
                    say(text, true)
                }
            }
        }

        fun view() = PairView(step, code?.let { Pairing.formatCode(it) }?.takeIf { step == "create" }, expiresAt, status, bad)
    }

    // ---------- calls (one at a time, in any room) ----------

    private class Call {
        var room: Room? = null
        var state = CallState.IDLE
        var peerVideo = false
        var myVideo = false
    }

    private val call = Call()
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var camera: CameraVideoCapturer? = null
    private var cameraHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var frontCamera = true

    private fun onCallMessage(room: Room, m: JSONObject) {
        when (m.optString("t")) {
            "ring" -> {
                if (call.state != CallState.IDLE) {
                    room.sendPeer(JSONObject().put("t", "busy"))
                    return
                }
                call.room = room
                call.state = CallState.INCOMING
                call.peerVideo = m.optBoolean("video")
            }
            "accept" -> {
                if (call.room !== room || call.state != CallState.OUTGOING) return
                call.peerVideo = m.optBoolean("video")
                call.state = CallState.ACTIVE
                room.remoteVideo?.addSink(remoteSink)
                attachTracks(room)
            }
            "decline", "busy" -> {
                if (call.room !== room || call.state != CallState.OUTGOING) return
                endCall(false)
                room.addSys(if (m.optString("t") == "busy") "The other person is busy." else "Call declined.")
            }
            "cancel", "end" -> {
                if (call.room !== room || call.state == CallState.IDLE) return
                if (call.state == CallState.INCOMING) room.addSys("Missed call.")
                endCall(false)
            }
            "video" -> if (call.room === room) call.peerVideo = m.optBoolean("on")
        }
        publish()
    }

    private fun ensureMic() {
        if (audioTrack != null) return
        audioSource = factory.createAudioSource(MediaConstraints())
        audioTrack = factory.createAudioTrack("audio0", audioSource)
    }

    // Reuse existing transceivers so repeated calls don't grow the connection (like the web app).
    private fun attachTracks(room: Room) {
        val p = room.pc ?: return
        for (track in listOfNotNull(audioTrack, videoTrack)) {
            if (p.senders.any { it.track() === track }) continue
            val type = if (track is VideoTrack) MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO else MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO
            val free = p.transceivers.firstOrNull { it.mediaType == type && it.sender.track() == null && !it.isStopped }
            if (free != null) {
                free.sender.setTrack(track, false)
                free.sender.setStreams(listOf("stream0"))
                if (free.direction != RtpTransceiver.RtpTransceiverDirection.SEND_RECV) {
                    free.direction = RtpTransceiver.RtpTransceiverDirection.SEND_RECV
                }
            } else {
                p.addTrack(track, listOf("stream0"))
            }
        }
    }

    // The camera only runs while it's on; turning it off releases it (and its light).
    private fun startCamera(): Boolean {
        if (videoTrack != null) return true
        return try {
            val cams = Camera2Enumerator(appContext)
            val name = cams.deviceNames.firstOrNull { cams.isFrontFacing(it) == frontCamera }
                ?: cams.deviceNames.firstOrNull() ?: return false
            frontCamera = cams.isFrontFacing(name)
            val cap = cams.createCapturer(name, null) ?: return false
            val helper = SurfaceTextureHelper.create("camera", egl.eglBaseContext)
            val source = factory.createVideoSource(false)
            cap.initialize(helper, appContext, source.capturerObserver)
            cap.startCapture(1280, 720, 30)
            val track = factory.createVideoTrack("video0", source)
            track.addSink(localSink)
            camera = cap
            cameraHelper = helper
            videoSource = source
            videoTrack = track
            call.myVideo = true
            true
        } catch (e: Exception) {
            Log.e(TAG, "camera failed", e)
            stopCamera()
            false
        }
    }

    private fun stopCamera() {
        val track = videoTrack
        if (track != null) call.room?.pc?.senders?.forEach { if (it.track() === track) it.setTrack(null, false) }
        track?.removeSink(localSink)
        try {
            camera?.stopCapture()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        camera?.dispose()
        track?.dispose()
        videoSource?.dispose()
        cameraHelper?.dispose()
        camera = null
        cameraHelper = null
        videoSource = null
        videoTrack = null
        call.myVideo = false
    }

    private fun startCallNow(roomId: String, wantVideo: Boolean) {
        val room = rooms[roomId] ?: return
        if (call.state != CallState.IDLE || !room.dcOpen()) return
        ensureMic()
        val video = wantVideo && startCamera()
        if (wantVideo && !video) room.addSys("Could not start the camera, calling with voice only.")
        main.post { audio.start() }
        call.room = room
        call.state = CallState.OUTGOING
        call.peerVideo = false
        room.sendPeer(JSONObject().put("t", "ring").put("video", video))
        publish()
    }

    private fun acceptCallNow(wantVideo: Boolean) {
        val room = call.room ?: return
        if (call.state != CallState.INCOMING) return
        ensureMic()
        val video = wantVideo && startCamera()
        if (wantVideo && !video) room.addSys("Could not start the camera, answering with voice only.")
        main.post { audio.start() }
        call.state = CallState.ACTIVE
        room.remoteVideo?.addSink(remoteSink)
        attachTracks(room)
        room.sendPeer(JSONObject().put("t", "accept").put("video", video))
        publish()
    }

    private fun setCameraNow(on: Boolean) {
        val room = call.room ?: return
        if (call.state != CallState.OUTGOING && call.state != CallState.ACTIVE) return
        if (on) {
            if (!startCamera()) return room.addSys("Could not start the camera.")
            if (call.state == CallState.ACTIVE) attachTracks(room)
        } else {
            stopCamera()
        }
        room.sendPeer(JSONObject().put("t", "video").put("on", on))
        publish()
    }

    private fun endCall(notify: Boolean) {
        val room = call.room
        if (notify && room != null && call.state != CallState.IDLE) {
            room.sendPeer(JSONObject().put("t", if (call.state == CallState.OUTGOING) "cancel" else "end"))
        }
        stopCamera()
        room?.pc?.senders?.forEach { if (it.track() != null) it.setTrack(null, false) }
        room?.remoteVideo?.removeSink(remoteSink)
        audioTrack?.dispose()
        audioSource?.dispose()
        audioTrack = null
        audioSource = null
        if (call.state != CallState.IDLE) main.post { audio.stop() }
        call.room = null
        call.state = CallState.IDLE
        call.peerVideo = false
        publish()
    }

    // ---------- snapshot for the screen ----------

    private fun view() = HubView(
        connected(),
        rooms.values.map { it.view() }.sortedByDescending { it.lastTs },
        pairing.view(),
        CallView(call.room?.id, call.state, call.peerVideo, call.myVideo, frontCamera),
    )

    companion object {
        private const val TAG = "Notes" // shows in logcat, so nothing that names the app
        // file names in the app's storage, kept neutral for the same reason
        private const val ROOMS = "index"
        private const val MSGS = "n-"
        private const val CERT = "c-"
        private const val PAD_BLOCK = 256

        @Volatile private var initialized = false

        fun init(context: Context) {
            if (initialized) return
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions(),
            )
            initialized = true
        }
    }
}
