package app.plaintext.editor.core

import java.net.URLDecoder
import java.net.URLEncoder

data class RecentEntry(val uri: String, val name: String, val openedAt: Long)

/** Persistence-agnostic recent list (most recent first, capped). Serialised to a single string. */
class RecentFiles(initial: List<RecentEntry> = emptyList(), private val max: Int = 10) {
    private var items: List<RecentEntry> = initial.take(max)

    fun list(): List<RecentEntry> = items

    /** @return entries that were pushed out of the list (so callers can release their URI permission). */
    fun add(entry: RecentEntry): List<RecentEntry> {
        val rest = items.filter { it.uri != entry.uri }
        val all = listOf(entry) + rest
        items = all.take(max)
        return all.drop(max)
    }

    fun remove(uri: String) {
        items = items.filter { it.uri != uri }
    }

    fun clear(): List<RecentEntry> {
        val old = items
        items = emptyList()
        return old
    }

    fun serialize(): String = items.joinToString("\n") {
        enc(it.uri) + "\t" + enc(it.name) + "\t" + it.openedAt
    }

    companion object {
        fun parse(s: String?, max: Int = 10): RecentFiles {
            if (s.isNullOrBlank()) return RecentFiles(emptyList(), max)
            val list = s.lines().mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size != 3) return@mapNotNull null
                try {
                    RecentEntry(dec(parts[0]), dec(parts[1]), parts[2].toLong())
                } catch (e: Exception) {
                    null
                }
            }
            return RecentFiles(list, max)
        }

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        private fun dec(s: String) = URLDecoder.decode(s, "UTF-8")
    }
}
