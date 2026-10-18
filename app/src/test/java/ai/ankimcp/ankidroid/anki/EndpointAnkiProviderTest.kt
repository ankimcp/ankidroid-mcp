package ai.ankimcp.ankidroid.anki

import ai.ankimcp.ankidroid.log.TunnelLog
import ai.ankimcp.ankidroid.tunnel.ElapsedClock
import android.content.ContentResolver
import android.database.CharArrayBuffer
import android.database.ContentObserver
import android.database.Cursor
import android.database.DataSetObserver
import android.net.Uri
import android.os.Bundle
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [EndpointAnkiProvider] through fake endpoints and a fake [Cursor]: everything that interprets a
 * result. The real [ContentResolver]/`ContentProviderClient` cannot be faked on the JVM (final
 * methods, hidden constructor; no Robolectric, D5), so the two real endpoints are only type
 * translation and are covered on the device.
 */
class EndpointAnkiProviderTest {
    /** A cursor over [rows]; [deliver] rows are returned before iteration stops, like a failed window fetch. */
    private class FakeCursor(
        private val columns: Array<String>,
        private val rows: List<List<Any?>>,
        private val deliver: Int = rows.size,
    ) : Cursor {
        private var position = -1
        var closed = false

        private fun value(i: Int) = rows[position][i]

        override fun getCount() = rows.size
        override fun getPosition() = position
        override fun move(offset: Int) = moveToPosition(position + offset)
        override fun moveToPosition(p: Int): Boolean {
            position = p
            return p in 0 until deliver
        }
        override fun moveToFirst() = moveToPosition(0)
        override fun moveToLast() = moveToPosition(rows.size - 1)
        override fun moveToNext() = moveToPosition(position + 1)
        override fun moveToPrevious() = moveToPosition(position - 1)
        override fun isFirst() = position == 0
        override fun isLast() = position == rows.size - 1
        override fun isBeforeFirst() = position < 0
        override fun isAfterLast() = position >= rows.size
        override fun getColumnIndex(columnName: String) = columns.indexOf(columnName)
        override fun getColumnIndexOrThrow(columnName: String) = columns.indexOf(columnName).also { require(it >= 0) }
        override fun getColumnName(columnIndex: Int) = columns[columnIndex]
        override fun getColumnNames() = columns
        override fun getColumnCount() = columns.size
        override fun getBlob(columnIndex: Int) = value(columnIndex) as ByteArray
        override fun getString(columnIndex: Int) = value(columnIndex)?.toString()
        override fun copyStringToBuffer(columnIndex: Int, buffer: CharArrayBuffer?) = Unit
        override fun getShort(columnIndex: Int) = (value(columnIndex) as Number).toShort()
        override fun getInt(columnIndex: Int) = (value(columnIndex) as Number).toInt()
        override fun getLong(columnIndex: Int) = (value(columnIndex) as Number).toLong()
        override fun getFloat(columnIndex: Int) = (value(columnIndex) as Number).toFloat()
        override fun getDouble(columnIndex: Int) = (value(columnIndex) as Number).toDouble()
        override fun getType(columnIndex: Int) = when (value(columnIndex)) {
            null -> Cursor.FIELD_TYPE_NULL
            is Long, is Int -> Cursor.FIELD_TYPE_INTEGER
            is Double, is Float -> Cursor.FIELD_TYPE_FLOAT
            is ByteArray -> Cursor.FIELD_TYPE_BLOB
            else -> Cursor.FIELD_TYPE_STRING
        }
        override fun isNull(columnIndex: Int) = value(columnIndex) == null

        @Deprecated("Deprecated in Java")
        override fun deactivate() = Unit

        @Deprecated("Deprecated in Java")
        override fun requery() = false
        override fun close() {
            closed = true
        }
        override fun isClosed() = closed
        override fun registerContentObserver(observer: ContentObserver?) = Unit
        override fun unregisterContentObserver(observer: ContentObserver?) = Unit
        override fun registerDataSetObserver(observer: DataSetObserver?) = Unit
        override fun unregisterDataSetObserver(observer: DataSetObserver?) = Unit
        override fun setNotificationUri(cr: ContentResolver?, uri: Uri?) = Unit
        override fun getNotificationUri(): Uri? = null
        override fun getWantsAllOnMoveCalls() = false
        override fun setExtras(extras: Bundle?) = Unit
        override fun getExtras(): Bundle? = null
        override fun respond(extras: Bundle?): Bundle? = null
    }

    /** Answers writes with fixed results; [throwOnWrite] replaces them. */
    private class FakeEndpoint(
        var cursor: Cursor? = null,
        var count: Int = 1,
        var uri: String? = "content://x/notes/1",
        var throwOnWrite: Exception? = null,
    ) : ProviderEndpoint {
        override fun query(path: String, projection: Array<String>?, selection: String?, args: Array<String>?, sortOrder: String?) = cursor
        override fun insert(path: String, values: Map<String, Any?>) = throwOnWrite?.let { throw it } ?: uri
        override fun update(path: String, values: Map<String, Any?>, selection: String?, args: Array<String>?) = throwOnWrite?.let { throw it } ?: count
        override fun delete(path: String, selection: String?, args: Array<String>?) = throwOnWrite?.let { throw it } ?: count
        override fun bulkInsert(path: String, rows: List<Map<String, Any?>>) = throwOnWrite?.let { throw it } ?: count
    }

    private val resolver = FakeEndpoint()
    private val client = FakeEndpoint()
    private var clientAvailable = false

    private val provider = EndpointAnkiProvider(
        acquire = { if (clientAvailable) AutoCloseable {} else null },
        endpointOf = { client },
        resolver = resolver,
        log = TunnelLog(mirrorToLogcat = false),
        clock = ElapsedClock { 0L },
    )

