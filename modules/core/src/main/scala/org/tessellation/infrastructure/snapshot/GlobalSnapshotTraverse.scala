package org.tessellation.infrastructure.snapshot

import cats.data.NonEmptyChain
import cats.effect.Sync
import cats.syntax.applicativeError._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.foldable._
import cats.syntax.functor._
import cats.syntax.option._
import cats.syntax.show._

import scala.util.control.NoStackTrace

import org.tessellation.ext.cats.syntax.partialPrevious.catsSyntaxPartialPrevious
import org.tessellation.schema._
import org.tessellation.sdk.domain.snapshot.SnapshotContextFunctions
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed

import org.typelevel.log4cats.slf4j.Slf4jLogger

trait GlobalSnapshotTraverse[F[_]] {
  def loadChain(): F[(GlobalSnapshotInfo, Signed[GlobalIncrementalSnapshot])]
}

object GlobalSnapshotTraverse {

  def make[F[_]: Sync](
    loadInc: Hash => F[Option[Signed[GlobalIncrementalSnapshot]]],
    loadFull: Hash => F[Option[Signed[GlobalSnapshot]]],
    contextFns: SnapshotContextFunctions[F, GlobalSnapshotArtifact, GlobalSnapshotContext],
    rollbackHash: Hash
  ): GlobalSnapshotTraverse[F] =
    new GlobalSnapshotTraverse[F] {
      val logger = Slf4jLogger.getLogger[F]

      def loadChain(): F[(GlobalSnapshotInfo, Signed[GlobalIncrementalSnapshot])] = {
        def loadFullOrErr(h: Hash) =
          loadFull(h).flatMap(_.liftTo[F](new Throwable(s"Global snapshot not found during rollback, hash=${h.show}")))
        def loadIncOrErr(h: Hash) =
          loadInc(h).flatMap(_.liftTo[F](new Throwable(s"Incremental snapshot not found during rollback, hash=${h.show}")))

        def discoverHashesChain(rollbackHash: Hash): F[(Hash, NonEmptyChain[Hash])] =
          (NonEmptyChain.one(rollbackHash), none[SnapshotOrdinal]).tailRecM {
            case (hashes, lastOrdinal) =>
              val lastHash = hashes.head

              loadInc(lastHash)
                .onError(_ => logger.error(s"Error during hash chain discovery at ${lastOrdinal.show} with hash ${lastHash.show}"))
                .map {
                  case Some(inc) => (hashes.prepend(inc.lastSnapshotHash), inc.ordinal.partialPrevious).asLeft
                  case None      => (hashes, lastOrdinal).asRight
                }
          }.flatMap {
            case (hashes, lastOrdinal) =>
              val globalHashCandidate = hashes.head

              NonEmptyChain.fromChain(hashes.tail) match {
                case Some(incHashes) =>
                  logger
                    .info(s"Finished rollback chain discovery with global hash candidate $globalHashCandidate at $lastOrdinal")
                    .as((globalHashCandidate, incHashes))
                case None => RollbackSnapshotNotFound(rollbackHash).raiseError[F, (Hash, NonEmptyChain[Hash])]
              }
          }

        for {
          (globalHashCandidate, incHashesNec) <- discoverHashesChain(rollbackHash)
          global <- loadFullOrErr(globalHashCandidate)
          firstInc <- loadIncOrErr(incHashesNec.head)

          (info, lastInc) <- incHashesNec.tail.foldLeftM((GlobalSnapshotInfoV1.toGlobalSnapshotInfo(global.info), firstInc)) {
            case ((lastCtx, lastInc), hash) =>
              loadIncOrErr(hash).flatMap { inc =>
                contextFns.createContext(lastCtx, lastInc, inc).map(_ -> inc)
              }
          }
        } yield (info, lastInc)
      }
    }

  case class RollbackSnapshotNotFound(h: Hash) extends NoStackTrace {
    override def getMessage: String = s"Rollback snapshot with hash=${h.show} not found!"
  }
}
