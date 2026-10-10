package app.plainnotes

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.util.TypedValue
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import java.text.DateFormat
import java.util.Date
import kotlin.concurrent.thread

class MainActivity : Activity(), HubUi {
    private enum class Screen { NOTES, PIN, HOME, PAIR, ROOM, DISGUISE }

    private lateinit var lock: Lock
    private var locked = false // showing the Notes disguise; Ghostline isn't connected
    private var disguiseEditing = false
    private var focusNotes = false
    private var pendingPhrase: String? = null // the phrase note that led to the PIN screen
    private var working = false // re-encrypting the rooms; the hub is stopped meanwhile
    private var started = false
    private var hub: Hub? = null
    private lateinit var audio: CallAudio
    private lateinit var egl: EglBase
    private val main = Handler(Looper.getMainLooper())
    private var view: HubView? = null
    private var screen = Screen.HOME
    private var roomId: String? = null
    private var pairStep = "create"
    private var callMinimized = false
    private var muted = false
    private var fillVideo = true
    private var lastCallState = CallState.IDLE
    private var builtKey: String? = null // what the current HOME / PAIR screen was built from

    // persistent layers
    private lateinit var content: FrameLayout
    private lateinit var callScreen: FrameLayout
    private lateinit var remoteView: SurfaceViewRenderer
    private lateinit var localView: SurfaceViewRenderer
    private lateinit var callTitle: TextView
    private lateinit var muteBtn: ImageButton
    private lateinit var speakerBtn: ImageButton
    private lateinit var camBtn: ImageButton
    private lateinit var flipBtn: ImageButton
    private lateinit var incoming: LinearLayout
    private lateinit var incomingText: TextView
    private lateinit var callBar: Button

    // room screen
    private var messages: LinearLayout? = null
    private var messagesScroll: ScrollView? = null
    private var renderedMsgs: String? = null
    private var roomRefs: RoomRefs? = null
    private var ttlIndex = 0
    private var expiryText: TextView? = null

    private class RoomRefs(
        val title: TextView, val status: TextView, val dot: View, val avatar: TextView,
        val voice: ImageButton, val video: ImageButton, val banner: LinearLayout, val bannerText: TextView,
        val input: EditText, val send: ImageButton, val timer: ImageButton,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Block screenshots, screen recording and the app-switcher thumbnail.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        Hub.init(this)
        audio = CallAudio(this)
        egl = EglBase.create()
        lock = Lock(this)
        buildLayers()
        if (lock.enabled) {
            locked = true
            show(Screen.NOTES)
        } else {
            startHub()
            show(Screen.HOME)
        }
    }

    override fun onStart() {
        super.onStart()
        started = true
        hub?.setForeground(true)
    }

    override fun onStop() {
        started = false
        hub?.setForeground(false)
        // With the disguise on, leaving the app locks it again, unless a call is going on. While
        // re-encrypting it waits, and locks once that's done.
        val call = view?.call
        if (lock.enabled && !locked && !working && (call == null || !inCall(call))) relock()
        else if (screen == Screen.PIN) show(Screen.NOTES)
        super.onStop()
    }

    private fun unlock() {
        hideKeyboard()
        locked = false
        pendingPhrase = null
        startHub()
        show(Screen.HOME)
    }

    private fun relock() {
        locked = true
        pendingPhrase = null
        lock.relock()
        hub?.close()
        hub = null
        view = null
        lastCallState = CallState.IDLE
        callMinimized = false
        incoming.visibility = View.GONE
        callBar.visibility = View.GONE
        callScreen.visibility = View.GONE
        setCallFullScreen(false)
        show(Screen.NOTES)
    }

