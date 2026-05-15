package io.constellationnetwork.node.shared.domain.snapshot.programs

import cats.syntax.show._

import scala.util.control.NoStackTrace

import io.constellationnetwork.schema.SnapshotOrdinal

/** Typed failures thrown by snapshot acceptance and persistence pipelines. They replace bare `throw new RuntimeException("...")` whose
  * messages used to be re-classified by string-prefix matching in the downstream `Download` classifier.
  */
sealed abstract class SnapshotFailure(message: String) extends RuntimeException(message) with NoStackTrace

object SnapshotFailure {

  final case class CleanupIncomplete(remaining: Long, ordinal: SnapshotOrdinal)
      extends SnapshotFailure(
        s"Cleanup incomplete: $remaining files still remain above ordinal ${ordinal.show}"
      )

  sealed abstract class BalanceArithmeticError(category: String, cause: String)
      extends SnapshotFailure(s"Balance arithmetic error updating balances by $category: $cause")

  object BalanceArithmeticError {
    final case class AllowSpends(cause: String) extends BalanceArithmeticError("allow spends", cause)
    final case class TokenLocks(cause: String) extends BalanceArithmeticError("token locks", cause)
    final case class SpendTransactions(cause: String) extends BalanceArithmeticError("spend transactions", cause)
  }

  final case class TokenUnlockGenerationFailed(cause: String) extends SnapshotFailure(s"Error generating token unlocks: $cause")
}
