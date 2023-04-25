package org.tessellation.sdk.modules

import cats.effect.Async

import org.tessellation.currency.schema.currency.{CurrencyBlock, CurrencyTransaction}
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.address.Address
import org.tessellation.schema.block.DAGBlock
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.transaction.DAGTransaction
import org.tessellation.sdk.domain.block.processing.BlockValidator
import org.tessellation.sdk.domain.statechannel.StateChannelValidator
import org.tessellation.sdk.domain.transaction.{TransactionChainValidator, TransactionValidator}
import org.tessellation.sdk.infrastructure.block.processing.BlockValidator
import org.tessellation.sdk.infrastructure.gossip.RumorValidator
import org.tessellation.security.SecurityProvider
import org.tessellation.security.signature.SignedValidator

object SdkValidators {

  def make[F[_]: Async: KryoSerializer: SecurityProvider](
    seedlist: Option[Set[PeerId]],
    stateChannelSeedlist: Option[Set[Address]]
  ) = {
    val signedValidator = SignedValidator.make[F]
    val transactionChainValidator = TransactionChainValidator.make[F, DAGTransaction]
    val transactionValidator = TransactionValidator.make[F, DAGTransaction](signedValidator)
    val blockValidator = BlockValidator.make[F, DAGTransaction, DAGBlock](signedValidator, transactionChainValidator, transactionValidator)
    val currencyTransactionChainValidator = TransactionChainValidator.make[F, CurrencyTransaction]
    val currencyTransactionValidator = TransactionValidator.make[F, CurrencyTransaction](signedValidator)
    val currencyBlockValidator = BlockValidator
      .make[F, CurrencyTransaction, CurrencyBlock](signedValidator, currencyTransactionChainValidator, currencyTransactionValidator)
    val rumorValidator = RumorValidator.make[F](seedlist, signedValidator)
    val stateChannelValidator = StateChannelValidator.make[F](signedValidator, stateChannelSeedlist)

    new SdkValidators[F](
      signedValidator,
      transactionChainValidator,
      transactionValidator,
      currencyTransactionChainValidator,
      currencyTransactionValidator,
      blockValidator,
      currencyBlockValidator,
      rumorValidator,
      stateChannelValidator
    ) {}
  }
}

sealed abstract class SdkValidators[F[_]] private (
  val signedValidator: SignedValidator[F],
  val transactionChainValidator: TransactionChainValidator[F, DAGTransaction],
  val transactionValidator: TransactionValidator[F, DAGTransaction],
  val currencyTransactionChainValidator: TransactionChainValidator[F, CurrencyTransaction],
  val currencyTransactionValidator: TransactionValidator[F, CurrencyTransaction],
  val blockValidator: BlockValidator[F, DAGTransaction, DAGBlock],
  val currencyBlockValidator: BlockValidator[F, CurrencyTransaction, CurrencyBlock],
  val rumorValidator: RumorValidator[F],
  val stateChannelValidator: StateChannelValidator[F]
)
