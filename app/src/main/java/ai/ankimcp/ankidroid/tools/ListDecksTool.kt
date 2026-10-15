package ai.ankimcp.ankidroid.tools

import ai.ankimcp.ankidroid.anki.AnkiDroidRepository
import ai.ankimcp.ankidroid.anki.AnkiResult
import ai.ankimcp.ankidroid.mcp.Tool
import ai.ankimcp.ankidroid.mcp.ToolResult
import ai.ankimcp.ankidroid.mcp.readOnlyAnnotations
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * `list_decks`, modelled on the add-on's tool of the same name. AnkiDroid's provider always
 * returns due counts, so there is no `include_stats` switch; `total_in_deck` is not available.
 *
 * Output: `{"decks": [{"id", "name", "is_filtered", "counts": {"new", "learn", "review"}}], "total"}`
 */
class ListDecksTool(private val repository: AnkiDroidRepository) : Tool {
    override val name = "list_decks"
    override val title = "List Decks"
    override val description =
        "List all available Anki decks in AnkiDroid on the user's phone, with per-deck due counts. " +
            "Counts reflect the local collection at call time; this call does not sync with AnkiWeb. " +
            "Returns deck objects with id, name, is_filtered, and counts (new, learn, review: cards due today)."

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", JsonObject(emptyMap()))
        put("additionalProperties", false)
    }

    override val outputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("decks") {
                put("type", "array")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("id") { put("type", "integer") }
                        putJsonObject("name") { put("type", "string") }
                        putJsonObject("is_filtered") { put("type", "boolean") }
                        putJsonObject("counts") {
                            put("type", "object")
                            put("description", "Cards due today; omitted if AnkiDroid returned no counts.")
                            putJsonObject("properties") {
                                putJsonObject("new") { put("type", "integer") }
                                putJsonObject("learn") { put("type", "integer") }
                                putJsonObject("review") { put("type", "integer") }
                            }
                            putJsonArray("required") {
                                add("new")
                                add("learn")
                                add("review")
                            }
                        }
                    }
                    putJsonArray("required") {
                        add("id")
                        add("name")
                        add("is_filtered")
                    }
                }
            }
            putJsonObject("total") { put("type", "integer") }
            putJsonObject("message") { put("type", "string") }
        }
        putJsonArray("required") {
            add("decks")
            add("total")
        }
    }

    override val annotations: JsonObject = readOnlyAnnotations(title)

    override suspend fun call(arguments: JsonObject): ToolResult =
        when (val result = repository.listDecks()) {
            is AnkiResult.Failure -> ToolResult.error(result.message)
            is AnkiResult.Success -> {
                val decks = result.value.sortedBy { it.name.lowercase() }
                ToolResult.structured(
                    buildJsonObject {
                        put(
                            "decks",
                            buildJsonArray {
                                decks.forEach { deck ->
                                    add(
                                        buildJsonObject {
                                            put("id", deck.id)
                                            put("name", deck.name)
                                            put("is_filtered", deck.isFiltered)
                                            deck.counts?.let { counts ->
                                                put(
                                                    "counts",
                                                    buildJsonObject {
                                                        put("new", counts.new)
                                                        put("learn", counts.learn)
                                                        put("review", counts.review)
                                                    },
                                                )
                                            }
                                        },
                                    )
                                }
                            },
                        )
                        put("total", decks.size)
                        if (decks.isEmpty()) put("message", "No decks found in AnkiDroid")
                    },
                )
            }
        }
}
