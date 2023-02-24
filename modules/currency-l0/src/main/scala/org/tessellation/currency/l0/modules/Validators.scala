package org.tessellation.currency.l0.modules

import cats.effect.Async

import org.tessellation.currency.schema.currency.{CurrencyBlock, CurrencyTransaction}
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.domain.block.processing.BlockValidator
import org.tessellation.sdk.domain.transaction.{TransactionChainValidator, TransactionValidator}
import org.tessellation.sdk.infrastructure.block.processing.BlockValidator
import org.tessellation.sdk.infrastructure.gossip.RumorValidator
import org.tessellation.security.SecurityProvider
import org.tessellation.security.signature.SignedValidator

object Validators {

  def make[F[_]: Async: KryoSerializer: SecurityProvider](
    seedlist: Option[Set[PeerId]]
  ): Validators[F] = {
    val signedValidator = SignedValidator.make[F]
    val transactionChainValidator = TransactionChainValidator.make[F, CurrencyTransaction]
    val transactionValidator = TransactionValidator.make[F, CurrencyTransaction](signedValidator)
    val blockValidator =
      BlockValidator.make[F, CurrencyTransaction, CurrencyBlock](signedValidator, transactionChainValidator, transactionValidator)
    val rumorValidator = RumorValidator.make[F](seedlist, signedValidator)

    new Validators[F](
      signedValidator,
      transactionChainValidator,
      transactionValidator,
      blockValidator,
      rumorValidator
    ) {}
  }
}

sealed abstract class Validators[F[_]] private (
  val signedValidator: SignedValidator[F],
  val transactionChainValidator: TransactionChainValidator[F, CurrencyTransaction],
  val transactionValidator: TransactionValidator[F, CurrencyTransaction],
  val blockValidator: BlockValidator[F, CurrencyTransaction, CurrencyBlock],
  val rumorValidator: RumorValidator[F]
)
