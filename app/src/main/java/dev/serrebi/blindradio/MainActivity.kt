package dev.serrebi.blindradio

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.OpenableColumns
import android.provider.Settings
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
import android.widget.SeekBar
import android.widget.TextView
import android.window.OnBackInvokedDispatcher
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

/** A list row: a station, a folder, the row that loads the next page, or a plain item. */
sealed class Row {
    /** [index] = its place in a library level, for moving and deleting (-1 elsewhere). */
    class Station(val s: JSONObject, val index: Int = -1) : Row()
    /** A directory folder in Browse. */
    class Folder(val id: String, val name: String) : Row()
    /** A folder of one of your lists, or (in All Lists) a whole list. */
    class Lib(val place: JSONObject, val name: String, val count: Int, val isList: Boolean) : Row()
    class More(val id: String) : Row()
    class Item(val text: String, val id: String) : Row()
}

enum class Kind { MAIN, LEVEL, BROWSE, SEARCH, FILTER, SCHEDULES }

/** One level of the screen stack. */
class Screen(val kind: Kind, var title: String, val id: String? = null, val place: JSONObject? = null) {
    var rows: MutableList<Row> = mutableListOf()
    var pos = 0
    /** Browse: still waiting for the directory. */
    var loading = false
    /** Search and Browse: the chosen sort (-1 = the directory's own). */
    var sort = -1
    var genre: String? = null
    var country: String? = null
    var language: String? = null
    var seq = 0
}

/**
 * One screen, one list. Landing on a station tunes it, like turning a dial:
 * TalkBack focus (a swipe or a touch), or the D-pad on TV (Settings can turn that off).
 * Everything a station offers is a TalkBack action (or a long press), not a button;
 * the player's commands are actions on the now-playing line and in Menu.
 */
class MainActivity : Activity() {
    private companion object {
        const val QUIET_MS = 600L
        const val IMPORT = 1
        const val EXPORT = 2
        const val IMPORT_FOLDER = 3
        const val EXPORT_FOLDER = 4
        val PLAYLIST_EXT = setOf("m3u", "m3u8", "m3u_plus", "pls")
    }

    private val stack = ArrayList<Screen>()
    private lateinit var heading: TextView
    private lateinit var now: TextView
    private lateinit var message: TextView
    private lateinit var query: EditText
    private lateinit var list: ListView
    private val adapter = RowAdapter()

