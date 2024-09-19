package io.constellationnetwork.currency.swap

import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

@derive(encoder, decoder)
sealed trait SwapCancellationReason
object SwapCancellationReason {
  case object ReceivedProposalForNonExistentOwnRound extends SwapCancellationReason
  case object MissingRoundPeers extends SwapCancellationReason
  case object CreatedInvalidBlock extends SwapCancellationReason
  case object CreatedEmptyBlock extends SwapCancellationReason
  case object PeerCancelled extends SwapCancellationReason
}
