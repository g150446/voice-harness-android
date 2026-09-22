package com.g150446.voiceharness.epub

import android.content.Context
import android.net.Uri
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal data class EpubBookInfo(
    val id: String,
    val title: String,
    val author: String,
    val addedAt: Long,
)

internal data class EpubPosition(val chapter: Int, val start: Int)

/** What the reader needs from storage; the real one is [EpubLibrary], tests use a fake. */
internal interface EpubStore {
    fun openBook(id: String): EpubBook
    fun lastBookId(): String?
    fun setLastBookId(id: String?)
    fun position(id: String): EpubPosition?
    fun savePosition(id: String, position: EpubPosition)
}

/** EPUB files copied into app storage, with a small index and the reading position of each book. */
internal class EpubLibrary(context: Context) : EpubStore {
    private val appContext = context.applicationContext
    private val directory = File(appContext.filesDir, "epub")
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun list(): List<EpubBookInfo> {
        val raw = prefs.getString(KEY_BOOKS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                EpubBookInfo(
                    id = item.getString("id"),
                    title = item.optString("title"),
                    author = item.optString("author"),
                    addedAt = item.optLong("addedAt"),
                )
            }.filter { fileFor(it.id).isFile }
        }.getOrDefault(emptyList())
    }

    /** Copies the picked file into the library and validates it; a bad file leaves nothing behind. */
    suspend fun import(uri: Uri): Result<EpubBookInfo> = withContext(Dispatchers.IO) {
        runCatching {
            directory.mkdirs()
            val temp = File(directory, "import-${System.nanoTime()}.tmp")
            val digest = MessageDigest.getInstance("SHA-256")
            try {
                val input = appContext.contentResolver.openInputStream(uri)
                    ?: throw EpubException("ファイルを開けません")
                input.use { source ->
                    temp.outputStream().use { sink ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        while (true) {
                            val read = source.read(buffer)
                            if (read < 0) break
                            digest.update(buffer, 0, read)
                            sink.write(buffer, 0, read)
                        }
                    }
                }
                val id = digest.digest().joinToString("") { "%02x".format(it) }.take(ID_CHARS)
                val target = fileFor(id)
                val existed = target.isFile
                if (!existed) check(temp.renameTo(target)) { "ファイルを保存できません" }
                val book = try {
                    EpubParser.parse(target, id)
                } catch (error: Exception) {
                    if (!existed) target.delete()
                    throw error
                }
                val info = EpubBookInfo(
                    id = id,
                    title = book.title.ifBlank { "無題の本" },
                    author = book.author,
                    addedAt = System.currentTimeMillis(),
                )
                save(list().filterNot { it.id == id } + info)
                info
            } finally {
                temp.delete()
            }
        }
    }

    fun remove(id: String) {
        fileFor(id).delete()
        save(list().filterNot { it.id == id })
        prefs.edit().remove(positionKey(id)).apply()
        if (lastBookId() == id) setLastBookId(null)
    }

    override fun openBook(id: String): EpubBook {
        val file = fileFor(id)
        if (!file.isFile) throw EpubException("本が見つかりません")
        return EpubParser.parse(file, id)
    }

    override fun lastBookId(): String? =
        prefs.getString(KEY_LAST, null)?.takeIf { fileFor(it).isFile }

    override fun setLastBookId(id: String?) {
        prefs.edit().apply { if (id == null) remove(KEY_LAST) else putString(KEY_LAST, id) }.apply()
    }

    override fun position(id: String): EpubPosition? {
        val raw = prefs.getString(positionKey(id), null) ?: return null
        val parts = raw.split(':')
        val chapter = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val start = parts.getOrNull(1)?.toIntOrNull() ?: return null
        return EpubPosition(chapter, start)
    }

    override fun savePosition(id: String, position: EpubPosition) {
        prefs.edit().putString(positionKey(id), "${position.chapter}:${position.start}").apply()
    }

    private fun save(books: List<EpubBookInfo>) {
        val array = JSONArray()
        books.forEach {
            array.put(
                JSONObject()
                    .put("id", it.id).put("title", it.title)
                    .put("author", it.author).put("addedAt", it.addedAt),
            )
        }
        prefs.edit().putString(KEY_BOOKS, array.toString()).apply()
    }

    private fun fileFor(id: String) = File(directory, "$id.epub")

    private fun positionKey(id: String) = "position_$id"

    private companion object {
        const val PREFS = "epub_library"
        const val KEY_BOOKS = "books"
        const val KEY_LAST = "last_book"
        const val BUFFER_BYTES = 64 * 1024
        const val ID_CHARS = 16
    }
}
