package io.constellationnetwork.dag.l1.domain.swap.block

import cats.data.EitherT
import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.option._
import cats.syntax.show._

import scala.util.control.NoStackTrace

import io.constellationnetwork.currency.schema.currency.CurrencyIncrementalSnapshot
import io.constellationnetwork.dag.l1.domain.address.storage.AddressStorage
import io.constellationnetwork.node.shared.domain.collateral.LatestBalances
import io.constellationnetwork.node.shared.domain.snapshot.storage.LastSnapshotStorage
import io.constellationnetwork.node.shared.domain.swap.AllowSpendStorage
import io.constellationnetwork.node.shared.domain.swap.block._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.{Amount, Balance}
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.snapshot.{Snapshot, SnapshotInfo, StateProof}
import io.constellationnetwork.schema.swap.{AllowSpendBlock, AllowSpendReference}
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, SnapshotOrdinal}
import io.constellationnetwork.security.hash.ProofsHash
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.{Hashed, Hasher}

import eu.timepit.refined.auto._

trait AllowSpendBlockService[F[_]] {
  def accept(signedBlock: Signed[AllowSpendBlock], snapshotOrdinal: SnapshotOrdinal)(
    implicit hasher: Hasher[F]
  ): F[Unit]
}

object AllowSpendBlockService {

  def make[F[_]: Async, P <: StateProof, S <: Snapshot, SI <: SnapshotInfo[P]](
    allowSpendBlockAcceptanceManager: AllowSpendBlockAcceptanceManager[F],
    addressStorage: AddressStorage[F],
    allowSpendBlockStorage: AllowSpendBlockStorage[F],
    allowSpendStorage: AllowSpendStorage[F],
    collateral: Amount,
    lastSnapshotStorage: LastSnapshotStorage[F, S, SI] with LatestBalances[F]
  ): AllowSpendBlockService[F] =
    new AllowSpendBlockService[F] {

      def accept(signedBlock: Signed[AllowSpendBlock], snapshotOrdinal: SnapshotOrdinal)(
        implicit hasher: Hasher[F]
      ): F[Unit] =
        for {
          lastGlobalEpochProgress <- lastSnapshotStorage.get.map {
            case Some(snapshot) =>
              snapshot.signed.value match {
                case cis: CurrencyIncrementalSnapshot =>
                  cis.globalSyncView.map(_.epochProgress).getOrElse(EpochProgress.MinValue)
                case gis: GlobalIncrementalSnapshot =>
                  gis.epochProgress
                case _ =>
                  EpochProgress.MinValue
              }
            case None =>
              EpochProgress.MinValue
          }
          result <- signedBlock.toHashed.flatMap { hashedBlock =>
            EitherT(
              allowSpendBlockAcceptanceManager
                .acceptBlock(signedBlock, context, snapshotOrdinal, shouldValidateCollateral = true, lastGlobalEpochProgress.some)
            )
              .leftSemiflatMap(processAcceptanceError(hashedBlock))
              .semiflatMap(processAcceptanceSuccess(hashedBlock))
              .rethrowT
          }
        } yield result

      private val context: AllowSpendBlockAcceptanceContext[F] = new AllowSpendBlockAcceptanceContext[F] {

        def getBalance(address: Address): F[Option[Balance]] =
          addressStorage.getBalance(address).map(_.some)

        def getLastTxRef(address: Address): F[Option[AllowSpendReference]] =
          allowSpendStorage.getLastProcessedAllowSpend(address).map(_.ref.some)

        def getInitialTxRef: AllowSpendReference =
          allowSpendStorage.getInitialTx.ref

        def getCollateral: Amount = collateral
      }

      private def processAcceptanceSuccess(
        hashedBlock: Hashed[AllowSpendBlock]
      )(contextUpdate: AllowSpendBlockAcceptanceContextUpdate)(implicit hasher: Hasher[F]): F[Unit] =
        for {
          hashedTransactions <- hashedBlock.signed.transactions.toNonEmptyList
            .sortBy(_.ordinal)
            .traverse(_.toHashed)

          _ <- hashedTransactions.traverse(allowSpendStorage.accept)
          _ <- allowSpendBlockStorage.accept(hashedBlock)
          _ <- addressStorage.updateBalances(contextUpdate.balances)
        } yield ()

      private def processAcceptanceError(
        hashedBlock: Hashed[AllowSpendBlock]
      )(reason: AllowSpendBlockNotAcceptedReason): F[AllowSpendBlockAcceptanceError] =
        allowSpendBlockStorage
          .postpone(hashedBlock)
          .as(AllowSpendBlockAcceptanceError(hashedBlock.proofsHash, reason))

    }

  case class AllowSpendBlockAcceptanceError(blockReference: ProofsHash, reason: AllowSpendBlockNotAcceptedReason) extends NoStackTrace {
    override def getMessage: String = s"Block ${blockReference.show} could not be accepted ${reason.show}"
  }
}
