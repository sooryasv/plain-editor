package app.plaintext.editor.core

/** File-name helpers. Pure functions so they can be unit-tested on the JVM. */
object FileNaming {
    const val DEFAULT_NAME = "Untitled.txt"
    const val DEFAULT_EXTENSION = "txt"
    val SUGGESTED_EXTENSIONS = listOf("txt", "md", "yml", "yaml", "py", "html", "htm", "json")

    /** Removes path separators and control characters; never returns an empty string. */
    fun sanitize(raw: String): String {
        val cleaned = buildString {
            for (ch in raw.trim()) {
                if (ch == '/' || ch == '\\' || ch.code < 0x20 || ch.code == 0x7F) append('_') else append(ch)
            }
        }.trim()
        val limited = if (cleaned.length > 120) cleaned.take(120) else cleaned
        return limited.ifEmpty { "Untitled" }
    }

    /** Lower-case extension without dot, or null when the name has none (a leading dot is not an extension). */
    fun extensionOf(name: String): String? {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return null
        return name.substring(dot + 1).lowercase()
    }

    /**
     * Keeps any extension the user typed, appends [defaultExt] only when there is none,
     * and collapses an accidentally repeated trailing extension ("a.md.md" -> "a.md").
     */
    fun ensureExtension(raw: String, defaultExt: String = DEFAULT_EXTENSION): String {
        var name = sanitize(raw)
        val ext = extensionOf(name)
        if (ext == null) return name.trimEnd('.') + "." + defaultExt
        val suffix = ".$ext"
        while (name.length > suffix.length * 2 && name.lowercase().endsWith(suffix + suffix)) {
            name = name.dropLast(suffix.length)
        }
        return name
    }

    /** Replaces the extension (or appends one). Used by the quick extension buttons in the name dialog. */
    fun withExtension(raw: String, ext: String): String {
        val name = sanitize(raw)
        val old = extensionOf(name)
        val base = if (old == null) name.trimEnd('.') else name.substring(0, name.length - old.length - 1)
        return "$base.$ext"
    }

    fun typeLabel(name: String): String = when (val e = extensionOf(name)) {
        null, "txt" -> "Plain text"
        "md", "markdown" -> "Markdown"
        "yml", "yaml" -> "YAML"
        "py" -> "Python"
        "html", "htm" -> "HTML"
        "json" -> "JSON"
        "pdf" -> "PDF"
        else -> "Text (.$e)"
    }

    fun isPdfName(name: String): Boolean = extensionOf(name) == "pdf"
}
