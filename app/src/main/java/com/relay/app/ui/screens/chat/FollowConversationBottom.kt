package com.relay.app.ui.screens.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/**
 * Reversed timelines open at index zero. Stable keys anchor older messages across insertions, so a new
 * message lands below the old bottom; while the reader is following, the list is put back on it.
 */
@Composable
internal fun FollowConversationBottom(state: LazyListState, newestKey: String?) {
    val tolerance = with(LocalDensity.current) { 48.dp.roundToPx() }
    val hasContent by rememberUpdatedState(newestKey != null)
    LaunchedEffect(state, tolerance) {
        var following = true
        var readerWasScrolling = false
        // One collector both decides and scrolls. The next sample is only taken after its own scroll has
        // finished, so only the reader's scrolling can turn following off. Separate effects used to see
        // their own scroll as the reader's, switch following off and leave a new picture off-screen.
        snapshotFlow { Triple(state.isScrollInProgress, state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset) to hasContent }
            .collect { (position, content) ->
                val (scrolling, index, offset) = position
                // Also the first sample after the reader lets go: it carries where the fling stopped.
                if (scrolling || readerWasScrolling) following = index == 0 && offset <= tolerance
                readerWasScrolling = scrolling
                if (following && content && !scrolling && (index != 0 || offset != 0)) try {
                    state.scrollToItem(0)
                } catch (e: CancellationException) {
                    // A drag that starts mid-scroll takes over; only a real cancellation ends this watcher.
                    if (!currentCoroutineContext().isActive) throw e
                }
            }
    }
}
