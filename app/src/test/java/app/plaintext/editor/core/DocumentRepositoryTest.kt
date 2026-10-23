package app.plaintext.editor.core

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class DocumentRepositoryTest {
    private val storage = FakeStorage()
    private val repo = DocumentRepository(storage)

    private fun opened(uri: String) = (repo.open(uri) as OpenResult.Success).doc

    @Test fun saveThenOpenRoundTripsBytesExactly() {
        val u = storage.create("a.yml")
        val text = "a:\n  b:\n    - \"é😀\"  \n\n\tx\n"
        val r = repo.save(u, text, LineEnding.LF, false)
        assertTrue(r is SaveResult.Success)
        assertArrayEquals(text.toByteArray(), storage.files[u])
        assertEquals(text, opened(u).text)
        assertEquals("a.yml", opened(u).name)
    }

    @Test fun savingShorterTextLeavesNoTrailingGarbage() {
        val u = storage.put("a.txt", "a very long original line of text\n".repeat(20).toByteArray())
        repo.save(u, "short", LineEnding.LF, false)
        assertEquals("short", String(storage.files[u]!!))
    }

    @Test fun missingFileOpenFailsGracefully() {
        val r = repo.open("content://gone/1")
        assertTrue(r is OpenResult.Failure)
        assertTrue((r as OpenResult.Failure).message.contains("not found"))
    }

    @Test fun invalidUtf8OpenFails() {
        val u = storage.put("x.txt", byteArrayOf(0x61, 0xC3.toByte(), 0x28))
        assertTrue(repo.open(u) is OpenResult.Failure)
    }

    @Test fun readOnlyIsReported() {
        val u = storage.put("ro.md", "hi".toByteArray()); storage.readOnly.add(u)
        assertTrue(opened(u).readOnly)
        assertTrue(repo.save(u, "changed", LineEnding.LF, false) is SaveResult.Failure)
    }

    @Test fun failedWriteIsReportedAsFailure() {
        val u = storage.create("a.txt")
        storage.failOpenOutput = IOException("provider offline")
        val r = repo.save(u, "x", LineEnding.LF, false)
        assertTrue(r is SaveResult.Failure); assertTrue((r as SaveResult.Failure).message.contains("provider offline"))
    }

    @Test fun midWriteFailureIsReportedAsFailure() {
        val u = storage.create("a.txt"); storage.failMidWrite = true
        assertTrue(repo.save(u, "0123456789", LineEnding.LF, false) is SaveResult.Failure)
    }

    @Test fun silentlyTruncatedWriteIsCaughtByVerification() {
        val u = storage.create("a.txt"); storage.silentlyTruncateTo = 3
        val r = repo.save(u, "0123456789", LineEnding.LF, false)
        assertTrue("verification must catch a short write", r is SaveResult.Failure)
    }

    @Test fun unencodableTextFailsWithoutTouchingTheFile() {
        val u = storage.put("a.txt", "original".toByteArray())
        assertTrue(repo.save(u, "bad\uD83D", LineEnding.LF, false) is SaveResult.Failure)
        assertEquals("original", String(storage.files[u]!!))
    }

    @Test fun limitIsEnforcedOnOpen() {
        val ok = storage.put("ok.txt", ByteArray(Limits.MAX_OPEN_BYTES.toInt()) { 'a'.code.toByte() })
        assertTrue(repo.open(ok) is OpenResult.Success)
        val big = storage.put("big.txt", ByteArray(Limits.MAX_OPEN_BYTES.toInt() + 1) { 'a'.code.toByte() })
        assertTrue(repo.open(big) is OpenResult.Failure)
    }

    @Test fun limitIsEnforcedEvenWhenProviderReportsNoSize() {
        val s = object : DocumentStorage by storage {
            override fun info(uri: String) = DocumentInfo(null, null, true)
        }
        val u = storage.put("big.txt", ByteArray(Limits.MAX_OPEN_BYTES.toInt() + 5) { 'a'.code.toByte() })
        assertTrue(DocumentRepository(s).open(u) is OpenResult.Failure)
    }

    @Test fun largeDocumentRoundTripWithTiming() {
        // ~1 MiB of mixed ASCII / accented / emoji / CJK text with 100k+ lines of varying indentation.
        val sb = StringBuilder()
        var i = 0
        while (sb.length < 760_000) {
            sb.append("    ".repeat(i % 5)).append("line $i: ünïcødé 日本語 😀 key: value \t \n")
            if (i % 50 == 0) sb.append("\n\n")
            i++
        }
        val text = sb.toString()
        val bytes = text.toByteArray()
        assertTrue("fixture should be near the limit: ${bytes.size}", bytes.size in 900_000..Limits.MAX_OPEN_BYTES.toInt())
        val u = storage.create("large.md")
        val t0 = System.nanoTime()
        assertTrue(repo.save(u, text, LineEnding.LF, false) is SaveResult.Success)
        val doc = opened(u)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(text, doc.text)
        println("LARGE-DOC: ${bytes.size} bytes, ${text.count { it == '\n' }} lines, save+verify+reopen took $ms ms (JVM, in-memory storage)")
    }

    @Test fun fallbackNameFromUri() {
        assertEquals("x.md", DocumentRepository.fallbackName("content://com.android.externalstorage.documents/document/primary%3ADownload%2Fx.md"))
    }

    @Test fun outOfMemoryIsReportedNotThrown() {
        val oom = object : DocumentStorage by storage {
            override fun openInput(uri: String): java.io.InputStream = throw OutOfMemoryError("simulated")
        }
        val u = storage.put("a.txt", "x".toByteArray())
        val r = DocumentRepository(oom)
        assertTrue((r.open(u) as OpenResult.Failure).message.contains("Not enough memory"))
        assertTrue((DocumentRepository(object : DocumentStorage by storage {
            override fun openOutput(uri: String): java.io.OutputStream = throw OutOfMemoryError("simulated")
        }).save(u, "y", LineEnding.LF, false) as SaveResult.Failure).message.contains("Not enough memory"))
    }

    @Test fun pdfsAreNeverDecodedAsText() {
        val pdf = "%PDF-1.7\n%\u00e2\u00e3\n1 0 obj".toByteArray(Charsets.ISO_8859_1)
        val u = storage.put("scan.txt", pdf)
        assertTrue(repo.open(u) is OpenResult.Pdf)
        val named = storage.put("not-really.pdf", "plain text".toByteArray())
        assertTrue((repo.open(named) as OpenResult.Failure).message.contains("not a valid PDF"))
        val mimeOnly = storage.put("x", "plain text".toByteArray(), mime = "application/pdf")
        assertTrue(repo.open(mimeOnly) is OpenResult.Failure)
        val huge = storage.put("huge.pdf", pdf + ByteArray(30 * 1024 * 1024))
        assertTrue("size limit applies to text only", repo.open(huge) is OpenResult.Pdf)
    }

    @Test fun savingTenMebibytesStreamsAndVerifies() {
        val text = Env.doc(10_000_000)
        val u = storage.create("big.txt")
        val r = repo.save(u, TextBuffer.fromString(text), LineEnding.LF, false)
        assertTrue(r is SaveResult.Success); assertArrayEquals(text.toByteArray(), storage.files[u])
        storage.silentlyTruncateTo = 9_000_000
        assertTrue(repo.save(u, TextBuffer.fromString(text), LineEnding.LF, false) is SaveResult.Failure)
    }

    @Test fun writeFailurePreservesOriginalFileAndReportsFailure() {
        val u = storage.put("preserve.txt", "original content".toByteArray())
        storage.failOpenOutput = IOException("drive disconnected")
        val r = repo.save(u, "new content", LineEnding.LF, false)
        assertTrue(r is SaveResult.Failure)
        val msg = (r as SaveResult.Failure).message
        assertTrue(msg.contains("preserved untouched") || msg.contains("drive disconnected"))
        assertEquals("original content", String(storage.files[u]!!))
    }

    @Test fun midWriteFailureRestoresOriginalFileFromBackup() {
        val u = storage.put("restore.txt", "original bytes".toByteArray())
        var openCount = 0
        val tempStorage = object : DocumentStorage by storage {
            override fun openOutput(uri: String): java.io.OutputStream {
                openCount++
                if (openCount == 1) {
                    // First call (target write): fail mid-write
                    return object : java.io.ByteArrayOutputStream() {
                        override fun write(b: ByteArray, off: Int, len: Int) {
                            super.write(b, off, len / 2)
                            storage.files[uri] = toByteArray()
                            throw IOException("disk full")
                        }
                    }
                }
                // Second call (restoration): allow clean restore
                return storage.openOutput(uri)
            }
        }
        val r = DocumentRepository(tempStorage).save(u, "new replaced text", LineEnding.LF, false)
        assertTrue(r is SaveResult.Failure)
        val msg = (r as SaveResult.Failure).message
        assertTrue("message must report restoration: $msg", msg.contains("restored from a temporary backup"))
        assertEquals("original bytes", String(storage.files[u]!!))
    }

    @Test fun verificationFailureRestoresOriginalFile() {
        val u = storage.put("verif.txt", "original before verify fail".toByteArray())
        var openCount = 0
        val tempStorage = object : DocumentStorage by storage {
            override fun openOutput(uri: String): java.io.OutputStream {
                openCount++
                if (openCount == 1) {
                    // First call (target write): silently truncate write
                    return object : java.io.ByteArrayOutputStream() {
                        override fun close() {
                            super.close()
                            val all = toByteArray()
                            storage.files[uri] = all.copyOf(minOf(2, all.size))
                        }
                    }
                }
                // Second call (restoration): allow clean restore
                return storage.openOutput(uri)
            }
        }
        val r = DocumentRepository(tempStorage).save(u, "new text that gets truncated", LineEnding.LF, false)
        assertTrue(r is SaveResult.Failure)
        val msg = (r as SaveResult.Failure).message
        assertTrue(msg.contains("restored from a temporary backup"))
        assertEquals("original before verify fail", String(storage.files[u]!!))
    }

    @Test fun recoveryWhenRestorationItselfFailsReportsFailureAndNeverSuccess() {
        val u = storage.put("fail_restore.txt", "initial text".toByteArray())
        var openCount = 0
        val flawStorage = object : DocumentStorage by storage {
            override fun openOutput(uri: String): java.io.OutputStream {
                openCount++
                if (openCount == 1) {
                    // Staging/write phase: write partial bytes then throw
                    return object : java.io.ByteArrayOutputStream() {
                        override fun write(b: ByteArray, off: Int, len: Int) {
                            super.write(b, off, minOf(len, 2))
                            storage.files[uri] = toByteArray()
                            throw IOException("simulated write crash")
                        }
                    }
                } else {
                    // Restoration phase: also fails
                    throw IOException("restoration failed")
                }
            }
        }
        val repository = DocumentRepository(flawStorage)
        val r = repository.save(u, "new value", LineEnding.LF, false)
        assertTrue(r is SaveResult.Failure)
        val msg = (r as SaveResult.Failure).message
        assertTrue("message must report failed restoration: $msg", msg.contains("could not be restored automatically"))
        assertTrue("message must mention recovery copy: $msg", msg.contains("recovery copy was saved in temporary storage"))
    }

    @Test fun backupCreationFailureDoesNotClaimBackupExisted() {
        val u = storage.create("unreadable_orig.txt")
        val flawStorage = object : DocumentStorage by storage {
            override fun openInput(uri: String): java.io.InputStream {
                throw IOException("original unreadable for backup")
            }
        }
        val r = DocumentRepository(flawStorage).save(u, "some text", LineEnding.LF, false)
        assertTrue(r is SaveResult.Failure)
        val msg = (r as SaveResult.Failure).message
        assertFalse("must not claim restored: $msg", msg.contains("restored from a temporary backup"))
        assertFalse("must not claim recovery copy saved: $msg", msg.contains("recovery copy was saved"))
    }

    @Test fun unicodeAndLineEndingsRoundTripAndPreservation() {
        val unicodeText = "Line 1: 😀 🎉 Emoji\r\nLine 2: 日本語 한국어 中文\r\nLine 3: ê ë ì ò \uD83D\uDE00\r\n"
        val u = storage.create("unicode.txt")
        val r = repo.save(u, unicodeText, LineEnding.CRLF, true)
        assertTrue(r is SaveResult.Success)

        val doc = opened(u)
        assertEquals(unicodeText.replace("\r\n", "\n"), doc.text)
        assertEquals(LineEnding.CRLF, doc.lineEnding)
        assertTrue(doc.hasBom)
    }
}
