package ai.ankimcp.ankidroid.anki

import ai.ankimcp.ankidroid.log.TunnelLog
import ai.ankimcp.ankidroid.tunnel.ElapsedClock
import android.content.ContentProviderClient
import android.content.ContentResolver
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.RemoteException
import androidx.core.net.toUri

/**
 * One cursor row copied out of the provider: column name to `null`, [Long], [Double], [String]
 * or [ByteArray], as the cursor typed it. A Boolean that AnkiDroid put into a MatrixCursor
 * crosses the binder as the string "true"/"false" (D11).
 */
typealias ProviderRow = Map<String, Any?>

/** What one provider call returned and how (D51). */
data class ProviderCall<out T>(
    /** Null for a null cursor (or a null URI from an insert, or a resolver write whose outcome is unknown). */
    val value: T?,
    /**
     * Time inside the provider call that produced [value], measured on the provider thread: no
     * queue wait, no retry sleeps, no earlier attempts on a dead client.
     */
    val tookMs: Long,
    /** That call went through the held client rather than the resolver. */
    val viaClient: Boolean,
    /** The held client threw a dead-object error during this call. */
    val clientDied: Boolean,
)

/**
 * The resolver could not find AnkiDroid's provider for a write (`ContentResolver` throws
 * "Unknown URL/URI" then, e.g. while the OEM freezer refuses the lookup), so nothing was sent.
 */
class ProviderUnreachableException(message: String?) : Exception(message)

/**
 * AnkiDroid's ContentProvider as plain Kotlin types. Paths are relative to the provider's
 * authority (`notes`, `models/123/templates`, `notes?deckId=1`). Implementations are blocking
 * and may throw [SecurityException] (permission missing), [IllegalStateException] (AnkiDroid
 * storage not set up) or [IllegalArgumentException] (AnkiDroid rejected the request). Kept as an
 * interface so the repository and tools are unit-testable without Android.
 *
 * Queries may retry on a dead provider client (D51); writes never do (D57). A query whose cursor
 * stopped delivering rows before its count throws [ProviderDiedException] instead of returning
 * a short list. A resolver write whose outcome the resolver swallowed returns a null value; one
 * the resolver could not even route throws [ProviderUnreachableException].
 */
interface AnkiProvider {
    /** Rows of [path]; a null value means a null cursor. */
    fun query(
        path: String,
        projection: List<String>?,
        selection: String? = null,
        selectionArgs: List<String>? = null,
        sortOrder: String? = null,
    ): ProviderCall<List<ProviderRow>>

    /** The inserted row's URI as a string; a null value means AnkiDroid returned no URI. */
    fun insert(path: String, values: Map<String, Any?>): ProviderCall<String> =
        throw UnsupportedOperationException("insert")

    /** Number of rows AnkiDroid reports as updated. */
    fun update(
        path: String,
        values: Map<String, Any?>,
        selection: String? = null,
        selectionArgs: List<String>? = null,
    ): ProviderCall<Int> = throw UnsupportedOperationException("update")

    /** Number of rows AnkiDroid reports as deleted. */
    fun delete(path: String, selection: String? = null, selectionArgs: List<String>? = null): ProviderCall<Int> =
        throw UnsupportedOperationException("delete")

    /** Number of rows AnkiDroid reports as inserted. */
    fun bulkInsert(path: String, rows: List<Map<String, Any?>>): ProviderCall<Int> =
        throw UnsupportedOperationException("bulkInsert")

    /** Drops the provider client held for the process lifetime (D51); the next query acquires a new one. */
    fun releaseClient(reason: String) {}

    /** A provider client is held right now, so the next query goes through it rather than the resolver (D51). */
    fun isClientHeld(): Boolean = false
}

/**
 * One way into AnkiDroid's provider, in plain types: the held client or the resolver. The
 * Android classes behind it ([ContentResolver]'s calls are final, [ContentProviderClient]'s
 * constructor is hidden) cannot be faked on the JVM without Robolectric (D5), so everything that
 * decides what a result means lives in [EndpointAnkiProvider] and is tested through fake
 * endpoints; the two real endpoints only translate types.
 */
