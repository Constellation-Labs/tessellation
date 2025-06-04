package io.constellationnetwork.currency.l0.infrastructure.snapshot

import cats.Parallel
import cats.effect.Async
import cats.syntax.all._

import scala.util.control.NoStackTrace

import io.constellationnetwork.currency.l0.domain.snapshot.storages.CurrencySnapshotCleanupStorage
import io.constellationnetwork.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshotInfo, CurrencySnapshotStateProof}
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.node.shared.infrastructure.snapshot.storage.{
  SnapshotInfoLocalFileSystemStorage,
  SnapshotLocalFileSystemStorage
}
import io.constellationnetwork.schema._
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash

import org.typelevel.log4cats.slf4j.Slf4jLogger

object CurrencySnapshotCleanupStorage {
  def make[F[_]: Async: KryoSerializer](
    persistedStorage: SnapshotLocalFileSystemStorage[F, CurrencyIncrementalSnapshot],
    snapshotInfoStorage: SnapshotInfoLocalFileSystemStorage[F, CurrencySnapshotStateProof, CurrencySnapshotInfo]
  ): CurrencySnapshotCleanupStorage[F] =
    new CurrencySnapshotCleanupStorage[F] {
      private val logger = Slf4jLogger.getLoggerFromName[F]("CurrencySnapshotCleanupStorage")
      case object CurrencyCleanupError extends NoStackTrace

      def deletePersisted(hash: Hash, ordinal: SnapshotOrdinal): F[Unit] =
        persistedStorage.delete(ordinal) >>
          persistedStorage.delete(hash)

      def cleanupAbove(
        ordinal: SnapshotOrdinal
      )(implicit hs: HasherSelector[F]): F[Unit] = {
        val deleteSnapshotInfo = for {
          _ <- logger.info(s"Starting cleanup above ordinal ${ordinal.show}")
          _ <- snapshotInfoStorage
            .deleteAbove(ordinal)
            .handleErrorWith(err =>
              logger.error(err)(s"Error while deleting snapshot_info files above ${ordinal.show}") >>
                Async[F].raiseError(err)
            )
          _ <- logger.info(s"Successfully deleted snapshot_info files above ordinal ${ordinal.show}")
        } yield ()

        val cleanupAboveOrdinal = persistedStorage
          .cleanupAboveOrdinal(ordinal, deletePersisted)
          .handleErrorWith { err =>
            logger.error(err)("Error during cleanup snapshot of the metagraph") >>
              CurrencyCleanupError.raiseError[F, Unit]
          }

        val verify =
          persistedStorage
            .findAbove(ordinal)
            .compile
            .count
            .flatMap { remainingFiles =>
              if (remainingFiles > 0) {
                logger.error(s"Cleanup incomplete: $remainingFiles files still remain above ordinal ${ordinal.show}") >>
                  Async[F].raiseError[Unit](
                    new IllegalStateException(s"Cleanup incomplete: $remainingFiles files still remain above ordinal ${ordinal.show}")
                  )
              } else {
                logger.info(s"Cleanup successful: No files remain above ordinal ${ordinal.show}") >> ().pure
              }
            }

        deleteSnapshotInfo >>
          cleanupAboveOrdinal >>
          verify
      }
    }
}
