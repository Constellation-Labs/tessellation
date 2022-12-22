package org.tessellation.modules

import cats.effect.Async

import org.tessellation.dag.block.BlockValidator
import org.tessellation.dag.transaction.{TransactionChainValidator, TransactionValidator}
import org.tessellation.domain.statechannel.StateChannelValidator
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.security.SecurityProvider
import org.tessellation.schema.security.signature.SignedValidator
import org.tessellation.sdk.infrastructure.gossip.RumorValidator

object Validators {

  def make[F[_]: Async: KryoSerializer: SecurityProvider](
    seedlist: Option[Set[PeerId]]
  ) = {
    val signedValidator = SignedValidator.make[F]
    val transactionChainValidator = TransactionChainValidator.make[F]
    val transactionValidator = TransactionValidator.make[F](signedValidator)
    val blockValidator = BlockValidator.make[F](signedValidator, transactionChainValidator, transactionValidator)
    val rumorValidator = RumorValidator.make[F](seedlist, signedValidator)
    val stateChannelValidator = StateChannelValidator.make[F](signedValidator)

    new Validators[F](
      signedValidator,
      transactionChainValidator,
      transactionValidator,
      blockValidator,
      rumorValidator,
      stateChannelValidator
    ) {}
  }
}

sealed abstract class Validators[F[_]] private (
  val signedValidator: SignedValidator[F],
  val transactionChainValidator: TransactionChainValidator[F],
  val transactionValidator: TransactionValidator[F],
  val blockValidator: BlockValidator[F],
  val rumorValidator: RumorValidator[F],
  val stateChannelValidator: StateChannelValidator[F]
)
