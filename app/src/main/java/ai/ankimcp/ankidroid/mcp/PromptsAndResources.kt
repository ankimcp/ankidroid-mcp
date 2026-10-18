package ai.ankimcp.ankidroid.mcp

/** A prompt argument as `prompts/list` lists it. Prompt arguments arrive as strings (MCP). */
data class PromptArgument(val name: String, val description: String? = null, val required: Boolean = false)

/** A failed `prompts/get` render, e.g. an argument that does not parse. */
class PromptArgumentException(message: String) : Exception(message)

/** An MCP prompt whose text is built from its arguments without touching AnkiDroid. */
interface Prompt {
    val name: String
    val title: String? get() = null
    val description: String
    val arguments: List<PromptArgument>

    /** The prompt text; throws [PromptArgumentException] for an argument it cannot use. */
    fun render(arguments: Map<String, String>): String
}

/** A static MCP resource served as text. */
interface Resource {
    val uri: String
    val name: String
    val title: String? get() = null
    val description: String
    val mimeType: String get() = "text/plain"

    fun read(): String
}

class PromptRegistry(prompts: List<Prompt>) {
    private val byName: Map<String, Prompt> = prompts.associateBy { it.name }.also {
        require(it.size == prompts.size) { "Duplicate prompt names" }
    }

    val prompts: List<Prompt> = prompts.toList()

    operator fun get(name: String): Prompt? = byName[name]
}

class ResourceRegistry(resources: List<Resource>) {
    private val byUri: Map<String, Resource> = resources.associateBy { it.uri }.also {
        require(it.size == resources.size) { "Duplicate resource URIs" }
    }

    val resources: List<Resource> = resources.toList()

    operator fun get(uri: String): Resource? = byUri[uri]
}
