package dev.serrebi.blindradio

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/** Small, screen-reader-safe dialog builders: every field has a visible label it is named by. */

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

/** A labelled list of choices; [checked] marks the current one. */
fun Activity.choose(title: String, items: List<String>, checked: Int = -1, pick: (Int) -> Unit) {
    val b = AlertDialog.Builder(this).setTitle(title)
    if (checked >= 0) {
        b.setSingleChoiceItems(items.toTypedArray(), checked) { d, i -> d.dismiss(); pick(i) }
    } else {
        b.setItems(items.toTypedArray()) { _, i -> pick(i) }
    }
    b.setNegativeButton(R.string.cancel, null).show()
}

/** Commands as a list: the long-press menu, the Menu button, TalkBack's actions. */
fun Activity.commands(title: String, list: List<Pair<String, () -> Unit>>) {
    if (list.isEmpty()) return
    choose(title, list.map { it.first }) { list[it].second() }
}

/** One text field; [done] gets the trimmed text. */
fun Activity.askText(title: String, label: String, start: String = "", type: Int = InputType.TYPE_CLASS_TEXT, done: (String) -> Unit) {
    val form = Form(this)
    val field = form.text(label, start, type)
    AlertDialog.Builder(this)
        .setTitle(title)
        .setView(form.scroll)
        .setPositiveButton(android.R.string.ok) { _, _ -> done(field.text.toString().trim()) }
        .setNegativeButton(R.string.cancel, null)
        .show()
    field.requestFocus()
}

fun Activity.showText(title: String, text: String) {
    AlertDialog.Builder(this).setTitle(title).setMessage(text).setPositiveButton(R.string.close, null).show()
}

fun Activity.confirm(text: String, yes: () -> Unit) {
    AlertDialog.Builder(this).setMessage(text)
        .setPositiveButton(android.R.string.ok) { _, _ -> yes() }
        .setNegativeButton(R.string.cancel, null).show()
}

/** A vertical form in a scroll view. Labels come before their fields, and name them. */
class Form(private val ctx: Context) {
    val box = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(ctx.dp(16), ctx.dp(8), ctx.dp(16), ctx.dp(8))
    }
    val scroll = ScrollView(ctx).apply { addView(box) }

    private fun add(v: View) = box.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

    fun heading(text: String) = add(TextView(ctx).apply {
        this.text = text
        textSize = 20f
        isAccessibilityHeading = true
        setPadding(0, ctx.dp(12), 0, ctx.dp(4))
    })

    fun check(label: String, on: Boolean): CheckBox = CheckBox(ctx).apply {
        text = label
        isChecked = on
        minHeight = ctx.dp(48)
        add(this)
    }

    fun text(label: String, start: String, type: Int = InputType.TYPE_CLASS_TEXT): EditText {
        val l = TextView(ctx).apply { text = label }
        add(l)
        return EditText(ctx).apply {
            setText(start)
            inputType = type
            isSingleLine = true
            id = View.generateViewId()
            l.labelFor = id
            add(this)
        }
    }

    fun number(label: String, start: Int) = text(label, start.toString(), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED)

    fun button(label: String, onClick: (Button) -> Unit): Button = Button(ctx).apply {
        text = label
        setOnClickListener { onClick(this) }
        add(this)
    }

    /** A slider TalkBack moves with swipes up and down; [say] gives what it reads. */
    fun slider(label: String, min: Int, max: Int, start: Int, say: (Int) -> String, moved: (Int) -> Unit): SeekBar {
        val l = TextView(ctx).apply { text = label }
        add(l)
        return SeekBar(ctx).apply {
            this.min = min
            this.max = max
            progress = start
            minimumHeight = ctx.dp(48)
            contentDescription = label
            state(say(start))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, v: Int, user: Boolean) {
                    state(say(v))
                    if (user) moved(v)
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
            add(this)
        }
    }
}

/** What a control is at (Android 11+); Android 10 reads the plain value. */
fun View.state(text: CharSequence?) {
    if (android.os.Build.VERSION.SDK_INT >= 30) stateDescription = text
}

fun JSONArray.objects(): List<JSONObject> = List(length()) { getJSONObject(it) }

fun JSONArray.strings(): List<String> = List(length()) { getString(it) }
