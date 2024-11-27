package io.constellationnetwork.currency.tokenlock

import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

@derive(encoder, decoder)
sealed trait TokenLockCancellationReason
object TokenLockCancellationReason {
  case object ReceivedProposalForNonExistentOwnRound extends TokenLockCancellationReason
  case object MissingRoundPeers extends TokenLockCancellationReason
  case object CreatedInvalidBlock extends TokenLockCancellationReason
  case object CreatedEmptyBlock extends TokenLockCancellationReason
  case object PeerCancelled extends TokenLockCancellationReason
}
