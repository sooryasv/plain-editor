package app.plaintext.editor.core

import java.io.InputStream
import java.io.OutputStream

data class DocumentInfo(val displayName: String?, val sizeBytes: Long?, val canWrite: Boolean, val mimeType: String? = null)

/** Thin seam over Android's ContentResolver so the file logic can be tested on the JVM. URIs are plain strings. */
interface DocumentStorage {
    @Throws(java.io.IOException::class)
    fun openInput(uri: String): InputStream

    /** Must open for writing and truncate existing content. */
    @Throws(java.io.IOException::class)
    fun openOutput(uri: String): OutputStream

    fun info(uri: String): DocumentInfo
}
