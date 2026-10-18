package ai.ankimcp.ankidroid.tools

import ai.ankimcp.ankidroid.anki.AnkiDroidRepository
import ai.ankimcp.ankidroid.anki.AnkiResult
import ai.ankimcp.ankidroid.anki.CallBudget
import kotlinx.coroutines.withContext
import ai.ankimcp.ankidroid.mcp.Tool
import ai.ankimcp.ankidroid.mcp.ToolResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.math.BigDecimal
import java.math.RoundingMode

/** A tool argument of the wrong type, out of range, missing, or not in the schema. */
class InvalidArguments(message: String) : Exception(message)

/** Ends a tool call with [result], like raising the add-on's `HandlerError`. */
class ToolFailure(val result: ToolResult) : Exception(null, null, false, false)

/** Raises the add-on's `HandlerError` wording as a tool error (see [addonError]). */
fun fail(message: String, hint: String? = null, code: String? = null, context: Map<String, Any?> = emptyMap()): Nothing =
    throw ToolFailure(addonError(message, hint, code, context))

/** The success value, or ends the tool call with the failure's message. */
fun <T> AnkiResult<T>.orFail(): T = when (this) {
    is AnkiResult.Success -> value
    is AnkiResult.Failure -> throw ToolFailure(ToolResult.error(message))
}

/**
 * Base for the AnkiDroid tools (D57), in this order: rejects arguments the schema does not list
 * (an argument the add-on has but AnkiDroid cannot honour is absent from the schema); unless
 * [needsToolSet] is false, runs the install/permission/AnkiDroid 2.24 check before anything else,
 * so a call that would not reach AnkiDroid still reports an outdated AnkiDroid; then runs the
 * tool under one [CallBudget] of [TOOL_BUDGET_MS] shared by all its repository calls. An
 * [InvalidArguments] becomes a tool error.
 */
abstract class AnkiTool(protected val repository: AnkiDroidRepository, private val needsToolSet: Boolean = true) : Tool {
    final override suspend fun call(arguments: JsonObject): ToolResult = try {
        val known = (inputSchema["properties"] as? JsonObject)?.keys.orEmpty()
        val unknown = arguments.keys.filter { it !in known }
        if (unknown.isNotEmpty()) {
            val accepted = if (known.isEmpty()) "no arguments" else known.joinToString(", ")
            throw InvalidArguments(
                "Unknown argument(s) for $name: ${unknown.joinToString(", ")}. On AnkiDroid this tool accepts $accepted.",
            )
        }
        val outdated = if (needsToolSet) repository.checkToolSet() else null
        outdated?.let { ToolResult.error(it.message) }
            ?: withContext(CallBudget(TOOL_BUDGET_MS)) { run(ToolArgs(arguments)) }
    } catch (e: InvalidArguments) {
        ToolResult.error(e.message ?: "Invalid arguments")
    } catch (e: ToolFailure) {
        e.result
    }

    protected abstract suspend fun run(args: ToolArgs): ToolResult

    companion object {
        /**
         * All AnkiDroid calls of one tool call share this. Below the relay's 15 s hold of a
         * request and the tunnel's 25 s, so a slow chain ends with a readable error (D57).
         */
        const val TOOL_BUDGET_MS = 12_000L
    }
}

/**
 * Typed access to `tools/call` arguments. Like the add-on's pydantic validation (lax mode), a
 * whole-number float or a numeric string is accepted as an integer, and 1/0 or strings like
 * "yes"/"off" as a boolean; JSON null means "not given".
 */
class ToolArgs(private val args: JsonObject) {
    /** The argument as sent, or null when absent or JSON null. */
    fun raw(name: String): JsonElement? = args[name]?.takeUnless { it is JsonNull }

    fun string(name: String): String =
        optString(name) ?: throw InvalidArguments("Missing required argument: $name")

    fun optString(name: String): String? = when (val v = raw(name)) {
        null -> null
        is JsonPrimitive -> if (v.isString) v.content else throw InvalidArguments("$name must be a string")
        else -> throw InvalidArguments("$name must be a string")
    }

    fun long(name: String): Long = optLong(name) ?: throw InvalidArguments("Missing required argument: $name")

    fun optLong(name: String): Long? = raw(name)?.let { toLong(name, it) }

    fun int(name: String, default: Int): Int {
        val value = optLong(name) ?: return default
        if (value < Int.MIN_VALUE || value > Int.MAX_VALUE) throw InvalidArguments("$name is out of range")
        return value.toInt()
    }

    /** pydantic's lax booleans: JSON true/false, the integers 1/0, and these strings in any case. */
    fun bool(name: String, default: Boolean): Boolean = when (val v = raw(name)) {
        null -> default
        is JsonPrimitive -> when {
            !v.isString && v.booleanOrNull != null -> v.booleanOrNull!!
            !v.isString && v.longOrNull == 1L -> true
            !v.isString && v.longOrNull == 0L -> false
            v.isString && v.content.lowercase() in TRUE_STRINGS -> true
            v.isString && v.content.lowercase() in FALSE_STRINGS -> false
            else -> throw InvalidArguments("$name must be a boolean")
        }
        else -> throw InvalidArguments("$name must be a boolean")
    }

    private companion object {
        val TRUE_STRINGS = setOf("1", "on", "t", "true", "y", "yes")
        val FALSE_STRINGS = setOf("0", "off", "f", "false", "n", "no")
    }

    fun longList(name: String): List<Long> = optLongList(name) ?: throw InvalidArguments("Missing required argument: $name")

    fun optLongList(name: String): List<Long>? = when (val v = raw(name)) {
        null -> null
        is JsonArray -> v.map { toLong(name, it) }
        else -> throw InvalidArguments("$name must be an array of integers")
    }

