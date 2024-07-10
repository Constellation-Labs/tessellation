package io.constellationnetwork.dag.l1.domain.transaction

import io.constellationnetwork.schema.transaction.{Transaction, TransactionFee}
import io.constellationnetwork.security.Hashed

trait TransactionFeeEstimator[F[_]] {
  def estimate(transaction: Hashed[Transaction]): F[TransactionFee]
}
