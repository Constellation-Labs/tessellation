package org.tessellation.dag.l1.domain.consensus.block

import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

@derive(encoder, decoder)
sealed trait CancellationReason

object CancellationReason {
  case object ReceivedProposalForNonExistentOwnRound extends CancellationReason
  case object MissingRoundPeers extends CancellationReason
  case object CreatedInvalidBlock extends CancellationReason
  case object PeerCancelled extends CancellationReason
}