    fun optStringList(name: String): List<String>? = when (val v = raw(name)) {
        null -> null
        is JsonArray -> v.map { item ->
            (item as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw InvalidArguments("$name must be an array of strings")
        }
        else -> throw InvalidArguments("$name must be an array of strings")
    }

    private fun toLong(name: String, element: JsonElement): Long {
        val invalid = InvalidArguments("$name must be an integer")
        val p = element as? JsonPrimitive ?: throw invalid
        if (p.isString) return p.content.trim().toLongOrNull() ?: throw invalid
        if (p.booleanOrNull != null) throw invalid
        p.longOrNull?.let { return it }
        val d = p.doubleOrNull ?: throw invalid
        if (d == Math.floor(d) && Math.abs(d) < 9.0e15) return d.toLong()
        throw invalid
    }
}

/** Returns the success value, or hands the failure's message to [fail] as a tool error. */
inline fun <T> AnkiResult<T>.valueOr(fail: (ToolResult) -> Nothing): T = when (this) {
    is AnkiResult.Success -> value
    is AnkiResult.Failure -> fail(ToolResult.error(message))
}

/**
 * A tool error worded like the add-on's `HandlerError`: `[code] message (hint: ...) (context:
 * {...})`, the context printed as a Python dict.
 */
fun addonError(message: String, hint: String? = null, code: String? = null, context: Map<String, Any?> = emptyMap()): ToolResult {
    var text = message
    if (code != null) text = "[$code] $text"
    if (hint != null) text += " (hint: $hint)"
    if (context.isNotEmpty()) text += " (context: ${pythonRepr(context)})"
    return ToolResult.error(text)
}

/** Python's `repr` for the value types the add-on puts into error contexts. */
internal fun pythonRepr(value: Any?): String = when (value) {
    null -> "None"
    is Boolean -> if (value) "True" else "False"
    is String -> pythonStringRepr(value)
    is Number -> value.toString()
    is Map<*, *> -> value.entries.joinToString(", ", "{", "}") { "${pythonRepr(it.key)}: ${pythonRepr(it.value)}" }
    is Iterable<*> -> value.joinToString(", ", "[", "]") { pythonRepr(it) }
    else -> pythonStringRepr(value.toString())
}

private fun pythonStringRepr(s: String): String {
    val quote = if ('\'' in s && '"' !in s) '"' else '\''
    val body = buildString {
        s.forEach { c ->
            when (c) {
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                quote -> append('\\').append(c)
                else -> append(c)
            }
        }
    }
    return "$quote$body$quote"
}

/**
 * Anki's question-side cloze render embeds the deleted text in `data-cloze="..."` on the cloze
 * span; the add-on strips it before a question reaches the client (`_render_helpers.py`).
 */
private val CLOZE_ANSWER_ATTR = Regex("""\s+data-cloze="[^"]*"""")

internal fun stripClozeAnswer(html: String): String = CLOZE_ANSWER_ATTR.replace(html, "")

/** Desktop's `question_and_style()` / `answer_and_style()`: the note type CSS in a style tag, then the HTML. */
internal fun withStyle(css: String, html: String): String = "<style>$css</style>$html"

/**
 * `col.build_search_string(SearchNode(deck=name))`: Anki wildcards (`\`, `*`, `_`) escaped
 * unless the name is `_*`, then `deck:...` quoted when it holds a space, an ideographic space
 * or a parenthesis, with `"` escaped (rslib `writer.rs`).
 */
internal fun deckSearch(name: String): String {
    val escaped = if (name == "_*") name else name.replace(Regex("""[\\*_]""")) { "\\" + it.value }
    val text = "deck:$escaped"
    val needsQuotes = Regex("""(?i)^and$|^or$|^-.| |　|\(|\)""").containsMatchIn(text)
    val quoted = text.replace("\"", "\\\"")
    return if (needsQuotes) "\"$quoted\"" else quoted
}

/** Length in Unicode code points, like Python's `len(str)`. */
internal fun pyLen(s: String): Int = s.codePointCount(0, s.length)

/** The first [n] code points, like Python's `s[:n]`. */
internal fun pyPrefix(s: String, n: Int): String =
    if (pyLen(s) <= n) s else s.substring(0, s.offsetByCodePoints(0, n))

/** Python's `round(x, digits)` for a float: the exact binary value rounded half-even. */
internal fun pyRound(x: Double, digits: Int): Double = BigDecimal(x).setScale(digits, RoundingMode.HALF_EVEN).toDouble()

/** Case-insensitive lookup as Anki's name lookups do (deck and note type names are unicase-unique). */
internal fun <T> List<T>.byName(name: String, nameOf: (T) -> String): T? =
    firstOrNull { nameOf(it) == name } ?: firstOrNull { nameOf(it).equals(name, ignoreCase = true) }

/** `{"type":"object","properties":{...},"required":[...],"additionalProperties":false}`. */
internal fun inputSchema(required: List<String> = emptyList(), properties: JsonObjectBuilder.() -> Unit): JsonObject =
    buildJsonObject {
        put("type", "object")
        putJsonObject("properties", properties)
        if (required.isNotEmpty()) putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
        put("additionalProperties", false)
    }

internal fun JsonObjectBuilder.property(
    name: String,
    type: String,
    description: String,
    default: JsonElement? = null,
    items: String? = null,
    extra: JsonObjectBuilder.() -> Unit = {},
) {
    putJsonObject(name) {
        put("type", type)
        if (items != null) putJsonObject("items") { put("type", items) }
        put("description", description)
        if (default != null) put("default", default)
        extra()
    }
}
