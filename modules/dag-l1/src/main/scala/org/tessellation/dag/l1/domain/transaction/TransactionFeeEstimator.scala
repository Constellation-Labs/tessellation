package org.tessellation.dag.l1.domain.transaction

import org.tessellation.schema.transaction.{Transaction, TransactionFee}
import org.tessellation.security.Hashed

trait TransactionFeeEstimator[F[_]] {
  def estimate(transaction: Hashed[Transaction]): F[TransactionFee]
}
