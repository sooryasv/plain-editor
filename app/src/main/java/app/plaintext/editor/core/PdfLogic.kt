package app.plaintext.editor.core

/** Pure helpers for the PDF viewer (the rendering itself uses android.graphics.pdf.PdfRenderer in the ui package). */
object PdfSniffer {
    const val HEAD_BYTES = 1024

    /** A PDF has "%PDF-" within its first 1024 bytes (ISO 32000). Authoritative: names and MIME types can lie. */
    fun isPdf(head: ByteArray, len: Int): Boolean {
        val sig = byteArrayOf('%'.code.toByte(), 'P'.code.toByte(), 'D'.code.toByte(), 'F'.code.toByte(), '-'.code.toByte())
        val max = minOf(len, head.size) - sig.size
        for (i in 0..max) {
            var ok = true
            for (j in sig.indices) if (head[i + j] != sig[j]) { ok = false; break }
            if (ok) return true
        }
        return false
    }
}

object PdfMath {
    const val MIN_ZOOM = 0.5f
    const val MAX_ZOOM = 4.0f
    const val ZOOM_STEP = 1.25f
    /** Largest page bitmap we will allocate (pixels). 4 bytes each, so ~32 MB. */
    const val MAX_PIXELS = 8_000_000L

    fun clampPage(page: Int, count: Int): Int = if (count <= 0) 0 else page.coerceIn(0, count - 1)

    fun zoomIn(z: Float): Float = (z * ZOOM_STEP).coerceIn(MIN_ZOOM, MAX_ZOOM)
    fun zoomOut(z: Float): Float = (z / ZOOM_STEP).coerceIn(MIN_ZOOM, MAX_ZOOM)
    fun clampZoom(z: Float): Float = if (z.isNaN()) 1f else z.coerceIn(MIN_ZOOM, MAX_ZOOM)

    /**
     * Bitmap size for a page of [pageW] x [pageH] points when 1.0 zoom means "fit [viewportW] pixels".
     * Scales down (keeping aspect ratio) if the result would exceed [maxPixels]. Never returns less than 1x1.
     * @return Triple(width, height, effectiveZoom)
     */
    fun bitmapSize(pageW: Int, pageH: Int, viewportW: Int, zoom: Float, maxPixels: Long = MAX_PIXELS): Triple<Int, Int, Float> {
        val pw = maxOf(pageW, 1).toDouble()
        val ph = maxOf(pageH, 1).toDouble()
        var z = clampZoom(zoom).toDouble()
        var w = maxOf(viewportW, 1) * z
        var h = w * ph / pw
        if (w * h > maxPixels) {
            val f = Math.sqrt(maxPixels / (w * h))
            w *= f; h *= f; z *= f
        }
        return Triple(maxOf(1, Math.round(w).toInt()), maxOf(1, Math.round(h).toInt()), z.toFloat())
    }

    /** Parses a 1-based page number typed by the user; returns a 0-based index, or null if invalid. */
    fun parsePageInput(text: String, count: Int): Int? {
        val n = text.trim().toIntOrNull() ?: return null
        return if (n in 1..count) n - 1 else null
    }
}
