package org.tessellation.dag.l1.modules

import cats.effect.Async

import org.tessellation.dag.block.BlockValidator
import org.tessellation.dag.block.config.BlockValidatorConfig
import org.tessellation.dag.domain.block.{BlockReference, DAGBlock}
import org.tessellation.dag.transaction.TransactionValidator
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Balance
import org.tessellation.schema.transaction.TransactionReference
import org.tessellation.security.SecurityProvider

object Validators {

  def make[F[_]: Async: KryoSerializer: SecurityProvider](
    storages: Storages[F],
    blockValidatorConfig: BlockValidatorConfig
  ): Validators[F] = {
    val transactionValidator = new TransactionValidator[F] {
      def getBalance(address: Address): F[Balance] =
        storages.address.getBalance(address)

      def getLastAcceptedTransactionRef(address: Address): F[TransactionReference] =
        storages.transaction.getLastAcceptedReference(address)
    }
    val blockValidator = new BlockValidator[F](transactionValidator, blockValidatorConfig) {
      def getLastAcceptedTransactionRef(address: Address): F[TransactionReference] =
        storages.transaction.getLastAcceptedReference(address)

      def areParentsAccepted(block: DAGBlock): F[Map[BlockReference, Boolean]] =
        storages.block.areParentsAccepted(block)
    }

    new Validators[F](blockValidator, transactionValidator) {}
  }
}

sealed abstract class Validators[F[_]] private (
  val block: BlockValidator[F],
  val transaction: TransactionValidator[F]
)
