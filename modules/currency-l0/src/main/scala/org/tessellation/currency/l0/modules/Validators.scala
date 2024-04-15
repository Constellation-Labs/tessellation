package org.tessellation.currency.l0.modules

import cats.effect.Async

import org.tessellation.node.shared.domain.block.processing.BlockValidator
import org.tessellation.node.shared.domain.seedlist.SeedlistEntry
import org.tessellation.node.shared.domain.transaction.{TransactionChainValidator, TransactionValidator}
import org.tessellation.node.shared.infrastructure.block.processing.BlockValidator
import org.tessellation.node.shared.infrastructure.gossip.RumorValidator
import org.tessellation.security.signature.SignedValidator
import org.tessellation.security.{Hasher, SecurityProvider}

object Validators {

  def make[F[_]: Async: SecurityProvider](
    seedlist: Option[Set[SeedlistEntry]],
    txHasher: Hasher[F]
  ): Validators[F] = {
    val signedValidator = SignedValidator.make[F]
    val transactionChainValidator = TransactionChainValidator.make[F](txHasher)
    val transactionValidator = TransactionValidator.make[F](signedValidator, txHasher)
    val blockValidator =
      BlockValidator.make[F](signedValidator, transactionChainValidator, transactionValidator, txHasher)
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
  val transactionChainValidator: TransactionChainValidator[F],
  val transactionValidator: TransactionValidator[F],
  val blockValidator: BlockValidator[F],
  val rumorValidator: RumorValidator[F]
)
