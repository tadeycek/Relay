package com.relay.app.ui.screens.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged

/** Reversed timelines open at index zero. Stable keys anchor older messages across insertions. */
@Composable
internal fun FollowConversationBottom(state: LazyListState, newestKey: String?) {
    val tolerance = with(LocalDensity.current) { 48.dp.roundToPx() }
    var following by remember(state) { mutableStateOf(true) }
    LaunchedEffect(state, tolerance) {
        snapshotFlow { Triple(state.isScrollInProgress, state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset) }
            .distinctUntilChanged().collect { (scrolling, index, offset) ->
                // Only the reader changes this policy: list insertions/resizing cannot turn it off/on.
                if (scrolling) following = index == 0 && offset <= tolerance
            }
    }
    val height by remember(state) { derivedStateOf { state.layoutInfo.viewportEndOffset } }
    LaunchedEffect(newestKey, height) {
        if (following && !state.isScrollInProgress && newestKey != null) state.scrollToItem(0)
    }
}
