package com.relay.app.call.group.e2ee

/** Everything the UI needs to decide the group-call security label. */
data class E2eeCoverage(
    /** Every participant device that must be covered (the current roster, all of its devices). */
    val expected: Set<ParticipantDevice>,
    /** Devices that proved possession of their CURRENT epoch key, and the epoch they proved. */
    val verifiedEpochs: Map<ParticipantDevice, Int>,
    val currentEpoch: Int,
    /** Every expected device advertised/negotiated frame E2EE for this call. */
    val allParticipantsCapable: Boolean,
    /** The call key arrived through an authenticated E2E channel, not via the server/SFU. */
    val keyDistributionAuthenticated: Boolean,
    /** This device's own send path is sealing frames (never a plaintext fallback). */
    val sendPathEncrypted: Boolean,
)

/**
 * The only supported security-label rule (WP17 step 4, LP-D16, G5).
 *
 * The label "Group calls are encrypted in transit, not end to end." may be dropped **only** when every
 * expected participant device has verified the current epoch, every device is E2EE-capable, the key
 * distribution was authenticated end to end, and this device is actually sealing its own frames. Any
 * missing, extra, stale-epoch or unknown device keeps the weaker label. An empty expected set is never
 * end-to-end. There is no mixed-room mode: a single plaintext or unverified peer keeps the label for the
 * whole call.
 *
 * "Verified" means cryptographically demonstrated possession of the epoch key — e.g. a frame from that
 * device authenticated under the expected key for [E2eeCoverage.currentEpoch], or an equivalent
 * authenticated key-confirmation control message. Presence in the roster or receipt of a key is not
 * verification.
 */
object GroupCallSecurityLabel {
    const val END_TO_END = "Group calls are end-to-end encrypted."
    const val IN_TRANSIT = "Group calls are encrypted in transit, not end to end."

    fun isEndToEnd(coverage: E2eeCoverage): Boolean =
        coverage.currentEpoch in 0..GroupCallKeySchedule.MAX_EPOCH &&
            coverage.expected.isNotEmpty() &&
            coverage.allParticipantsCapable &&
            coverage.keyDistributionAuthenticated &&
            coverage.sendPathEncrypted &&
            coverage.verifiedEpochs.keys == coverage.expected &&
            coverage.verifiedEpochs.values.all { it == coverage.currentEpoch }

    fun label(coverage: E2eeCoverage): String =
        if (isEndToEnd(coverage)) END_TO_END else IN_TRANSIT
}

/**
 * Mutable per-epoch verification tracker owned by the call runtime. [verify] only accepts a known
 * participant device at the tracker's epoch; anything else is ignored and reported `false`, so a buggy or
 * hostile callback cannot upgrade the label. Create a new tracker after rotation (old proofs do not carry
 * over).
 */
class GroupCallE2eeStatus private constructor(
    private val epoch: Int,
    private val expected: Set<ParticipantDevice>,
) {
    private val verified = HashMap<ParticipantDevice, Int>()

    fun verify(sender: ParticipantDevice, epoch: Int): Boolean {
        if (epoch != this.epoch || sender !in expected) return false
        verified[sender] = epoch
        return true
    }

    fun invalidate(sender: ParticipantDevice) {
        verified.remove(sender)
    }

    fun verifiedEpochs(): Map<ParticipantDevice, Int> = verified.toMap()

    fun coverage(
        allParticipantsCapable: Boolean,
        keyDistributionAuthenticated: Boolean,
        sendPathEncrypted: Boolean,
    ): E2eeCoverage = E2eeCoverage(
        expected = expected,
        verifiedEpochs = verified.toMap(),
        currentEpoch = epoch,
        allParticipantsCapable = allParticipantsCapable,
        keyDistributionAuthenticated = keyDistributionAuthenticated,
        sendPathEncrypted = sendPathEncrypted,
    )

    fun label(
        allParticipantsCapable: Boolean,
        keyDistributionAuthenticated: Boolean,
        sendPathEncrypted: Boolean,
    ): String = GroupCallSecurityLabel.label(
        coverage(allParticipantsCapable, keyDistributionAuthenticated, sendPathEncrypted))

    companion object {
        fun forSchedule(schedule: GroupCallKeySchedule): GroupCallE2eeStatus =
            GroupCallE2eeStatus(schedule.epoch, schedule.participants.toSet())

        fun forEpoch(epoch: Int, expected: Collection<ParticipantDevice>): GroupCallE2eeStatus {
            require(epoch in 0..GroupCallKeySchedule.MAX_EPOCH) { "epoch out of range" }
            return GroupCallE2eeStatus(epoch, expected.toSet())
        }
    }
}
