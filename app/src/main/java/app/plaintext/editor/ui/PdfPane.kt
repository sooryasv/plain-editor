package app.plaintext.editor.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.app.AlertDialog
import app.plaintext.editor.R
import app.plaintext.editor.core.PdfMath
import app.plaintext.editor.core.PerfStats
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Read-only PDF viewer built on the platform's android.graphics.pdf.PdfRenderer (no dependency).
 * One page is rendered at a time into a bitmap sized by [PdfMath]; zoom 1.0 = fit width. Page navigation and zoom
 * buttons; all rendering runs on one background thread (PdfRenderer allows only one open page at a time).
 */
class PdfPane(context: Context, private val onState: (page: Int, zoom: Float) -> Unit) : LinearLayout(context) {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "pdf-render").also { it.isDaemon = true } }

    private val image = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    private val imageContainer = android.widget.FrameLayout(context).apply { addView(image) }
    private val hScroll = HorizontalScrollView(context).apply { isFillViewport = false; addView(imageContainer) }
    private val vScroll = ScrollView(context).apply { isFillViewport = true; addView(hScroll) }
    private val message = TextView(context).apply { gravity = Gravity.CENTER; setPadding(32, 32, 32, 32); visibility = View.GONE }
    private val pageButton = Button(context, null, 0, android.R.style.Widget_Material_Button_Borderless).apply { isAllCaps = false }
    private val zoomLabel = TextView(context).apply { setPadding(12, 0, 12, 0) }

    @Volatile private var renderer: PdfRenderer? = null
    @Volatile private var pfd: ParcelFileDescriptor? = null
    @Volatile private var tempFile: File? = null
    private var pageCount = 0
    private var page = 0
    private var zoom = 1f
    private var token = 0
    private var currentBitmap: Bitmap? = null

    init {
        orientation = VERTICAL
        val content = android.widget.FrameLayout(context)
        content.addView(vScroll, android.widget.FrameLayout.LayoutParams(-1, -1))
        content.addView(message, android.widget.FrameLayout.LayoutParams(-1, -1))
        addView(content, LayoutParams(-1, 0, 1f))
        val bar = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setBackgroundResource(R.color.symbol_bg) }
        bar.addView(button(R.string.pdf_prev) { goTo(page - 1) })
        pageButton.setOnClickListener { askPage() }
        bar.addView(pageButton)
        bar.addView(button(R.string.pdf_next) { goTo(page + 1) })
        bar.addView(View(context), LayoutParams(0, 1, 1f))
        bar.addView(button(R.string.pdf_zoom_out) { setZoom(PdfMath.zoomOut(zoom)) })
        bar.addView(zoomLabel)
        bar.addView(button(R.string.pdf_zoom_in) { setZoom(PdfMath.zoomIn(zoom)) })
        bar.addView(button(R.string.pdf_fit) { setZoom(1f) })
        addView(bar, LayoutParams(-1, -2))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (pageCount > 0 && w > 0 && w != oldw) post { renderCurrent() }     // e.g. rotation: fit the new width
    }

    private fun button(label: Int, click: () -> Unit) =
        Button(context, null, 0, android.R.style.Widget_Material_Button_Borderless).apply {
            setText(label); isAllCaps = false; minWidth = 0; minimumWidth = 0; setPadding(20, 0, 20, 0)
            setOnClickListener { click() }
        }

    /** Opens [uri]; [startPage] is 0-based. Safe to call again for another file. */
    fun open(uri: String, startPage: Int, startZoom: Float) {
        close()
        page = maxOf(startPage, 0); zoom = PdfMath.clampZoom(startZoom)
        showMessage(context.getString(R.string.pdf_loading))
        val my = ++token
        io.execute {
            try {
                val (descriptor, temp) = openDescriptor(uri)
                val r = try { PdfRenderer(descriptor) } catch (e: Exception) { descriptor.close(); temp?.delete(); throw e }
                renderer = r; pfd = descriptor; tempFile = temp
                val count = r.pageCount
                main.post {
                    if (my != token) return@post
                    pageCount = count; page = PdfMath.clampPage(page, count)
                    message.visibility = View.GONE; renderCurrent()
                }
            } catch (e: Throwable) {
                main.post { if (my == token) showMessage(context.getString(R.string.pdf_error, describe(e))) }
            }
        }
    }

    /** Releases the renderer and file descriptor. */
    fun close() {
        token++
        val r = renderer; val d = pfd; val t = tempFile
        renderer = null; pfd = null; tempFile = null; pageCount = 0
        io.execute { try { r?.close() } catch (e: Exception) { }; try { d?.close() } catch (e: Exception) { }; t?.delete() }
        image.setImageDrawable(null)
        currentBitmap?.recycle(); currentBitmap = null
    }

    private fun describe(e: Throwable): String = when (e) {
        is SecurityException -> "it is password-protected or uses an unsupported security setting."
        is OutOfMemoryError -> "not enough memory."
        is IOException -> e.message ?: "it could not be read."
        else -> e.message ?: "it may be damaged."
    }

    /** Seekable file descriptor for [uri]; copies to a cache file when the provider's descriptor cannot seek. */
    private fun openDescriptor(uri: String): Pair<ParcelFileDescriptor, File?> {
        val u = Uri.parse(uri)
        try {
            val d = context.contentResolver.openFileDescriptor(u, "r")
            if (d != null) {
                try {
                    android.system.Os.lseek(d.fileDescriptor, 0, android.system.OsConstants.SEEK_SET)
                    return d to null
                } catch (e: Exception) {
                    d.close()
                }
            }
        } catch (e: Exception) {
            // fall through to the copy
        }
        val input = context.contentResolver.openInputStream(u) ?: throw IOException("cannot open the file")
        val tmp = File(context.cacheDir, "pdf-view-${System.nanoTime()}.pdf")
        input.use { i ->
            tmp.outputStream().use { o ->
                val buf = ByteArray(64 * 1024); var total = 0L
                while (true) {
                    val n = i.read(buf); if (n < 0) break
                    total += n; if (total > MAX_COPY_BYTES) { tmp.delete(); throw IOException("the file is too large to copy for viewing") }
                    o.write(buf, 0, n)
                }
            }
        }
        return ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY) to tmp
    }

    private fun goTo(p: Int) {
        if (pageCount == 0) return
        val np = PdfMath.clampPage(p, pageCount)
        if (np == page) return
        page = np; renderCurrent(); vScroll.scrollTo(0, 0); hScroll.scrollTo(0, 0)
    }

    private fun setZoom(z: Float) {
        val nz = PdfMath.clampZoom(z)
        if (nz == zoom && currentBitmap != null) return
        zoom = nz; renderCurrent()
    }

    private fun askPage() {
        if (pageCount == 0) return
        val input = EditText(context).apply { inputType = InputType.TYPE_CLASS_NUMBER; hint = context.getString(R.string.pdf_goto_hint, pageCount) }
        AlertDialog.Builder(context).setTitle(R.string.pdf_goto_title).setView(input)
            .setPositiveButton(R.string.btn_ok) { _, _ -> PdfMath.parsePageInput(input.text.toString(), pageCount)?.let { goTo(it) } }
            .setNegativeButton(R.string.btn_cancel, null).show()
    }

    private fun showMessage(text: String) { message.text = text; message.visibility = View.VISIBLE }

    private fun renderCurrent() {
        pageButton.text = if (pageCount > 0) "${page + 1} / $pageCount" else "–"
        zoomLabel.text = "${Math.round(zoom * 100)}%"
        onState(page, zoom)
        if (pageCount == 0) return

        if (vScroll.width <= 0) {
            vScroll.post { if (pageCount > 0) renderCurrent() }
            return
        }

        val my = ++token
        val wanted = page; val z = zoom
        val viewportW = vScroll.width
        io.execute {
            val r = renderer ?: return@execute
            try {
                val t0 = System.nanoTime()
                val p = r.openPage(wanted)
                val bmp: Bitmap
                val w: Int
                val h: Int
                try {
                    val size = PdfMath.bitmapSize(p.width, p.height, viewportW, z)
                    w = size.first
                    h = size.second
                    bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                } finally {
                    p.close()
                }
                PerfStats.record("pdf page render", (System.nanoTime() - t0) / 1_000_000)
                main.post {
                    if (my != token) { bmp.recycle(); return@post }
                    val old = currentBitmap
                    image.layoutParams = android.widget.FrameLayout.LayoutParams(w, h).apply {
                        gravity = Gravity.CENTER_HORIZONTAL
                    }
                    imageContainer.layoutParams = android.widget.FrameLayout.LayoutParams(
                        maxOf(w, viewportW),
                        maxOf(h, 1)
                    )
                    image.setImageBitmap(bmp); currentBitmap = bmp
                    old?.recycle()
                }
            } catch (e: Throwable) {
                main.post { if (my == token) showMessage(context.getString(R.string.pdf_error, describe(e))) }
            }
        }
    }

    private companion object {
        const val MAX_COPY_BYTES = 300L * 1024 * 1024
    }
}
