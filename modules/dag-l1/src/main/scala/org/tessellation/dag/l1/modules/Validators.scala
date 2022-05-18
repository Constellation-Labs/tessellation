package org.tessellation.dag.l1.modules

import cats.effect.Async

import org.tessellation.dag.block.BlockValidator
import org.tessellation.dag.transaction.{TransactionChainValidator, TransactionValidator}
import org.tessellation.kryo.KryoSerializer
import org.tessellation.security.SecurityProvider
import org.tessellation.security.signature.SignedValidator

object Validators {

  def make[F[_]: Async: KryoSerializer: SecurityProvider]: Validators[F] = {
    val signedValidator = SignedValidator.make[F]
    val transactionChainValidator = TransactionChainValidator.make[F]
    val transactionValidator = TransactionValidator.make[F](signedValidator)
    val blockValidator = BlockValidator.make[F](signedValidator, transactionChainValidator, transactionValidator)

    new Validators[F](signedValidator, blockValidator, transactionValidator) {}
  }
}

sealed abstract class Validators[F[_]] private (
  val signed: SignedValidator[F],
  val block: BlockValidator[F],
  val transaction: TransactionValidator[F]
)