    @Test
    fun `rows keep the cursor's types and the cursor is closed`() {
        val blob = byteArrayOf(1, 2)
        val cursor = FakeCursor(arrayOf("_id", "name", "f", "b", "n"), listOf(listOf(7L, "Basic", 1.5, blob, null)))
        resolver.cursor = cursor

        val call = provider.query("models", null)

        val row = call.value!!.single()
        assertEquals(7L, row["_id"])
        assertEquals("Basic", row["name"])
        assertEquals(1.5, row["f"])
        assertArrayEquals(blob, row["b"] as ByteArray)
        assertTrue(row.containsKey("n"))
        assertNull(row["n"])
        assertEquals(listOf("_id", "name", "f", "b", "n"), row.keys.toList())
        assertTrue(cursor.closed)
        assertEquals(false, call.viaClient)
    }

    @Test
    fun `a null cursor is a null value`() {
        assertNull(provider.query("notes", listOf("_id")).value)
    }

    @Test
    fun `a cursor that stops before its count is a dead provider, not a short list`() {
        val cursor = FakeCursor(arrayOf("_id"), listOf(listOf(1L), listOf(2L), listOf(3L)), deliver = 2)
        resolver.cursor = cursor
        try {
            provider.query("cards", listOf("_id"))
            fail("expected ProviderDiedException")
        } catch (e: ProviderDiedException) {
            assertTrue(e.message, e.message!!.contains("2 of 3"))
        }
        assertTrue(cursor.closed)
    }

    @Test
    fun `a short read through the held client goes into dead-client handling and is re-read`() {
        val cursors = ArrayDeque(
            listOf(
                FakeCursor(arrayOf("_id"), listOf(listOf(1L), listOf(2L)), deliver = 1),
                FakeCursor(arrayOf("_id"), listOf(listOf(1L), listOf(2L))),
            ),
        )
        val acquired = mutableListOf<AutoCloseable>()
        val queued = object : ProviderEndpoint by client {
            override fun query(path: String, projection: Array<String>?, selection: String?, args: Array<String>?, sortOrder: String?) =
                cursors.removeFirst()
        }
        val held = EndpointAnkiProvider(
            acquire = { AutoCloseable {}.also { acquired += it } },
            endpointOf = { queued },
            resolver = resolver,
            log = TunnelLog(mirrorToLogcat = false),
            clock = ElapsedClock { 0L },
        )

        val call = held.query("cards", listOf("_id"))

        assertEquals(listOf(1L, 2L), call.value!!.map { it["_id"] })
        assertTrue(call.clientDied)
        assertTrue(call.viaClient)
        assertEquals("the dead client was swapped for a fresh one", 2, acquired.size)
    }

    @Test
    fun `resolver -1 from update and delete and 0 from a non-empty bulkInsert are unknown outcomes`() {
        resolver.count = -1
        assertNull(provider.update("notes/1", mapOf("tags" to "a")).value)
        assertNull(provider.delete("notes/1").value)
        resolver.count = 0
        assertNull(provider.bulkInsert("notes?deckId=1", listOf(mapOf("mid" to 1L))).value)
        assertEquals(0, provider.bulkInsert("notes?deckId=1", emptyList()).value)
        resolver.uri = null
        assertNull(provider.insert("notes", mapOf("mid" to 1L)).value)
        resolver.count = 2
        assertEquals(2, provider.update("notes/1", mapOf("tags" to "a")).value)
    }

    @Test
    fun `through the held client the counts are taken as they are`() {
        clientAvailable = true
        client.count = 0
        val call = provider.bulkInsert("notes?deckId=1", listOf(mapOf("mid" to 1L)))
        assertEquals(0, call.value)
        assertTrue(call.viaClient)
    }

    @Test
    fun `a resolver write with no provider to send to is unreachable, other argument errors pass through`() {
        resolver.throwOnWrite = IllegalArgumentException("Unknown URI content://com.ichi2.anki.flashcards/notes/1")
        try {
            provider.update("notes/1", mapOf("tags" to "a"))
            fail("expected ProviderUnreachableException")
        } catch (_: ProviderUnreachableException) {
        }
        resolver.throwOnWrite = IllegalArgumentException("Unknown URL content://com.ichi2.anki.flashcards/notes")
        try {
            provider.insert("notes", mapOf("mid" to 1L))
            fail("expected ProviderUnreachableException")
        } catch (_: ProviderUnreachableException) {
        }
        resolver.throwOnWrite = IllegalArgumentException("Incorrect flds argument")
        try {
            provider.update("notes/1", mapOf("flds" to "a"))
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertEquals("Incorrect flds argument", e.message)
        }
    }

    @Test
    fun `a dead client during a write is rethrown, not retried`() {
        clientAvailable = true
        provider.query("decks", null)
        var writes = 0
        client.throwOnWrite = ProviderDiedException(RuntimeException("DeadObjectException"))
        val counting = object : ProviderEndpoint by client {
            override fun update(path: String, values: Map<String, Any?>, selection: String?, args: Array<String>?): Int {
                writes++
                return client.update(path, values, selection, args)
            }
        }
        val single = EndpointAnkiProvider(
            acquire = { AutoCloseable {} },
            endpointOf = { counting },
            resolver = resolver,
            log = TunnelLog(mirrorToLogcat = false),
            clock = ElapsedClock { 0L },
        )
        try {
            single.update("notes/1", mapOf("tags" to "a"))
            fail("expected ProviderDiedException")
        } catch (_: ProviderDiedException) {
        }
        assertEquals(1, writes)
        assertEquals(false, single.isClientHeld())
    }
}
