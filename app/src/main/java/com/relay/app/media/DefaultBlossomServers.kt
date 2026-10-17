package com.relay.app.media

/** Blob-storage endpoints tried in order. Only encrypted bytes are uploaded; availability and
 * retention are controlled by these independent services and can change. */
object DefaultBlossomServers {
    val LIST = listOf(
        "https://blossom.ditto.pub",
        "https://blossom.data.haus",
    )
}
