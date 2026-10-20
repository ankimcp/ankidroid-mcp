package ai.ankimcp.ankidroid.ui

import ai.ankimcp.ankidroid.R
import ai.ankimcp.ankidroid.local.LocalServerState
import ai.ankimcp.ankidroid.tunnel.TunnelState
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** The two halves of the Status screen (D59). */
enum class StatusTab(
    @param:StringRes val title: Int,
    /** TalkBack's state word for [TabDot.Off], [TabDot.Connecting] and [TabDot.Connected]. */
    val stateWords: List<Int>,
) {
    /** The AnkiMCP tunnel. */
    Remote(
        R.string.status_tab_remote,
        listOf(R.string.status_tab_remote_off, R.string.status_tab_remote_connecting, R.string.status_tab_remote_connected),
    ),

    /** The local server on the phone's Wi-Fi. */
    Local(
        R.string.status_tab_local,
        listOf(R.string.status_tab_local_off, R.string.status_tab_local_waiting, R.string.status_tab_local_running),
    ),
}

/** The add-on's `IndicatorState`: grey, amber, green; no separate error colour. */
internal enum class TabDot { Off, Connecting, Connected }

/** A tab the user picked, and whether they were signed in at the time. */
internal data class StatusTabChoice(val tab: StatusTab, val signedIn: Boolean)

/**
 * The Status tab selection: the user's pick and the account state last seen. A pick is
 * dropped whenever the account state flips (sign-in, sign-out, a session that ended), so each
 * account state starts on its default tab.
 */
internal data class StatusTabState(val seenSignedIn: Boolean? = null, val choice: StatusTabChoice? = null) {
    /** Called with the current account state once credentials are loaded. */
    fun withAccount(signedIn: Boolean): StatusTabState =
        if (seenSignedIn != null && seenSignedIn != signedIn) StatusTabState(signedIn, null) else copy(seenSignedIn = signedIn)

    fun pick(tab: StatusTab, signedIn: Boolean): StatusTabState = StatusTabState(signedIn, StatusTabChoice(tab, signedIn))

    /** True when [signedIn] is a sign-out or an ended session after a signed-in state was seen. */
    fun isSignOut(signedIn: Boolean): Boolean = seenSignedIn == true && !signedIn

    /**
     * Called with the current account state once credentials are loaded. A sign-out or an ended
     * session lands on Remote, where the sign-in card and the reason are, even with the local
     * server on. Otherwise the tab on screen becomes the pick, so a later change of
     * [localEnabled] (the server stopped from its notification) does not switch tabs.
     */
    fun settle(signedIn: Boolean, localEnabled: Boolean): StatusTabState {
        if (isSignOut(signedIn)) return pick(StatusTab.Remote, signedIn = false)
        val next = withAccount(signedIn)
        return if (next.choice?.signedIn == signedIn) next else next.pick(next.selected(signedIn, localEnabled), signedIn)
    }

    /** The pick made in this account state; otherwise Remote, or Local when signed out with the local server on. */
    fun selected(signedIn: Boolean, localEnabled: Boolean): StatusTab =
        choice?.takeIf { it.signedIn == signedIn }?.tab ?: if (!signedIn && localEnabled) StatusTab.Local else StatusTab.Remote

    companion object {
        private const val NONE = "-"

        val Saver: Saver<StatusTabState, String> = Saver(
            save = { state ->
                listOf(
                    state.seenSignedIn?.toString() ?: NONE,
                    state.choice?.tab?.name ?: NONE,
                    state.choice?.signedIn?.toString() ?: NONE,
                ).joinToString(":")
            },
            restore = { saved ->
                val (seen, tab, signedIn) = saved.split(':')
                StatusTabState(
                    seenSignedIn = seen.takeIf { it != NONE }?.toBooleanStrict(),
                    choice = if (tab == NONE) null else StatusTabChoice(StatusTab.valueOf(tab), signedIn.toBooleanStrict()),
                )
            },
        )
    }
}

/**
 * Seeds the selection when the activity is re-created without saved state (after Back left
 * the app, in the same process); `rememberSaveable` covers rotation, Log/About and process death.
 */
internal object StatusTabMemory {
    @Volatile
    var state: StatusTabState = StatusTabState()
}

/**
 * The dot before the tab title, as the add-on's `_derive_state` (toolbar_indicator.py): green
 * when connected, amber while a run is going on but not connected, grey otherwise. Errors are
 * grey, as there: the add-on has no error colour. Remote access off (D63) is grey whatever the
 * tunnel state still says. For the local server, "going on" is the switch on and the server
 * starting or waiting for Wi-Fi; a public address or a failed bind is an error.
 */
internal fun statusTabDot(
    tab: StatusTab,
    remoteEnabled: Boolean,
    tunnelState: TunnelState,
    localEnabled: Boolean,
    localState: LocalServerState,
): TabDot =
    when (tab) {
        StatusTab.Remote -> if (!remoteEnabled) {
            TabDot.Off
        } else {
            when (tunnelState) {
                is TunnelState.Connected -> TabDot.Connected
                TunnelState.WaitingForNetwork, is TunnelState.Connecting, is TunnelState.Reconnecting -> TabDot.Connecting
                TunnelState.Idle, is TunnelState.Stopped -> TabDot.Off
            }
        }
        StatusTab.Local -> if (!localEnabled) {
            TabDot.Off
        } else {
            when (localState) {
                is LocalServerState.Running -> TabDot.Connected
                LocalServerState.Off, LocalServerState.NoWifi -> TabDot.Connecting
                LocalServerState.PublicAddress, is LocalServerState.Failed -> TabDot.Off
            }
        }
    }

@Composable
internal fun StatusTabRow(selected: StatusTab, dot: (StatusTab) -> TabDot, onSelect: (StatusTab) -> Unit, modifier: Modifier = Modifier) {
    val status = LocalStatusColors.current
    SecondaryTabRow(selectedTabIndex = selected.ordinal, modifier = modifier) {
        StatusTab.entries.forEach { tab ->
            val state = dot(tab)
            val stateWord = stringResource(tab.stateWords[state.ordinal])
            val dotColor = when (state) {
                TabDot.Off -> status.off
                TabDot.Connecting -> status.connectingDot
                TabDot.Connected -> status.connected
            }
            Tab(
                selected = tab == selected,
                onClick = { onSelect(tab) },
                modifier = Modifier.semantics { stateDescription = stateWord },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).background(dotColor, CircleShape))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(tab.title), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
            )
        }
    }
}