internal interface ProviderEndpoint {
    /** Throws [ProviderDiedException] when the client's binder call fails. */
    fun query(path: String, projection: Array<String>?, selection: String?, args: Array<String>?, sortOrder: String?): Cursor?

    fun insert(path: String, values: Map<String, Any?>): String?

    fun update(path: String, values: Map<String, Any?>, selection: String?, args: Array<String>?): Int

    fun delete(path: String, selection: String?, args: Array<String>?): Int

    fun bulkInsert(path: String, rows: List<Map<String, Any?>>): Int
}

/**
 * [AnkiProvider] over [ProviderPin]: through the held client when there is one, else the
 * resolver. Resolver quirks it handles (AOSP `ContentResolver`): a write that hit a
 * `RemoteException` comes back as null (insert), -1 (update, delete) or 0 (bulkInsert of
 * non-empty rows), which becomes a null value ("outcome unknown"); a write for which no provider
 * could be found throws "Unknown URL"/"Unknown URI", which becomes [ProviderUnreachableException].
 */
internal class EndpointAnkiProvider<C : AutoCloseable>(
    acquire: () -> C?,
    private val endpointOf: (C) -> ProviderEndpoint,
    private val resolver: ProviderEndpoint,
    log: TunnelLog,
    clock: ElapsedClock,
) : AnkiProvider {
    private val pin = ProviderPin(acquire, log, clock)

    override fun releaseClient(reason: String) = pin.release(reason)

    override fun isClientHeld(): Boolean = pin.isHeld

    override fun query(
        path: String,
        projection: List<String>?,
        selection: String?,
        selectionArgs: List<String>?,
        sortOrder: String?,
    ): ProviderCall<List<ProviderRow>> = pin.withClient { client ->
        val endpoint = client?.let(endpointOf) ?: resolver
        endpoint.query(path, projection?.toTypedArray(), selection, selectionArgs?.toTypedArray(), sortOrder)?.let(::readRows)
    }

    override fun insert(path: String, values: Map<String, Any?>): ProviderCall<String> = write { endpoint ->
        endpoint.insert(path, values)
    }

    override fun update(
        path: String,
        values: Map<String, Any?>,
        selection: String?,
        selectionArgs: List<String>?,
    ): ProviderCall<Int> = write { endpoint ->
        endpoint.update(path, values, selection, selectionArgs?.toTypedArray())
            .takeUnless { it < 0 && endpoint === resolver }
    }

    override fun delete(path: String, selection: String?, selectionArgs: List<String>?): ProviderCall<Int> = write { endpoint ->
        endpoint.delete(path, selection, selectionArgs?.toTypedArray())
            .takeUnless { it < 0 && endpoint === resolver }
    }

    override fun bulkInsert(path: String, rows: List<Map<String, Any?>>): ProviderCall<Int> = write { endpoint ->
        endpoint.bulkInsert(path, rows)
            .takeUnless { it == 0 && rows.isNotEmpty() && endpoint === resolver }
    }

    private fun <T> write(block: (ProviderEndpoint) -> T?): ProviderCall<T> = pin.withClient(retryDead = false) { client ->
        val endpoint = client?.let(endpointOf) ?: resolver
        try {
            block(endpoint)
        } catch (e: IllegalArgumentException) {
            val message = e.message.orEmpty()
            if (endpoint === resolver && (message.startsWith("Unknown URL") || message.startsWith("Unknown URI"))) {
                throw ProviderUnreachableException(message)
            }
            throw e
        }
    }
}

/**
 * Copies every row out of [cursor] and closes it. A cursor from another process fetches its rows
 * window by window, and a fetch that fails (AnkiDroid died) just ends the iteration, so fewer
 * rows than `getCount()` means the read was cut short: that throws [ProviderDiedException]
 * rather than returning a truncated list.
 */
