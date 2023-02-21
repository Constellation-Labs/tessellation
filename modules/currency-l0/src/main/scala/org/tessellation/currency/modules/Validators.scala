package org.tessellation.currency.modules

import cats.effect.Async

import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.Block
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.transaction.Transaction
import org.tessellation.sdk.domain.block.processing.BlockValidator
import org.tessellation.sdk.domain.transaction.{TransactionChainValidator, TransactionValidator}
import org.tessellation.sdk.infrastructure.block.processing.BlockValidator
import org.tessellation.sdk.infrastructure.gossip.RumorValidator
import org.tessellation.security.SecurityProvider
import org.tessellation.security.signature.SignedValidator

object Validators {

  def make[F[_]: Async: KryoSerializer: SecurityProvider, T <: Transaction, B <: Block[T]](
    seedlist: Option[Set[PeerId]]
  ) = {
    val signedValidator = SignedValidator.make[F]
    val transactionChainValidator = TransactionChainValidator.make[F, T]
    val transactionValidator = TransactionValidator.make[F, T](signedValidator)
    val blockValidator = BlockValidator.make[F, T, B](signedValidator, transactionChainValidator, transactionValidator)
    val rumorValidator = RumorValidator.make[F](seedlist, signedValidator)

    new Validators[F, T, B](
      signedValidator,
      transactionChainValidator,
      transactionValidator,
      blockValidator,
      rumorValidator
    ) {}
  }
}

sealed abstract class Validators[F[_], T <: Transaction, B <: Block[T]] private (
  val signedValidator: SignedValidator[F],
  val transactionChainValidator: TransactionChainValidator[F, T],
  val transactionValidator: TransactionValidator[F, T],
  val blockValidator: BlockValidator[F, T, B],
  val rumorValidator: RumorValidator[F]
)