    override fun onDestroy() {
        hub?.close()
        hub = null
        remoteView.release()
        localView.release()
        egl.release()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val call = view?.call
        when {
            call != null && inCall(call) && !callMinimized -> {
                callMinimized = true
                render()
            }
            screen == Screen.ROOM || screen == Screen.DISGUISE -> show(Screen.HOME)
            screen == Screen.PIN -> show(Screen.NOTES)
            screen == Screen.PAIR -> {
                hub?.pairCancel()
                show(Screen.HOME)
            }
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    private fun server() = getSharedPreferences("settings", MODE_PRIVATE).getString("server", "") ?: ""

    private fun startHub() {
        hub?.close()
        hub = null
        val origin = server()
        if (origin.isEmpty()) return
        hub = Hub(this, origin, this, audio, egl, remoteView, localView, lock.dataKey).also { it.start() }
    }

    override fun onView(view: HubView) {
        if (locked) return // a late update from the hub that relock() just closed
        this.view = view
        val call = view.call
        if (call.state == CallState.INCOMING && lastCallState != CallState.INCOMING) vibrate()
        if (call.state == CallState.IDLE && lastCallState != CallState.IDLE) {
            muted = false
            callMinimized = false
        }
        lastCallState = call.state
        if (screen == Screen.ROOM && view.rooms.none { it.id == roomId }) show(Screen.HOME) else render()
    }

    override fun onNotice(text: String) {
        if (locked) return
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Room closed").setMessage(text).setPositiveButton("OK", null).show()
    }

    private fun inCall(c: CallView) = c.state == CallState.OUTGOING || c.state == CallState.ACTIVE

    // ---------- navigation ----------

    private fun show(s: Screen, room: String? = null) {
        screen = s
        roomId = if (s == Screen.ROOM) room else null
        hub?.setOpenRoom(roomId)
        builtKey = null
        renderedMsgs = null
        roomRefs = null
        messages = null
        content.removeAllViews()
        setBars(light = s == Screen.NOTES || s == Screen.PIN)
        keepScreenOn()
        if (s == Screen.ROOM) content.addView(buildRoom())
        render()
    }

    private fun render() {
        if (working) {
            rebuildIfChanged("working") {
                column(pad = 32).apply {
                    gravity = Gravity.CENTER
                    setBackgroundColor(BG)
                    addView(text("Encrypting your rooms…", 16f, MUTED).apply { gravity = Gravity.CENTER })
                }
            }
            return
        }
        when (screen) {
            Screen.NOTES -> rebuildIfChanged("notes|" + notes()) { buildNotes() }
            Screen.PIN -> rebuildIfChanged("pin") { buildPin() }
            Screen.DISGUISE -> rebuildIfChanged("disguise|${lock.enabled}|$disguiseEditing") { buildDisguise() }
            Screen.HOME -> rebuildIfChanged(homeKey()) { buildHome() }
            Screen.PAIR -> rebuildIfChanged(pairKey()) { buildPair() }
            Screen.ROOM -> renderRoom()
        }
        renderCall()
    }

    // Rebuild only when what the screen shows changed, so typing in a field isn't interrupted.
    private fun rebuildIfChanged(key: String, build: () -> View) {
        if (key == builtKey) return
        builtKey = key
        content.removeAllViews()
        content.addView(build())
    }

    private fun homeKey(): String {
        val v = view
        return "${server()}|${hub != null}|${lock.enabled}|${v?.connected}|" + v?.rooms?.joinToString(";") {
            "${it.id},${it.name},${it.preview},${it.unread},${it.ready},${it.ended}"
        }
    }

    private fun pairKey(): String {
        val p = view?.pair
        return "$pairStep|${p?.step}|${p?.code}|${p?.status}|${p?.bad}|${p?.expiresAt}"
    }

    // ---------- home ----------

    private fun buildHome(): View {
        val v = view
        val root = column(pad = 0)
        val top = row().apply { setPadding(dp(20), dp(20), dp(12), dp(8)) }
        top.addView(text("Ghostline", 30f, TEXT, bold = true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        if (hub != null && v != null && !v.connected) top.addView(text("Connecting…", 13f, WAIT))
        root.addView(top)

        if (server().isEmpty()) {
            val box = column(pad = 20)
            box.addView(text("Enter your Ghostline server to start.", 15f, MUTED).spaced(0, 8))
            val field = field("https://your-server.example.com", "")
            field.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            box.addView(field)
            box.addView(button("Save", primary = true) { saveServer(field.text.toString()) }.spaced(12, 0))
            root.addView(box)
            return scroll(root)
        }

        val actions = row().apply { setPadding(dp(17), dp(8), dp(17), dp(8)) }
        actions.addView(button("New room", primary = true) {
            pairStep = "create"
            show(Screen.PAIR)
            hub?.pairCreate()
        }, weighted())
        actions.addView(button("Join with code") {
            pairStep = "join"
            show(Screen.PAIR)
        }, weighted())
        root.addView(actions)

        val rooms = v?.rooms.orEmpty()
        if (rooms.isEmpty()) {
            root.addView(text("No rooms yet.\nTap New room to get a code, or Join with code if someone gave you one.", 15f, MUTED).apply {
                gravity = Gravity.CENTER
                setLineSpacing(0f, 1.2f)
                setPadding(dp(32), dp(48), dp(32), dp(48))
            })
        } else {
            root.addView(text("ROOMS", 12f, MUTED, bold = true).apply { letterSpacing = 0.08f; setPadding(dp(20), dp(16), dp(20), dp(4)) })
            for (r in rooms) root.addView(roomRow(r))
        }

        root.addView(
            text(
                "No app can protect you from spyware already on your phone that records the screen, keyboard or microphone. Use a phone you trust.",
                12f, Color.parseColor("#cdb67a"),
            ).apply {
                background = rounded(Color.parseColor("#1f1a0e"), 12)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                setLineSpacing(0f, 1.15f)
            }.spaced(24, 0, 20),
        )
        root.addView(text("Disguise as Notes · ${if (lock.enabled) "On" else "Off"}", 12f, MUTED).apply {
            setPadding(dp(20), dp(14), dp(20), 0)
            background = ripple()
            setOnClickListener {
                disguiseEditing = false
                show(Screen.DISGUISE)
            }
        })
        root.addView(text("Server · ${server().removePrefix("https://")}", 12f, MUTED).apply {
            setPadding(dp(20), dp(14), dp(20), dp(24))
            background = ripple()
            setOnClickListener { askServer() }
        })
        return scroll(root)
    }

    // ---------- notes (the disguise) ----------

    private fun notes(): JSONArray = try {
        JSONArray(getSharedPreferences("notes", MODE_PRIVATE).getString("items", "[]"))
    } catch (e: Exception) {
        JSONArray()
    }

    private fun saveNotes(a: JSONArray) {
        getSharedPreferences("notes", MODE_PRIVATE).edit().putString("items", a.toString()).apply()
        render()
    }

    private fun buildNotes(): View {
        val items = notes()
        val root = column(pad = 0)
        val top = column(pad = 0).apply { setPadding(dp(20), dp(24), dp(20), dp(12)) }
        top.addView(text("Notes", 30f, N_TEXT, bold = true))
        top.addView(text(DateFormat.getDateInstance(DateFormat.FULL).format(Date()), 13f, N_MUTED).apply { setPadding(0, dp(2), 0, 0) })
        root.addView(top)

        val composer = row().apply { setPadding(dp(16), dp(4), dp(16), dp(12)) }
        val input = edit("Add a note", "").apply {
            setTextColor(N_TEXT)
            background = rounded(Color.WHITE, 12, N_LINE)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            // the secret phrase is typed here, so the keyboard mustn't learn it or suggest it later
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            filters = arrayOf(InputFilter.LengthFilter(200))
        }
        input.setOnEditorActionListener { _, _, e -> if (e?.action != KeyEvent.ACTION_UP) addNote(input); true }
        composer.addView(input, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { rightMargin = dp(8) })
        composer.addView(iconButton(R.drawable.ic_add, "Add note", tint = Color.WHITE, bg = N_ACCENT, size = 46) { addNote(input) })
        root.addView(composer)

        if (items.length() == 0) {
            root.addView(text("No notes yet.", 15f, N_MUTED).apply {
                gravity = Gravity.CENTER
                setPadding(dp(32), dp(48), dp(32), dp(48))
            })
        }
        for (i in 0 until items.length()) root.addView(noteRow(items, i))

        if (focusNotes) {
            focusNotes = false
            focusLater(input)
        }
        return ScrollView(this).apply {
            setBackgroundColor(N_BG)
            isFillViewport = true
            addView(root)
        }
    }

    private fun noteRow(items: JSONArray, i: Int): View {
        val note = items.getJSONObject(i)
        val done = note.optBoolean("done")
        val item = row().apply {
            setPadding(dp(20), dp(14), dp(20), dp(14))
            background = ripple(bounded = true)
            setOnClickListener {
                note.put("done", !done)
                saveNotes(items)
            }
            setOnLongClickListener {
                AlertDialog.Builder(this@MainActivity, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                    .setMessage("Delete this note?")
                    .setPositiveButton("Delete") { _, _ -> items.remove(i); saveNotes(items) }
                    .setNegativeButton("Cancel", null)
                    .show()
                true
            }
        }
        item.addView(View(this).apply {
            background = if (done) circle(N_ACCENT) else circle(Color.TRANSPARENT).apply { setStroke(dp(2), N_MUTED) }
        }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { rightMargin = dp(14) })
        item.addView(text(note.optString("t"), 16f, if (done) N_MUTED else N_TEXT).apply {
            if (done) paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        return item
    }

    private fun addNote(input: EditText) {
        val t = input.text.toString().trim()
        if (t.isEmpty()) return
        input.setText("")
        if (lock.isPhrase(t)) {
            pendingPhrase = t
            show(Screen.PIN)
            return
        }
        val items = notes()
        items.put(JSONObject().put("t", t).put("done", false))
        focusNotes = true
        saveNotes(items)
    }

    // Looks like a notes app's locked note. A wrong PIN just goes back to the notes.
    private fun buildPin(): View {
        val root = column(pad = 24).apply {
            setBackgroundColor(N_BG)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(32), dp(96), dp(32), dp(32))
        }
        root.addView(text("Locked note", 22f, N_TEXT, bold = true).apply { gravity = Gravity.CENTER })
        root.addView(text("Enter the PIN to view this note.", 14f, N_MUTED).apply { gravity = Gravity.CENTER }.spaced(6, 0))
        val field = edit("PIN", "").apply {
            setTextColor(N_TEXT)
            textSize = 26f
            gravity = Gravity.CENTER
            letterSpacing = 0.3f
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            filters = arrayOf(InputFilter.LengthFilter(12))
            background = rounded(Color.WHITE, 12, N_LINE)
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        lateinit var openButton: Button
        // Unwrapping the data key takes a moment, so it runs off the main thread.
        val open: () -> Unit = open@{
            if (!field.isEnabled) return@open
            val phrase = pendingPhrase.orEmpty()
            val pin = field.text.toString()
            field.isEnabled = false
            openButton.isEnabled = false
            openButton.text = "Opening…"
            thread {
                val ok = try {
                    lock.unlock(phrase, pin)
                } catch (e: Exception) {
                    Log.e("Notes", "unlock failed", e)
                    false
                }
                runOnUiThread {
                    if (ok && screen == Screen.PIN && started) {
                        unlock()
                    } else {
                        lock.relock() // also if they left while it was opening
                        if (screen == Screen.PIN) {
                            hideKeyboard()
                            show(Screen.NOTES)
                        }
                    }
                }
            }
        }
        field.setOnEditorActionListener { _, _, e -> if (e?.action != KeyEvent.ACTION_UP) open(); true }
        root.addView(field, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(28) })
        openButton = button("Open", primary = true) { open() }.apply { background = rounded(N_ACCENT, 14) }
        root.addView(openButton.spaced(14, 0))
        root.addView(text("Cancel", 15f, N_MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(14))
            setOnClickListener { hideKeyboard(); show(Screen.NOTES) }
        }.spaced(4, 0))
        focusLater(field)
        return root
    }

    // Focus a field once it's on screen; asking before it's attached is ignored.
    private fun focusLater(field: EditText) = field.post {
        field.requestFocus()
        getSystemService(InputMethodManager::class.java)?.showSoftInput(field, 0)
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(window.decorView.windowToken, 0)
    }

    // ---------- disguise settings ----------

    private fun buildDisguise(): View {
        val root = column(pad = 0)
        val top = row().apply { setPadding(dp(4), dp(8), dp(16), dp(8)) }
        top.addView(iconButton(R.drawable.ic_back, "Back") { show(Screen.HOME) })
        top.addView(text("Disguise as Notes", 20f, TEXT, bold = true))
        root.addView(top)
        val body = column(pad = 20)
        root.addView(body)

        if (lock.enabled && !disguiseEditing) {
            body.addView(text(
                "On. Ghostline opens as a Notes app. To get in, add a note that is your secret phrase, then enter your PIN.\n\n" +
                    "Your rooms are encrypted with a key that only your phrase and PIN can unlock. " +
                    "It locks again whenever you leave the app, except during a call. While locked you are offline, " +
                    "so messages arrive when you next open it.",
                15f, MUTED,
            ).apply { setLineSpacing(0f, 1.15f) })
            body.addView(button("Change phrase and PIN") { disguiseEditing = true; render() }.spaced(20, 0))
            body.addView(button("Turn off", danger = true) {
                confirm(
                    "Turn off the disguise?",
                    "Ghostline will open normally again, and your rooms will no longer need the PIN to be read.",
                    "Turn off",
                ) {
                    rekeying({ lock.disable() }) { show(Screen.DISGUISE) }
                }
            }.spaced(10, 0))
            return scroll(root)
        }

        body.addView(text(
            "Ghostline will open as a Notes app. Typing your secret phrase as a new note asks for your PIN, and the right PIN opens Ghostline.\n\n" +
                "Your rooms will be encrypted with a key that only your phrase and PIN unlock. Pick a phrase you would never write as a real note. " +
                "If you forget either one, nobody can recover your rooms; the only way back in is to clear the app's data, which deletes them.",
            15f, MUTED,
        ).apply { setLineSpacing(0f, 1.15f) })
        val phrase = field("Secret phrase", "").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            filters = arrayOf(InputFilter.LengthFilter(200))
        }
        body.addView(phrase, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(20) })
        val pin = field("PIN (4–12 digits)", "").apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(12))
        }
        body.addView(pin, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) })
        val again = field("PIN again", "").apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(12))
        }
        body.addView(again, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) })
        body.addView(button(if (lock.enabled) "Save" else "Turn on", primary = true) {
            val p = pin.text.toString()
            when {
                Lock.normalize(phrase.text.toString()).length < 4 -> toast("The phrase needs at least 4 characters.")
                !Regex("^\\d{4,12}$").matches(p) -> toast("The PIN needs 4 to 12 digits.")
                p != again.text.toString() -> toast("The PINs don't match.")
                else -> {
                    val ph = phrase.text.toString()
                    val changing = lock.enabled
                    rekeying({ lock.set(ph, p) }) {
                        toast(if (changing) "Phrase and PIN changed." else "Disguise on. It takes effect when you leave the app.")
                        show(Screen.HOME)
                    }
                }
            }
        }.spaced(16, 0))
        return scroll(root)
    }

    // Re-encrypting must not race the hub's own saves, so the hub is stopped while it runs.
    private fun rekeying(work: () -> Unit, done: () -> Unit) {
        val call = view?.call
        if (call != null && call.state != CallState.IDLE) {
            toast("Finish the call first.")
            return
        }
        hideKeyboard()
        val h = hub
        hub = null
        view = null
        h?.close()
        working = true
        render()
        thread {
            h?.awaitClosed()
            val ok = try {
                work()
                true
            } catch (e: Exception) {
                Log.e("Notes", "re-encrypting failed", e)
                false
            }
            runOnUiThread {
                working = false
                if (!ok) toast("Couldn't finish. Please try again.")
                if (!started && lock.enabled) relock() else {
                    startHub()
                    done()
                }
            }
        }
    }

    // Light bars for the Notes disguise, dark ones for Ghostline.
    @Suppress("DEPRECATION")
    private fun setBars(light: Boolean) {
        window.statusBarColor = if (light) N_BG else BG
        window.navigationBarColor = if (light) N_BG else SURFACE
        if (Build.VERSION.SDK_INT >= 30) {
            val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (light) mask else 0, mask)
        } else {
            window.decorView.systemUiVisibility =
                if (light) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0
        }
    }

    private fun roomRow(r: RoomView): View {
        val item = row().apply {
            setPadding(dp(20), dp(12), dp(16), dp(12))
            background = ripple(bounded = true)
            setOnClickListener { show(Screen.ROOM, r.id) }
        }
        val av = FrameLayout(this)
        av.addView(avatar(r.name, 48), FrameLayout.LayoutParams(dp(48), dp(48)))
        av.addView(View(this).apply {
            background = circle(dotColor(if (r.ended) "bad" else if (r.ready) "ok" else "off")).apply { setStroke(dp(2), BG) }
        }, FrameLayout.LayoutParams(dp(14), dp(14), Gravity.BOTTOM or Gravity.END))
        item.addView(av, LinearLayout.LayoutParams(dp(48), dp(48)).apply { rightMargin = dp(14) })
        val body = column(pad = 0)
        body.addView(text(r.name, 16f, TEXT, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        body.addView(text(r.preview, 14f, if (r.unread > 0) TEXT else MUTED).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(2), 0, 0)
        })
        item.addView(body, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val side = column(pad = 0).apply { gravity = Gravity.END }
        side.addView(text(shortTime(r.lastTs), 12f, if (r.unread > 0) ACCENT else MUTED))
        if (r.unread > 0) {
            side.addView(text(if (r.unread > 99) "99+" else r.unread.toString(), 12f, Color.WHITE, bold = true).apply {
                gravity = Gravity.CENTER
                minWidth = dp(22)
                background = rounded(ACCENT, 11)
                setPadding(dp(7), dp(2), dp(7), dp(2))
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(4); gravity = Gravity.END })
        }
        item.addView(side, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(8) })
        return item
    }

    private fun saveServer(input: String) {
        val t = input.trim().trimEnd('/')
        val m = Regex("^(https?)://([A-Za-z0-9.-]+)(:\\d+)?$").find(t)
        if (m == null || (m.groupValues[1] == "http" && m.groupValues[2] !in setOf("localhost", "127.0.0.1"))) {
            toast("Enter the server address, starting with https://")
            return
        }
        getSharedPreferences("settings", MODE_PRIVATE).edit().putString("server", t).apply()
        startHub()
        show(Screen.HOME)
    }

    private fun askServer() {
        val field = edit("https://…", server())
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Server")
            .setMessage("Both people must use the same server. Your rooms stay on this phone.")
            .setView(FrameLayout(this).apply { setPadding(dp(20), 0, dp(20), 0); addView(field) })
            .setPositiveButton("Save") { _, _ -> saveServer(field.text.toString()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- pairing ----------

    private fun buildPair(): View {
        val p = view?.pair
        val step = p?.step ?: pairStep
        val root = column(pad = 0)
        val top = row().apply { setPadding(dp(4), dp(8), dp(16), dp(8)) }
        top.addView(iconButton(R.drawable.ic_back, "Back") { hub?.pairCancel(); show(Screen.HOME) })
        top.addView(text(when (step) { "join" -> "Join with code"; "name" -> "Room ready"; else -> "New room" }, 20f, TEXT, bold = true))
        root.addView(top)
        val body = column(pad = 20)
        root.addView(body)
        expiryText = null

        when (step) {
            "create" -> {
                body.addView(text("Share this code with one person. It works once and expires in 10 minutes.", 15f, MUTED).apply { setLineSpacing(0f, 1.15f) })
                body.addView(text(p?.code ?: "· · ·", 44f, TEXT, bold = true).apply {
                    typeface = Typeface.MONOSPACE
                    gravity = Gravity.CENTER
                    letterSpacing = 0.12f
                    background = rounded(SURFACE, 18, BORDER)
                    setPadding(0, dp(28), 0, dp(28))
                    setTextIsSelectable(true)
                }.spaced(24, 10))
                expiryText = text("", 13f, MUTED).apply { gravity = Gravity.CENTER }
                body.addView(expiryText, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                updateExpiry()
            }
            "join" -> {
                body.addView(text("Enter the 6-letter code shown on the other person's screen.", 15f, MUTED))
                val field = field("ABC-DEF", "").apply {
                    textSize = 30f
                    typeface = Typeface.MONOSPACE
                    gravity = Gravity.CENTER
                    letterSpacing = 0.12f
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
                        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    filters = arrayOf(InputFilter.LengthFilter(7), InputFilter.AllCaps())
                    imeOptions = EditorInfo.IME_ACTION_GO
                    setPadding(dp(16), dp(18), dp(16), dp(18))
                    setOnEditorActionListener { tv, _, _ -> hub?.pairJoin(tv.text.toString()); true }
                }
                body.addView(field, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(20) })
                body.addView(button("Join", primary = true) { hub?.pairJoin(field.text.toString()) }.spaced(14, 0))
                field.requestFocus()
            }
            "name" -> {
                val ok = row()
                ok.addView(ImageView(this).apply { setImageResource(R.drawable.ic_shield); imageTintList = ColorStateList.valueOf(OK) },
                    LinearLayout.LayoutParams(dp(28), dp(28)).apply { rightMargin = dp(10) })
                ok.addView(text("Paired", 20f, OK, bold = true))
                body.addView(ok)
                body.addView(text("Give this room a name. Only you see it.", 15f, MUTED).spaced(12, 0))
                val field = field("e.g. Alex", "").apply { filters = arrayOf(InputFilter.LengthFilter(40)) }
                body.addView(field, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(14) })
                body.addView(button("Save", primary = true) {
                    hub?.pairSave(field.text.toString()) { id -> show(Screen.ROOM, id) }
                }.spaced(14, 0))
                field.requestFocus()
            }
        }
        val status = p?.status.orEmpty()
        if (status.isNotEmpty()) body.addView(text(status, 14f, if (p?.bad == true) DANGER else MUTED).apply { gravity = Gravity.CENTER }.spaced(18, 0))
        return scroll(root)
    }

    // The countdown updates only its own text, so the screen isn't rebuilt every second.
    private fun updateExpiry() {
        val t = expiryText ?: return
        val p = view?.pair
        if (p?.code == null || p.expiresAt <= 0) { t.text = ""; return }
        val left = ((p.expiresAt - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
        t.text = "Expires in ${left / 60}:${"%02d".format(left % 60)}"
        main.removeCallbacks(tick)
        if (left > 0) main.postDelayed(tick, 1000)
    }

    private val tick = Runnable { if (screen == Screen.PAIR) updateExpiry() }

    // ---------- room ----------

    private fun buildRoom(): View {
        val root = column(pad = 0)
        val bar = row().apply { setBackgroundColor(SURFACE); setPadding(dp(2), dp(6), dp(4), dp(6)) }
        bar.addView(iconButton(R.drawable.ic_back, "Back") { show(Screen.HOME) })
        val av = avatar("", 38)
        bar.addView(av, LinearLayout.LayoutParams(dp(38), dp(38)).apply { rightMargin = dp(10) })
        val titleBox = column(pad = 0)
        val title = text("", 16f, TEXT, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        titleBox.addView(title)
        val statusRow = row()
        val dot = View(this)
        statusRow.addView(dot, LinearLayout.LayoutParams(dp(7), dp(7)).apply { rightMargin = dp(5) })
        val status = text("", 12f, MUTED).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        statusRow.addView(status)
        titleBox.addView(statusRow)
        bar.addView(titleBox, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val voice = iconButton(R.drawable.ic_call, "Voice call") { roomId?.let { id -> withPermissions(video = false) { hub?.startCall(id, false) } } }
        val video = iconButton(R.drawable.ic_video, "Video call") { roomId?.let { id -> withPermissions(video = true) { cam -> hub?.startCall(id, cam) } } }
        bar.addView(voice)
        bar.addView(video)
        bar.addView(iconButton(R.drawable.ic_more, "Room options") { anchor -> roomMenu(anchor) })
        root.addView(bar)

        // until the safety code is verified, a slim banner offers to check it
        val banner = row().apply {
            setPadding(dp(16), dp(10), dp(16), dp(10))
            visibility = View.GONE
            setOnClickListener { currentRoom()?.let { safetyDialog(it) } }
        }
        banner.addView(ImageView(this).apply { setImageResource(R.drawable.ic_shield); imageTintList = ColorStateList.valueOf(WAIT) },
            LinearLayout.LayoutParams(dp(20), dp(20)).apply { rightMargin = dp(10) })
        val bannerText = text("", 13f, TEXT)
        banner.addView(bannerText, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        banner.addView(text("Check", 13f, ACCENT, bold = true))
        root.addView(banner)

        val list = column(pad = 0).apply { setPadding(dp(12), dp(8), dp(12), dp(12)) }
        val scroll = ScrollView(this).apply { addView(list); isFillViewport = true }
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        messages = list
        messagesScroll = scroll

        val composer = row().apply { setPadding(dp(6), dp(6), dp(8), dp(10)); gravity = Gravity.BOTTOM }
        val timer = iconButton(R.drawable.ic_timer, "Disappearing messages") { anchor -> ttlMenu(anchor) }
        composer.addView(timer)
        val input = field("Message", "").apply {
            imeOptions = EditorInfo.IME_ACTION_SEND
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters = arrayOf(InputFilter.LengthFilter(4000))
            maxLines = 5
            background = rounded(SURFACE2, 22)
            setPadding(dp(16), dp(11), dp(16), dp(11))
        }
        composer.addView(input, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { leftMargin = dp(2); rightMargin = dp(8) })
        val send = iconButton(R.drawable.ic_send, "Send", tint = Color.WHITE, bg = ACCENT, size = 46) { sendMessage() }
        input.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { sendMessage(); true } else false }
        composer.addView(send)
        root.addView(composer)

        roomRefs = RoomRefs(title, status, dot, av, voice, video, banner, bannerText, input, send, timer)
        return root.apply { setBackgroundColor(BG) }
    }

    private fun ttlMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, -1, 0, "Disappearing messages").isEnabled = false
            TTL_LABELS.forEachIndexed { i, label -> menu.add(0, i, i + 1, (if (i == ttlIndex) "✓ " else "    ") + label) }
            setOnMenuItemClickListener { item ->
                if (item.itemId >= 0) {
                    ttlIndex = item.itemId
                    renderRoom()
                }
                true
            }
            show()
        }
    }

    private fun safetyDialog(r: RoomView) {
        val msg = when {
            r.code == null -> "The safety code appears once you're connected."
            r.codeChanged -> "The safety code changed since you verified it. This may be a different device or someone in the middle.\n\n${r.code}\n\nCompare it with the other person again before saying anything sensitive."
            r.verified -> "${r.code}\n\nVerified. You'll be warned if it ever changes."
            else -> "${r.code}\n\nRead it out loud on a call, or compare it in person. If both screens show the same numbers, nobody is in the middle."
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle("Safety code").setMessage(msg).apply {
            if (r.code != null && !r.verified) setPositiveButton("They match") { _, _ -> hub?.verify(r.id) }
            setNegativeButton("Close", null)
        }.show()
    }

    private fun currentRoom() = view?.rooms?.firstOrNull { it.id == roomId }

    private fun sendMessage() {
        val refs = roomRefs ?: return
        val id = roomId ?: return
        val text = refs.input.text.toString().trim()
        if (text.isEmpty() || currentRoom()?.ended == true) return
        hub?.send(id, text, TTL_SECONDS[ttlIndex])
        refs.input.setText("")
    }

    private fun renderRoom() {
        val r = currentRoom() ?: return
        val refs = roomRefs ?: return
        val call = view?.call
        refs.title.text = r.name
        refs.status.text = r.status
        refs.dot.background = circle(dotColor(r.kind))
        styleAvatar(refs.avatar, r.name)
        val idle = call == null || call.state == CallState.IDLE
        for (b in listOf(refs.voice, refs.video)) {
            b.isEnabled = r.ready && idle
            b.alpha = if (b.isEnabled) 1f else 0.35f
        }
        refs.input.isEnabled = !r.ended
        refs.send.isEnabled = !r.ended
        refs.send.alpha = if (r.ended) 0.35f else 1f
        val ttl = TTL_SECONDS[ttlIndex]
        refs.timer.imageTintList = ColorStateList.valueOf(if (ttl > 0) ACCENT else MUTED)
        refs.input.hint = when {
            r.ended -> "This room has ended"
            ttl > 0 -> "Message · disappears after ${TTL_LABELS[ttlIndex]}"
            r.ready -> "Message"
            else -> "Message · sent when they're online"
        }
        when {
            r.codeChanged -> {
                refs.banner.visibility = View.VISIBLE
                refs.banner.setBackgroundColor(Color.parseColor("#3a1717"))
                refs.bannerText.text = "Safety code changed. Check it before saying anything sensitive."
            }
            r.code != null && !r.verified && !r.ended -> {
                refs.banner.visibility = View.VISIBLE
                refs.banner.setBackgroundColor(Color.parseColor("#1f1a0e"))
                refs.bannerText.text = "Compare the safety code to make sure this chat is private."
            }
            else -> refs.banner.visibility = View.GONE
        }

        val key = r.id + ":" + r.msgsKey
        if (key == renderedMsgs) return
        renderedMsgs = key
        val list = messages ?: return
        list.removeAllViews()
        var prevDay: String? = null
        var prevMine: Boolean? = null
        for (m in r.msgs) {
            val day = dayText(m.ts)
            if (day != prevDay) {
                list.addView(pill(day))
                prevDay = day
                prevMine = null
            }
            list.addView(messageView(m, sameSender = !m.sys && prevMine == m.mine))
            prevMine = if (m.sys) null else m.mine
        }
        messagesScroll?.post { messagesScroll?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun pill(s: String) = text(s, 12f, MUTED).apply {
        gravity = Gravity.CENTER
        background = rounded(SURFACE, 10)
        setPadding(dp(10), dp(4), dp(10), dp(4))
        layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(12); bottomMargin = dp(4) }
    }

    private fun messageView(m: MsgView, sameSender: Boolean): View {
        if (m.sys) {
            return text(m.text, 12f, MUTED).apply {
                gravity = Gravity.CENTER
                setLineSpacing(0f, 1.15f)
                setPadding(dp(24), dp(8), dp(24), dp(8))
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            }
        }
        val r = dp(18).toFloat()
        val small = dp(5).toFloat()
        val bubble = column(pad = 0).apply {
            background = GradientDrawable().apply {
                setColor(if (m.mine) ME else THEM)
                // the corner on the sender's side is tucked in, like most chat apps
                cornerRadii = if (m.mine) floatArrayOf(r, r, r, r, small, small, r, r) else floatArrayOf(r, r, r, r, r, r, small, small)
            }
            setPadding(dp(13), dp(8), dp(13), dp(6))
            // explicit WRAP_CONTENT: a vertical LinearLayout's default MATCH_PARENT would squeeze it to the time's width
            addView(text(m.text, 15.5f, TEXT).apply { setTextIsSelectable(true); setLineSpacing(0f, 1.1f) }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            val tick = if (m.mine) when (m.status) { "pending" -> "  🕓"; "sent" -> "  ✓"; "delivered" -> "  ✓✓"; else -> "" } else ""
            val ttl = if (m.ttl > 0) "⏱ " else ""
            addView(text(ttl + shortClock(m.ts) + tick, 11f, if (m.mine) Color.parseColor("#b9c8ea") else MUTED).apply {
                gravity = Gravity.END
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { gravity = Gravity.END; topMargin = dp(2) })
        }
        bubble.layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            topMargin = dp(if (sameSender) 3 else 10)
            gravity = if (m.mine) Gravity.END else Gravity.START
            if (m.mine) leftMargin = dp(56) else rightMargin = dp(56)
        }
        return bubble
    }

    private fun shortClock(ts: Long) = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ts))

    private fun dayText(ts: Long): String {
        val f = DateFormat.getDateInstance(DateFormat.MEDIUM)
        val d = f.format(Date(ts))
        return when (d) {
            f.format(Date()) -> "Today"
            f.format(Date(System.currentTimeMillis() - 86_400_000)) -> "Yesterday"
            else -> d
        }
    }

    private fun shortTime(ts: Long) = if (dayText(ts) == "Today") shortClock(ts) else dayText(ts)

    private fun roomMenu(anchor: View) {
        val r = currentRoom() ?: return
        PopupMenu(this, anchor).apply {
            menu.add(0, 0, 0, if (r.owner) "You own this room" else "You joined this room").isEnabled = false
            menu.add(0, 1, 1, "Rename")
            menu.add(0, 2, 2, "Clear chat")
            if (r.owner && !r.ended) menu.add(0, 4, 3, "Remove participant")
            menu.add(0, 3, 4, if (r.ended) "Delete room" else if (r.owner) "End room for both" else "Leave room")
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> rename(r)
                    2 -> confirm(
                        "Clear chat?",
                        if (r.ready) "Messages are deleted on both devices." else "The other person is offline, so only your copy is deleted.",
                        "Clear",
                    ) { hub?.clearChat(r.id) }
                    3 -> when {
                        r.ended -> confirm("Delete this room?", "Its chat history is deleted from this phone.", "Delete") { hub?.leave(r.id) }
                        r.owner -> confirm(
                            "End this room for both?",
                            "The room and its chat history are deleted on both devices. " +
                                (if (r.ready) "" else "The other person is offline, so this reaches them when they next connect. ") +
                                "This cannot be undone.",
                            "End for both",
                        ) { hub?.endRoom(r.id) }
                        else -> confirm(
                            "Leave this room?",
                            "The room and its chat history are deleted from this phone. " +
                                (if (r.ready) "The owner is told you left. " else "The owner is offline, so they'll just see you as offline. ") +
                                "To talk again you'll need a new code.",
                            "Leave",
                        ) { hub?.leave(r.id) }
                    }
                    4 -> confirm(
                        "Remove the participant?",
                        "They are removed and their copy is deleted. " +
                            (if (r.ready) "" else "They are offline, so this takes effect when they next connect. ") +
                            "You keep the chat history here as a read-only archive.",
                        "Remove",
                    ) { hub?.removeParticipant(r.id) }
                }
                true
            }
            show()
        }
    }

    private fun rename(r: RoomView) {
        val field = edit("Name", r.name).apply { filters = arrayOf(InputFilter.LengthFilter(40)) }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Rename room")
            .setMessage("Only you see this name.")
            .setView(FrameLayout(this).apply { setPadding(dp(20), 0, dp(20), 0); addView(field) })
            .setPositiveButton("Save") { _, _ -> field.text.toString().trim().takeIf { it.isNotEmpty() }?.let { hub?.rename(r.id, it) } }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirm(title: String, message: String, ok: String, action: () -> Unit) {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(ok) { _, _ -> action() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- calls ----------

    private fun buildLayers() {
        content = FrameLayout(this)

        callScreen = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.parseColor("#1b2433"), Color.BLACK))
            visibility = View.GONE
            isClickable = true
        }
        remoteView = SurfaceViewRenderer(this).apply {
            init(egl.eglBaseContext, null)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            setEnableHardwareScaler(true)
            visibility = View.GONE
            // tap to switch between filling the screen and showing the whole picture
            setOnClickListener {
                fillVideo = !fillVideo
                setScalingType(if (fillVideo) RendererCommon.ScalingType.SCALE_ASPECT_FILL else RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            }
        }
        callScreen.addView(remoteView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        localView = SurfaceViewRenderer(this).apply {
            init(egl.eglBaseContext, null)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            setZOrderMediaOverlay(true) // the self-view draws above the other person's video
            setMirror(true)
            visibility = View.GONE
        }
        callScreen.addView(localView, cornerParams())

        // name and state; centred with a big avatar when there's no video
        callAvatar = avatar("", 112).apply { textSize = 44f }
        callScreen.addView(callAvatar, FrameLayout.LayoutParams(dp(112), dp(112), Gravity.CENTER).apply { bottomMargin = dp(120) })
        val top = row().apply { setPadding(dp(20), dp(48), dp(12), dp(12)) }
        callTitle = text("", 20f, Color.WHITE, bold = true).apply { setShadowLayer(8f, 0f, 1f, Color.BLACK); setLineSpacing(0f, 1.1f) }
        top.addView(callTitle, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        top.addView(iconButton(R.drawable.ic_chat, "Back to chat", tint = Color.WHITE, bg = Color.argb(70, 255, 255, 255)) { callMinimized = true; render() })
        callScreen.addView(top, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))

        val controls = row().apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(40), dp(8), dp(40))
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(Color.argb(210, 0, 0, 0), Color.TRANSPARENT))
        }
        muteBtn = callControl(controls, R.drawable.ic_mic, "Mute") { muted = !muted; hub?.setMuted(muted); renderCall() }
        speakerBtn = callControl(controls, R.drawable.ic_speaker, "Speaker") { audio.toggleSpeaker(); renderCall() }
        camBtn = callControl(controls, R.drawable.ic_video, "Camera") {
            if (view?.call?.myVideo == true) hub?.setCamera(false) else withPermissions(video = true) { cam -> if (cam) hub?.setCamera(true) }
        }
        flipBtn = callControl(controls, R.drawable.ic_flip, "Flip") { hub?.flipCamera() }
        callControl(controls, R.drawable.ic_call_end, "End", bg = DANGER) { hub?.hangUp() }
        callScreen.addView(controls, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))

        incoming = column(pad = 16).apply {
            visibility = View.GONE
            background = rounded(SURFACE2, 20, BORDER)
            elevation = dp(10).toFloat()
        }
        val ihead = row()
        incomingAvatar = avatar("", 44)
        ihead.addView(incomingAvatar, LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(12) })
        incomingText = text("Incoming call", 16f, TEXT, bold = true)
        ihead.addView(incomingText, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        incoming.addView(ihead)
        val ir = row().apply { gravity = Gravity.END }.spaced(14, 0)
        ir.addView(iconButton(R.drawable.ic_call_end, "Decline", tint = Color.WHITE, bg = DANGER, size = 52) { hub?.declineCall() },
            LinearLayout.LayoutParams(dp(52), dp(52)).apply { rightMargin = dp(14) })
        ir.addView(iconButton(R.drawable.ic_video, "Answer with video", tint = Color.WHITE, bg = OK, size = 52) {
            withPermissions(video = true, onDenied = { hub?.declineCall() }) { cam -> hub?.acceptCall(cam); openCallRoom() }
        }, LinearLayout.LayoutParams(dp(52), dp(52)).apply { rightMargin = dp(14) })
        ir.addView(iconButton(R.drawable.ic_call, "Answer", tint = Color.WHITE, bg = OK, size = 52) {
            withPermissions(video = false, onDenied = { hub?.declineCall() }) { hub?.acceptCall(false); openCallRoom() }
        }, LinearLayout.LayoutParams(dp(52), dp(52)))
        incoming.addView(ir)

        callBar = Button(this).apply {
            text = "Call in progress · tap to return"
            isAllCaps = false
            setTextColor(Color.parseColor("#04210f"))
            setBackgroundColor(OK)
            visibility = View.GONE
            setOnClickListener { openCallRoom() }
        }

        setContentView(FrameLayout(this).apply {
            setBackgroundColor(BG)
            addView(content, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(callScreen, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(incoming, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP).apply { setMargins(dp(10), dp(40), dp(10), 0) })
            addView(callBar, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        })
    }

    // A round call button with a small label underneath.
    private fun callControl(parent: LinearLayout, icon: Int, label: String, bg: Int = Color.argb(60, 255, 255, 255), onClick: () -> Unit): ImageButton {
        val box = column(pad = 0).apply { gravity = Gravity.CENTER_HORIZONTAL }
        val b = iconButton(icon, label, tint = Color.WHITE, bg = bg, size = 56) { onClick() }
        box.addView(b, LinearLayout.LayoutParams(dp(56), dp(56)))
        box.addView(text(label, 12f, Color.WHITE).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(6) })
        parent.addView(box, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        return b
    }

    private lateinit var callAvatar: TextView
    private lateinit var incomingAvatar: TextView

    private fun openCallRoom() {
        val id = view?.call?.roomId ?: return
        callMinimized = false
        if (screen != Screen.ROOM || roomId != id) show(Screen.ROOM, id) else render()
    }

    private fun renderCall() {
        val c = view?.call ?: return
        val name = view?.rooms?.firstOrNull { it.id == c.roomId }?.name ?: ""
        val active = inCall(c)
        incoming.visibility = if (c.state == CallState.INCOMING) View.VISIBLE else View.GONE
        incomingText.text = "$name\nIncoming ${if (c.peerVideo) "video" else "voice"} call"
        styleAvatar(incomingAvatar, name)
        val full = active && !callMinimized
        callScreen.visibility = if (full) View.VISIBLE else View.GONE
        callBar.visibility = if (active && callMinimized) View.VISIBLE else View.GONE
        val remote = c.state == CallState.ACTIVE && c.peerVideo
        remoteView.visibility = if (remote && full) View.VISIBLE else View.GONE
        localView.visibility = if (c.myVideo && full) View.VISIBLE else View.GONE
        localView.setMirror(c.frontCamera)
        // until the other person's video arrives, your own camera fills the screen
        localView.layoutParams = if (remote) cornerParams() else FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        callAvatar.visibility = if (remote || c.myVideo) View.GONE else View.VISIBLE
        styleAvatar(callAvatar, name)
        callTitle.text = "$name\n" + when {
            c.state == CallState.OUTGOING -> "Calling…"
            remote || c.myVideo -> "Video call · end-to-end encrypted"
            else -> "Voice call · end-to-end encrypted"
        }
        if (!active) muted = false
        setToggle(muteBtn, muted)
        setToggle(speakerBtn, audio.speaker)
        setToggle(camBtn, c.myVideo)
        flipBtn.isEnabled = c.myVideo
        flipBtn.alpha = if (c.myVideo) 1f else 0.35f
        setCallFullScreen(full)
        keepScreenOn()
    }

    // During a call, and while pairing: the screen timing out would lock the app (with the disguise
    // on) and so break off the pairing.
    private fun keepScreenOn() {
        val c = view?.call
        if (screen == Screen.PAIR || (c != null && c.state != CallState.IDLE)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // "on" buttons are white with a dark icon, like most call screens
    private fun setToggle(b: ImageButton, on: Boolean) {
        b.background = circle(if (on) Color.WHITE else Color.argb(60, 255, 255, 255))
        b.imageTintList = ColorStateList.valueOf(if (on) Color.BLACK else Color.WHITE)
    }

    private var immersive = false

    // Full screen during calls: draw under the hidden system bars and the camera cutout.
    @Suppress("DEPRECATION")
    private fun setCallFullScreen(on: Boolean) {
        if (on == immersive) return
        immersive = on
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = if (on) {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                } else {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(!on)
            window.insetsController?.let { c ->
                if (on) {
                    c.hide(WindowInsets.Type.systemBars())
                    c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                } else {
                    c.show(WindowInsets.Type.systemBars())
                }
            }
        } else {
            window.decorView.systemUiVisibility = if (on) {
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            } else {
                0
            }
        }
    }

    private fun cornerParams() = FrameLayout.LayoutParams(dp(110), dp(150), Gravity.TOP or Gravity.END).apply {
        setMargins(dp(12), dp(110), dp(12), dp(12))
    }

    @Suppress("DEPRECATION")
    private fun vibrate() {
        getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 300, 400, 300, 400), -1))
    }

    // ---------- permissions ----------

    // Ask for the microphone (and the camera for video). The action gets whether the camera
    // may be used, so a video call falls back to voice if only the camera is refused.
    private fun withPermissions(video: Boolean, onDenied: (() -> Unit)? = null, action: (camera: Boolean) -> Unit) {
        val wanted = listOfNotNull(Manifest.permission.RECORD_AUDIO, if (video) Manifest.permission.CAMERA else null)
        val missing = wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) return action(video)
        pendingAction = action
        pendingDenied = onDenied
        pendingVideo = video
        requestPermissions(missing.toTypedArray(), 1)
    }

    private var pendingAction: ((Boolean) -> Unit)? = null
    private var pendingDenied: (() -> Unit)? = null
    private var pendingVideo = false

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        val granted = { p: String -> checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED }
        if (granted(Manifest.permission.RECORD_AUDIO)) {
            val camera = pendingVideo && granted(Manifest.permission.CAMERA)
            if (pendingVideo && !camera) toast("Camera permission was refused, so this call is voice only.")
            pendingAction?.invoke(camera)
        } else {
            toast("Microphone permission is needed for calls.")
            pendingDenied?.invoke()
        }
        pendingAction = null
        pendingDenied = null
    }

    // ---------- tiny view helpers ----------

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun dotColor(kind: String) = when (kind) {
        "ok" -> OK
        "bad" -> DANGER
        "off" -> Color.parseColor("#4a525e")
        else -> WAIT
    }

    private fun scroll(v: View) = ScrollView(this).apply { setBackgroundColor(BG); addView(v) }

    private fun ripple(bounded: Boolean = false) = TypedValue().let { tv ->
        theme.resolveAttribute(if (bounded) android.R.attr.selectableItemBackground else android.R.attr.selectableItemBackgroundBorderless, tv, true)
        getDrawable(tv.resourceId)
    }

    private fun iconButton(icon: Int, desc: String, tint: Int = TEXT, bg: Int? = null, size: Int = 44, onClick: (View) -> Unit) =
        ImageButton(this).apply {
            setImageResource(icon)
            contentDescription = desc
            imageTintList = ColorStateList.valueOf(tint)
            background = if (bg != null) circle(bg) else ripple()
            scaleType = ImageView.ScaleType.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
            setOnClickListener { onClick(it) }
        }

    // A coloured circle with the first letter of the name; the colour is stable per name.
    private fun avatar(name: String, size: Int) = TextView(this).apply {
        gravity = Gravity.CENTER
        textSize = size / 2.6f
        setTextColor(Color.WHITE)
        setTypeface(typeface, Typeface.BOLD)
        styleAvatar(this, name)
    }

    private fun styleAvatar(v: TextView, name: String) {
        v.text = name.trim().take(1).uppercase().ifEmpty { "·" }
        v.background = circle(AVATAR_COLORS[(name.hashCode() and 0x7fffffff) % AVATAR_COLORS.size])
    }

    // An input field with a rounded background instead of an underline.
    private fun field(hint: String, value: String) = edit(hint, value).apply {
        background = rounded(SURFACE2, 12, BORDER)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        textSize = 16f
    }

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

    private fun button(label: String, primary: Boolean = false, danger: Boolean = false, onClick: (View) -> Unit) =
        Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(if (danger) DANGER else Color.WHITE)
            background = rounded(if (primary) ACCENT else SURFACE2, 14, if (danger) DANGER else null)
            minHeight = dp(50)
            textSize = 15f
            if (primary) setTypeface(typeface, Typeface.BOLD)
            stateListAnimator = null
            setOnClickListener { onClick(it) }
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

    private fun <T : View> T.spaced(top: Int, bottom: Int, side: Int = 0): T = apply {
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = dp(top)
            bottomMargin = dp(bottom)
            leftMargin = dp(side)
            rightMargin = dp(side)
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
        private val ME = Color.parseColor("#2f5bb7")
        private val THEM = Color.parseColor("#232a33")
        private val BORDER = Color.parseColor("#2a313b")
        // the Notes disguise is light, so it doesn't look like Ghostline
        private val N_BG = Color.parseColor("#faf8f3")
        private val N_TEXT = Color.parseColor("#22252a")
        private val N_MUTED = Color.parseColor("#8d9096")
        private val N_ACCENT = Color.parseColor("#e3a21a")
        private val N_LINE = Color.parseColor("#e7e3d9")
        private val AVATAR_COLORS = listOf("#5b7cfa", "#22a39f", "#d9822b", "#c2508f", "#7e57c2", "#3f9e4d", "#c9534f").map { Color.parseColor(it) }
        private val TTL_LABELS = listOf("Keep", "30s", "5m", "1h")
        private val TTL_SECONDS = listOf(0, 30, 300, 3600)
    }
}
