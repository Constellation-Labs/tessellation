package org.tessellation.dag.l0.infrastructure.snapshot

import cats.data.NonEmptyChain
import cats.effect.kernel.Async
import cats.syntax.all._

import scala.util.control.NoStackTrace

import org.tessellation.ext.cats.syntax.partialPrevious.catsSyntaxPartialPrevious
import org.tessellation.merkletree.StateProofValidator
import org.tessellation.node.shared.domain.snapshot.SnapshotContextFunctions
import org.tessellation.schema._
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed
import org.tessellation.security.{HashSelect, Hasher}

import org.typelevel.log4cats.slf4j.Slf4jLogger

trait GlobalSnapshotTraverse[F[_]] {
  def loadChain(): F[(GlobalSnapshotInfo, Signed[GlobalIncrementalSnapshot])]
}

object GlobalSnapshotTraverse {

  def make[F[_]: Async: Hasher](
    loadInc: Hash => F[Option[Signed[GlobalIncrementalSnapshot]]],
    loadFull: Hash => F[Option[Signed[GlobalSnapshot]]],
    loadInfo: SnapshotOrdinal => F[Option[GlobalSnapshotInfo]],
    contextFns: SnapshotContextFunctions[F, GlobalSnapshotArtifact, GlobalSnapshotContext],
    rollbackHash: Hash,
    hashSelect: HashSelect
  ): GlobalSnapshotTraverse[F] =
    new GlobalSnapshotTraverse[F] {
      val logger = Slf4jLogger.getLogger[F]

      def loadChain(): F[(GlobalSnapshotInfo, Signed[GlobalIncrementalSnapshot])] = {
        def loadIncOrErr(h: Hash) =
          loadInc(h).flatMap(_.liftTo[F](new Exception(s"Incremental snapshot not found during rollback, hash=${h.show}")))
        def loadInfoOrErr(o: SnapshotOrdinal) =
          loadInfo(o).flatMap(_.liftTo[F](new Exception(s"Expected SnapshotInfo not found during rollback, ordinal=${o.show}")))
        def loadFullOrIncOrErr(h: Hash) =
          loadFull(h)
            .map(_.map(_.asRight[Signed[GlobalIncrementalSnapshot]]))
            .flatMap {
              _.fold(loadInc(h).map(_.map(_.asLeft[Signed[GlobalSnapshot]])))(_.some.pure[F])
            }
            .flatMap(_.liftTo[F](new Exception(s"Found neither global snapshot nor global incremental snapshot for hash=${h.show}")))

        def discoverHashesChain(rollbackHash: Hash): F[(Hash, NonEmptyChain[Hash])] =
          (NonEmptyChain.one(rollbackHash), none[SnapshotOrdinal]).tailRecM {
            case (hashes, lastOrdinal) =>
              val lastHash = hashes.head

              loadInc(lastHash)
                .onError(_ => logger.error(s"Error during hash chain discovery at ${lastOrdinal.show} with hash ${lastHash.show}"))
                .flatMap {
                  case Some(inc) =>
                    loadInfo(inc.ordinal).map {
                      case Some(_) =>
                        (hashes, lastOrdinal).asRight[(NonEmptyChain[Hash], Option[SnapshotOrdinal])]
                      case None =>
                        (hashes.prepend(inc.lastSnapshotHash), inc.ordinal.partialPrevious)
                          .asLeft[(NonEmptyChain[Hash], Option[SnapshotOrdinal])]
                    }

                  case None =>
                    (hashes, lastOrdinal).asRight[(NonEmptyChain[Hash], Option[SnapshotOrdinal])].pure[F]
                }
          }.flatMap {
            case (hashes, lastOrdinal) =>
              val hashCandidate = hashes.head

              loadInc(hashCandidate)
                .map(_.fold(hashes.tail)(_ => hashes.toChain))
                .flatMap(c =>
                  NonEmptyChain.fromChain(c) match {
                    case Some(incHashes) =>
                      logger
                        .info(s"Finished rollback chain discovery with hash candidate $hashCandidate at $lastOrdinal")
                        .as((hashCandidate, incHashes))
                    case None => RollbackSnapshotNotFound(rollbackHash).raiseError[F, (Hash, NonEmptyChain[Hash])]
                  }
                )
          }

        for {
          (hashCandidate, incHashesNec) <- discoverHashesChain(rollbackHash)
          _ <- logger.info(s"Rollback hash candidate: ${hashCandidate.show}")
          firstInc <- loadIncOrErr(incHashesNec.head)

          firstInfo <- loadFullOrIncOrErr(hashCandidate).flatMap {
            case Left(globalIncrementalSnapshot) => loadInfoOrErr(globalIncrementalSnapshot.ordinal)
            case Right(globalSnapshot)           => GlobalSnapshotInfoV1.toGlobalSnapshotInfo(globalSnapshot.info).pure[F]
          }

          stateProofInvalid <- StateProofValidator.validate(firstInc, firstInfo, hashSelect).map(_.isInvalid)

          _ <- (new Exception(s"Snapshot info does not match the snapshot at ordinal=${firstInc.ordinal.show}"))
            .raiseError[F, Unit]
            .whenA(stateProofInvalid)

          (info, lastInc) <- incHashesNec.tail.foldLeftM((firstInfo, firstInc)) {
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
