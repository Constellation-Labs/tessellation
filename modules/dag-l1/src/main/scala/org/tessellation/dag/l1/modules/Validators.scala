package org.tessellation.dag.l1.modules

import cats.effect.Async

import org.tessellation.dag.l1.domain.transaction.{
  ContextualTransactionValidator,
  CustomContextualTransactionValidator,
  TransactionLimitConfig
}
import org.tessellation.node.shared.domain.block.processing.BlockValidator
import org.tessellation.node.shared.domain.seedlist.SeedlistEntry
import org.tessellation.node.shared.domain.transaction._
import org.tessellation.node.shared.infrastructure.block.processing.BlockValidator
import org.tessellation.node.shared.infrastructure.gossip.RumorValidator
import org.tessellation.schema.snapshot.{Snapshot, SnapshotInfo, StateProof}
import org.tessellation.security.signature.SignedValidator
import org.tessellation.security.{Hasher, SecurityProvider}

object Validators {

  def make[
    F[_]: Async: SecurityProvider,
    P <: StateProof,
    S <: Snapshot,
    SI <: SnapshotInfo[P]
  ](
    seedlist: Option[Set[SeedlistEntry]],
    transactionLimitConfig: TransactionLimitConfig,
    customContextualTransactionValidator: Option[CustomContextualTransactionValidator],
    txHasher: Hasher[F]
  ): Validators[F] = {
    val signedValidator = SignedValidator.make[F]
    val transactionChainValidator = TransactionChainValidator.make[F](txHasher)
    val transactionValidator = TransactionValidator.make[F](signedValidator, txHasher)
    val blockValidator =
      BlockValidator.make[F](signedValidator, transactionChainValidator, transactionValidator, txHasher)

    val contextualTransactionValidator = ContextualTransactionValidator.make(
      transactionLimitConfig,
      customContextualTransactionValidator
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
  val transactionContextual: ContextualTransactionValidator,
  val rumorValidator: RumorValidator[F]
)
