package ai.ankimcp.ankidroid.tools

import ai.ankimcp.ankidroid.anki.AnkiDroidRepository
import ai.ankimcp.ankidroid.mcp.ToolResult
import ai.ankimcp.ankidroid.mcp.readOnlyAnnotations
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * `list_decks`, the add-on's tool with its keys (D57). AnkiDroid's deck list has no per-deck
 * total, so `stats.total_in_deck` and `summary.total_cards` are not returned. Works on every
 * AnkiDroid version the app supports (no 2.24 gate).
 */
class ListDecksTool(repository: AnkiDroidRepository) : AnkiTool(repository, needsToolSet = false) {
    override val name = "list_decks"
    override val title = "List Decks"
    override val description =
        "List all available Anki decks, optionally with statistics. " +
            "Counts reflect the local collection at call time; this call does not sync with AnkiWeb. " +
            "Returns deck objects with deck_id, name, and is_filtered. With include_stats=true, adds per-deck " +
            "card counts due today (new, learn, review; AnkiDroid does not report a per-deck total)."

    override val inputSchema: JsonObject = inputSchema {
        property(
            "include_stats",
            "boolean",
            "Add per-deck card counts (new, learn, review)",
            default = JsonPrimitive(false),
        )
    }

    override val outputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("decks") {
                put("type", "array")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("deck_id") { put("type", "integer") }
                        putJsonObject("name") { put("type", "string") }
                        putJsonObject("is_filtered") { put("type", "boolean") }
                        putJsonObject("stats") {
                            put("type", "object")
                            put("description", "Cards due today; only with include_stats, omitted if AnkiDroid returned no counts.")
                            putJsonObject("properties") {
                                putJsonObject("new_count") { put("type", "integer") }
                                putJsonObject("learn_count") { put("type", "integer") }
                                putJsonObject("review_count") { put("type", "integer") }
                            }
                            putJsonArray("required") {
                                add("new_count")
                                add("learn_count")
                                add("review_count")
                            }
                        }
                    }
                    putJsonArray("required") {
                        add("deck_id")
                        add("name")
                        add("is_filtered")
                    }
                }
            }
            putJsonObject("total") { put("type", "integer") }
            putJsonObject("summary") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("new_cards") { put("type", "integer") }
                    putJsonObject("learning_cards") { put("type", "integer") }
                    putJsonObject("review_cards") { put("type", "integer") }
                }
            }
            putJsonObject("message") { put("type", "string") }
        }
        putJsonArray("required") {
            add("decks")
            add("total")
        }
    }

    override val annotations: JsonObject = readOnlyAnnotations(title)

    override suspend fun run(args: ToolArgs): ToolResult {
        val includeStats = args.bool("include_stats", false)
        val decks = repository.listDecks(minVersion = null).valueOr { return it }.sortedBy { it.name.lowercase() }
        if (decks.isEmpty()) {
            return ToolResult.structured(
                buildJsonObject {
                    put("message", "No decks found in Anki")
                    put("decks", buildJsonArray {})
                    put("total", 0)
                },
            )
        }
        var newCards = 0L
        var learningCards = 0L
        var reviewCards = 0L
        val items = buildJsonArray {
            decks.forEach { deck ->
                add(
                    buildJsonObject {
                        put("deck_id", deck.id)
                        put("name", deck.name)
                        put("is_filtered", deck.isFiltered)
                        val counts = deck.counts
                        if (includeStats && counts != null) {
                            putJsonObject("stats") {
                                put("new_count", counts.new)
                                put("learn_count", counts.learn)
                                put("review_count", counts.review)
                            }
                            newCards += counts.new
                            learningCards += counts.learn
                            reviewCards += counts.review
                        }
                    },
                )
            }
        }
        return ToolResult.structured(
            buildJsonObject {
                put("decks", items)
                put("total", decks.size)
                if (includeStats) {
                    putJsonObject("summary") {
                        put("new_cards", newCards)
                        put("learning_cards", learningCards)
                        put("review_cards", reviewCards)
                    }
                }
            },
        )
    }
}
