package org.tessellation.dag.l1.modules

import cats.effect.Async

import org.tessellation.dag.block.BlockValidator
import org.tessellation.dag.transaction.{ContextualTransactionValidator, TransactionChainValidator, TransactionValidator}
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.address.Address
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.security.SecurityProvider
import org.tessellation.schema.security.signature.SignedValidator
import org.tessellation.sdk.infrastructure.gossip.RumorValidator

object Validators {

  def make[F[_]: Async: KryoSerializer: SecurityProvider](
    storages: Storages[F],
    seedlist: Option[Set[PeerId]]
  ): Validators[F] = {
    val signedValidator = SignedValidator.make[F]
    val transactionChainValidator = TransactionChainValidator.make[F]
    val transactionValidator = TransactionValidator.make[F](signedValidator)
    val blockValidator =
      BlockValidator.make[F](signedValidator, transactionChainValidator, transactionValidator)
    val contextualTransactionValidator = ContextualTransactionValidator.make[F](
      transactionValidator,
      (address: Address) => storages.transaction.getLastAcceptedReference(address)
    )
    val rumorValidator = RumorValidator.make[F](seedlist, signedValidator)

    new Validators[F](
      signedValidator,
      blockValidator,
      transactionValidator,
      contextualTransactionValidator,
      rumorValidator
    ) {}
  }
}

sealed abstract class Validators[F[_]] private (
  val signed: SignedValidator[F],
  val block: BlockValidator[F],
  val transaction: TransactionValidator[F],
  val transactionContextual: ContextualTransactionValidator[F],
  val rumorValidator: RumorValidator[F]
)
