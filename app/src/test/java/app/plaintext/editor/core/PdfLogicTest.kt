package app.plaintext.editor.core

import org.junit.Assert.*
import org.junit.Test

class PdfLogicTest {

    @Test fun pdfSnifferIdentifiesPdfHeaderInFirstKilobyte() {
        val validPdf = ("%PDF-1.7\n1 0 obj\n<< /Type /Catalog >>\nendobj").toByteArray()
        assertTrue(PdfSniffer.isPdf(validPdf, validPdf.size))

        val paddedPdf = ByteArray(1024).also {
            it[200] = '%'.code.toByte()
            it[201] = 'P'.code.toByte()
            it[202] = 'D'.code.toByte()
            it[203] = 'F'.code.toByte()
            it[204] = '-'.code.toByte()
        }
        assertTrue(PdfSniffer.isPdf(paddedPdf, paddedPdf.size))

        val notPdf = "Plain text document without PDF signature".toByteArray()
        assertFalse(PdfSniffer.isPdf(notPdf, notPdf.size))
    }

    @Test fun clampPageStaysWithinPageBounds() {
        assertEquals(0, PdfMath.clampPage(-5, 10))
        assertEquals(0, PdfMath.clampPage(0, 10))
        assertEquals(4, PdfMath.clampPage(4, 10))
        assertEquals(9, PdfMath.clampPage(9, 10))
        assertEquals(9, PdfMath.clampPage(15, 10))
        assertEquals(0, PdfMath.clampPage(2, 0))
    }

    @Test fun zoomInAndOutStepAndClampWithinLimits() {
        assertEquals(1.25f, PdfMath.zoomIn(1.0f), 0.001f)
        assertEquals(1.5625f, PdfMath.zoomIn(1.25f), 0.001f)
        assertEquals(0.8f, PdfMath.zoomOut(1.0f), 0.001f)

        // Clamping at boundaries
        assertEquals(PdfMath.MAX_ZOOM, PdfMath.zoomIn(3.8f), 0.001f)
        assertEquals(PdfMath.MIN_ZOOM, PdfMath.zoomOut(0.55f), 0.001f)
        assertEquals(1.0f, PdfMath.clampZoom(Float.NaN), 0.001f)
        assertEquals(PdfMath.MIN_ZOOM, PdfMath.clampZoom(0.1f), 0.001f)
        assertEquals(PdfMath.MAX_ZOOM, PdfMath.clampZoom(10.0f), 0.001f)
    }

    @Test fun bitmapSizeCalculatesExactPixelDimensions() {
        // Fit width (zoom = 1.0)
        val fit = PdfMath.bitmapSize(600, 800, 1000, 1.0f)
        assertEquals(1000, fit.first)
        assertEquals(1333, fit.second)
        assertEquals(1.0f, fit.third, 0.001f)

        // Zoom Out (zoom = 0.5) -> half width & height
        val zoomOut = PdfMath.bitmapSize(600, 800, 1000, 0.5f)
        assertEquals(500, zoomOut.first)
        assertEquals(667, zoomOut.second)

        // Zoom In (zoom = 2.0) -> double width & height
        val zoomIn = PdfMath.bitmapSize(600, 800, 1000, 2.0f)
        assertEquals(2000, zoomIn.first)
        assertEquals(2667, zoomIn.second)

        // Max pixels cap scaling
        val huge = PdfMath.bitmapSize(1000, 1000, 5000, 4.0f, maxPixels = 1_000_000L)
        assertTrue(huge.first * huge.second <= 1_000_000L)
    }

    @Test fun parsePageInputConvertsOneBasedIndex() {
        assertEquals(0, PdfMath.parsePageInput("1", 5))
        assertEquals(2, PdfMath.parsePageInput(" 3 ", 5))
        assertEquals(4, PdfMath.parsePageInput("5", 5))

        assertNull(PdfMath.parsePageInput("0", 5))
        assertNull(PdfMath.parsePageInput("6", 5))
        assertNull(PdfMath.parsePageInput("invalid", 5))
    }
}
