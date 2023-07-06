package org.tessellation.currency.dataApplication

import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

@derive(encoder, decoder)
sealed trait DataCancellationReason
object DataCancellationReason {
  case object ReceivedProposalForNonExistentOwnRound extends DataCancellationReason
  case object MissingRoundPeers extends DataCancellationReason
  case object CreatedInvalidBlock extends DataCancellationReason
  case object CreatedEmptyBlock extends DataCancellationReason
  case object PeerCancelled extends DataCancellationReason
}
