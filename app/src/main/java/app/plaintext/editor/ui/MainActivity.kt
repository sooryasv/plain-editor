package app.plaintext.editor.ui

import android.annotation.TargetApi
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewTreeObserver
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import app.plaintext.editor.R
import app.plaintext.editor.core.DocKind
import app.plaintext.editor.core.EditorController
import app.plaintext.editor.core.EditorView
import app.plaintext.editor.core.FileNaming
import app.plaintext.editor.core.Limits
import app.plaintext.editor.core.PerfStats
import app.plaintext.editor.core.SaveStatus
import app.plaintext.editor.core.SearchStatus
import app.plaintext.editor.core.UiState

/**
 * Thin view layer. All document state lives in [EditorController] (which survives this Activity); this class
 * only shows one window of the active tab in an EditText, forwards edits/selection/scroll, and hosts dialogs.
 */
class MainActivity : Activity(), EditorView {

    private lateinit var controller: EditorController
    private lateinit var prefs: Prefs
    private lateinit var recents: PrefsRecents

    private lateinit var tabScroll: HorizontalScrollView
    private lateinit var tabStrip: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var editor: EditorEditText
    private lateinit var content: FrameLayout
    private lateinit var navSeek: SeekBar
    private lateinit var symbolScroll: View
    private lateinit var symbolDivider: View
    private lateinit var findBar: View
    private lateinit var replaceRow: View
    private lateinit var findQuery: EditText
    private lateinit var replaceText: EditText
    private lateinit var findCase: CheckBox
    private lateinit var findWord: CheckBox
    private lateinit var findCount: TextView
    private var pdfPane: PdfPane? = null

    private val handler = Handler(Looper.getMainLooper())
    private var suppress = false               // true while the controller itself changes the EditText
    private var windowLen = 0
    private var lastState: UiState? = null
    private var menu: Menu? = null
    private var dialog: Dialog? = null
    private var highlight: BackgroundColorSpan? = null
    private var seeking = false
    private var lastShiftAt = 0L
    private var pendingTop = 0
    private var pendingToEnd = false
    private var layoutT0 = 0L

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        recents = AppState.recents(this)
        controller = AppState.controller(this)
        bindViews()
        buildToolBar()
        applyPrefs()
        controller.attach(this)
        controller.start()
        if (savedInstanceState == null) handleIntent(intent)
        if (Build.VERSION.SDK_INT >= 33) registerBackCallback()
        editor.requestFocus()
        editor.postDelayed({ showKeyboard() }, 250)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onPause() {
        super.onPause()
        controller.onPause()
    }

    override fun onDestroy() {
        controller.detach(this)       // the workspace itself lives on in AppState
        pdfPane?.close()
        dialog?.dismiss()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onBackPressed() { handleBack() }

    @TargetApi(33)
    private fun registerBackCallback() {
        onBackInvokedDispatcher.registerOnBackInvokedCallback(
            OnBackInvokedDispatcher.PRIORITY_DEFAULT,
            OnBackInvokedCallback { handleBack() },
        )
    }

    private fun handleBack() {
        if (findBar.visibility == View.VISIBLE) { closeFind(); return }
        controller.requestExit()
    }

    // ------------------------------------------------------------------ view wiring

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun bindViews() {
        tabScroll = findViewById(R.id.tab_scroll)
        tabStrip = findViewById(R.id.tab_strip)
        scroll = findViewById(R.id.scroll)
        editor = findViewById(R.id.editor)
        content = findViewById(R.id.content)
        navSeek = findViewById(R.id.nav_seek)
        symbolScroll = findViewById(R.id.symbol_scroll)
        symbolDivider = findViewById(R.id.symbol_divider)
        findBar = findViewById(R.id.find_bar)
        replaceRow = findViewById(R.id.replace_row)
        findQuery = findViewById(R.id.find_query)
        replaceText = findViewById(R.id.replace_text)
        findCase = findViewById(R.id.find_case)
        findWord = findViewById(R.id.find_word)
        findCount = findViewById(R.id.find_count)

        editor.addTextChangedListener(watcher)
        editor.onSelection = { a, b -> if (!suppress) controller.onSelectionChanged(a, b) }
        editor.onTabKey = { shift -> if (shift) controller.outdentSelection() else controller.tabKey(); true }
        scroll.setOnScrollChangeListener { _, _, y, _, _ -> onScrollChanged(y) }

        navSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(s: SeekBar) { seeking = true }
            override fun onStopTrackingTouch(s: SeekBar) { seeking = false; controller.jumpToPermille(s.progress) }
        })

