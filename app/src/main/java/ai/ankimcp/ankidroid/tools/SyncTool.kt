package ai.ankimcp.ankidroid.tools

import ai.ankimcp.ankidroid.anki.AnkiDroidRepository
import ai.ankimcp.ankidroid.anki.AnkiDroidSync
import ai.ankimcp.ankidroid.anki.SyncRequest
import ai.ankimcp.ankidroid.mcp.ToolResult
import ai.ankimcp.ankidroid.mcp.writeAnnotations
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `sync`, the phone's own version (owner decision, D58): AnkiDroid's provider has no sync, and a
 * background app may not start AnkiDroid's sync activity, so this posts a notification the user
 * taps to start it. Nothing about the sync comes back. The add-on's `job_id`/`resolve` polling has
 * no equivalent and is not offered.
 */
class SyncTool(repository: AnkiDroidRepository, private val sync: AnkiDroidSync) : AnkiTool(repository) {
    override val name = "sync"
    override val title = "Sync with AnkiWeb"
    override val description =
        "Ask the user to sync AnkiDroid with AnkiWeb. Posts a \"Tap to sync AnkiDroid\" notification on the phone; " +
            "the sync starts only when the user taps it, and no result is reported back (check in AnkiDroid). " +
            "AnkiDroid must be signed in to AnkiWeb, and it skips a sync requested less than 2 minutes after the last one."
    override val inputSchema: JsonObject = inputSchema {}
    override val annotations: JsonObject = writeAnnotations(title, openWorld = true)

    override suspend fun run(args: ToolArgs): ToolResult = when (val result = sync.requestSync()) {
        SyncRequest.NotificationPosted -> ToolResult.structured(
            buildJsonObject {
                put("status", "requested")
                put(
                    "message",
                    "Sync requested. The user must tap the \"Tap to sync AnkiDroid\" notification on the phone to start it; " +
                        "no sync result is reported back. AnkiDroid must be signed in to AnkiWeb.",
                )
            },
        )
        is SyncRequest.NotificationBlocked -> ToolResult.error(
            "Could not ask for a sync: ${result.reason} for the AnkiMCP app on the phone, so the \"Tap to sync AnkiDroid\" notification " +
                "would not be seen. Ask the user to sync in AnkiDroid, or to allow the notification and try again.",
        )
        SyncRequest.AnkiDroidMissing -> ToolResult.error(
            "Could not ask for a sync: AnkiDroid does not accept sync requests on this phone. Ask the user to sync in AnkiDroid.",
        )
    }
}
