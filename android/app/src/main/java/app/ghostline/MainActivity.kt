package app.ghostline

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import java.text.DateFormat
import java.util.Date

class MainActivity : Activity(), SessionUi {
    private var session: Session? = null
    private lateinit var audio: CallAudio
    private lateinit var egl: EglBase
    private var remoteView: SurfaceViewRenderer? = null
    private var peerVideo = false
    private var callState = CallState.IDLE
    private var pendingMicAction: (() -> Unit)? = null
    private var verifiedCode: String? = null
    private var currentCode: String? = null
    private var ready = false
    private var muted = false

    // room views
    private lateinit var statusText: TextView
    private lateinit var dot: View
    private lateinit var voiceBtn: Button
    private lateinit var invitePanel: LinearLayout
    private lateinit var safetyText: TextView
    private lateinit var verifyBtn: Button
    private lateinit var incomingPanel: LinearLayout
    private lateinit var callPanel: LinearLayout
    private lateinit var callLabel: TextView
    private lateinit var muteBtn: Button
    private lateinit var speakerBtn: Button
    private lateinit var messages: LinearLayout
    private lateinit var messagesScroll: ScrollView
    private lateinit var input: EditText
    private lateinit var sendBtn: Button
    private lateinit var ttl: Spinner

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Block screenshots, screen recording and the app-switcher thumbnail.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.statusBarColor = BG
        window.navigationBarColor = SURFACE
        Session.init(this)
        audio = CallAudio(this)
        egl = EglBase.create()
        showHome(intent?.getStringExtra(Intent.EXTRA_TEXT)?.let { Invite.find(it) })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val invite = intent.getStringExtra(Intent.EXTRA_TEXT)?.let { Invite.find(it) } ?: return
        if (session == null) showHome(invite)
    }

    override fun onDestroy() {
        leaveRoom(showHomeAfter = false)
        egl.release()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (session != null) leaveRoom() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    // ---------- home ----------

    private fun showHome(prefill: Invite?) {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val root = column(pad = 20)
        root.addView(text("Ghostline", 30f, TEXT, bold = true))
        root.addView(text("Private chat and voice calls between two people. No accounts, no history.", 16f, MUTED).spaced(4, 20))

        val server = edit("Server, e.g. https://ghostline.onrender.com", prefs.getString("server", "") ?: "")
        server.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        root.addView(server)
        root.addView(button("Create private room", primary = true) {
            val origin = normalizeOrigin(server.text.toString())
            if (origin == null) {
                toast("Enter the server address, starting with https://")
                return@button
            }
            prefs.edit().putString("server", origin).apply()
            enterRoom(Invite.create(origin), showInvite = true)
        }.spaced(8, 28))

        root.addView(text("Or join with an invite link", 14f, MUTED).spaced(0, 6))
        val link = edit("Paste the invite link", prefill?.link ?: "")
        link.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        root.addView(link)
        root.addView(button("Join", primary = true) {
            val invite = Invite.parse(link.text.toString())
            if (invite == null) toast("That doesn't look like a Ghostline invite link.") else enterRoom(invite, showInvite = false)
        }.spaced(8, 28))

        root.addView(
            text(
                "No app can protect you from spyware already on your phone that records the screen, " +
                    "keyboard or microphone. Use a phone you trust.",
                13f, Color.parseColor("#f3d38b"),
            ).apply {
                background = rounded(Color.parseColor("#2a2211"), 10)
                setPadding(dp(12), dp(10), dp(12), dp(10))
            },
        )
        setContentView(ScrollView(this).apply { setBackgroundColor(BG); addView(root) })
    }

    private fun normalizeOrigin(s: String): String? {
        val t = s.trim().trimEnd('/')
        val m = Regex("^(https?)://([A-Za-z0-9.-]+)(:\\d+)?$").find(t) ?: return null
        return if (m.groupValues[1] == "http" && m.groupValues[2] !in setOf("localhost", "127.0.0.1")) null else t
    }

    // ---------- room ----------

    private fun enterRoom(invite: Invite, showInvite: Boolean) {
        verifiedCode = null
        currentCode = null
        ready = false
        muted = false
        peerVideo = false
        callState = CallState.IDLE
        buildRoom(invite, showInvite)
        session = Session(this, invite, this, audio, egl, remoteView!!).also { it.start() }
    }

    private fun leaveRoom(showHomeAfter: Boolean = true) {
        val s = session ?: return
        session = null
        s.hangUp()
        s.close()
        remoteView?.release()
        remoteView = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (showHomeAfter) showHome(null)
    }

    private fun buildRoom(invite: Invite, showInvite: Boolean) {
        val root = column(pad = 0)

        // header
        val bar = column(pad = 12).apply { setBackgroundColor(SURFACE) }
        val statusRow = row()
        dot = View(this).apply { background = circle(WAIT) }
        statusRow.addView(dot, LinearLayout.LayoutParams(dp(10), dp(10)).apply { rightMargin = dp(8) })
        statusText = text("Connecting…", 14f, TEXT)
        statusRow.addView(statusText)
        bar.addView(statusRow)
        val actions = row().spaced(8, 0)
        voiceBtn = button("Voice call") { withMic { session?.startCall() } }.apply { isEnabled = false }
        actions.addView(voiceBtn, weighted())
        actions.addView(button("Invite") { invitePanel.visibility = if (invitePanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE }, weighted())
        actions.addView(button("Leave", danger = true) { leaveRoom() }, weighted())
        bar.addView(actions)
        root.addView(bar)

        // invite
        invitePanel = column(pad = 12).apply {
            visibility = if (showInvite) View.VISIBLE else View.GONE
            addView(text("Send this link to one person over a channel you trust. Anyone with it can join (max 2 people).", 13f, MUTED))
            addView(text(invite.link, 12f, TEXT).apply { setTextIsSelectable(true); typeface = Typeface.MONOSPACE }.spaced(6, 6))
            val r = row()
            r.addView(button("Copy") { copyLink(invite.link) }, weighted())
            r.addView(button("Share") {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, invite.link), "Send invite"))
            }, weighted())
            addView(r)
        }
        root.addView(invitePanel)

        // safety code
        val safety = column(pad = 12)
        val sr = row()
        sr.addView(text("Safety code: ", 13f, MUTED))
        safetyText = text("—", 15f, TEXT, bold = true).apply { typeface = Typeface.MONOSPACE }
        sr.addView(safetyText)
        verifyBtn = button("Mark verified") {
            verifiedCode = currentCode
            onSystem("Safety code marked as verified. You will be warned if it changes.")
            renderSafety()
        }.apply { visibility = View.GONE; textSize = 12f }
        sr.addView(verifyBtn, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(8) })
        safety.addView(sr)
        safety.addView(text("Read it out loud on a call. If both screens match, nobody is listening in the middle.", 12f, MUTED))
        root.addView(safety)

        // incoming call
        incomingPanel = column(pad = 12).apply {
            visibility = View.GONE
            background = rounded(SURFACE, 12, ACCENT)
            addView(text("Incoming call", 16f, TEXT, bold = true))
            val r = row().spaced(8, 0)
            r.addView(button("Answer", primary = true) { withMic(onDenied = { session?.declineCall() }) { session?.acceptCall() } }, weighted())
            r.addView(button("Decline", danger = true) { session?.declineCall() }, weighted())
            addView(r)
        }
        root.addView(incomingPanel, margins(12))

        // active / outgoing call
        callPanel = column(pad = 12).apply {
            visibility = View.GONE
            background = rounded(Color.BLACK, 12)
            remoteView = SurfaceViewRenderer(this@MainActivity).apply {
                init(egl.eglBaseContext, null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                setEnableHardwareScaler(true)
                visibility = View.GONE
            }
            addView(remoteView, LinearLayout.LayoutParams(MATCH_PARENT, dp(260)).apply { bottomMargin = dp(8) })
            callLabel = text("Calling…", 16f, TEXT, bold = true).apply { gravity = Gravity.CENTER }
            addView(callLabel, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            val r = row().spaced(8, 0)
            muteBtn = button("Mute") {
                muted = !muted
                session?.setMuted(muted)
                muteBtn.text = if (muted) "Unmute" else "Mute"
            }
            r.addView(muteBtn, weighted())
            speakerBtn = button("Earpiece") {
                speakerBtn.text = if (audio.toggleSpeaker()) "Earpiece" else "Speaker"
            }
            r.addView(speakerBtn, weighted())
            r.addView(button("Hang up", danger = true) { session?.hangUp() }, weighted())
            addView(r)
        }
        root.addView(callPanel, margins(12))

        // messages
        messages = column(pad = 12)
        messagesScroll = ScrollView(this).apply { addView(messages); isFillViewport = true }
        root.addView(messagesScroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        // composer
        val composer = row().apply { setBackgroundColor(SURFACE); setPadding(dp(8), dp(8), dp(8), dp(8)) }
        ttl = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, TTL_LABELS)
        }
        composer.addView(ttl)
        input = edit("Message", "").apply {
            isEnabled = false
            imeOptions = EditorInfo.IME_ACTION_SEND
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { send(); true } else false }
        }
        composer.addView(input, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        sendBtn = button("Send", primary = true) { send() }.apply { isEnabled = false }
        composer.addView(sendBtn)
        composer.addView(button("Clear") {
            messages.removeAllViews()
            session?.clearChat()
        })
        root.addView(composer)

        setContentView(root.apply { setBackgroundColor(BG) })
    }

    private fun send() {
        val text = input.text.toString().trim().take(4000)
        if (text.isEmpty() || !ready) return
        session?.sendMessage(text, TTL_SECONDS[ttl.selectedItemPosition])
        input.setText("")
    }

    private fun copyLink(link: String) {
        val clip = ClipData.newPlainText("Ghostline invite", link)
        // Keep the room key out of the clipboard preview and history where supported.
        if (Build.VERSION.SDK_INT >= 33) {
            clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        }
        getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        toast("Copied")
    }

    private fun withMic(onDenied: (() -> Unit)? = null, action: () -> Unit) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            action()
            return
        }
        pendingMicAction = action
        pendingMicDenied = onDenied
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
    }

    private var pendingMicDenied: (() -> Unit)? = null

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        if (granted) pendingMicAction?.invoke() else {
            onSystem("Microphone permission is needed for calls.")
            pendingMicDenied?.invoke()
        }
        pendingMicAction = null
        pendingMicDenied = null
    }

    // ---------- SessionUi ----------

    override fun onStatus(text: String, kind: String) {
        if (session == null) return
        statusText.text = if (kind == "ok" && verifiedCode != null && verifiedCode == currentCode) "$text · verified" else text
        dot.background = circle(when (kind) { "ok" -> OK; "bad" -> DANGER; else -> WAIT })
    }

    override fun onSystem(text: String) {
        if (session == null) return
        messages.addView(text(text, 12f, MUTED).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) })
        scrollDown()
    }

    override fun onMessage(text: String, mine: Boolean, ttl: Int) {
        if (session == null) return
        val bubble = column(pad = 0).apply {
            background = rounded(if (mine) ME else THEM, 14)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            addView(text(text, 15f, TEXT).apply { setTextIsSelectable(true) })
            val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date()) +
                if (ttl > 0) " · disappears in ${if (ttl >= 60) "${ttl / 60}m" else "${ttl}s"}" else ""
            addView(text(time, 11f, MUTED))
        }
        messages.addView(bubble, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            topMargin = dp(6)
            gravity = if (mine) Gravity.END else Gravity.START
            if (mine) leftMargin = dp(48) else rightMargin = dp(48)
        })
        scrollDown()
        if (ttl > 0) bubble.postDelayed({ messages.removeView(bubble) }, ttl * 1000L)
    }

    override fun onClear() {
        if (session != null) messages.removeAllViews()
    }

    override fun onSafety(code: String?) {
        if (session == null) return
        if (code != null && verifiedCode != null && code != verifiedCode) {
            onSystem("⚠ The safety code changed: the other person reconnected, or someone else joined with the link. Verify it again before saying anything sensitive.")
        }
        currentCode = code
        renderSafety()
    }

    private fun renderSafety() {
        val code = currentCode
        val verified = code != null && code == verifiedCode
        safetyText.text = if (verified) "$code ✓" else code ?: "—"
        safetyText.setTextColor(if (verifiedCode != null && code != null && !verified) DANGER else if (verified) OK else TEXT)
        verifyBtn.visibility = if (code != null && !verified) View.VISIBLE else View.GONE
        if (ready) onStatus(Session.CONNECTED, "ok")
    }

    override fun onReady(ready: Boolean) {
        if (session == null) return
        this.ready = ready
        input.isEnabled = ready
        sendBtn.isEnabled = ready
        voiceBtn.isEnabled = ready && callPanel.visibility == View.GONE && incomingPanel.visibility == View.GONE
        if (ready) invitePanel.visibility = View.GONE // don't leave the room key on screen
    }

    override fun onPeerVideo(on: Boolean) {
        if (session == null) return
        peerVideo = on
        renderVideo()
    }

    private fun renderVideo() {
        val show = peerVideo && callState == CallState.ACTIVE
        remoteView?.visibility = if (show) View.VISIBLE else View.GONE
        callLabel.text = when {
            callState == CallState.OUTGOING -> "Calling…"
            show -> "Video call (your camera stays off in this app)"
            else -> "Voice call"
        }
    }

    override fun onCall(state: CallState) {
        if (session == null) return
        callState = state
        incomingPanel.visibility = if (state == CallState.INCOMING) View.VISIBLE else View.GONE
        callPanel.visibility = if (state == CallState.OUTGOING || state == CallState.ACTIVE) View.VISIBLE else View.GONE
        renderVideo()
        voiceBtn.isEnabled = ready && state == CallState.IDLE
        if (state == CallState.IDLE) {
            muted = false
            muteBtn.text = "Mute"
        }
        speakerBtn.text = if (audio.speaker) "Earpiece" else "Speaker"
        if (state == CallState.INCOMING) vibrate()
        if (state == CallState.IDLE) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrate() {
        getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 300, 400, 300, 400), -1))
    }

    private fun scrollDown() = messagesScroll.post { messagesScroll.fullScroll(View.FOCUS_DOWN) }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ---------- tiny view helpers ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun column(pad: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
    }

    private fun row() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun edit(hint: String, value: String) = EditText(this).apply {
        this.hint = hint
        setText(value)
        setTextColor(TEXT)
        setHintTextColor(MUTED)
        textSize = 15f
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
    }

    private fun button(label: String, primary: Boolean = false, danger: Boolean = false, onClick: () -> Unit) =
        Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(if (danger) DANGER else Color.WHITE)
            background = rounded(if (primary) ACCENT else SURFACE2, 10, if (danger) DANGER else null)
            setOnClickListener { onClick() }
        }

    private fun rounded(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        stroke?.let { setStroke(dp(1), it) }
    }

    private fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun weighted() = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { setMargins(dp(3), 0, dp(3), 0) }
    private fun margins(m: Int) = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { setMargins(dp(m), dp(6), dp(m), dp(6)) }

    private fun <T : View> T.spaced(top: Int, bottom: Int): T = apply {
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = dp(top)
            bottomMargin = dp(bottom)
        }
    }

    companion object {
        private val BG = Color.parseColor("#0d0f12")
        private val SURFACE = Color.parseColor("#161a20")
        private val SURFACE2 = Color.parseColor("#1f252d")
        private val TEXT = Color.parseColor("#e7eaee")
        private val MUTED = Color.parseColor("#8b95a3")
        private val ACCENT = Color.parseColor("#4f8cff")
        private val OK = Color.parseColor("#3ccf8e")
        private val WAIT = Color.parseColor("#f2b84b")
        private val DANGER = Color.parseColor("#ef5b5b")
        private val ME = Color.parseColor("#2b4a86")
        private val THEM = Color.parseColor("#262c35")
        private val TTL_LABELS = listOf("Keep", "30s", "5m", "1h")
        private val TTL_SECONDS = listOf(0, 30, 300, 3600)
    }
}
