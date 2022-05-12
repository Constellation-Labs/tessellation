package org.tessellation.modules

import cats.effect.Async

import org.tessellation.config.types.SnapshotConfig
import org.tessellation.dag.block.BlockValidator
import org.tessellation.dag.transaction.{TransactionChainValidator, TransactionValidator}
import org.tessellation.infrastructure.snapshot.SnapshotPreconditionsValidator
import org.tessellation.kryo.KryoSerializer
import org.tessellation.security.SecurityProvider
import org.tessellation.security.signature.SignedValidator

object Validators {

  def make[F[_]: Async: KryoSerializer: SecurityProvider](snapshotConfig: SnapshotConfig) = {
    val snapshotPreconditions = new SnapshotPreconditionsValidator[F](snapshotConfig)
    val signedValidator = SignedValidator.make[F]
    val transactionChainValidator = TransactionChainValidator.make[F]
    val transactionValidator = TransactionValidator.make[F](signedValidator)
    val blockValidator = BlockValidator.make[F](signedValidator, transactionChainValidator, transactionValidator)

    new Validators[F](
      snapshotPreconditions,
      signedValidator,
      transactionChainValidator,
      transactionValidator,
      blockValidator
    ) {}
  }
}

sealed abstract class Validators[F[_]] private (
  val snapshotPreconditions: SnapshotPreconditionsValidator[F],
  val signedValidator: SignedValidator[F],
  val transactionChainValidator: TransactionChainValidator[F],
  val transactionValidator: TransactionValidator[F],
  val blockValidator: BlockValidator[F]
)
