package dev.serrebi.blindradio

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.style.RelativeSizeSpan
import android.view.ContextMenu
import android.view.KeyEvent
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.window.OnBackInvokedDispatcher
import org.json.JSONArray
import org.json.JSONObject

/** A list row: a station, a folder to open, or the row that loads the next page. */
sealed class Row {
    class Station(val s: JSONObject) : Row()
    class Folder(val id: String, val name: String) : Row()
    class More(val id: String) : Row()
}

enum class Kind { MAIN, BROWSE, SEARCH }

/** One level of the screen stack: the main list, a Browse folder, or Search. */
class Screen(val kind: Kind, var title: String, val id: String?, var rows: MutableList<Row> = mutableListOf()) {
    var pos = 0
    /** Browse: still waiting for the directory. */
    var loading = false
}

/**
 * One screen, one list. Landing on a station tunes it, like turning a dial:
 * TalkBack focus (a swipe or a touch), or the D-pad on TV. Everything else
 * a station offers is a TalkBack action (or a long press), not a button.
 */
class MainActivity : Activity() {
    private companion object { const val QUIET_MS = 600L }

    private val stack = ArrayList<Screen>()
    private lateinit var heading: TextView
    private lateinit var now: TextView
    private lateinit var message: TextView
    private lateinit var query: EditText
    private lateinit var list: ListView
    private val adapter = RowAdapter()

    /** Row index to dial index (stations only), -1 for folders and Load More. */
    private var dial = IntArray(0)
    /** The row the screen opened on: landing there does not tune, a move away and back does. */
    private var quietRow = -1
    /** ListView reports its own first selection as the screen opens (D-pad mode): not the user. */
    private var quietUntil = 0L
    private var lastTuned = -1
    private var mainListId = ""
    private var shownPlaying: String? = null

