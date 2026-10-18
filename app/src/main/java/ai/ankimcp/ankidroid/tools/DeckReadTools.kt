package ai.ankimcp.ankidroid.tools

import ai.ankimcp.ankidroid.anki.AnkiDroidRepository
import ai.ankimcp.ankidroid.anki.DeckOptions
import ai.ankimcp.ankidroid.anki.NoteField
import ai.ankimcp.ankidroid.mcp.ToolResult
import ai.ankimcp.ankidroid.mcp.readOnlyAnnotations
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * `tag_list`. AnkiDroid does not expose Anki's tag registry, so the collection-wide list is the
 * tags found on notes: a registered tag no note uses is not listed (D57). The deck-scoped list
 * is the add-on's own approach (tags of the notes with a card in the deck).
 */
class TagListTool(repository: AnkiDroidRepository) : AnkiTool(repository) {
    override val name = "tag_list"
    override val title = "List Tags"
    override val description =
        "List all tags in use in the collection. The optional deck param scopes results to tags " +
            "on notes with at least one card in that deck (subdecks included); omit it for " +
            "all collection tags (on AnkiDroid: tags on at least one note). Returns tags array and count."
    override val inputSchema: JsonObject = inputSchema {
        property(
            "deck",
            "string",
            "Optional deck name to scope tags to notes with a card in this deck " +
                "(subdecks included). Omit or leave empty for all collection tags.",
            default = JsonPrimitive(""),
        )
    }
    override val annotations: JsonObject = readOnlyAnnotations(title)

    override suspend fun run(args: ToolArgs): ToolResult {
        val deck = args.optString("deck")?.trim() ?: ""
        val query = if (deck.isEmpty()) "" else deckSearch(deck)
        val notes = repository.searchNotes(query, setOf(NoteField.TAGS)).valueOr { return it }
        val distinct = notes.flatMap { it.tags.orEmpty() }.toSet()
        // The registry the add-on reads is ordered case-insensitively (unicase); its deck-scoped list is sorted().
        val tags = if (deck.isEmpty()) distinct.sortedWith(String.CASE_INSENSITIVE_ORDER.thenBy { it }) else distinct.sorted()
        return ToolResult.structured(
            buildJsonObject {
                putJsonArray("tags") { tags.forEach { add(it) } }
                put("count", tags.size)
                put("message", if (deck.isEmpty()) "Found ${tags.size} tag(s)" else "Found ${tags.size} tag(s) in deck '$deck'")
            },
        )
    }
}

/**
 * `get_fsrs_params` from each deck's options group as AnkiDroid reports it (legacy deck config
 * JSON). AnkiDroid exposes neither the collection's FSRS switch nor the FSRS version, so
 * `fsrs_enabled` and `fsrs_version` are not returned; per-deck desired-retention overrides are
 * not visible, so `desired_retention` is the preset's; presets no deck uses are not listed.
 * `max_interval` is the preset's `rev.maxIvl` (D57).
 */
class GetFsrsParamsTool(repository: AnkiDroidRepository) : AnkiTool(repository) {
    override val name = "get_fsrs_params"
    override val title = "Get FSRS Parameters"
    override val description =
        "Get FSRS scheduler parameters for Anki deck presets. " +
            "Returns FSRS weights, desired retention, max interval, and other settings. " +
            "If deck_name is empty, returns parameters for all presets in use by a deck, with their decks. " +
            "If deck_name is provided, returns parameters for the preset used by that deck. " +
            "AnkiDroid does not report whether FSRS is enabled."
    override val inputSchema: JsonObject = inputSchema {
        property("deck_name", "string", "Deck to look up the preset for; empty returns all presets", default = JsonPrimitive(""))
    }
    override val annotations: JsonObject = readOnlyAnnotations(title)

    private class Preset(val id: Long, val config: JsonObject)

    override suspend fun run(args: ToolArgs): ToolResult {
        val deckName = args.optString("deck_name") ?: ""
        val decks = repository.listDeckOptions().valueOr { return it }.sortedBy { it.name.lowercase() }
        val presetOf = decks.associateWith(::parsePreset)
        // The add-on groups by col.decks.config_dict_for_deck_id, which for a filtered deck is the
        // deck itself rather than a preset; those decks are left out of every preset's list.
        val members = decks.filter { !it.isFiltered }.groupBy { presetOf[it]?.id }

        if (deckName.isNotEmpty()) {
            val deck = decks.byName(deckName) { it.name }
                ?: return addonError("Deck not found: $deckName", hint = "Check spelling or use list_decks to see available decks")
            // A filtered deck has no preset; AnkiDroid's `options` for it is not one (D57).
            if (deck.isFiltered) {
                return addonError(
                    "Deck '${deck.name}' is a filtered deck, which has no FSRS preset",
                    hint = "Ask about the deck its cards come from, or call without deck_name to list every preset",
                )
            }
            val preset = presetOf[deck]
                ?: return ToolResult.error("AnkiDroid returned no options for deck \"${deck.name}\".")
            return ToolResult.structured(
                buildJsonObject {
                    put("deck_name", deckName)
                    put("preset", format(preset, members[preset.id].orEmpty()))
                },
            )
        }
        val presets = presetOf.filterKeys { !it.isFiltered }.values.filterNotNull()
            .filter { members[it.id] != null }
            .distinctBy { it.id }
            .sortedBy { it.id }
        return ToolResult.structured(
            buildJsonObject {
                putJsonArray("presets") { presets.forEach { add(format(it, members[it.id].orEmpty())) } }
                put("total_presets", presets.size)
            },
        )
    }

