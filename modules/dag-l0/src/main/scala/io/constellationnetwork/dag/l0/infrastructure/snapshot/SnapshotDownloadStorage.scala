package io.constellationnetwork.dag.l0.infrastructure.snapshot

import cats.Parallel
import cats.effect.Async
import cats.effect.syntax.all._
import cats.syntax.all._

import io.constellationnetwork.cutoff.{LogarithmicOrdinalCutoff, OrdinalCutoff}
import io.constellationnetwork.dag.l0.domain.snapshot.storages.SnapshotDownloadStorage
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.merkletree.StateProofValidator
import io.constellationnetwork.node.shared.infrastructure.snapshot.storage.{
  SnapshotInfoLocalFileSystemStorage,
  SnapshotLocalFileSystemStorage
}
import io.constellationnetwork.schema._
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed

import org.typelevel.log4cats.slf4j.Slf4jLogger

object SnapshotDownloadStorage {
  def make[F[_]: Async: Parallel: HasherSelector: KryoSerializer](
    tmpStorage: SnapshotLocalFileSystemStorage[F, GlobalIncrementalSnapshot],
    persistedStorage: SnapshotLocalFileSystemStorage[F, GlobalIncrementalSnapshot],
    fullGlobalSnapshotStorage: SnapshotLocalFileSystemStorage[F, GlobalSnapshot],
    snapshotInfoStorage: SnapshotInfoLocalFileSystemStorage[F, GlobalSnapshotStateProof, GlobalSnapshotInfo],
    snapshotInfoKryoStorage: SnapshotInfoLocalFileSystemStorage[F, GlobalSnapshotStateProof, GlobalSnapshotInfoV2],
    hashSelect: HashSelect
  ): SnapshotDownloadStorage[F] =
    new SnapshotDownloadStorage[F] {

      val logger = Slf4jLogger.getLogger[F]

      val cutoffLogic: OrdinalCutoff = LogarithmicOrdinalCutoff.make

      val maxParallelFileOperations = 4

      def readPersisted(ordinal: SnapshotOrdinal): F[Option[Signed[GlobalIncrementalSnapshot]]] = persistedStorage.read(ordinal)

      def readTmp(ordinal: SnapshotOrdinal): F[Option[Signed[GlobalIncrementalSnapshot]]] = tmpStorage.read(ordinal)

      def writeTmp(snapshot: Signed[GlobalIncrementalSnapshot]): F[Unit] =
        tmpStorage.exists(snapshot.ordinal).flatMap(tmpStorage.delete(snapshot.ordinal).whenA) >>
          tmpStorage.writeUnderOrdinal(snapshot)

      def writePersisted(snapshot: Signed[GlobalIncrementalSnapshot]): F[Unit] = HasherSelector[F].forOrdinal(snapshot.ordinal) {
        implicit hasher => persistedStorage.write(snapshot)
      }

      def deletePersisted(ordinal: SnapshotOrdinal): F[Unit] = persistedStorage.delete(ordinal)

      def isPersisted(hash: Hash): F[Boolean] = persistedStorage.exists(hash)

      def hasCorrectSnapshotInfo(
        ordinal: SnapshotOrdinal,
        proof: GlobalSnapshotStateProof
      )(implicit hasher: Hasher[F]): F[Boolean] =
        (hashSelect.select(ordinal) match {
          case JsonHash => snapshotInfoStorage.read(ordinal).flatMap(_.traverse(_.stateProof(ordinal)))
          case KryoHash => snapshotInfoKryoStorage.read(ordinal).flatMap(_.traverse(_.stateProof(ordinal)))
        }).map {
          case Some(calculatedProof) => calculatedProof === proof
          case _                     => false
        }

      def getHighestSnapshotInfoOrdinal(lte: SnapshotOrdinal): F[Option[SnapshotOrdinal]] =
        snapshotInfoStorage.listStoredOrdinals
          .flatMap(_.filter(_ <= lte).compile.toList)
          .map(_.maximumOption)

      def readCombined(
        ordinal: SnapshotOrdinal
      )(implicit hasher: Hasher[F]): F[Option[(Signed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]] = {
        val maybeInfo = hashSelect.select(ordinal) match {
          case JsonHash => snapshotInfoStorage.read(ordinal).map(_.map(_.asRight[GlobalSnapshotInfoV2]))
          case KryoHash => snapshotInfoKryoStorage.read(ordinal).map(_.map(_.asLeft[GlobalSnapshotInfo]))
        }

        (readPersisted(ordinal).flatMap(_.traverse(_.toHashed)), maybeInfo).tupled.map(_.tupled).flatMap {
          case Some((snapshot, info)) =>
            info
              .bitraverse(_.stateProof(ordinal), _.stateProof(ordinal))
              .map(_.fold(identity, identity))
              .flatMap(stateProof => StateProofValidator.validate(snapshot, stateProof).map(_.isValid))
              .ifM(
                (snapshot.signed, info.leftMap(_.toGlobalSnapshotInfo).fold(identity, identity)).some.pure[F],
                new Exception("Persisted snapshot info does not match the persisted snapshot")
                  .raiseError[F, Option[(Signed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]]
              )
          case _ => none.pure[F]
        }
      }

      def persistSnapshotInfoWithCutoff(ordinal: SnapshotOrdinal, info: GlobalSnapshotInfo): F[Unit] =
        snapshotInfoStorage.write(ordinal, info) >> {
          val toKeep = cutoffLogic.cutoff(SnapshotOrdinal.MinValue, ordinal)

          snapshotInfoStorage.listStoredOrdinals.flatMap {
            _.compile.toList
              .map(_.toSet.diff(toKeep).toList)
              .flatMap(_.traverse_(snapshotInfoStorage.delete))
          }
        }

      def movePersistedToTmp(hash: Hash, ordinal: SnapshotOrdinal): F[Unit] =
        tmpStorage.getPath(hash).flatMap(persistedStorage.move(hash, _) >> persistedStorage.delete(ordinal))

      def moveTmpToPersisted(snapshot: Signed[GlobalIncrementalSnapshot]): F[Unit] =
        HasherSelector[F].forOrdinal(snapshot.ordinal) { implicit hasher =>
          persistedStorage.getPath(snapshot).flatMap(tmpStorage.moveByOrdinal(snapshot, _) >> persistedStorage.link(snapshot))
        }

      def readGenesis(ordinal: SnapshotOrdinal): F[Option[Signed[GlobalSnapshot]]] = fullGlobalSnapshotStorage.read(ordinal)

      def writeGenesis(genesis: Signed[GlobalSnapshot]): F[Unit] = HasherSelector[F].forOrdinal(genesis.ordinal) { implicit hasher =>
        fullGlobalSnapshotStorage.write(genesis)
      }

      def cleanupAbove(ordinal: SnapshotOrdinal): F[Unit] =
        snapshotInfoStorage.deleteAbove(ordinal) >>
          persistedStorage
            .findAbove(ordinal)
            .map {
              _.map(_.name.toLongOption.flatMap(SnapshotOrdinal(_))).collect { case Some(a) => a }
            }
            .flatMap {
              _.compile.toList.flatMap {
                _.parTraverseN(maxParallelFileOperations) { ordinal =>
                  readPersisted(ordinal).flatMap {
                    case Some(snapshot) =>
                      HasherSelector[F]
                        .forOrdinal(snapshot.ordinal) { implicit hasher =>
                          snapshot.toHashed.flatMap(s => movePersistedToTmp(s.hash, s.ordinal))
                        }
                        .handleErrorWith { error =>
                          implicit val kryoHasher = Hasher.forKryo[F]
                          logger.warn(error)(s"cleanupAbove failed for ordinal=${snapshot.ordinal}, retrying with Kryo hasher") >>
                            snapshot.toHashed.flatMap { s =>
                              movePersistedToTmp(s.hash, s.ordinal)
                            }
                        }
                    case None => Async[F].unit
                  }
                }.void
              }
            }
    }
}
