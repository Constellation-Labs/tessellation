package io.constellationnetwork.node.shared.infrastructure.consensus

/** Safety authority for a concrete consensus key.
  *
  * The layer policy only selects legacy behavior. Once v35 is active, both layers leave the artifact-only lock behind and use the durable
  * full-value lock/QC journal.
  */
sealed trait ViewSafetyMode extends Product with Serializable

object ViewSafetyMode {
  case object LegacyFreezeAfterVote extends ViewSafetyMode
  case object LegacyPreserve extends ViewSafetyMode
  case object CertifiedFullValue extends ViewSafetyMode
}

/** Layer policy before v35 activation. This is local wiring, not a wire or state schema. */
sealed trait LegacyViewChangePolicy extends Product with Serializable {
  final def mode(certifiedConsensusActive: Boolean): ViewSafetyMode =
    if (certifiedConsensusActive) ViewSafetyMode.CertifiedFullValue
    else
      this match {
        case LegacyViewChangePolicy.FreezeAfterVote => ViewSafetyMode.LegacyFreezeAfterVote
        case LegacyViewChangePolicy.PreserveLegacy  => ViewSafetyMode.LegacyPreserve
      }
}

object LegacyViewChangePolicy {
  case object PreserveLegacy extends LegacyViewChangePolicy
  case object FreezeAfterVote extends LegacyViewChangePolicy
}
