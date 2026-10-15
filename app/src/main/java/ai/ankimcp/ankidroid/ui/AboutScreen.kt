package ai.ankimcp.ankidroid.ui

import ai.ankimcp.ankidroid.R
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri

private data class AboutLink(val title: String, val url: String)

private val Links = listOf(
    AboutLink("Website", "https://ankimcp.ai"),
    AboutLink("Dashboard", "https://web.ankimcp.ai"),
    AboutLink("Forum", "https://forum.ankimcp.ai/"),
    AboutLink("Service status", "https://status.ankimcp.ai/"),
    AboutLink("Source code", "https://github.com/ankimcp/ankidroid-mcp"),
)

@Composable
fun AboutScreen(appVersion: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OwlMark(size = 64.dp)
            Column(Modifier.padding(start = 16.dp)) {
                Text("AnkiDroid MCP", style = MaterialTheme.typography.titleLarge)
                SupportingText("Version $appVersion")
            }
        }
        Text("Lets Claude read your AnkiDroid decks through AnkiMCP while your phone is connected.")

        Card(Modifier.fillMaxWidth()) {
            Links.forEachIndexed { index, link ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ListItem(
                    headlineContent = { Text(link.title) },
                    supportingContent = { Text(link.url.removePrefix("https://").trimEnd('/')) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable(role = Role.Button, onClickLabel = "Open ${link.title}") {
                        openLink(context, link.url)
                    },
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Report a problem", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Open the Log tab, tap Share log, and post it with a short description of what " +
                        "happened on forum.ankimcp.ai. The shared log has your phone model and app " +
                        "version but not your email, and anything that looks like a token is removed.",
                )
            }
        }

        SupportingText("Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).")
    }
}

/** The ankimcp.ai owl on its navy tile, like the launcher icon. */
@Composable
internal fun OwlMark(size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.22f))
            .background(OwlNavy),
    ) {
        Image(
            painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private fun openLink(context: Context, url: String) {
    val uri = url.toUri()
    try {
        CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, uri)
    } catch (_: ActivityNotFoundException) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
    }
}