        findViewById<Button>(R.id.find_next).setOnClickListener { doFind(true) }
        findViewById<Button>(R.id.find_prev).setOnClickListener { doFind(false) }
        findViewById<Button>(R.id.find_close).setOnClickListener { closeFind() }
        findViewById<Button>(R.id.replace_one).setOnClickListener {
            controller.replaceCurrent(findQuery.text.toString(), replaceText.text.toString(), findCase.isChecked, findWord.isChecked)
            applyHighlight()
        }
        findViewById<Button>(R.id.replace_all).setOnClickListener {
            controller.replaceAll(findQuery.text.toString(), replaceText.text.toString(), findCase.isChecked, findWord.isChecked)
        }
        findQuery.setOnEditorActionListener { _, id, e ->
            if (id == EditorInfo.IME_ACTION_SEARCH || (e != null && e.keyCode == KeyEvent.KEYCODE_ENTER)) { doFind(true); true } else false
        }
        val recount = Runnable { controller.refreshSearchCount(findQuery.text.toString(), findCase.isChecked, findWord.isChecked) }
        findQuery.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence, a: Int, b: Int, c: Int) {
                handler.removeCallbacks(recount); handler.postDelayed(recount, 300)
            }
            override fun afterTextChanged(s: Editable) {}
        })
        findCase.setOnCheckedChangeListener { _, _ -> recount.run() }
        findWord.setOnCheckedChangeListener { _, _ -> recount.run() }
    }

    // ------------------------------------------------------------------ text watcher / scrolling

    private val watcher = object : TextWatcher {
        private var start = 0
        private var removed = ""

        override fun beforeTextChanged(s: CharSequence, st: Int, count: Int, after: Int) {
            if (suppress) return
            start = st
            removed = if (count == 0) "" else s.subSequence(st, st + count).toString()
        }

        override fun onTextChanged(s: CharSequence, st: Int, before: Int, count: Int) {
            if (suppress) return
            val inserted = if (count == 0) "" else s.subSequence(st, st + count).toString()
            if (inserted == removed) return                    // the keyboard re-sent identical text: not an edit
            clearHighlight()
            windowLen = s.length
            controller.onWindowEdit(start, removed, inserted, s.length)
        }

        override fun afterTextChanged(s: Editable) {}
    }

    private fun onScrollChanged(y: Int) {
        val layout = editor.layout ?: return
        val line = layout.getLineForVertical((y - editor.paddingTop).coerceAtLeast(0))
        controller.onScrolled(layout.getLineStart(line))
        if (navSeek.visibility == View.VISIBLE && !seeking) navSeek.progress = controller.currentPermille()
        // At a window edge of a large document: move the window (rate limited; the controller ignores it at the real ends).
        val now = System.currentTimeMillis()
        if (now - lastShiftAt < 400) return
        val contentH = editor.height
        if (y <= 0) { lastShiftAt = now; controller.requestShift(-1) }
        else if (y + scroll.height >= contentH - dp(8)) { lastShiftAt = now; controller.requestShift(+1) }
    }

    private fun applyPendingScroll() {
        val layout = editor.layout ?: return
        if (pendingToEnd) {
            scroll.scrollTo(0, maxOf(0, editor.height - scroll.height))
        } else {
            val off = pendingTop.coerceIn(0, editor.text.length)
            scroll.scrollTo(0, layout.getLineTop(layout.getLineForOffset(off)) + editor.paddingTop)
        }
        lastShiftAt = System.currentTimeMillis()
    }

    private fun ensureVisible(offset: Int) {
        val layout = editor.layout ?: return
        val line = layout.getLineForOffset(offset.coerceIn(0, editor.text.length))
        val top = layout.getLineTop(line) + editor.paddingTop
        val bottom = layout.getLineBottom(line) + editor.paddingTop
        if (top < scroll.scrollY || bottom > scroll.scrollY + scroll.height) scroll.scrollTo(0, maxOf(0, top - scroll.height / 3))
    }

    // ------------------------------------------------------------------ EditorView: text

    override fun showWindow(text: String, selStart: Int, selEnd: Int, topOffset: Int, scrollToEnd: Boolean) {
        showEditorPane()
        clearHighlight()
        layoutT0 = System.nanoTime()
        lastShiftAt = System.currentTimeMillis()      // a window swap must not trigger an edge shift from its own scroll events
        suppress = true
        try {
            editor.setText(text)
            val len = editor.text.length
            editor.setSelection(selStart.coerceIn(0, len), selEnd.coerceIn(selStart.coerceIn(0, len), len))
        } finally {
            suppress = false
        }
        windowLen = text.length
        pendingTop = topOffset; pendingToEnd = scrollToEnd
        val vto = editor.viewTreeObserver
        if (vto.isAlive) vto.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (editor.viewTreeObserver.isAlive) editor.viewTreeObserver.removeOnPreDrawListener(this)
                applyPendingScroll()
                PerfStats.record("window layout (${text.length / 1000}k chars)", (System.nanoTime() - layoutT0) / 1_000_000)
                return true
            }
        })
    }

    override fun replaceInWindow(start: Int, end: Int, replacement: String, selStart: Int, selEnd: Int) {
        clearHighlight()
        suppress = true
        try {
            editor.text.replace(start, end, replacement)
            val len = editor.text.length
            editor.setSelection(selStart.coerceIn(0, len), selEnd.coerceIn(selStart.coerceIn(0, len), len))
        } finally {
            suppress = false
        }
        windowLen = editor.text.length
        getSystemService(InputMethodManager::class.java)?.restartInput(editor)
        ensureVisible(selStart)
    }

    override fun selectInWindow(selStart: Int, selEnd: Int) {
        val len = editor.text.length
        val a = selStart.coerceIn(0, len)
        suppress = true
        try { editor.setSelection(a, selEnd.coerceIn(a, len)) } finally { suppress = false }
        ensureVisible(a)
    }

    override fun scrollToEdge(end: Boolean) {
        scroll.post { if (end) scroll.scrollTo(0, maxOf(0, editor.height - scroll.height)) else scroll.scrollTo(0, 0) }
    }

    override fun windowText(): String = editor.text.toString()

    private fun applyHighlight() {
        clearHighlight()
        val a = editor.selectionStart; val b = editor.selectionEnd
        if (a < 0 || b <= a) return
        val span = BackgroundColorSpan(resources.getColor(R.color.find_highlight, theme))
        editor.text.setSpan(span, a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        highlight = span
    }

    private fun clearHighlight() {
        highlight?.let { editor.text.removeSpan(it) }
        highlight = null
    }

    // ------------------------------------------------------------------ EditorView: PDF + panes

    private fun showEditorPane() {
        scroll.visibility = View.VISIBLE
        pdfPane?.let { if (it.visibility == View.VISIBLE) { it.visibility = View.GONE; it.close() } }
    }

    override fun showPdf(uri: String, name: String, page: Int, zoom: Float) {
        hideKeyboard()
        scroll.visibility = View.GONE
        closeFindQuietly()
        val pane = pdfPane ?: PdfPane(this) { p, z -> controller.onPdfState(p, z) }.also {
            pdfPane = it
            content.addView(it, FrameLayout.LayoutParams(-1, -1))
        }
        pane.visibility = View.VISIBLE
        pane.open(uri, page, zoom)
    }

    // ------------------------------------------------------------------ EditorView: state rendering

    override fun render(state: UiState) {
        if (state == lastState) return
        val prev = lastState
        lastState = state
        editor.isEnabled = !state.busy
        if (prev != null && prev.busy && !state.busy) editor.requestFocus()

        val bar = actionBar
        if (bar != null) {
            bar.title = (if (state.status == SaveStatus.UNSAVED) "• " else "") + state.name
            val statusText: String; val colorRes: Int
            when {
                state.busy -> { statusText = getString(R.string.status_opening); colorRes = R.color.status_neutral }
                state.kind == DocKind.PDF -> { statusText = "PDF · view only"; colorRes = R.color.status_neutral }
                state.status == SaveStatus.SAVED -> { statusText = getString(R.string.status_saved); colorRes = R.color.status_saved }
                state.status == SaveStatus.SAVING -> { statusText = getString(R.string.status_saving); colorRes = R.color.status_neutral }
                state.status == SaveStatus.UNSAVED -> {
                    statusText = getString(if (state.draftBackedUp) R.string.status_unsaved_backed_up else R.string.status_unsaved)
                    colorRes = R.color.status_unsaved
                }
                else -> { statusText = getString(R.string.status_new); colorRes = R.color.status_neutral }
            }
            val prefix = if (state.kind == DocKind.PDF) "" else state.typeLabel + " · " + state.lineEnding.label + " · "
            val sb = SpannableStringBuilder(prefix)
            val from = sb.length
            sb.append(statusText)
            sb.setSpan(ForegroundColorSpan(resources.getColor(colorRes, theme)), from, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (state.readOnly && state.kind == DocKind.TEXT) sb.append(" · ").append(getString(R.string.status_read_only))
            bar.subtitle = sb
        }
        if (prev == null || prev.tabs != state.tabs || prev.canNewTab != state.canNewTab) rebuildTabs(state)
        navSeek.visibility = if (state.windowed && state.kind == DocKind.TEXT) View.VISIBLE else View.GONE
        if (!seeking && navSeek.visibility == View.VISIBLE) navSeek.progress = state.navPermille
        updateToolbarVisibility()
        if (state.kind != DocKind.TEXT) closeFindQuietly()
        updateMenu()
    }

    private fun updateToolbarVisibility() {
        val show = prefs.symbolBar && (lastState?.kind ?: DocKind.TEXT) == DocKind.TEXT
        symbolScroll.visibility = if (show) View.VISIBLE else View.GONE
        symbolDivider.visibility = symbolScroll.visibility
    }

    private fun rebuildTabs(state: UiState) {
        tabStrip.removeAllViews()
        var activeChip: View? = null
        for (t in state.tabs) {
            val chip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            if (t.active) { chip.setBackgroundResource(R.color.tab_active_bg); activeChip = chip }
            val label = TextView(this).apply {
                val shown = if (t.name.length > 22) t.name.take(21) + "…" else t.name
                text = (if (t.dirty) "• " else "") + shown + if (t.loading) " …" else ""
                setTextColor(resources.getColor(if (t.active) R.color.tab_text else R.color.tab_text_dim, theme))
                setTypeface(typeface, if (t.active) Typeface.BOLD else Typeface.NORMAL)
                setSingleLine(true)
                setPadding(dp(12), dp(10), dp(4), dp(10))
                setOnClickListener { controller.selectTab(t.id) }
            }
            val close = TextView(this).apply {
                text = "×"; textSize = 18f
                contentDescription = getString(R.string.cd_tab_close, t.name)
                setTextColor(resources.getColor(R.color.tab_text_dim, theme))
                setPadding(dp(6), dp(6), dp(12), dp(6))
                setOnClickListener { controller.closeTab(t.id) }
            }
            chip.addView(label); chip.addView(close)
            tabStrip.addView(chip)
        }
        val plus = TextView(this).apply {
            text = "+"; textSize = 22f; gravity = Gravity.CENTER
            contentDescription = getString(R.string.cd_tab_new)
            setTextColor(resources.getColor(if (state.canNewTab) R.color.tab_text else R.color.tab_text_dim, theme))
            setPadding(dp(16), dp(4), dp(16), dp(4))
            setOnClickListener { controller.requestNew() }
        }
        tabStrip.addView(plus)
        activeChip?.let { c -> tabScroll.post { tabScroll.smoothScrollTo(maxOf(0, c.left - dp(40)), 0) } }
    }

    private fun updateMenu() {
        val m = menu ?: return
        val s = lastState ?: return
        val isText = s.kind == DocKind.TEXT
        m.findItem(R.id.action_undo)?.isEnabled = s.canUndo
        m.findItem(R.id.action_redo)?.isEnabled = s.canRedo
        for (id in intArrayOf(R.id.action_save, R.id.action_save_as, R.id.action_export, R.id.action_find, R.id.action_replace,
            R.id.action_top, R.id.action_bottom, R.id.action_indent)) m.findItem(id)?.isEnabled = isText
        m.findItem(R.id.action_file_name)?.isEnabled = s.canSetName
        m.findItem(R.id.action_new)?.isEnabled = s.canNewTab
    }

    // ------------------------------------------------------------------ toolbar (jump + indentation + symbols)

    private val symbols = listOf(
        "#" to "Hash", "-" to "Dash", "_" to "Underscore", "*" to "Asterisk", "`" to "Backtick", "~" to "Tilde",
        "/" to "Slash", "\\" to "Backslash", "{}" to "Curly braces", "[]" to "Square brackets", "()" to "Parentheses",
        ":" to "Colon", "=" to "Equals", "<>" to "Angle brackets", "\"\"" to "Double quotes",
    )

    private fun toolButton(label: String, desc: String, mono: Boolean, click: () -> Unit): Button {
        val b = Button(this, null, 0, android.R.style.Widget_Material_Button_Borderless)
        b.text = label
        b.contentDescription = desc
        if (mono) { b.typeface = Typeface.MONOSPACE; b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f) } else b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        b.isAllCaps = false
        b.setTextColor(resources.getColor(R.color.symbol_text, theme))
        b.minWidth = dp(44); b.minimumWidth = dp(44); b.minHeight = dp(48); b.minimumHeight = dp(48)
        b.setPadding(dp(8), 0, dp(8), 0)
        b.isFocusable = false                      // keep focus (and the keyboard) in the editor
        b.isFocusableInTouchMode = false
        b.setOnClickListener { click() }
        return b
    }

    private fun buildToolBar() {
        val bar = findViewById<LinearLayout>(R.id.symbol_bar)
        fun add(v: View) = bar.addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(48)))
        add(toolButton(getString(R.string.tool_top), getString(R.string.menu_top), false) { controller.jumpTop() })
        add(toolButton(getString(R.string.tool_bottom), getString(R.string.menu_bottom), false) { controller.jumpBottom() })
        add(toolButton(getString(R.string.tool_tab), "Insert tab / indent", false) { controller.tabKey() })
        add(toolButton(getString(R.string.tool_outdent), "Outdent selected lines", false) { controller.outdentSelection() })
        add(toolButton(getString(R.string.tool_indent), "Indent selected lines", false) { controller.indentSelection() })
        for ((sym, label) in symbols) add(toolButton(sym, "Insert $label", true) { insertSymbol(sym) })
    }

    private fun insertSymbol(sym: String) {
        val text = editor.text
        var a = editor.selectionStart; var b = editor.selectionEnd
        if (a < 0 || b < 0) { a = text.length; b = text.length }
        val lo = minOf(a, b); val hi = maxOf(a, b)
        if (sym.length == 2) {                       // pairs: wrap the selection, or insert the pair with the cursor inside
            if (hi > lo) {
                val selected = text.subSequence(lo, hi).toString()
                text.replace(lo, hi, sym[0] + selected + sym[1])
                editor.setSelection(lo + 1, hi + 1)
            } else {
                text.replace(lo, hi, sym); editor.setSelection(lo + 1)
            }
        } else {
            text.replace(lo, hi, sym); editor.setSelection(lo + sym.length)
        }
    }

    private fun applyPrefs() {
        editor.setHorizontallyScrolling(!prefs.wrap)
        editor.typeface = if (prefs.mono) Typeface.MONOSPACE else Typeface.DEFAULT
    }

    private fun showKeyboard() {
        if (isFinishing || isDestroyed || scroll.visibility != View.VISIBLE) return
        getSystemService(InputMethodManager::class.java)?.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(editor.windowToken, 0)
    }

    // ------------------------------------------------------------------ find bar

    private fun openFind(withReplace: Boolean) {
        if (lastState?.kind != DocKind.TEXT) return
        findBar.visibility = View.VISIBLE
        replaceRow.visibility = if (withReplace) View.VISIBLE else View.GONE
        val a = editor.selectionStart; val b = editor.selectionEnd
        if (a >= 0 && b > a && b - a <= 100) {
            val sel = editor.text.subSequence(a, b).toString()
            if (!sel.contains('\n')) findQuery.setText(sel)
        }
        findQuery.requestFocus(); findQuery.selectAll()
        getSystemService(InputMethodManager::class.java)?.showSoftInput(findQuery, InputMethodManager.SHOW_IMPLICIT)
        controller.refreshSearchCount(findQuery.text.toString(), findCase.isChecked, findWord.isChecked)
    }

    private fun closeFind() {
        closeFindQuietly()
        editor.requestFocus()
    }

    private fun closeFindQuietly() {
        if (findBar.visibility != View.VISIBLE) return
        findBar.visibility = View.GONE
        clearHighlight()
    }

    private fun doFind(forward: Boolean) {
        val ok = controller.find(findQuery.text.toString(), findCase.isChecked, findWord.isChecked, forward)
        if (ok) applyHighlight()
    }

    override fun onSearchStatus(status: SearchStatus) {
        findCount.text = when {
            status.total == 0 -> getString(R.string.find_none)
            status.index > 0 -> "${status.index} / ${status.total}" + if (status.capped) "+" else ""
            else -> "${status.total}" + (if (status.capped) "+" else "") + " matches"
        }
    }

    // ------------------------------------------------------------------ menu

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        this.menu = menu
        menu.findItem(R.id.action_wrap).isChecked = prefs.wrap
        menu.findItem(R.id.action_mono).isChecked = prefs.mono
        menu.findItem(R.id.action_symbols).isChecked = prefs.symbolBar
        updateMenu()
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_undo -> controller.undo()
            R.id.action_redo -> controller.redo()
            R.id.action_find -> openFind(false)
            R.id.action_replace -> openFind(true)
            R.id.action_save -> controller.requestSave()
            R.id.action_new -> controller.requestNew()
            R.id.action_open -> controller.requestOpen()
            R.id.action_recent -> showRecent()
            R.id.action_save_as -> controller.requestSaveAs()
            R.id.action_export -> controller.requestExport()
            R.id.action_file_name -> showFileNameDialog()
            R.id.action_close_tab -> lastState?.tabs?.firstOrNull { it.active }?.let { controller.closeTab(it.id) }
            R.id.action_top -> controller.jumpTop()
            R.id.action_bottom -> controller.jumpBottom()
            R.id.action_next_tab -> controller.nextTab(1)
            R.id.action_prev_tab -> controller.nextTab(-1)
            R.id.action_indent -> showIndentDialog()
            R.id.action_wrap -> { item.isChecked = !item.isChecked; prefs.wrap = item.isChecked; applyPrefs() }
            R.id.action_mono -> { item.isChecked = !item.isChecked; prefs.mono = item.isChecked; applyPrefs() }
            R.id.action_symbols -> { item.isChecked = !item.isChecked; prefs.symbolBar = item.isChecked; updateToolbarVisibility() }
            R.id.action_about -> showAbout()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    // ------------------------------------------------------------------ pickers and external intents

    private fun launch(intent: Intent, code: Int) {
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, code)
        } catch (e: ActivityNotFoundException) {
            showError(getString(R.string.err_no_picker_title), getString(R.string.err_no_picker_message))
        }
    }

    private val grantFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION

    override fun launchOpenPicker() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"                   // many providers report .py/.yml/.md generically; content is validated after opening
            addFlags(grantFlags)
        }
        launch(i, REQ_OPEN)
    }

    override fun launchCreatePicker(suggestedName: String) {
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = MimeUtil.forFileName(suggestedName)
            putExtra(Intent.EXTRA_TITLE, suggestedName)
            addFlags(grantFlags)
        }
        launch(i, REQ_CREATE)
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = if (resultCode == RESULT_OK) data?.data else null
        if (uri != null && data != null) persistGrant(uri, data.flags)
        when (requestCode) {
            REQ_OPEN -> controller.onOpenPicked(uri?.toString())
            REQ_CREATE -> controller.onCreatePicked(uri?.toString())
        }
    }

    private fun persistGrant(uri: android.net.Uri, flags: Int) {
        try {
            contentResolver.takePersistableUriPermission(uri, flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
        } catch (e: Exception) {
            // Not persistable (e.g. a one-time grant from "Open with"): the tab works now but cannot be reopened after a restart.
        }
    }

    /** "Open with" / ACTION_VIEW / ACTION_EDIT: opens the file in a NEW tab; existing tabs and unsaved changes are never touched. */
    private fun handleIntent(i: Intent?) {
        if (i == null) return
        if (i.action != Intent.ACTION_VIEW && i.action != Intent.ACTION_EDIT) return
        val uri = i.data ?: return
        if (uri.scheme != "content") {
            showError("Can't open this link", "Only files shared by Android's file picker or a file manager (content:// links) can be opened. " +
                "“${uri.scheme}” links are not supported.")
            return
        }
        val writeGranted = (i.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0 ||
            checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED
        persistGrant(uri, i.flags)
        controller.openExternal(uri.toString(), writeGranted)
    }

    // ------------------------------------------------------------------ EditorView: dialogs

    override fun confirmUnsaved(name: String, onSave: () -> Unit, onDiscard: () -> Unit, onCancel: () -> Unit) {
        if (isFinishing || isDestroyed) return
        dialog?.dismiss()
        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.dlg_unsaved_title)
            .setMessage(getString(R.string.dlg_unsaved_message, name))
            .setPositiveButton(R.string.btn_save) { _, _ -> onSave() }
            .setNeutralButton(R.string.btn_discard) { _, _ -> onDiscard() }
            .setNegativeButton(R.string.btn_cancel) { _, _ -> onCancel() }
            .setOnCancelListener { onCancel() }
            .show()
    }

    override fun confirmExit(dirtyNames: List<String>, onKeepDrafts: () -> Unit, onDiscardAll: () -> Unit, onCancel: () -> Unit) {
        if (isFinishing || isDestroyed) return
        dialog?.dismiss()
        val list = dirtyNames.joinToString("\n") { "• $it" }
        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.dlg_exit_title)
            .setMessage(getString(R.string.dlg_exit_message, list))
            .setPositiveButton(R.string.btn_exit_keep) { _, _ -> onKeepDrafts() }
            .setNeutralButton(R.string.btn_discard_all) { _, _ -> onDiscardAll() }
            .setNegativeButton(R.string.btn_cancel) { _, _ -> onCancel() }
            .setOnCancelListener { onCancel() }
            .show()
    }

    override fun showMessage(message: String) {
        if (isFinishing || isDestroyed) return
        Toast.makeText(this, message, if (message.length > 45) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    override fun showError(title: String, message: String) {
        if (isFinishing || isDestroyed) return
        dialog?.dismiss()
        dialog = AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton(R.string.btn_ok, null).show()
    }

    override fun finishApp() { finish() }

    private fun showRecent() {
        val items = recents.list()
        if (items.isEmpty()) { showMessage(getString(R.string.msg_no_recent)); return }
        dialog?.dismiss()
        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.dlg_recent_title)
            .setItems(items.map { it.name }.toTypedArray()) { _, which -> controller.openRecent(items[which].uri) }
            .setNeutralButton(R.string.btn_clear_list) { _, _ -> recents.clear() }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun showFileNameDialog() {
        val input = EditText(this).apply { setText(controller.currentName); setSingleLine(true); setSelection(text.length) }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (ext in FileNaming.SUGGESTED_EXTENSIONS.filter { it != "htm" && it != "yaml" }) {
            val b = Button(this, null, 0, android.R.style.Widget_Material_Button_Borderless).apply {
                text = ".$ext"; isAllCaps = false; minWidth = 0; minimumWidth = 0; setPadding(dp(4), 0, dp(4), 0)
                setOnClickListener { input.setText(FileNaming.withExtension(input.text.toString(), ext)); input.setSelection(input.text.length) }
            }
            row.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0); addView(input); addView(row)
        }
        dialog?.dismiss()
        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.dlg_name_title).setView(box)
            .setPositiveButton(R.string.btn_ok) { _, _ -> controller.setFileName(input.text.toString()) }
            .setNegativeButton(R.string.btn_cancel, null).show()
    }

    private fun showIndentDialog() {
        val widths = intArrayOf(2, 3, 4, 8)
        val group = RadioGroup(this)
        widths.forEachIndexed { idx, w -> group.addView(RadioButton(this).apply { id = 1000 + idx; text = getString(R.string.indent_spaces, w); isChecked = w == prefs.indentWidth }) }
        val tabs = CheckBox(this).apply { text = getString(R.string.indent_use_tabs); isChecked = prefs.useTabs }
        val note = TextView(this).apply { text = getString(R.string.indent_note); setPadding(0, dp(8), 0, 0) }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0)
            addView(group); addView(tabs); addView(note)
        }
        dialog?.dismiss()
        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.dlg_indent_title).setView(box)
            .setPositiveButton(R.string.btn_ok) { _, _ ->
                val idx = group.checkedRadioButtonId - 1000
                if (idx in widths.indices) prefs.indentWidth = widths[idx]
                prefs.useTabs = tabs.isChecked
                showMessage(getString(R.string.msg_indent_saved, if (prefs.useTabs) "tab characters" else "${prefs.indentWidth} spaces"))
            }
            .setNegativeButton(R.string.btn_cancel, null).show()
    }

    private fun showAbout() {
        val version = try {
            if (Build.VERSION.SDK_INT >= 33) packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0)).versionName
            else @Suppress("DEPRECATION") packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) { "?" }
        dialog?.dismiss()
        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.about_title)
            .setMessage(getString(R.string.about_body, version, Limits.MAX_OPEN_BYTES / (1024 * 1024), PerfStats.summary()))
            .setPositiveButton(R.string.btn_ok, null).show()
    }

    private companion object {
        const val REQ_OPEN = 101
        const val REQ_CREATE = 102
    }
}
