package org.tessellation.dag.l1.modules

import cats.effect.Async

import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.address.Address
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.snapshot.{Snapshot, SnapshotInfo, StateProof}
import org.tessellation.sdk.domain.block.processing.BlockValidator
import org.tessellation.sdk.domain.transaction.{ContextualTransactionValidator, TransactionChainValidator, TransactionValidator}
import org.tessellation.sdk.infrastructure.block.processing.BlockValidator
import org.tessellation.sdk.infrastructure.gossip.RumorValidator
import org.tessellation.security.SecurityProvider
import org.tessellation.security.signature.SignedValidator

object Validators {

  def make[
    F[_]: Async: KryoSerializer: SecurityProvider,
    P <: StateProof,
    S <: Snapshot,
    SI <: SnapshotInfo[P]
  ](
    storages: Storages[F, P, S, SI],
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
