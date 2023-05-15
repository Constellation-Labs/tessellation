package org.tessellation.sdk.modules

import cats.effect.Async

import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.address.Address
import org.tessellation.schema.peer.PeerId
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
    val transactionChainValidator = TransactionChainValidator.make[F]
    val transactionValidator = TransactionValidator.make[F](signedValidator)
    val blockValidator = BlockValidator.make[F](signedValidator, transactionChainValidator, transactionValidator)
    val currencyTransactionChainValidator = TransactionChainValidator.make[F]
    val currencyTransactionValidator = TransactionValidator.make[F](signedValidator)
    val currencyBlockValidator = BlockValidator
      .make[F](signedValidator, currencyTransactionChainValidator, currencyTransactionValidator)
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
  val transactionChainValidator: TransactionChainValidator[F],
  val transactionValidator: TransactionValidator[F],
  val currencyTransactionChainValidator: TransactionChainValidator[F],
  val currencyTransactionValidator: TransactionValidator[F],
  val blockValidator: BlockValidator[F],
  val currencyBlockValidator: BlockValidator[F],
  val rumorValidator: RumorValidator[F],
  val stateChannelValidator: StateChannelValidator[F]
)
