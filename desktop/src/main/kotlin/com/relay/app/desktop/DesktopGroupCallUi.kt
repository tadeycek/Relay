package com.relay.app.desktop

import com.relay.app.call.group.GroupCallState

enum class GroupCallStage { IDLE, STARTING, JOINING, ACTIVE, RECONNECTING }

/** A live (or rejoinable) call in a group thread: the banner offers Join / Rejoin. */
data class GroupCallBanner(
    val conversationId: String, val room: String, val starter: String, val starterName: String,
    /** True after this desktop left a call that has not ended: Join becomes Rejoin. */
    val rejoin: Boolean = false,
)

data class GroupDeviceUi(
    val pid: String, val device: String, val muted: Boolean, val deafened: Boolean, val speaking: Boolean, val volume: Float,
)

/** One person with every device that is in the call (a person on phone and desktop is one row, two devices). */
data class GroupPersonUi(
    val pubkey: String, val name: String, val self: Boolean,
    /** False when the identity is not in this desktop's copy of the group roster: shown as "Not in this group". */
    val inRoster: Boolean,
    val devices: List<GroupDeviceUi>,
) {
    val speaking: Boolean get() = devices.any { it.speaking }
    val muted: Boolean get() = devices.isNotEmpty() && devices.all { it.muted }
}

/** Everything the group-call UI renders. */
data class DesktopGroupCallUi(
    val banners: Map<String, GroupCallBanner> = emptyMap(),
    val stage: GroupCallStage = GroupCallStage.IDLE,
    val conversationId: String? = null,
    val groupName: String = "",
    val people: List<GroupPersonUi> = emptyList(),
    val muted: Boolean = false,
    val deafened: Boolean = false,
    /** This desktop started the call: it may end it for everyone. */
    val host: Boolean = false,
    val connectedAtMs: Long? = null,
    val notice: String? = null,
    /**
     * WP17 security label. Defaults to the in-transit wording and only the controller may raise it, after every participant
     * device (this one included) proved possession of the current epoch key — see [GroupCallE2ee].
     */
    val label: String = GroupCallState.SECURITY_LABEL,
    /** Set when the call has no usable frame key: the microphone and speaker are off and this says why. */
    val securityError: String? = null,
) {
    val live: Boolean get() = stage != GroupCallStage.IDLE
}