    private val cur get() = stack.last()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        volumeControlStream = AudioManager.STREAM_MUSIC
        buildViews()
        val err = Engine.start(this)
        if (err != null) {
            say(getString(R.string.no_engine, err))
            return
        }
        Engine.listener = ::onEvent
        stack.add(Screen(Kind.MAIN, getString(R.string.stations), null))
        loadMain()
        askNotifications()
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { back() }
        }
    }

    override fun onStart() {
        super.onStart()
        Engine.visible = true
        if (Engine.started) refreshState()
    }

    override fun onStop() {
        Engine.visible = false
        super.onStop()
    }

    override fun onDestroy() {
        if (Engine.listener == ::onEvent) Engine.listener = null
        super.onDestroy()
    }

    // Android 12 and older; 13 and later use the OnBackInvokedCallback set in onCreate.
    @SuppressLint("GestureBackNavigation")
    @Deprecated("Android 12 and older")
    override fun onBackPressed() = back()

    private fun back() {
        if (stack.size > 1) {
            stack.removeAt(stack.size - 1)
            show()
        } else {
            finish()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!Engine.started) return super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> Engine.toggle()
            KeyEvent.KEYCODE_MEDIA_PLAY -> if (Engine.playing == null) Engine.toggle()
            KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP -> Engine.stop()
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    private fun askNotifications() {
        if (Build.VERSION.SDK_INT < 33) return
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
    }

    // ---- views ----

    private fun buildViews() {
        val pad = (12 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        // Android 15+ draws under the bars: keep everything inside them.
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = if (Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            } else {
                @Suppress("DEPRECATION") insets.systemWindowInsets
            }
            v.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom)
            insets
        }
        heading = TextView(this).apply {
            textSize = 22f
            isAccessibilityHeading = true
        }
        now = TextView(this).apply { textSize = 16f }
        message = TextView(this).apply {
            textSize = 16f
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun button(text: Int, onClick: () -> Unit) = Button(this).apply {
            setText(text)
            setOnClickListener { if (Engine.started) onClick() }
            buttons.addView(this, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        button(R.string.lists) { chooseList() }
        button(R.string.search) { openSearch() }
        button(R.string.browse) { openBrowse(null, getString(R.string.browse)) }
        query = EditText(this).apply {
            hint = getString(R.string.search_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            isSingleLine = true
            visibility = View.GONE
            setOnEditorActionListener { _, action, event ->
                val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
                if (action == EditorInfo.IME_ACTION_SEARCH || enter) { search(); true } else false
            }
        }
        list = ListView(this).apply {
            adapter = this@MainActivity.adapter
            itemsCanFocus = false
            onItemClickListener = AdapterView.OnItemClickListener { _, _, pos, _ -> activate(pos) }
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                // The D-pad moving the selection (TV, keyboards).
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = landed(pos)
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        registerForContextMenu(list)
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        val full = ViewGroup.LayoutParams.MATCH_PARENT
        root.addView(heading, LinearLayout.LayoutParams(full, wrap))
        root.addView(now, LinearLayout.LayoutParams(full, wrap))
        root.addView(message, LinearLayout.LayoutParams(full, wrap))
        root.addView(buttons, LinearLayout.LayoutParams(full, wrap))
        root.addView(query, LinearLayout.LayoutParams(full, wrap))
        root.addView(list, LinearLayout.LayoutParams(full, 0, 1f))
        setContentView(root)
    }

    private fun say(text: String) {
        // The same words again still change the text, so the live region speaks them.
        message.text = if (message.text.toString() == text) "$text " else text
    }

    private fun refreshState() {
        now.text = Engine.line.ifEmpty { getString(R.string.stopped) }
        val id = Engine.playing?.optString("id")
        if (id != shownPlaying) {
            shownPlaying = id
            // Rebind the visible rows in place: a full refresh would move TalkBack.
            for (i in 0 until list.childCount) {
                val pos = list.firstVisiblePosition + i
                if (pos < adapter.count) adapter.getView(pos, list.getChildAt(i), list)
            }
        }
    }

    // ---- screens ----

    private fun stationsOf(arr: JSONArray?) =
        MutableList<Row>(arr?.length() ?: 0) { Row.Station(arr!!.getJSONObject(it)) }

    private fun loadMain(keep: Boolean = false) {
        val m = Engine.call("main") as JSONObject
        mainListId = m.getString("id")
        val root = stack.first()
        val oldPos = root.pos
        root.title = m.getString("name")
        root.rows = stationsOf(m.optJSONArray("stations"))
        val current = m.optString("current")
        root.pos = if (keep) oldPos.coerceAtMost(maxOf(root.rows.size - 1, 0))
        else root.rows.indexOfFirst { (it as Row.Station).s.optString("id") == current }.coerceAtLeast(0)
        if (root.rows.isEmpty()) say(getString(R.string.starter_loading))
        if (stack.size == 1) show()
    }

    /** Puts the top screen on the list, the dial on its stations, and focus on its row. */
    private fun show() {
        val s = cur
        heading.text = s.title
        query.visibility = if (s.kind == Kind.SEARCH) View.VISIBLE else View.GONE
        val stations = JSONArray()
        dial = IntArray(s.rows.size) { i ->
            val r = s.rows[i]
            if (r is Row.Station) { stations.put(r.s); stations.length() - 1 } else -1
        }
        Engine.call("dial", stations)
        adapter.notifyDataSetChanged()
        refreshState()
        if (s.rows.isEmpty()) {
            if (s.kind == Kind.SEARCH) query.requestFocus() else list.requestFocus()
            return
        }
        val pos = s.pos.coerceIn(0, s.rows.size - 1)
        quietRow = pos
        quietUntil = SystemClock.uptimeMillis() + QUIET_MS
        lastTuned = -1
        list.setSelection(pos)
        list.requestFocus()
        // TalkBack lands on the remembered row too (back from a folder lands on that folder,
        // not the top of the screen); quietRow keeps that from tuning.
        @SuppressLint("AccessibilityFocus")
        list.post {
            list.getChildAt(pos - list.firstVisiblePosition)
                ?.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
        }
    }

    private fun push(screen: Screen) {
        stack.add(screen)
        show()
    }

    private fun openSearch() {
        if (cur.kind == Kind.SEARCH) { query.requestFocus(); return }
        push(Screen(Kind.SEARCH, getString(R.string.search), null))
        query.requestFocus()
    }

    private fun search() {
        val text = query.text.toString().trim()
        if (text.isEmpty()) return
        say(getString(R.string.searching))
        Engine.call("search", text)
        Engine.schedule()
    }

    private fun openBrowse(id: String?, name: String) {
        val screen = Screen(Kind.BROWSE, name, id)
        if (id == null) {
            screen.rows = nodes(Engine.call("browse") as JSONArray)
        } else {
            screen.loading = true
            say(getString(R.string.loading))
            Engine.call("browse", id)
            Engine.schedule()
        }
        push(screen)
    }

    private fun nodes(arr: JSONArray) = MutableList(arr.length()) {
        val n = arr.getJSONObject(it)
        when (n.getString("t")) {
            "folder" -> Row.Folder(n.getString("id"), n.getString("name"))
            "more" -> Row.More(n.getString("id"))
            else -> Row.Station(n.getJSONObject("s"))
        }
    }

    private fun chooseList() {
        val lists = Engine.call("lists") as JSONArray
        val names = MutableList(lists.length()) { lists.getJSONObject(it).getString("name") }
        names += getString(R.string.add_station)
        names += getString(R.string.get_starter)
        AlertDialog.Builder(this)
            .setTitle(R.string.lists)
            .setItems(names.toTypedArray()) { _, which ->
                when (which) {
                    lists.length() -> addStation()
                    lists.length() + 1 -> { say(getString(R.string.starter_loading)); Engine.call("starter"); Engine.schedule() }
                    else -> {
                        Engine.call("set_main", lists.getJSONObject(which).getString("id"))
                        while (stack.size > 1) stack.removeAt(stack.size - 1)
                        loadMain()
                    }
                }
            }
            .show()
    }

    private fun addStation() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val name = EditText(this).apply { hint = getString(R.string.name); isSingleLine = true }
        val url = EditText(this).apply {
            hint = getString(R.string.address)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, 0, pad, 0)
            addView(name)
            addView(url)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.add_station)
            .setView(box)
            .setPositiveButton(R.string.add) { _, _ ->
                try {
                    val st = Engine.call("add_custom", JSONObject().put("name", name.text).put("url", url.text)) as JSONObject
                    Engine.call("play", st)
                    Engine.schedule()
                    if (stack.size == 1) loadMain(keep = true)
                } catch (e: EngineError) {
                    say(e.message ?: "")
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun onEvent(e: JSONObject) {
        when (e.getString("k")) {
            "say" -> say(e.getString("text"))
            "state" -> refreshState()
            "lists" -> {
                loadMain(keep = true)
                val root = stack.first()
                if (root.rows.isNotEmpty()) say("${root.title}, ${resources.getQuantityString(R.plurals.results, root.rows.size, root.rows.size)}")
            }
            "search" -> {
                val s = stack.lastOrNull { it.kind == Kind.SEARCH } ?: return
                s.rows = stationsOf(e.optJSONArray("stations"))
                s.pos = 0
                say(if (s.rows.isEmpty()) getString(R.string.empty) else resources.getQuantityString(R.plurals.results, s.rows.size, s.rows.size))
                if (s === cur) show()
            }
            "browse" -> {
                val id = e.getString("id")
                val err = e.optString("error")
                val got = e.optJSONArray("nodes")?.let(::nodes) ?: mutableListOf()
                // A Load More row asked for this id: the new page replaces that row.
                val more = stack.firstNotNullOfOrNull { s ->
                    s.rows.indexOfFirst { it is Row.More && it.id == id }.takeIf { it >= 0 }?.let { s to it }
                }
                if (more != null) {
                    val (s, i) = more
                    s.rows.removeAt(i)
                    s.rows.addAll(i, got)
                    s.pos = i
                    if (s === cur) show()
                    return
                }
                val s = stack.lastOrNull { it.kind == Kind.BROWSE && it.id == id && it.loading } ?: return
                s.loading = false
                s.rows = got
                if (err.isNotEmpty()) say(err) else if (got.isEmpty()) say(getString(R.string.empty)) else say(s.title)
                if (s === cur) show()
            }
        }
    }

    // ---- rows ----

    private fun station(pos: Int) = (cur.rows.getOrNull(pos) as? Row.Station)?.s

    private fun isPlaying(s: JSONObject?) = s != null && Engine.playing?.optString("id") == s.optString("id")

    /** Focus landed on a row: a station tunes in, unless it is where the screen opened. */
    private fun landed(pos: Int) {
        if (pos !in cur.rows.indices) return
        if (pos == quietRow || SystemClock.uptimeMillis() < quietUntil) return
        quietRow = -1
        cur.pos = pos
        val d = dial[pos]
        if (d < 0 || pos == lastTuned) return
        lastTuned = pos
        if (isPlaying(station(pos))) return
        Engine.call("focus", d)
        Engine.schedule()
    }

    private fun activate(pos: Int) {
        cur.pos = pos
        when (val r = cur.rows.getOrNull(pos)) {
            is Row.Station -> {
                lastTuned = pos
                quietRow = -1
                if (isPlaying(r.s)) Engine.stop() else { Engine.call("play", r.s); Engine.schedule() }
            }
            is Row.Folder -> openBrowse(r.id, r.name)
            is Row.More -> { say(getString(R.string.loading)); Engine.call("browse", r.id); Engine.schedule() }
            null -> {}
        }
    }

    /** A row's actions, for TalkBack's actions menu and the long-press menu alike. */
    private fun actions(pos: Int): List<Pair<String, () -> Unit>> {
        val s = station(pos) ?: return emptyList()
        val out = ArrayList<Pair<String, () -> Unit>>()
        val fav = Engine.call("is_fav", s) == true
        out += getString(if (fav) R.string.remove_favorite else R.string.add_favorite) to {
            val nowFav = Engine.call("fav", s) == true
            say(getString(if (nowFav) R.string.added_favorite else R.string.removed_favorite))
            if (cur.kind == Kind.MAIN && mainListId == "favorites") loadMain(keep = true)
        }
        out += getString(R.string.details) to { details(s) }
        if (cur.kind == Kind.MAIN && mainListId != "all-playlists") {
            out += getString(R.string.delete) to {
                Engine.call("remove", JSONObject().put("list", mainListId).put("station", s))
                say(getString(R.string.deleted))
                loadMain(keep = true)
            }
        }
        return out
    }

    private fun details(s: JSONObject) {
        AlertDialog.Builder(this)
            .setTitle(s.optString("name"))
            .setMessage(Engine.call("details", s) as String)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private val actionIds = intArrayOf(R.id.act_0, R.id.act_1, R.id.act_2, R.id.act_3, R.id.act_4, R.id.act_5)

    override fun onCreateContextMenu(menu: ContextMenu, v: View, info: ContextMenu.ContextMenuInfo?) {
        val pos = (info as? AdapterView.AdapterContextMenuInfo)?.position ?: return
        actions(pos).forEachIndexed { i, (label, _) -> menu.add(0, i, i, label) }
        menu.setHeaderTitle(station(pos)?.optString("name"))
    }

    override fun onContextItemSelected(item: MenuItem): Boolean {
        val pos = (item.menuInfo as? AdapterView.AdapterContextMenuInfo)?.position ?: return false
        actions(pos).getOrNull(item.itemId)?.second?.invoke() ?: return false
        return true
    }

    /** A row: its text, its state, its click label, and its TalkBack actions. */
    inner class RowView(context: Context) : TextView(context) {
        var pos = -1

        init {
            val pad = (12 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            textSize = 18f
            // Large enough to touch, per the minimum target size.
            minHeight = (48 * resources.displayMetrics.density).toInt()
        }

        override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            val r = cur.rows.getOrNull(pos) ?: return
            val click = when (r) {
                is Row.Station -> getString(if (isPlaying(r.s)) R.string.stop else R.string.play)
                is Row.Folder -> getString(R.string.open)
                is Row.More -> getString(R.string.load_more)
            }
            info.addAction(AccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, click))
            actions(pos).take(actionIds.size).forEachIndexed { i, (label, _) ->
                info.addAction(AccessibilityAction(actionIds[i], label))
            }
        }

        override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
            val i = actionIds.indexOf(action)
            if (i >= 0) {
                actions(pos).getOrNull(i)?.second?.invoke() ?: return false
                return true
            }
            val done = super.performAccessibilityAction(action, arguments)
            // A swipe or a touch put TalkBack here: tune, like an arrow key would.
            if (action == AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS) landed(pos)
            return done
        }
    }

    inner class RowAdapter : BaseAdapter() {
        override fun getCount() = if (stack.isEmpty()) 0 else cur.rows.size
        override fun getItem(pos: Int) = cur.rows[pos]
        override fun getItemId(pos: Int) = pos.toLong()

        override fun getView(pos: Int, convert: View?, parent: ViewGroup): View {
            val v = convert as? RowView ?: RowView(parent.context)
            v.pos = pos
            when (val r = cur.rows[pos]) {
                is Row.Station -> {
                    val text = SpannableStringBuilder(r.s.optString("name"))
                    val info = r.s.optString("info")
                    if (info.isNotEmpty()) {
                        val start = text.length
                        text.append('\n').append(info)
                        text.setSpan(RelativeSizeSpan(0.8f), start, text.length, 0)
                    }
                    v.text = text
                    val playing = isPlaying(r.s)
                    if (Build.VERSION.SDK_INT >= 30) {
                        v.stateDescription = if (playing) getString(R.string.playing) else null
                        v.contentDescription = null
                    } else {
                        v.contentDescription = if (playing) "$text, ${getString(R.string.playing)}" else null
                    }
                }
                is Row.Folder -> { v.text = r.name; v.contentDescription = null }
                is Row.More -> { v.text = getString(R.string.load_more); v.contentDescription = null }
            }
            return v
        }
    }
}
