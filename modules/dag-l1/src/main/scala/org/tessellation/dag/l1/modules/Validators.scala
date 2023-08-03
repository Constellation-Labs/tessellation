package org.tessellation.dag.l1.modules

import cats.effect.Async

import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.Block
import org.tessellation.schema.address.Address
import org.tessellation.schema.snapshot.{Snapshot, SnapshotInfo, StateProof}
import org.tessellation.sdk.domain.block.processing.BlockValidator
import org.tessellation.sdk.domain.seedlist.SeedlistEntry
import org.tessellation.sdk.domain.transaction.{ContextualTransactionValidator, TransactionChainValidator, TransactionValidator}
import org.tessellation.sdk.infrastructure.block.processing.BlockValidator
import org.tessellation.sdk.infrastructure.gossip.RumorValidator
import org.tessellation.security.SecurityProvider
import org.tessellation.security.signature.SignedValidator

object Validators {

  def make[
    F[_]: Async: KryoSerializer: SecurityProvider,
    B <: Block,
    P <: StateProof,
    S <: Snapshot[B],
    SI <: SnapshotInfo[P]
  ](
    storages: Storages[F, B, P, S, SI],
    seedlist: Option[Set[SeedlistEntry]]
  ): Validators[F, B] = {
    val signedValidator = SignedValidator.make[F]
    val transactionChainValidator = TransactionChainValidator.make[F]
    val transactionValidator = TransactionValidator.make[F](signedValidator)
    val blockValidator =
      BlockValidator.make[F, B](signedValidator, transactionChainValidator, transactionValidator)
    val contextualTransactionValidator = ContextualTransactionValidator.make[F](
      transactionValidator,
      (address: Address) => storages.transaction.getLastAcceptedReference(address)
    )
    val rumorValidator = RumorValidator.make[F](seedlist, signedValidator)

    new Validators[F, B](
      signedValidator,
      blockValidator,
      transactionValidator,
      contextualTransactionValidator,
      rumorValidator
    ) {}
  }
}

sealed abstract class Validators[F[_], B <: Block] private (
  val signed: SignedValidator[F],
  val block: BlockValidator[F, B],
  val transaction: TransactionValidator[F],
  val transactionContextual: ContextualTransactionValidator[F],
  val rumorValidator: RumorValidator[F]
)