    private fun parsePreset(deck: DeckOptions): Preset? {
        val config = deck.optionsJson?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() } as? JsonObject ?: return null
        val id = (config["id"] as? JsonPrimitive)?.longOrNull ?: return null
        return Preset(id, config)
    }

    private fun format(preset: Preset, decks: List<DeckOptions>): JsonObject {
        val config = preset.config
        val (version, weights) = fsrsParams(config)
        return buildJsonObject {
            put("preset_name", config["name"] ?: JsonNull)
            put("preset_id", preset.id)
            put("fsrs_weights", weights)
            put("fsrs_param_version", version?.let(::JsonPrimitive) ?: JsonNull)
            put("desired_retention", config["desiredRetention"]?.takeUnless { it is JsonNull } ?: JsonPrimitive(0.9))
            put("max_interval", maxInterval(config))
            putJsonArray("decks") { decks.forEach { add(it.name) } }
            val paramSearch = nonEmpty(config["paramSearch"]) ?: nonEmpty(config["weightSearch"])
            if (paramSearch != null) put("param_search", paramSearch)
            val ignoreBefore = nonEmpty(config["ignoreRevlogsBeforeDate"])
            if (ignoreBefore != null) put("ignore_revlogs_before_date", ignoreBefore)
            val easyDays = nonEmpty(config["easyDaysPercentages"])
            if (easyDays != null) put("easy_days_percentages", easyDays)
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val PARAMS_KEY = Regex("""^fsrsParams(\d+)$""")

        /** Highest non-empty `fsrsParamsN` and its N, else the legacy `fsrsWeights` with no version, as the add-on reads them. */
        internal fun fsrsParams(config: JsonObject): Pair<Int?, JsonArray> {
            val versioned = config.entries
                .mapNotNull { (key, value) -> PARAMS_KEY.find(key)?.let { it.groupValues[1].toInt() to value } }
                .filter { (version, value) -> version >= 4 && value is JsonArray && value.isNotEmpty() }
                .maxByOrNull { it.first }
            if (versioned != null) return versioned.first to versioned.second as JsonArray
            val legacy = config["fsrsWeights"] as? JsonArray
            return null to (legacy?.takeIf { it.isNotEmpty() } ?: JsonArray(emptyList()))
        }

        /** `rev.maxIvl` in Anki's legacy deck config JSON; 36500 (Anki's default) if absent. */
        internal fun maxInterval(config: JsonObject): JsonElement =
            ((config["rev"] as? JsonObject)?.get("maxIvl") ?: config["maxIvl"])?.takeUnless { it is JsonNull }
                ?: JsonPrimitive(36500)

        /** Python truthiness for the optional keys the add-on only returns when set. */
        private fun nonEmpty(value: JsonElement?): JsonElement? = when (value) {
            null, JsonNull -> null
            is JsonArray -> value.takeIf { it.isNotEmpty() }
            is JsonObject -> value.takeIf { it.isNotEmpty() }
            is JsonPrimitive -> value.takeIf { if (it.isString) it.content.isNotEmpty() else it.content != "0" && it.content != "false" }
        }
    }
}

/**
 * `filtered_deck_get_info`. AnkiDroid does not expose a filtered deck's search terms or
 * reschedule setting, so `search_terms` and `reschedule` are not returned (D57). `card_count`
 * counts the cards in the deck itself, not its subdecks, like `col.decks.cids(did,
 * children=False)`.
 */
class FilteredDeckGetInfoTool(repository: AnkiDroidRepository) : AnkiTool(repository) {
    override val name = "filtered_deck_get_info"
    override val title = "Get Filtered Deck Info"
    override val description =
        "Inspect up to $MAX_DECKS decks by ID. " +
            "Non-filtered decks are included with is_filtered=false; " +
            "unknown deck IDs are skipped and counted in not_found. " +
            "Returns name, is_filtered and card count per deck (AnkiDroid does not expose a filtered deck's " +
            "search terms or reschedule setting)."
    override val inputSchema: JsonObject = inputSchema(required = listOf("deck_ids")) {
        property("deck_ids", "array", "Deck IDs to inspect (max $MAX_DECKS)", items = "integer") {
            put("minItems", 1)
            put("maxItems", MAX_DECKS)
        }
    }
    override val annotations: JsonObject = readOnlyAnnotations(title)

    override suspend fun run(args: ToolArgs): ToolResult {
        val ids = args.longList("deck_ids")
        if (ids.isEmpty()) {
            return addonError("deck_ids cannot be empty", hint = "Provide at least one deck ID", code = "validation_error")
        }
        if (ids.size > MAX_DECKS) {
            return addonError(
                "Maximum $MAX_DECKS deck IDs per request (requested: ${ids.size})",
                hint = "Split your request into smaller batches",
                code = "limit_exceeded",
            )
        }
        val decks = repository.listDecks().valueOr { return it }.associateBy { it.id }
        var notFound = 0
        val items = buildJsonArray {
            ids.forEach { id ->
                val deck = decks[id]
                if (deck == null) {
                    notFound++
                    return@forEach
                }
                // `did:` also matches cards whose home deck is this one while they sit in a filtered deck.
                val query = if (deck.isFiltered) "did:$id" else "did:$id -deck:filtered"
                val count = repository.searchCardIds(query).valueOr { return it }.size
                addJsonObject {
                    put("deck_id", id)
                    put("name", deck.name)
                    put("card_count", count)
                    put("is_filtered", deck.isFiltered)
                }
            }
        }
        val count = items.size
        return ToolResult.structured(
            buildJsonObject {
                put("decks", items)
                put("count", count)
                put("not_found", notFound)
                put(
                    "message",
                    if (notFound > 0) {
                        "Retrieved info for $count deck(s). $notFound deck(s) not found."
                    } else {
                        "Retrieved info for $count deck(s)"
                    },
                )
            },
        )
    }

    companion object {
        const val MAX_DECKS = 50
    }
}
