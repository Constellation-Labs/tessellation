package io.constellationnetwork.node.shared.infrastructure.mempool

import cats.Show

/** Reasons an event may be rejected from entering the mempool.
  */
sealed trait MempoolRejectionReason

object MempoolRejectionReason {

  /** Event not added because mempool is at capacity */
  case object MempoolFull extends MempoolRejectionReason

  /** Event has already been added to the mempool (duplicate) */
  case object Duplicate extends MempoolRejectionReason

  /** Event was previously rejected and is in the rejection cache */
  case object PreviouslyRejected extends MempoolRejectionReason

  /** Event has exceeded its TTL */
  case object Expired extends MempoolRejectionReason

  implicit val show: Show[MempoolRejectionReason] = Show.show {
    case MempoolFull        => "MempoolFull"
    case Duplicate          => "Duplicate"
    case PreviouslyRejected => "PreviouslyRejected"
    case Expired            => "Expired"
  }
}
