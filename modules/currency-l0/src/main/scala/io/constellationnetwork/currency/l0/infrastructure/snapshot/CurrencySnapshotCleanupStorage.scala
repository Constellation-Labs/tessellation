package io.constellationnetwork.currency.l0.infrastructure.snapshot

import cats.effect.Async
import cats.syntax.all._

import scala.util.control.NoStackTrace

import io.constellationnetwork.currency.l0.domain.snapshot.storages.CurrencySnapshotCleanupStorage
import io.constellationnetwork.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshotInfo, CurrencySnapshotStateProof}
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.node.shared.domain.snapshot.programs.SnapshotFailure
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
        // Remove the content-addressed path first. Both indexes are hardlinks,
        // so the ordinal still owns readable bytes if the process dies between
        // deletes and a retry can rediscover/recompute the same hash. Deleting
        // the ordinal first can strand a remotely servable future hash with no
        // ordinal index by which recovery can identify it.
        persistedStorage.delete(hash) >>
          persistedStorage.delete(ordinal)

      private def cleanup(
        ordinal: SnapshotOrdinal,
        anchorHash: Option[Hash]
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

        val cleanupAboveOrdinal = anchorHash
          .fold(persistedStorage.cleanupAboveOrdinal(ordinal, deletePersisted))(
            persistedStorage.cleanupCanonicalSuffix(ordinal, _, deletePersisted)
          )
          .handleErrorWith {
            case _: java.nio.file.NoSuchFileException =>
              // Files already cleaned up - not an error
              Async[F].unit
            case err =>
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
                  Async[F].raiseError[Unit](SnapshotFailure.CleanupIncomplete(remainingFiles, ordinal))
              } else {
                logger.info(s"Cleanup successful: No files remain above ordinal ${ordinal.show}") >> ().pure
              }
            }

        deleteSnapshotInfo >>
          cleanupAboveOrdinal >>
          verify
      }

      def cleanupAbove(ordinal: SnapshotOrdinal)(implicit hs: HasherSelector[F]): F[Unit] =
        cleanup(ordinal, none)

      def cleanupCanonicalSuffix(ordinal: SnapshotOrdinal, anchorHash: Hash)(implicit hs: HasherSelector[F]): F[Unit] =
        cleanup(ordinal, anchorHash.some)
    }
}
