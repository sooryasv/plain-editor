package app.plaintext.editor.core

import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.PushbackInputStream
import java.net.URLDecoder
import java.security.MessageDigest

class LoadedDocument(
    val uri: String,
    val name: String,
    val buffer: TextBuffer,
    val lineEnding: LineEnding,
    val hasBom: Boolean,
    val lineEndingsNormalized: Boolean,
    val readOnly: Boolean,
    val sizeBytes: Int,
) {
    /** Whole text as a String (convenient for tests and small documents; production code uses [buffer]). */
    val text: String get() = buffer.toString()
}

sealed interface OpenResult {
    class Success(val doc: LoadedDocument) : OpenResult
    /** The file is a PDF: it must be shown in the viewer, never decoded as text. */
    class Pdf(val uri: String, val name: String, val sizeBytes: Long?) : OpenResult
    class Failure(val message: String) : OpenResult
}

sealed interface SaveResult {
    class Success(val bytesWritten: Long, val exceedsOpenLimit: Boolean, val displayName: String?) : SaveResult
    class Failure(val message: String) : SaveResult
}

/** Reads/writes documents through [DocumentStorage]. Never throws for ordinary I/O problems; returns Failure. */
class DocumentRepository(
    private val storage: DocumentStorage,
    private val maxOpenBytes: Long = Limits.MAX_OPEN_BYTES,
    private val tempDir: java.io.File? = null,
) {
    /** Call from a background thread: reads and decodes the whole file. */
    fun open(uri: String): OpenResult {
        return try {
            val info = try { storage.info(uri) } catch (e: Exception) { DocumentInfo(null, null, true) }
            val name = info.displayName?.takeIf { it.isNotBlank() } ?: fallbackName(uri)
            val input = PushbackInputStream(storage.openInput(uri), PdfSniffer.HEAD_BYTES)
            input.use {
                val head = ByteArray(PdfSniffer.HEAD_BYTES)
                var n = 0
                while (n < head.size) {
                    val r = it.read(head, n, head.size - n)
                    if (r < 0) break
                    n += r
                }
                val isPdf = PdfSniffer.isPdf(head, n)
                if (isPdf) return OpenResult.Pdf(uri, name, info.sizeBytes)
                if (name.endsWith(".pdf", ignoreCase = true) || info.mimeType == "application/pdf") {
                    return OpenResult.Failure("“$name” is not a valid PDF file (it has no PDF header), so it was not opened.")
                }
                if (info.sizeBytes != null && info.sizeBytes > maxOpenBytes) return tooLarge()
                if (n > 0) it.unread(head, 0, n)
                val raw = readAtMostRaw(it, maxOpenBytes, info.sizeBytes) ?: return tooLarge()
                when (val d = TextCodec.decodeToBuffer(raw.first, raw.second)) {
                    is TextCodec.BufferResult.Rejected -> OpenResult.Failure(d.message)
                    is TextCodec.BufferResult.Ok -> OpenResult.Success(
                        LoadedDocument(
                            uri = uri, name = name, buffer = d.doc.buffer, lineEnding = d.doc.lineEnding,
                            hasBom = d.doc.hasBom, lineEndingsNormalized = d.doc.lineEndingsNormalized,
                            readOnly = !info.canWrite, sizeBytes = raw.second,
                        )
                    )
                }
            }
        } catch (e: Throwable) {   // includes OutOfMemoryError: report it instead of crashing
            if (e is VirtualMachineError && e !is OutOfMemoryError) throw e
            OpenResult.Failure(describe(e, "open"))
        }
    }

    /**
     * Stages [source] to a temp file, verifies staging SHA-256 digest, backs up target destination,
     * streams into [storage], and verifies readback.
     * Restores original file if target write or verification fails. Retains drafts on failure.
     * Never reports success on failed or incomplete save.
     */
    fun save(uri: String, source: CharSequence, lineEnding: LineEnding, hasBom: Boolean): SaveResult {
        val bad = TextCodec.findUnpairedSurrogate(source)
        if (bad >= 0) {
            return SaveResult.Failure("The text contains an invalid character (unpaired surrogate) at position $bad and cannot be written as UTF-8.")
        }
        var tempFile: java.io.File? = null
        var backupFile: java.io.File? = null
        var keepBackup = false
        return try {
            // Stage write to a temporary file first and verify its digest before touching the target file.
            tempFile = java.io.File.createTempFile("plain_save_", ".tmp", tempDir)
            val md = MessageDigest.getInstance("SHA-256")
            var written = 0L
            tempFile.outputStream().use { out ->
                val buffered = java.io.BufferedOutputStream(out, 64 * 1024)
                written = TextCodec.encodeTo(source, lineEnding, hasBom, buffered, md)
                buffered.flush()
            }
            val expectedDigest = md.digest()

            // Verify the temp file read-back matches
            val tempCheck = MessageDigest.getInstance("SHA-256")
            var tempRead = 0L
            tempFile.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    tempCheck.update(buf, 0, n)
                    tempRead += n
                    if (tempRead > written) break
                }
            }
            if (tempRead != written || !MessageDigest.isEqual(expectedDigest, tempCheck.digest())) {
                return SaveResult.Failure(
                    "Failed to stage file for saving (staging readback mismatch). Your text remains safe in the editor and as a draft."
                )
            }

            // Try backing up the existing original file before opening target output stream.
            var hasBackup = false
            try {
                storage.openInput(uri).use { origIn ->
                    val bak = java.io.File.createTempFile("plain_bak_", ".tmp", tempDir)
                    bak.outputStream().use { bakOut ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = origIn.read(buf)
                            if (n < 0) break
                            bakOut.write(buf, 0, n)
                        }
                        bakOut.flush()
                    }
                    backupFile = bak
                    hasBackup = true
                }
            } catch (e: Exception) {
                backupFile?.delete()
                backupFile = null
                hasBackup = false
            }

            // Stream the verified tempFile into target storage destination.
            var lastError: Throwable? = null
            var outputOpened = false
            var targetWriteSuccess = false
            try {
                storage.openOutput(uri).use { out ->
                    outputOpened = true
                    tempFile.inputStream().use { inStream ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = inStream.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                        }
                    }
                    out.flush()
                }

                // Verify destination file read-back
                val check = MessageDigest.getInstance("SHA-256")
                var readBack = 0L
                storage.openInput(uri).use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        check.update(buf, 0, n)
                        readBack += n
                        if (readBack > written) break
                    }
                }

                if (readBack == written && MessageDigest.isEqual(expectedDigest, check.digest())) {
                    targetWriteSuccess = true
                } else {
                    lastError = IOException("The file was written, but reading it back did not match what was saved.")
                }
            } catch (e: Throwable) {
                if (e is VirtualMachineError && e !is OutOfMemoryError) throw e
                lastError = e
            }

            if (!targetWriteSuccess) {
                // Attempt to restore original file from backup if available
                var restored = false
                if (outputOpened && hasBackup && backupFile != null && backupFile!!.exists()) {
                    try {
                        storage.openOutput(uri).use { out ->
                            backupFile!!.inputStream().use { bakIn ->
                                val buf = ByteArray(64 * 1024)
                                while (true) {
                                    val n = bakIn.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                }
                            }
                            out.flush()
                        }
                        restored = true
                    } catch (restoreEx: Exception) {
                        restored = false
                    }
                }

                val statusDetail = when {
                    !outputOpened && hasBackup -> " The original file on disk was preserved untouched."
                    !outputOpened -> " The destination file was not modified."
                    restored -> " The original file was restored from a temporary backup."
                    hasBackup -> " The original file could not be restored automatically, but a recovery copy was saved in temporary storage."
                    else -> ""
                }

                if (hasBackup && !restored) {
                    keepBackup = true
                }

                val causeMsg = lastError?.let { describe(it, "save") } ?: "The file write or verification failed."
                return SaveResult.Failure("$causeMsg$statusDetail Your text remains safe in the editor and as a draft.")
            }

            val name = try { storage.info(uri).displayName } catch (e: Exception) { null }
            SaveResult.Success(written, written > maxOpenBytes, name)
        } catch (e: Throwable) {
            if (e is VirtualMachineError && e !is OutOfMemoryError) throw e
            SaveResult.Failure(describe(e, "save"))
        } finally {
            tempFile?.delete()
            if (!keepBackup) {
                backupFile?.delete()
            }
        }
    }

    private fun tooLarge() = OpenResult.Failure(
        "This file is larger than the ${maxOpenBytes / (1024 * 1024)} MiB limit this editor supports, so it was not opened."
    )

    private fun describe(e: Throwable, verb: String): String = when (e) {
        is FileNotFoundException ->
            "The file was not found or is no longer accessible (it may have been moved, renamed or deleted, or the app lost access)."
        is SecurityException ->
            "Android denied access to this file. It may be read-only or the permission was revoked."
        is UnsupportedOperationException ->
            "The storage provider does not support this operation ($verb)."
        is OutOfMemoryError -> "Not enough memory to $verb this file. Close other tabs and try again."
        is IOException -> "Storage error while trying to $verb: ${e.message ?: e.javaClass.simpleName}"
        else -> "Unexpected problem while trying to $verb: ${e.message ?: e.javaClass.simpleName}"
    }

    companion object {
        /** Reads up to [max] bytes; null if more is available. Returns (array, usedLength) without a final copy. */
        fun readAtMostRaw(input: InputStream, max: Long, sizeHint: Long?): Pair<ByteArray, Int>? {
            var cap = if (sizeHint != null && sizeHint in 1..max) (sizeHint + 1).toInt() else 64 * 1024
            var buf = ByteArray(cap)
            var n = 0
            while (true) {
                if (n == cap) {
                    if (n.toLong() >= max + 1) return null
                    cap = minOf(cap.toLong() * 2, max + 1).toInt()
                    buf = buf.copyOf(cap)
                }
                val r = input.read(buf, n, cap - n)
                if (r < 0) break
                n += r
                if (n > max) return null
            }
            return buf to n
        }

        fun readAtMost(input: InputStream, max: Long): ByteArray? =
            readAtMostRaw(input, max, null)?.let { it.first.copyOf(it.second) }

        fun fallbackName(uri: String): String {
            val decoded = try { URLDecoder.decode(uri, "UTF-8") } catch (e: Exception) { uri }
            val last = decoded.substringAfterLast('/').substringAfterLast(':')
            return last.ifBlank { FileNaming.DEFAULT_NAME }
        }
    }
}
