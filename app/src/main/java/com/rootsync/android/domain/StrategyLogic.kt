package com.rootsync.android.domain

/** Pure protocol decisions, independent of transport, disk and Android lifecycle. */
object StrategyLogic {
    enum class Decision { APPLY, DUPLICATE, STALE, INVALID }

    fun receive(profile: PeerProfile, update: StrategyUpdate): Decision {
        if (profile.deviceId != update.deviceId || !update.revision.valid ||
            (update.rangeMode == SyncRangeMode.SINCE && (update.sinceEpochMillis ?: 0) <= 0)) {
            return Decision.INVALID
        }
        val comparison = update.revision.compareTo(profile.strategyRevision)
        if (comparison < 0) return Decision.STALE
        if (comparison > 0) return Decision.APPLY
        return if (profile.role == update.role.opposite() && profile.rangeMode == update.rangeMode &&
            profile.strictContentCheck == update.strictContentCheck &&
            (profile.rangeMode == SyncRangeMode.ALL || profile.sinceEpochMillis == update.sinceEpochMillis)) Decision.DUPLICATE else Decision.INVALID
    }

    fun matchesRequest(profile: PeerProfile, request: SyncPrepareRequest): Boolean =
        request.strategyRevision == profile.strategyRevision && request.role.opposite() == profile.role &&
            request.rangeMode == profile.rangeMode && request.strictChecksum == profile.strictContentCheck &&
            (profile.rangeMode == SyncRangeMode.ALL || request.sinceEpochMillis == profile.sinceEpochMillis)

    fun acceptsAck(expectedDeviceId: String, expectedRevision: StrategyRevision,
        actualDeviceId: String, actualRevision: StrategyRevision): Boolean =
        expectedRevision.valid && expectedDeviceId == actualDeviceId && expectedRevision == actualRevision

    fun apply(profile: PeerProfile, update: StrategyUpdate): PeerProfile {
        require(receive(profile, update) in setOf(Decision.APPLY, Decision.DUPLICATE))
        return profile.copy(role = update.role.opposite(), rangeMode = update.rangeMode,
            strictContentCheck = update.strictContentCheck,
            sinceEpochMillis = update.sinceEpochMillis, strategyRevision = update.revision,
            confirmedStrategyRevision = update.revision, queuedStrategy = null,
            queuedRole = if (update.revision >= (profile.queuedRoleRevision ?: StrategyRevision())) null else profile.queuedRole,
            queuedRoleRevision = profile.queuedRoleRevision?.takeIf { it > update.revision })
    }
}