internal fun readRows(cursor: Cursor): List<ProviderRow> =
    cursor.use { c ->
        val names = c.columnNames
        val expected = c.count
        val rows = buildList {
            while (c.moveToNext()) {
                val row = LinkedHashMap<String, Any?>(names.size)
                names.forEachIndexed { i, name ->
                    row[name] = when (c.getType(i)) {
                        Cursor.FIELD_TYPE_NULL -> null
                        Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                        Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                        Cursor.FIELD_TYPE_BLOB -> c.getBlob(i)
                        else -> c.getString(i)
                    }
                }
                add(row)
            }
        }
        if (expected >= 0 && rows.size != expected) {
            throw ProviderDiedException(IllegalStateException("cursor delivered ${rows.size} of $expected rows"))
        }
        rows
    }

class ContentResolverAnkiProvider(
    resolver: ContentResolver,
    log: TunnelLog,
    clock: ElapsedClock,
) : AnkiProvider by EndpointAnkiProvider<ContentProviderClient>(
    acquire = { resolver.acquireUnstableContentProviderClient(FlashCardsContract.AUTHORITY) },
    endpointOf = ::ClientEndpoint,
    resolver = ResolverEndpoint(resolver),
    log = log,
    clock = clock,
)

private class ClientEndpoint(private val client: ContentProviderClient) : ProviderEndpoint {
    override fun query(path: String, projection: Array<String>?, selection: String?, args: Array<String>?, sortOrder: String?) =
        remote { client.query(uriOf(path), projection, selection, args, sortOrder) }

    override fun insert(path: String, values: Map<String, Any?>) = remote { client.insert(uriOf(path), contentValues(values)) }?.toString()

    override fun update(path: String, values: Map<String, Any?>, selection: String?, args: Array<String>?) =
        remote { client.update(uriOf(path), contentValues(values), selection, args) }

    override fun delete(path: String, selection: String?, args: Array<String>?) = remote { client.delete(uriOf(path), selection, args) }

    override fun bulkInsert(path: String, rows: List<Map<String, Any?>>) =
        remote { client.bulkInsert(uriOf(path), rows.map(::contentValues).toTypedArray()) }

    private inline fun <T> remote(call: () -> T): T = try {
        call()
    } catch (e: RemoteException) {
        throw ProviderDiedException(e)
    }
}

private class ResolverEndpoint(private val resolver: ContentResolver) : ProviderEndpoint {
    override fun query(path: String, projection: Array<String>?, selection: String?, args: Array<String>?, sortOrder: String?) =
        resolver.query(uriOf(path), projection, selection, args, sortOrder)

    override fun insert(path: String, values: Map<String, Any?>) = resolver.insert(uriOf(path), contentValues(values))?.toString()

    override fun update(path: String, values: Map<String, Any?>, selection: String?, args: Array<String>?) =
        resolver.update(uriOf(path), contentValues(values), selection, args)

    override fun delete(path: String, selection: String?, args: Array<String>?) = resolver.delete(uriOf(path), selection, args)

    override fun bulkInsert(path: String, rows: List<Map<String, Any?>>) =
        resolver.bulkInsert(uriOf(path), rows.map(::contentValues).toTypedArray())
}

private fun uriOf(path: String): Uri = "content://${FlashCardsContract.AUTHORITY}/$path".toUri()

private fun contentValues(values: Map<String, Any?>): ContentValues = ContentValues(values.size).apply {
    values.forEach { (key, value) ->
        when (value) {
            null -> putNull(key)
            is String -> put(key, value)
            is Long -> put(key, value)
            is Int -> put(key, value)
            is Double -> put(key, value)
            is Float -> put(key, value)
            is Boolean -> put(key, value)
            is ByteArray -> put(key, value)
            else -> throw IllegalArgumentException("Unsupported value type for $key: ${value.javaClass.simpleName}")
        }
    }
}
