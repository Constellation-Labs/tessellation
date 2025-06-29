package io.constellationnetwork.dag.l1.modules

import cats.effect.Async

import io.constellationnetwork.dag.l1.domain.transaction.{
  ContextualTransactionValidator,
  CustomContextualTransactionValidator,
  TransactionLimitConfig
}
import io.constellationnetwork.domain.seedlist.SeedlistEntry
import io.constellationnetwork.node.shared.config.types.SharedConfig
import io.constellationnetwork.node.shared.domain.block.processing.BlockValidator
import io.constellationnetwork.node.shared.domain.swap.block.AllowSpendBlockValidator
import io.constellationnetwork.node.shared.domain.swap.{AllowSpendChainValidator, AllowSpendValidator, ContextualAllowSpendValidator}
import io.constellationnetwork.node.shared.domain.tokenlock.block.TokenLockBlockValidator
import io.constellationnetwork.node.shared.domain.tokenlock.{ContextualTokenLockValidator, TokenLockChainValidator, TokenLockValidator}
import io.constellationnetwork.node.shared.domain.transaction._
import io.constellationnetwork.node.shared.infrastructure.block.processing.BlockValidator
import io.constellationnetwork.node.shared.infrastructure.gossip.RumorValidator
import io.constellationnetwork.schema.snapshot.{Snapshot, SnapshotInfo, StateProof}
import io.constellationnetwork.schema.swap.CurrencyId
import io.constellationnetwork.security.signature.SignedValidator
import io.constellationnetwork.security.{Hasher, SecurityProvider}

object Validators {

  def make[
    F[_]: Async: SecurityProvider: Hasher,
    P <: StateProof,
    S <: Snapshot,
    SI <: SnapshotInfo[P]
  ](
    cfg: SharedConfig,
    seedlist: Option[Set[SeedlistEntry]],
    transactionLimitConfig: TransactionLimitConfig,
    customContextualTransactionValidator: Option[CustomContextualTransactionValidator],
    txHasher: Hasher[F],
    currencyId: Option[CurrencyId]
  ): Validators[F] = {
    val signedValidator = SignedValidator.make[F]
    val transactionChainValidator = TransactionChainValidator.make[F](txHasher)
    val transactionValidator = TransactionValidator.make[F](cfg.addresses, signedValidator, txHasher)
    val blockValidator =
      BlockValidator.make[F](signedValidator, transactionChainValidator, transactionValidator, txHasher)

    val contextualTransactionValidator = ContextualTransactionValidator.make(
      transactionLimitConfig,
      customContextualTransactionValidator
    )

    val rumorValidator = RumorValidator.make[F](seedlist, signedValidator)

    val allowSpendValidator = AllowSpendValidator.make[F](signedValidator)
    val allowSpendChainValidator = AllowSpendChainValidator.make[F]
    val contextualAllowSpendValidator = ContextualAllowSpendValidator.make(currencyId, None, cfg.allowSpends)
    val allowSpendBlockValidator = AllowSpendBlockValidator.make[F](signedValidator, allowSpendChainValidator, allowSpendValidator)

    val tokenLockValidator = TokenLockValidator.make[F](signedValidator)
    val tokenLockChainValidator = TokenLockChainValidator.make[F]
    val contextualTokenLockValidator = ContextualTokenLockValidator.make(None, cfg.tokenLocks, currencyId)
    val tokenLockBlockValidator = TokenLockBlockValidator.make[F](signedValidator, tokenLockChainValidator, tokenLockValidator)

    new Validators[F](
      signedValidator,
      blockValidator,
      transactionValidator,
      contextualTransactionValidator,
      rumorValidator,
      allowSpendValidator,
      contextualAllowSpendValidator,
      allowSpendBlockValidator,
      tokenLockValidator,
      contextualTokenLockValidator,
      tokenLockBlockValidator
    ) {}
  }
}

sealed abstract class Validators[F[_]] private (
  val signed: SignedValidator[F],
  val block: BlockValidator[F],
  val transaction: TransactionValidator[F],
  val transactionContextual: ContextualTransactionValidator,
  val rumorValidator: RumorValidator[F],
  val allowSpend: AllowSpendValidator[F],
  val allowSpendContextual: ContextualAllowSpendValidator,
  val allowSpendBlock: AllowSpendBlockValidator[F],
  val tokenLock: TokenLockValidator[F],
  val tokenLockContextual: ContextualTokenLockValidator,
  val tokenLockBlock: TokenLockBlockValidator[F]
)