    /** Row index to dial index (stations only), -1 for everything else. */
    private var dial = IntArray(0)
    /** The row the screen opened on: landing there does not tune, a move away and back does. */
    private var quietRow = -1
    /** ListView reports its own first selection as the screen opens (D-pad mode): not the user. */
    private var quietUntil = 0L
    private var lastTuned = -1
    private var mainListId = ""
    private var shownPlaying: String? = null
    /** Copy or Cut: the station, and for a cut, where it is taken from. */
    private var clip: Pair<JSONObject, JSONObject?>? = null
    /** Copy Folder or Cut Folder: where the folder is, and whether it moves. */
    private var folderClip: Pair<JSONObject, Boolean>? = null
    private var filters: JSONObject? = null
    private var whenFilters: (() -> Unit)? = null
    private var exportText = ""
    /** Export as folder: the list's name and its files, waiting for the picked folder. */
    private var exportFiles: JSONObject? = null
    private var details: Pair<String, AlertDialog>? = null

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
        val start = Engine.call("android", JSONObject().put("record_dir", recordDir())) as JSONObject
        Engine.reloadSettings()
        stack.add(Screen(Kind.MAIN, getString(R.string.stations)))
        loadMain()
        askNotifications()
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { back() }
        }
        if (start.optBoolean("play_last") && start.optBoolean("first") && Engine.playing == null) Engine.toggle()
        Alarms.sync(this)
        Updater.daily(this, ::say)
    }

    override fun onStart() {
        super.onStart()
        Engine.visible = true
        if (Engine.started) {
            Engine.listener = ::onEvent
            refreshState()
        }
    }

    private var resumedOnce = false

    override fun onResume() {
        super.onResume()
        // Back from Settings: the dial and folder choices may have changed.
        if (resumedOnce && Engine.started && stack.size == 1) loadMain(keep = true)
        resumedOnce = true
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
            reload()
        } else {
            finish()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!Engine.started) return super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> Engine.toggle()
            KeyEvent.KEYCODE_MEDIA_PLAY -> if (Engine.playing == null) Engine.toggle()
            KeyEvent.KEYCODE_MEDIA_PAUSE -> if (Engine.playing != null) Engine.toggle()
            KeyEvent.KEYCODE_MEDIA_STOP -> Engine.stop()
            KeyEvent.KEYCODE_MEDIA_REWIND -> Engine.run("seek", -1)
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> Engine.run("seek", 1)
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> Engine.run("prev_station")
            KeyEvent.KEYCODE_MEDIA_NEXT -> Engine.run("next_station")
            KeyEvent.KEYCODE_MEDIA_RECORD -> Engine.run("record")
            // TV remotes: channel keys turn the dial without moving off the list.
            KeyEvent.KEYCODE_CHANNEL_UP -> step(-1)
            KeyEvent.KEYCODE_CHANNEL_DOWN -> step(1)
            KeyEvent.KEYCODE_MENU -> menu()
            else -> {
                val c = event.unicodeChar.toChar()
                if (list.hasFocus() && c.isLetterOrDigit()) { jump(c); return true }
                return super.onKeyDown(keyCode, event)
            }
        }
        return true
    }

    /** Typing a letter in the list: the next row starting with it, as desktop lists do. */
    private fun jump(c: Char) {
        val rows = cur.rows
        if (rows.isEmpty()) return
        val from = list.selectedItemPosition.coerceAtLeast(cur.pos)
        for (k in 1..rows.size) {
            val i = (from + k) % rows.size
            if (label(rows[i]).startsWith(c, ignoreCase = true)) { goTo(i); return }
        }
    }

    private fun step(d: Int) {
        val i = (list.selectedItemPosition.takeIf { it >= 0 } ?: cur.pos) + d
        if (i in cur.rows.indices) goTo(i)
    }

    private fun goTo(i: Int) {
        quietUntil = 0
        list.setSelection(i)
        landed(i)
        list.post { list.getChildAt(i - list.firstVisiblePosition)?.let(::accessibilityFocus) }
    }

    @SuppressLint("AccessibilityFocus")
    private fun accessibilityFocus(v: View) {
        v.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
    }

    private fun askNotifications() {
        if (Build.VERSION.SDK_INT < 33) return
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
    }

    /** Music/BlindRadio, where file apps look (Android 11+); else the app's own music folder. */
    private fun recordDir(): String {
        if (Build.VERSION.SDK_INT >= 30) {
            @Suppress("DEPRECATION")
            val d = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "BlindRadio")
            if ((d.isDirectory || d.mkdirs()) && d.canWrite()) return d.path
        }
        return (getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: filesDir).path
    }

    // ---- views ----

    private fun buildViews() {
        val pad = dp(12)
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
        now = NowView(this).apply {
            textSize = 16f
            isFocusable = true
            minHeight = dp(48)
            setOnClickListener { if (Engine.started) Engine.toggle() }
            setOnLongClickListener { if (Engine.started) player(); true }
        }
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
        button(R.string.menu) { menu() }
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

    /** A confirmation of something the user did: Settings' Announce Actions governs it. */
    private fun act(text: String) {
        if (Engine.flag("announce_actions")) say(text)
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

    private fun stationsOf(arr: JSONArray?) = arr?.objects()?.map { Row.Station(it) as Row }?.toMutableList() ?: mutableListOf()

    private fun levelRows(place: JSONObject): Pair<String, MutableList<Row>> {
        val l = Engine.call("level", place) as JSONObject
        val rows = l.getJSONArray("rows").objects().map { r ->
            when (r.getString("t")) {
                "lib" -> Row.Lib(r.getJSONObject("place"), r.getString("name"), r.optInt("count"), r.optBoolean("list"))
                else -> Row.Station(r.getJSONObject("s"), r.optInt("i", -1))
            }
        }
        return l.getString("title") to rows.toMutableList()
    }

    private fun place(list: String, path: JSONArray = JSONArray()) = JSONObject().put("list", list).put("path", path)

    /** The main list: flat like the desktop dial, or as its folders (Settings). */
    private fun loadMain(keep: Boolean = false) {
        val m = Engine.call("main") as JSONObject
        mainListId = m.getString("id")
        val old = stack.first()
        val root = if (m.optBoolean("flat", true)) {
            Screen(Kind.MAIN, m.getString("name")).also { it.rows = stationsOf(m.optJSONArray("stations")) }
        } else {
            val p = place(mainListId)
            val (title, rows) = levelRows(p)
            Screen(Kind.LEVEL, title, place = p).also { it.rows = rows }
        }
        val current = m.optString("current")
        root.pos = if (keep && old.kind == root.kind) old.pos.coerceAtMost(maxOf(root.rows.size - 1, 0))
        else root.rows.indexOfFirst { it is Row.Station && it.s.optString("id") == current }.coerceAtLeast(0)
        stack[0] = root
        if (root.rows.isEmpty() && m.optJSONArray("stations")?.length() == 0) say(getString(R.string.starter_loading))
        if (stack.size == 1) show()
    }

    /** Fresh rows for the screen on top after a change to the library; the place stays. */
    private fun reload() {
        val s = cur
        when (s.kind) {
            Kind.MAIN -> { loadMain(keep = true); return }
            Kind.LEVEL -> try {
                val (title, rows) = levelRows(s.place!!)
                s.title = title
                s.rows = rows
                s.pos = s.pos.coerceAtMost(maxOf(rows.size - 1, 0))
            } catch (e: EngineError) {
                // The folder went away (deleted from inside it): one level up.
                if (stack.size > 1) { stack.removeAt(stack.size - 1); reload(); return }
            }
            Kind.SCHEDULES -> s.rows = scheduleRows()
            else -> {}
        }
        show()
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
        list.post { list.getChildAt(pos - list.firstVisiblePosition)?.let(::accessibilityFocus) }
    }

    private fun push(screen: Screen) {
        stack.add(screen)
        show()
    }

    private fun popToRoot() {
        while (stack.size > 1) stack.removeAt(stack.size - 1)
    }

    private fun openSearch() {
        if (cur.kind == Kind.SEARCH) { query.requestFocus(); return }
        push(Screen(Kind.SEARCH, getString(R.string.search)))
        query.requestFocus()
    }

    private fun search() {
        val s = stack.lastOrNull { it.kind == Kind.SEARCH } ?: return
        val text = query.text.toString().trim()
        if (text.isEmpty() && s.genre == null && s.country == null && s.language == null) return
        say(getString(R.string.searching))
        s.seq++
        Engine.call(
            "search",
            JSONObject().put("name", text).put("genre", s.genre ?: JSONObject.NULL).put("country", s.country ?: JSONObject.NULL)
                .put("language", s.language ?: JSONObject.NULL).put("sort", s.sort).put("seq", s.seq),
        )
        Engine.schedule()
    }

    private fun openBrowse(id: String?, name: String, sort: Int = -1) {
        val screen = Screen(Kind.BROWSE, name, id)
        screen.sort = sort
        if (id == null) {
            screen.rows = nodes(Engine.call("browse") as JSONArray)
        } else {
            screen.loading = true
            say(getString(R.string.loading))
            Engine.call("browse", JSONObject().put("id", id).put("sort", sort))
            Engine.schedule()
        }
        push(screen)
    }

    private fun nodes(arr: JSONArray) = arr.objects().map { n ->
        when (n.getString("t")) {
            "folder" -> Row.Folder(n.getString("id"), n.getString("name"))
            "more" -> Row.More(n.getString("id"))
            else -> Row.Station(n.getJSONObject("s"))
        }
    }.toMutableList()

    private fun openLevel(place: JSONObject) {
        val (title, rows) = levelRows(place)
        push(Screen(Kind.LEVEL, title, place = place).also { it.rows = rows })
    }

    private fun chooseList() {
        val lists = (Engine.call("lists") as JSONArray).objects()
        val names = lists.map { it.getString("name") } + listOf(
            getString(R.string.new_list), getString(R.string.add_station), getString(R.string.detect), getString(R.string.import_playlist), getString(R.string.import_folder), getString(R.string.get_starter),
        )
        choose(getString(R.string.lists), names) { which ->
            when (which - lists.size) {
                0 -> newList()
                1 -> addStation()
                2 -> detect()
                3 -> importPlaylist()
                4 -> importFolder()
                5 -> { say(getString(R.string.starter_loading)); Engine.call("starter"); Engine.schedule() }
                else -> setMain(lists[which].getString("id"))
            }
        }
    }

    private fun setMain(id: String) {
        Engine.call("set_main", id)
        Engine.reloadSettings()
        popToRoot()
        loadMain()
    }

    private fun newList() = askText(getString(R.string.new_list), getString(R.string.name)) { name ->
        if (name.isEmpty()) return@askText
        val id = Engine.call("list_new", name) as String
        act(getString(R.string.list_made, name))
        setMain(id)
    }

    private fun addStation() {
        val f = Form(this)
        val name = f.text(getString(R.string.name), "")
        val url = f.text(getString(R.string.address), "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        AlertDialog.Builder(this)
            .setTitle(R.string.add_station)
            .setView(f.scroll)
            .setPositiveButton(R.string.add) { _, _ ->
                try {
                    val st = Engine.call("add_custom", JSONObject().put("name", name.text).put("url", url.text)) as JSONObject
                    Engine.run("play", st)
                    reload()
                } catch (e: EngineError) {
                    say(e.message ?: "")
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Detect Audio Stream on Page: a web page, or a YouTube or Twitch link, to its streams. */
    private fun detect() = askText(getString(R.string.detect), getString(R.string.page_address), "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI) { url ->
        if (url.isEmpty()) return@askText
        say(getString(R.string.loading))
        Engine.call("detect", url)
        Engine.schedule()
    }

    private fun onEvent(e: JSONObject) {
        when (e.getString("k")) {
            "say", "title" -> say(e.getString("text"))
            "saved" -> {
                say(e.getString("text"))
                val paths = e.optJSONArray("paths")?.strings().orEmpty()
                if (paths.isNotEmpty()) MediaScannerConnection.scanFile(this, paths.toTypedArray(), null, null)
            }
            "state" -> refreshState()
            "lists" -> {
                loadMain(keep = true)
                val root = stack.first()
                if (root.rows.isNotEmpty()) say("${root.title}, ${count(root.rows.size)}")
            }
            "history" -> {
                val items = e.getJSONArray("items").strings()
                if (items.isEmpty()) say(getString(R.string.no_history))
                else showText(getString(R.string.history_of, e.getString("name")), items.joinToString("\n"))
            }
            "details" -> details?.let { (id, d) ->
                if (id == e.getString("id") && d.isShowing) d.setMessage(Engine.call("details_text", e.getJSONObject("station")) as String)
            }
            "filters" -> { filters = e; whenFilters?.invoke(); whenFilters = null }
            "detected" -> {
                val rows = stationsOf(e.optJSONArray("stations"))
                say(if (rows.isEmpty()) getString(R.string.no_streams) else count(rows.size))
                if (rows.isNotEmpty()) push(Screen(Kind.FILTER, getString(R.string.streams_found)).also { it.rows = rows })
            }
            "search" -> {
                val s = stack.lastOrNull { it.kind == Kind.SEARCH } ?: return
                if (e.optInt("seq") != s.seq) return
                s.rows = stationsOf(e.optJSONArray("stations"))
                s.pos = 0
                say(if (s.rows.isEmpty()) getString(R.string.empty) else count(s.rows.size))
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
                s.pos = 0
                if (err.isNotEmpty()) say(err) else if (got.isEmpty()) say(getString(R.string.empty)) else say(s.title)
                if (s === cur) show()
            }
        }
    }

    private fun count(n: Int) = resources.getQuantityString(R.plurals.results, n, n)

    // ---- rows ----

    private fun label(r: Row) = when (r) {
        is Row.Station -> r.s.optString("name")
        is Row.Folder -> r.name
        is Row.Lib -> r.name
        is Row.More -> getString(R.string.load_more)
        is Row.Item -> r.text
    }

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
        // Settings, Play a Station as I Move to It: off, a move only selects.
        if (!Engine.flag("play_on_move")) return
        if (isPlaying(station(pos))) return
        Engine.run("focus", d)
    }

    private fun activate(pos: Int) {
        cur.pos = pos
        when (val r = cur.rows.getOrNull(pos)) {
            is Row.Station -> {
                lastTuned = pos
                quietRow = -1
                if (isPlaying(r.s)) {
                    Engine.toggle()
                } else {
                    Engine.run("play", r.s)
                    // Settings, Close Lists When Playing: back to the main list.
                    if (Engine.flag("close_lists_when_playing") && cur.kind in setOf(Kind.BROWSE, Kind.SEARCH, Kind.FILTER)) {
                        popToRoot()
                        show()
                    }
                }
            }
            is Row.Folder -> openBrowse(r.id, r.name)
            is Row.Lib -> openLevel(r.place)
            is Row.More -> { say(getString(R.string.loading)); Engine.call("browse", JSONObject().put("id", r.id).put("sort", cur.sort)); Engine.schedule() }
            is Row.Item -> actions(pos).firstOrNull()?.second?.invoke()
            null -> {}
        }
    }

    /** Where the rows sit in a list of yours, if they do (moves, deletes, cuts, pastes). */
    private fun rowPlace(): JSONObject? {
        val p = cur.place ?: return null
        return p.takeIf { cur.kind == Kind.LEVEL && it.getString("list") != "all-playlists" }
    }

    /** A row's actions, for TalkBack's actions menu and the long-press menu alike. */
    private fun actions(pos: Int): List<Pair<String, () -> Unit>> = when (val r = cur.rows.getOrNull(pos)) {
        is Row.Station -> stationActions(pos, r)
        is Row.Lib -> libActions(r)
        is Row.Item -> scheduleActions(r)
        else -> emptyList()
    }

    private fun stationActions(pos: Int, r: Row.Station): List<Pair<String, () -> Unit>> {
        val s = r.s
        val out = ArrayList<Pair<String, () -> Unit>>()
        val fav = Engine.call("is_fav", s) == true
        out += getString(if (fav) R.string.remove_favorite else R.string.add_favorite) to {
            val nowFav = Engine.call("fav", s) == true
            act(getString(if (nowFav) R.string.added_favorite else R.string.removed_favorite))
            reload()
        }
        out += getString(R.string.add_to_list) to { addToList(s) }
        out += getString(R.string.details) to { showDetails(s) }
        out += getString(R.string.history) to { Engine.call("history", s); Engine.schedule() }
        val home = s.optJSONObject("details")?.optString("homepage").orEmpty()
        if (home.isNotEmpty()) out += getString(R.string.open_homepage) to { openUrl(home) }
        out += getString(R.string.copy_address) to {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(s.optString("name"), s.optString("url")))
            act(getString(R.string.copied))
        }
        if (Engine.call("in_library", s) == true) out += getString(R.string.edit_station) to { editStation(s) }
        out += getString(R.string.station_sound) to { stationSound(s) }
        out += getString(R.string.schedule) to { scheduleFor(s) }
        out += getString(R.string.copy) to { clip = s to null; folderClip = null; act(getString(R.string.copied)) }
        val here = rowPlace()
        if (here != null && r.index >= 0) {
            out += getString(R.string.cut) to {
                clip = s to JSONObject().put("place", here).put("index", r.index)
                folderClip = null
                act(getString(R.string.cut_done))
            }
            if (clip != null) out += getString(R.string.paste) to { paste(here) }
            out += getString(R.string.move_up) to { moveStation(here, r.index, true, pos) }
            out += getString(R.string.move_down) to { moveStation(here, r.index, false, pos) }
            out += getString(R.string.delete) to {
                confirm(getString(R.string.delete_ask, s.optString("name"))) {
                    Engine.call("remove_at", JSONObject().put("place", here).put("index", r.index))
                    act(getString(R.string.deleted))
                    reload()
                }
            }
        } else if (cur.kind == Kind.MAIN && mainListId != "all-playlists") {
            out += getString(R.string.delete) to {
                confirm(getString(R.string.delete_ask, s.optString("name"))) {
                    Engine.call("remove", JSONObject().put("list", mainListId).put("station", s))
                    act(getString(R.string.deleted))
                    loadMain(keep = true)
                }
            }
        }
        return out
    }

    private fun libActions(r: Row.Lib): List<Pair<String, () -> Unit>> {
        val out = ArrayList<Pair<String, () -> Unit>>()
        val list = r.place.getString("list")
        out += getString(R.string.open) to { openLevel(r.place) }
        if (r.isList) {
            out += getString(R.string.make_main) to { setMain(list) }
            out += getString(R.string.rename) to {
                askText(getString(R.string.rename), getString(R.string.name), r.name) { n ->
                    try { Engine.call("list_rename", JSONObject().put("id", list).put("name", n)); reload() } catch (e: EngineError) { say(e.message ?: "") }
                }
            }
            out += getString(R.string.move_up) to { Engine.call("list_move", JSONObject().put("id", list).put("up", true)); cur.pos--; reload() }
            out += getString(R.string.move_down) to { Engine.call("list_move", JSONObject().put("id", list).put("up", false)); cur.pos++; reload() }
            out += getString(R.string.delete) to {
                confirm(getString(R.string.delete_list_ask, r.name, count(r.count))) {
                    try { Engine.call("list_delete", list); act(getString(R.string.deleted)); reload() } catch (e: EngineError) { say(e.message ?: "") }
                }
            }
        } else {
            out += getString(R.string.new_folder) to { newFolder(r.place) }
            if (clip != null) out += getString(R.string.paste_into) to { paste(r.place) }
            out += getString(R.string.copy_folder) to { folderClip = r.place to false; clip = null; act(getString(R.string.copied)) }
            out += getString(R.string.cut_folder) to { folderClip = r.place to true; clip = null; act(getString(R.string.cut_done)) }
            if (folderClip != null) out += getString(R.string.paste_folder_into) to { pasteFolder(r.place) }
            out += getString(R.string.rename) to {
                askText(getString(R.string.rename), getString(R.string.name), r.name) { n ->
                    Engine.call("folder_rename", JSONObject().put("place", r.place).put("name", n)); reload()
                }
            }
            out += getString(R.string.move_up) to { folderMove(r, true) }
            out += getString(R.string.move_down) to { folderMove(r, false) }
            out += getString(R.string.delete) to {
                confirm(getString(R.string.delete_folder_ask, r.name, count(r.count))) {
                    Engine.call("folder_delete", JSONObject().put("place", r.place)); act(getString(R.string.deleted)); reload()
                }
            }
        }
        out += getString(R.string.export_list) to { export(r.place, r.name) }
        out += getString(R.string.export_folder) to { exportFolder(r.place) }
        return out
    }

    private fun folderMove(r: Row.Lib, up: Boolean) {
        val moved = Engine.call("folder_move", JSONObject().put("place", r.place).put("up", up)) as? JSONObject ?: return
        reload()
        val at = cur.rows.indexOfFirst { it is Row.Lib && it.place.toString() == moved.toString() }
        if (at >= 0) goToQuiet(at)
    }

    private fun moveStation(place: JSONObject, index: Int, up: Boolean, pos: Int) {
        val to = (Engine.call("move", JSONObject().put("place", place).put("index", index).put("up", up)) as Number).toInt()
        reload()
        goToQuiet(pos + (to - index))
    }

    /** Focus onto a row that moved with it: no tuning, the station is the same. */
    private fun goToQuiet(i: Int) {
        if (i !in cur.rows.indices) return
        cur.pos = i
        quietRow = i
        list.setSelection(i)
        list.post { list.getChildAt(i - list.firstVisiblePosition)?.let(::accessibilityFocus) }
    }

    private fun paste(place: JSONObject) {
        val (s, cut) = clip ?: return
        val added = Engine.call("paste", JSONObject().put("place", place).put("station", s).put("cut", cut ?: JSONObject.NULL)) == true
        if (added && cut != null) clip = null
        act(getString(if (added) R.string.pasted else R.string.already_there_plain))
        reload()
    }

    private fun pasteFolder(to: JSONObject) {
        val (from, cut) = folderClip ?: return
        try {
            val added = Engine.call("folder_paste", JSONObject().put("from", from).put("place", to).put("cut", cut)) == true
            if (added && cut) folderClip = null
            act(getString(if (added) R.string.pasted else R.string.already_there_plain))
        } catch (e: EngineError) {
            say(e.message ?: "")
        }
        reload()
    }

    private fun newFolder(place: JSONObject) = askText(getString(R.string.new_folder), getString(R.string.name)) { n ->
        if (n.isEmpty()) return@askText
        if (Engine.call("folder_new", JSONObject().put("place", place).put("name", n)) == true) act(getString(R.string.folder_made, n))
        reload()
    }

    private fun addToList(s: JSONObject) {
        val dests = (Engine.call("destinations") as JSONArray).objects()
        choose(getString(R.string.add_to_list), dests.map { it.getString("name") }) { i ->
            val added = Engine.call("add_to", JSONObject().put("place", dests[i].getJSONObject("place")).put("station", s)) == true
            act(getString(if (added) R.string.added_to else R.string.already_there, dests[i].getString("name")))
            reload()
        }
    }

    private fun showDetails(s: JSONObject) {
        val d = AlertDialog.Builder(this)
            .setTitle(s.optString("name"))
            .setMessage(Engine.call("details", s) as String)
            .setPositiveButton(R.string.close, null)
            .show()
        details = s.optString("id") to d
        Engine.schedule()
    }

    private fun openUrl(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) { say(getString(R.string.no_browser)) }
    }

    private fun editStation(s: JSONObject) {
        val f = Form(this)
        val name = f.text(getString(R.string.name), s.optString("name"))
        val url = f.text(getString(R.string.address), s.optString("url"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val info = f.text(getString(R.string.info), s.optString("info"))
        AlertDialog.Builder(this)
            .setTitle(R.string.edit_station)
            .setView(f.scroll)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                Engine.call("edit", JSONObject().put("id", s.optString("id")).put("name", name.text).put("url", url.text).put("info", info.text))
                act(getString(R.string.saved))
                reload()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Edit Station's sound part: this station's equalizer, channels, title delay, recognition. */
    private fun stationSound(s: JSONObject) {
        val id = s.optString("id")
        val o = Engine.call("station_settings", id) as JSONObject
        val presets = (Engine.call("presets") as JSONArray).objects()
        val f = Form(this)
        val eqLabels = listOf(getString(R.string.use_global), getString(R.string.off)) + presets.map { it.getString("name") }
        val eqNow = o.opt("eq")
        var eqAt = when {
            eqNow == null || eqNow == JSONObject.NULL -> 0
            eqNow == "off" -> 1
            eqNow is JSONObject -> 2 + presets.indexOfFirst { it.getString("id") == eqNow.optString("preset") }.coerceAtLeast(0)
            else -> 0
        }
        f.button("${getString(R.string.equalizer)}: ${eqLabels[eqAt]}") { b ->
            choose(getString(R.string.equalizer), eqLabels, eqAt) { eqAt = it; b.text = "${getString(R.string.equalizer)}: ${eqLabels[it]}" }
        }
        val chValues = listOf(null, "stereo", "mono", "left", "right")
        val chLabels = listOf(getString(R.string.use_global), getString(R.string.stereo), getString(R.string.mono), getString(R.string.left), getString(R.string.right))
        var chAt = chValues.indexOf(o.optString("channels").takeUnless { o.isNull("channels") }).coerceAtLeast(0)
        f.button("${getString(R.string.channels)}: ${chLabels[chAt]}") { b ->
            choose(getString(R.string.channels), chLabels, chAt) { chAt = it; b.text = "${getString(R.string.channels)}: ${chLabels[it]}" }
        }
        val recLabels = listOf(getString(R.string.use_global), getString(R.string.on), getString(R.string.off))
        var recAt = if (o.isNull("recognition")) 0 else if (o.optBoolean("recognition")) 1 else 2
        f.button("${getString(R.string.recognize)}: ${recLabels[recAt]}") { b ->
            choose(getString(R.string.recognize), recLabels, recAt) { recAt = it; b.text = "${getString(R.string.recognize)}: ${recLabels[it]}" }
        }
        val delay = f.number(getString(R.string.title_delay), o.optInt("metadata_offset_seconds", 0))
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.station_sound) + ": " + s.optString("name"))
            .setView(f.scroll)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                o.put("eq", when (eqAt) { 0 -> JSONObject.NULL; 1 -> "off"; else -> JSONObject().put("preset", presets[eqAt - 2].getString("id")) })
                o.put("channels", chValues[chAt] ?: JSONObject.NULL)
                o.put("recognition", when (recAt) { 0 -> JSONObject.NULL; 1 -> true; else -> false })
                val d = delay.text.toString().toIntOrNull()?.coerceIn(-60, 60) ?: 0
                o.put("metadata_offset_seconds", if (d == 0) JSONObject.NULL else d)
                Engine.call("set_station_settings", JSONObject().put("id", id).put("settings", o))
                act(getString(R.string.saved))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---- schedules ----

    private fun scheduleRows(): MutableList<Row> {
        val fmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        return (Engine.call("schedules") as JSONArray).objects().map { e ->
            val what = getString(if (e.optBoolean("alarm")) R.string.alarm else R.string.recording)
            val parts = mutableListOf(
                e.getJSONObject("station").optString("name"), what,
                fmt.format(Date(e.optLong("start_unix") * 1000)),
            )
            if (!e.optBoolean("alarm")) parts += getString(R.string.minutes_n, e.optInt("duration_minutes"))
            parts += e.optString("repeat")
            if (!e.optBoolean("enabled")) parts += getString(R.string.off)
            Row.Item(parts.joinToString(", "), e.getString("id")) as Row
        }.toMutableList()
    }

    private fun openSchedules() {
        push(Screen(Kind.SCHEDULES, getString(R.string.schedules)).also { it.rows = scheduleRows() })
        if (cur.rows.isEmpty()) say(getString(R.string.no_schedules))
    }

    private fun scheduleActions(r: Row.Item): List<Pair<String, () -> Unit>> = listOf(
        getString(R.string.turn_on_off) to { Engine.call("schedule_toggle", r.id); Alarms.sync(this); reload() },
        getString(R.string.delete) to { Engine.call("schedule_remove", r.id); Alarms.sync(this); act(getString(R.string.deleted)); reload() },
    )

    /** Schedule a recording (or an alarm) of a station: date, time, then how long and how often. */
    private fun scheduleFor(s: JSONObject) {
        val c = Calendar.getInstance()
        DatePickerDialog(this, { _, y, m, d ->
            c.set(y, m, d)
            TimePickerDialog(this, { _, h, min ->
                c.set(Calendar.HOUR_OF_DAY, h); c.set(Calendar.MINUTE, min); c.set(Calendar.SECOND, 0)
                scheduleDetails(s, c.timeInMillis / 1000)
            }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), android.text.format.DateFormat.is24HourFormat(this)).show()
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun scheduleDetails(s: JSONObject, start: Long) {
        val f = Form(this)
        val minutes = f.number(getString(R.string.duration), 60)
        val repeats = listOf("once", "daily", "weekly")
        val repeatLabels = listOf(getString(R.string.once), getString(R.string.daily), getString(R.string.weekly))
        var at = 0
        f.button("${getString(R.string.repeat)}: ${repeatLabels[0]}") { b ->
            choose(getString(R.string.repeat), repeatLabels, at) { at = it; b.text = "${getString(R.string.repeat)}: ${repeatLabels[it]}" }
        }
        val alarm = f.check(getString(R.string.alarm_instead), false)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.schedule) + ": " + s.optString("name"))
            .setView(f.scroll)
            .setPositiveButton(R.string.add) { _, _ ->
                Engine.call("schedule_add", JSONObject().put("station", s).put("start_unix", start)
                    .put("duration_minutes", minutes.text.toString().toIntOrNull()?.coerceIn(1, 24 * 60) ?: 60)
                    .put("repeat", repeats[at]).put("alarm", alarm.isChecked).put("silent", true))
                Alarms.sync(this)
                act(getString(R.string.scheduled))
                if (!Alarms.exactAllowed(this) && Build.VERSION.SDK_INT >= 31) {
                    say(getString(R.string.allow_alarms))
                    startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---- menu ----

    private fun seekStep() = Engine.settings.optJSONObject("timeshift")?.optInt("seek_step_seconds", 15) ?: 15

    private fun recording() = Engine.line.contains("recording")

    /** The player: also TalkBack actions on the now-playing line. */
    private fun playerCommands(): List<Pair<String, () -> Unit>> {
        val p = Engine.playing
        val out = ArrayList<Pair<String, () -> Unit>>()
        out += getString(if (p != null) R.string.pause else R.string.play) to { Engine.toggle() }
        if (p != null) out += getString(R.string.stop) to { Engine.stop() }
        out += getString(R.string.back_n, seekStep()) to { Engine.run("seek", -1) }
        out += getString(R.string.forward_n, seekStep()) to { Engine.run("seek", 1) }
        out += getString(R.string.go_live) to { Engine.run("live") }
        out += getString(R.string.prev_station) to { Engine.run("prev_station") }
        out += getString(R.string.next_station) to { Engine.run("next_station") }
        out += getString(R.string.volume_up) to { act(getString(R.string.volume_n, (Engine.call("volume", JSONObject().put("delta", 5)) as Number).toInt())) }
        out += getString(R.string.volume_down) to { act(getString(R.string.volume_n, (Engine.call("volume", JSONObject().put("delta", -5)) as Number).toInt())) }
        out += getString(R.string.mute) to { act(getString(if (Engine.call("mute") == true) R.string.muted else R.string.unmuted)) }
        out += getString(if (recording()) R.string.stop_recording else R.string.record) to { Engine.run("record") }
        if (p != null) out += getString(R.string.history) to { Engine.call("history", p); Engine.schedule() }
        out += getString(R.string.say_playing) to { say(Engine.line.ifEmpty { getString(R.string.stopped) }) }
        return out
    }

    private fun player() = commands(getString(R.string.player), playerCommands())

    private fun menu() {
        val out = ArrayList<Pair<String, () -> Unit>>()
        out += getString(R.string.player) to { player() }
        out += getString(R.string.timeshift_menu) to { timeshiftMenu() }
        out += getString(R.string.recording_menu) to { recordingMenu() }
        out += getString(R.string.sound_menu) to { soundMenu() }
        out += getString(R.string.list_menu) to { listMenu() }
        if (cur.kind == Kind.SEARCH) out += getString(R.string.search_filters) to { searchFilters() }
        if (cur.kind == Kind.BROWSE && cur.id != null) out += getString(R.string.sort) to { browseSort() }
        out += getString(R.string.schedules) to { openSchedules() }
        out += getString(R.string.settings) to { startActivity(Intent(this, SettingsActivity::class.java)) }
        out += getString(R.string.check_now) to { Updater.check(this, quiet = false, say = ::say) }
        out += getString(R.string.help) to { showText(getString(R.string.help), asset("README.md")) }
        out += getString(R.string.changelog) to { showText(getString(R.string.changelog), asset("CHANGELOG.md")) }
        out += getString(R.string.about) to { about() }
        commands(getString(R.string.menu), out)
    }

    private fun timeshiftMenu() = commands(getString(R.string.timeshift_menu), listOf(
        getString(R.string.pause_resume) to { Engine.toggle() },
        getString(R.string.back_n, seekStep()) to { Engine.run("seek", -1) },
        getString(R.string.forward_n, seekStep()) to { Engine.run("seek", 1) },
        getString(R.string.buffer_start) to { Engine.run("seek_start") },
        getString(R.string.go_live) to { Engine.run("live") },
        getString(R.string.prev_song) to { Engine.run("segment", false) },
        getString(R.string.next_song) to { Engine.run("segment", true) },
    ))

    private fun recordingMenu() {
        val songs = Engine.settings.optJSONObject("recording")?.optBoolean("split_songs") == true
        commands(getString(R.string.recording_menu), listOf(
            getString(if (recording()) R.string.stop_recording else R.string.record) to { Engine.run("record") },
            getString(R.string.pause_recording) to { Engine.run("record_pause") },
            getString(if (songs) R.string.record_songs_off else R.string.record_songs_on) to {
                val on = Engine.call("record_songs") == true
                Engine.reloadSettings()
                act(getString(if (on) R.string.record_songs_now_on else R.string.record_songs_now_off))
            },
            getString(R.string.save_buffer) to { Engine.run("save_buffer") },
            getString(R.string.save_song) to { Engine.run("write_segment", "current") },
            getString(R.string.save_prev_song) to { Engine.run("write_segment", "previous") },
            getString(R.string.save_all_songs) to { Engine.run("write_segment", "all") },
            getString(R.string.where_recordings) to { say(Engine.settings.optJSONObject("recording")?.optString("dir").orEmpty()) },
        ))
    }

    private fun soundMenu() = commands(getString(R.string.sound_menu), listOf(
        getString(R.string.volume) to { volumeDialog() },
        getString(R.string.mute) to { act(getString(if (Engine.call("mute") == true) R.string.muted else R.string.unmuted)) },
        getString(R.string.equalizer) to { equalizer() },
        getString(R.string.channels) to {
            val values = listOf("stereo", "mono", "left", "right")
            val labels = listOf(getString(R.string.stereo), getString(R.string.mono), getString(R.string.left), getString(R.string.right))
            choose(getString(R.string.channels), labels, values.indexOf(Engine.settings.optString("channels")).coerceAtLeast(0)) {
                Engine.saveSettings(JSONObject(Engine.settings.toString()).put("channels", values[it]))
                act(labels[it])
            }
        },
        getString(R.string.sleep) to { sleepDialog() },
    ))

    private fun listMenu() {
        val out = ArrayList<Pair<String, () -> Unit>>()
        val here = rowPlace()
        out += getString(R.string.filter) to { filterList() }
        out += getString(R.string.go_playing) to { goPlaying() }
        if (here != null) {
            out += getString(R.string.new_folder) to { newFolder(here) }
            if (clip != null) out += getString(R.string.paste) to { paste(here) }
            if (folderClip != null) out += getString(R.string.paste_folder) to { pasteFolder(here) }
        }
        out += getString(R.string.new_list) to { newList() }
        out += getString(R.string.import_playlist) to { importPlaylist() }
        out += getString(R.string.import_folder) to { importFolder() }
        val exportPlace = cur.place ?: place(mainListId).takeIf { cur.kind == Kind.MAIN }
        if (exportPlace != null) {
            out += getString(R.string.export_list) to { export(exportPlace, cur.title) }
            out += getString(R.string.export_folder) to { exportFolder(exportPlace) }
        }
        commands(getString(R.string.list_menu), out)
    }

    private fun goPlaying() {
        val id = Engine.playing?.optString("id") ?: return say(getString(R.string.stopped))
        val i = cur.rows.indexOfFirst { it is Row.Station && it.s.optString("id") == id }
        if (i >= 0) goToQuiet(i) else say(getString(R.string.not_in_list))
    }

    private fun filterList() = askText(getString(R.string.filter), getString(R.string.filter_label)) { text ->
        if (text.isEmpty()) return@askText
        val words = text.lowercase().split(' ').filter { it.isNotEmpty() }
        val hits = cur.rows.filter { r -> r is Row.Station && words.all { r.s.optString("name").lowercase().contains(it) } }
        push(Screen(Kind.FILTER, getString(R.string.filtered, text)).also { it.rows = hits.toMutableList() })
        say(if (hits.isEmpty()) getString(R.string.empty) else count(hits.size))
    }

    private fun withFilters(then: () -> Unit) {
        if (filters != null) return then()
        whenFilters = then
        say(getString(R.string.loading))
        Engine.call("filters"); Engine.schedule()
    }

    private fun searchFilters(): Unit = withFilters {
        val s = stack.lastOrNull { it.kind == Kind.SEARCH } ?: return@withFilters
        val f = filters ?: return@withFilters
        fun pickOne(title: Int, items: List<String>, now: String?, set: (String?) -> Unit) {
            val labels = listOf(getString(R.string.any)) + items
            choose(getString(title), labels, (items.indexOf(now) + 1).coerceAtLeast(0)) { set(if (it == 0) null else items[it - 1]); searchFilters() }
        }
        val sorts = (Engine.call("sorts") as JSONArray).objects()
        commands(getString(R.string.search_filters), listOf(
            "${getString(R.string.genre)}: ${s.genre ?: getString(R.string.any)}" to { pickOne(R.string.genre, f.getJSONArray("genres").strings(), s.genre) { s.genre = it } },
            "${getString(R.string.country)}: ${s.country ?: getString(R.string.any)}" to { pickOne(R.string.country, f.getJSONArray("countries").strings(), s.country) { s.country = it } },
            "${getString(R.string.language)}: ${s.language ?: getString(R.string.any)}" to { pickOne(R.string.language, f.getJSONArray("languages").strings(), s.language) { s.language = it } },
            "${getString(R.string.sort)}: ${sorts.getOrNull(s.sort)?.getString("name") ?: getString(R.string.default_sort)}" to {
                choose(getString(R.string.sort), sorts.map { it.getString("name") }, s.sort) { s.sort = it; searchFilters() }
            },
            getString(R.string.search_now) to { search() },
        ))
    }

    private fun browseSort() {
        val s = cur
        val sorts = (Engine.call("sorts") as JSONArray).objects()
        choose(getString(R.string.sort), sorts.map { it.getString("name") }, s.sort) {
            stack.removeAt(stack.size - 1)
            openBrowse(s.id, s.title, it)
        }
    }

    private fun volumeDialog() {
        val f = Form(this)
        val v = (Engine.call("volume", JSONObject()) as Number).toInt()
        f.slider(getString(R.string.volume), 0, 200, v, { getString(R.string.percent, it) }) {
            Engine.call("volume", JSONObject().put("set", it))
        }
        AlertDialog.Builder(this).setTitle(R.string.volume).setView(f.scroll).setPositiveButton(R.string.close, null).show()
    }

    private fun sleepDialog() {
        val minutes = listOf(0, 15, 30, 45, 60, 90, 120, 180)
        val labels = minutes.map { if (it == 0) getString(R.string.sleep_off) else getString(R.string.minutes_n, it) }
        choose(getString(R.string.sleep), labels) { say(Engine.call("sleep", minutes[it]) as String) }
    }

    private fun equalizer() {
        val eq = Engine.settings.optJSONObject("eq") ?: JSONObject()
        val presets = (Engine.call("presets") as JSONArray).objects()
        val f = Form(this)
        val names = listOf("60 Hz", "170 Hz", "310 Hz", "600 Hz", "1 kHz", "3 kHz", "6 kHz", "12 kHz", "14 kHz", "16 kHz")
        val db = { v: Int -> getString(R.string.db, v) }
        val on = f.check(getString(R.string.eq_on), eq.optBoolean("enabled"))
        val sliders = ArrayList<SeekBar>()
        fun send() { Engine.call("eq", eq); Engine.reloadSettings() }
        on.setOnCheckedChangeListener { _, c -> eq.put("enabled", c); send() }
        val saved = { eq.optJSONArray("saved")?.objects()?.map { it.getString("name") }.orEmpty() }
        val presetName = {
            eq.optString("saved_name").takeIf { it.isNotEmpty() && !eq.isNull("saved_name") }
                ?: presets.firstOrNull { it.getString("id") == eq.optString("preset") }?.getString("name") ?: ""
        }
        f.button("${getString(R.string.preset)}: ${presetName()}") { b ->
            val names = presets.map { it.getString("name") } + saved().map { getString(R.string.saved_preset, it) }
            choose(getString(R.string.preset), names, presets.indexOfFirst { it.getString("id") == eq.optString("preset") }) { i ->
                if (i < presets.size) Engine.call("set_preset", presets[i].getString("id"))
                else Engine.call("eq_apply_saved", saved()[i - presets.size])
                Engine.reloadSettings()
                val fresh = Engine.settings.getJSONObject("eq")
                fresh.keys().forEach { k -> eq.put(k, fresh.get(k)) }
                on.isChecked = true
                b.text = "${getString(R.string.preset)}: ${presetName()}"
                val bands = eq.getJSONArray("bands")
                sliders.forEachIndexed { j, s -> s.progress = bands.optInt(j) }
            }
        }
        val bands = eq.optJSONArray("bands") ?: JSONArray(List(10) { 0 })
        names.forEachIndexed { i, n ->
            sliders += f.slider(n, -12, 12, bands.optInt(i), db) { v ->
                bands.put(i, v); eq.put("bands", bands); eq.put("preset", "custom"); eq.put("enabled", true); on.isChecked = true; send()
            }
        }
        f.slider(getString(R.string.preamp), -12, 12, eq.optInt("preamp_db"), db) { v ->
            eq.put("preamp_db", v); eq.put("preamp_enabled", true); send()
        }
        f.button(getString(R.string.save_preset)) {
            askText(getString(R.string.save_preset), getString(R.string.name)) { n ->
                if (n.isEmpty()) return@askText
                Engine.call("eq_save", n)
                Engine.reloadSettings()
                Engine.settings.optJSONObject("eq")?.let { fresh -> fresh.keys().forEach { k -> eq.put(k, fresh.get(k)) } }
                act(getString(R.string.saved))
            }
        }
        f.button(getString(R.string.delete_preset)) {
            val names = saved()
            if (names.isEmpty()) return@button say(getString(R.string.no_saved_presets))
            choose(getString(R.string.delete_preset), names) { i ->
                Engine.call("eq_delete", names[i])
                Engine.reloadSettings()
                Engine.settings.optJSONObject("eq")?.let { fresh -> fresh.keys().forEach { k -> eq.put(k, fresh.get(k)) } }
                act(getString(R.string.deleted))
            }
        }
        AlertDialog.Builder(this).setTitle(R.string.equalizer).setView(f.scroll).setPositiveButton(R.string.close, null).show()
    }

    private fun asset(name: String) = try { assets.open(name).bufferedReader().readText() } catch (_: Exception) { "" }

    private fun about() {
        val v = packageManager.getPackageInfo(packageName, 0).versionName
        // Every project link is its own button, so TalkBack and a keyboard
        // reach it, instead of URLs inside the text.
        val form = Form(this)
        form.para(getString(R.string.about_text, v))
        val repo = "https://git.serrebiradio.com/serrebi/BlindRadio-Android"
        for ((label, url) in listOf(
            R.string.about_github to "https://github.com/serrebidev/BlindRadio-Android",
            R.string.about_forgejo to repo,
            R.string.about_projects to "https://projects.serrebiradio.com",
            R.string.about_telegram to "https://t.me/SerrebiProjects",
            R.string.about_credits to "$repo/src/branch/main/CREDITS.md",
        )) form.button(getString(label)) { openUrl(url) }
        AlertDialog.Builder(this).setTitle(R.string.about).setView(form.scroll).setPositiveButton(R.string.close, null).show()
    }

    // ---- import, export ----

    private fun importPlaylist() {
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), IMPORT)
    }

    private fun importFolder() {
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), IMPORT_FOLDER)
    }

    /** Each top folder of the place as its own playlist file, in a new folder inside the one picked. */
    private fun exportFolder(place: JSONObject) {
        exportFiles = Engine.call("export_folder", place) as JSONObject
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), EXPORT_FOLDER)
    }

    /** Every playlist file under a picked folder, with the subfolders it sits in. Slow on a big folder: off the main thread. */
    private fun readTree(tree: Uri, docId: String, dirs: List<String>, out: JSONArray) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val cols = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)
        contentResolver.query(children, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                val name = c.getString(1) ?: continue
                if (c.getString(2) == Document.MIME_TYPE_DIR) {
                    readTree(tree, id, dirs + name, out)
                } else if (name.substringAfterLast('.', "").lowercase() in PLAYLIST_EXT) {
                    val text = contentResolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, id))?.use { String(it.readBytes()) } ?: continue
                    out.put(JSONObject().put("dirs", JSONArray(dirs)).put("file", name).put("text", text))
                }
            }
        }
    }

    /** Off the main thread, then back on it: engine calls stay on the main thread. */
    private fun inBackground(work: () -> Unit) {
        Thread {
            try {
                work()
            } catch (e: Exception) {
                runOnUiThread { say(e.message ?: e.toString()) }
            }
        }.start()
    }

    private fun export(place: JSONObject, name: String) {
        exportText = Engine.call("export", place) as String
        @Suppress("DEPRECATION")
        startActivityForResult(
            Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/x-mpegurl").putExtra(Intent.EXTRA_TITLE, "$name.m3u"),
            EXPORT,
        )
    }

    @Deprecated("Framework activity results")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        try {
            when (requestCode) {
                EXPORT -> {
                    contentResolver.openOutputStream(uri)?.use { it.write(exportText.toByteArray()) }
                    act(getString(R.string.exported))
                }
                IMPORT_FOLDER -> {
                    val root = DocumentsContract.getTreeDocumentId(uri)
                    val name = displayName(DocumentsContract.buildDocumentUriUsingTree(uri, root))
                    choose(getString(R.string.import_folder), listOf(getString(R.string.import_as_list, name), getString(R.string.import_into_custom))) { i ->
                        say(getString(R.string.reading_folder))
                        inBackground {
                            val files = JSONArray()
                            readTree(uri, root, emptyList(), files)
                            runOnUiThread {
                                try {
                                    say(Engine.call("import_folder", JSONObject().put("files", files).put("name", name).put("as_list", i == 0)) as String)
                                    reload()
                                } catch (e: Exception) {
                                    say(e.message ?: e.toString())
                                }
                            }
                        }
                    }
                }
                EXPORT_FOLDER -> {
                    val ex = exportFiles ?: return
                    val parent = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
                    inBackground {
                        // The provider names it Jazz (1) when Jazz is taken: nothing is overwritten.
                        val dir = DocumentsContract.createDocument(contentResolver, parent, Document.MIME_TYPE_DIR, ex.getString("name"))
                            ?: throw java.io.IOException(getString(R.string.not_saved))
                        for (f in ex.getJSONArray("files").objects()) {
                            val doc = DocumentsContract.createDocument(contentResolver, dir, "audio/x-mpegurl", f.getString("name"))
                                ?: throw java.io.IOException(getString(R.string.not_saved))
                            contentResolver.openOutputStream(doc)?.use { it.write(f.getString("text").toByteArray()) }
                        }
                        runOnUiThread { act(getString(R.string.exported)) }
                    }
                }
                IMPORT -> {
                    val text = contentResolver.openInputStream(uri)?.use { it.readBytes() }?.let { String(it) } ?: return
                    val name = displayName(uri).substringBeforeLast('.').ifEmpty { "Imported" }
                    choose(getString(R.string.import_playlist), listOf(getString(R.string.import_as_list, name), getString(R.string.import_into_custom))) { i ->
                        say(Engine.call("import", JSONObject().put("text", text).put("name", name).put("as_list", i == 0)) as String)
                        reload()
                    }
                }
            }
        } catch (e: Exception) {
            say(e.message ?: e.toString())
        }
    }

    private fun displayName(uri: Uri): String =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: "Imported"

    // ---- row and context menus ----

    private val actionIds = intArrayOf(
        R.id.act_0, R.id.act_1, R.id.act_2, R.id.act_3, R.id.act_4, R.id.act_5, R.id.act_6, R.id.act_7,
        R.id.act_8, R.id.act_9, R.id.act_10, R.id.act_11, R.id.act_12, R.id.act_13, R.id.act_14, R.id.act_15,
        R.id.act_16, R.id.act_17, R.id.act_18, R.id.act_19,
    )

    override fun onCreateContextMenu(menu: ContextMenu, v: View, info: ContextMenu.ContextMenuInfo?) {
        val pos = (info as? AdapterView.AdapterContextMenuInfo)?.position ?: return
        actions(pos).forEachIndexed { i, (label, _) -> menu.add(0, i, i, label) }
        cur.rows.getOrNull(pos)?.let { menu.setHeaderTitle(label(it)) }
    }

    override fun onContextItemSelected(item: MenuItem): Boolean {
        val pos = (item.menuInfo as? AdapterView.AdapterContextMenuInfo)?.position ?: return false
        actions(pos).getOrNull(item.itemId)?.second?.invoke() ?: return false
        return true
    }

    /** The now-playing line: click plays or pauses, the player's commands are its TalkBack actions. */
    inner class NowView(context: Context) : TextView(context) {
        override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            if (!Engine.started) return
            info.addAction(AccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, getString(if (Engine.playing != null) R.string.pause else R.string.play)))
            playerCommands().take(actionIds.size).forEachIndexed { i, (label, _) -> info.addAction(AccessibilityAction(actionIds[i], label)) }
        }

        override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
            val i = actionIds.indexOf(action)
            if (i >= 0 && Engine.started) {
                playerCommands().getOrNull(i)?.second?.invoke() ?: return false
                return true
            }
            return super.performAccessibilityAction(action, arguments)
        }
    }

    /** A row: its text, its state, its click label, and its TalkBack actions. */
    inner class RowView(context: Context) : TextView(context) {
        var pos = -1

        init {
            val pad = dp(12)
            setPadding(pad, pad, pad, pad)
            textSize = 18f
            // Large enough to touch, per the minimum target size.
            minHeight = dp(48)
        }

        override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            val r = cur.rows.getOrNull(pos) ?: return
            val click = when (r) {
                is Row.Station -> getString(if (isPlaying(r.s)) R.string.pause else R.string.play)
                is Row.Folder, is Row.Lib -> getString(R.string.open)
                is Row.More -> getString(R.string.load_more)
                is Row.Item -> getString(R.string.turn_on_off)
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
            v.contentDescription = null
            v.state(null)
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
                    if (isPlaying(r.s)) {
                        if (Build.VERSION.SDK_INT >= 30) v.state(getString(R.string.playing))
                        else v.contentDescription = "$text, ${getString(R.string.playing)}"
                    }
                }
                is Row.Folder -> v.text = r.name
                is Row.Lib -> v.text = getString(if (r.isList) R.string.list_row else R.string.folder_row, r.name, count(r.count))
                is Row.More -> v.text = getString(R.string.load_more)
                is Row.Item -> v.text = r.text
            }
            return v
        }
    }
}
