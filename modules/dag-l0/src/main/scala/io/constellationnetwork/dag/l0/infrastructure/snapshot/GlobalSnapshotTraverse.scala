package io.constellationnetwork.dag.l0.infrastructure.snapshot

import cats.Parallel
import cats.data.NonEmptyChain
import cats.effect.kernel.Async
import cats.syntax.all._

import scala.util.control.NoStackTrace

import io.constellationnetwork.dag.l0.StoragesInitializer.initializeStorages
import io.constellationnetwork.ext.cats.syntax.partialPrevious.catsSyntaxPartialPrevious
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.node.shared.domain.snapshot.SnapshotContextFunctions
import io.constellationnetwork.node.shared.domain.snapshot.programs.Download
import io.constellationnetwork.node.shared.domain.snapshot.storage.{LastNGlobalSnapshotStorage, LastSnapshotStorage, SnapshotStorage}
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.mpt.GlobalStateConverter.syntax._
import io.constellationnetwork.schema.mpt.{GlobalStateKey, MptStore}
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.mpt.MptFieldDigests
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.validator.StateProofValidator

import io.circe.Json
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

trait GlobalSnapshotTraverse[F[_]] {
  def loadChain(): F[(GlobalSnapshotInfo, Signed[GlobalIncrementalSnapshot])]
}

object GlobalSnapshotTraverse {

  def make[F[_]: Async: Parallel: HasherSelector: JsonSerializer](
    loadInc: Hash => F[Option[Signed[GlobalIncrementalSnapshot]]],
    loadFull: Hash => F[Option[Signed[GlobalSnapshot]]],
    loadInfo: SnapshotOrdinal => F[Option[GlobalSnapshotInfo]],
    contextFns: SnapshotContextFunctions[F, GlobalSnapshotArtifact, GlobalSnapshotContext],
    rollbackHash: Hash,
    getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]],
    globalSnapshotStorage: SnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
    lastNGlobalSnapshotStorage: LastNGlobalSnapshotStorage[F],
    lastGlobalSnapshotStorage: LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
    download: Download[F, GlobalIncrementalSnapshot],
    mptStore: MptStore[F, GlobalStateKey]
  )(
    implicit globalStateProofSelector: GlobalStateProofSelector
  ): GlobalSnapshotTraverse[F] =
    new GlobalSnapshotTraverse[F] {
      implicit val logger: SelfAwareStructuredLogger[F] = Slf4jLogger.getLoggerFromName[F](this.getClass.getName)
      private val builder = GlobalSnapshotInfo.stateProofBuilder(Some(mptStore.underlying))

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
            case Right(globalSnapshot)           => globalSnapshot.info.toGlobalSnapshotInfo.pure[F]
          }

          firstInfoCalculatedProof <- HasherSelector[F].withCurrent { implicit hasher =>
            hasher.getLogic(firstInc.ordinal) match {
              case KryoHash =>
                GlobalSnapshotInfoV2.fromGlobalSnapshotInfo(firstInfo).stateProof(firstInc.ordinal)
              case JsonHash =>
                // Use syncFullIfNeeded for atomic sync - avoids race condition where
                // two concurrent calls both see entries.isEmpty=true and both try to sync
                mptStore.syncFullIfNeeded[Json](firstInfo.allStateEntries[F], firstInc.ordinal) >>
                  builder.buildProof(firstInfo, firstInc.ordinal)
            }
          }

          hashedFirstInc <- HasherSelector[F].withCurrent(implicit hasher => firstInc.toHashed)
          stateProofInvalid <- StateProofValidator.validateProof(hashedFirstInc, firstInfoCalculatedProof).map(_.isInvalid)

          // Log field-level diagnostics when state proof validation fails
          _ <- (for {
            _ <- logger.error(s"========== STATE PROOF MISMATCH DEBUG ==========")
            _ <- logger.error(s"Ordinal: ${firstInc.ordinal.show}")
            _ <- logger.error(s"Expected mptRoot: ${hashedFirstInc.signed.value.stateProof.mptRoot}")
            _ <- logger.error(s"Calculated mptRoot: ${firstInfoCalculatedProof.mptRoot}")

            // Get cached field digests from consensus (if available)
            consensusCache <- mptStore.underlying.getOrdinalCache(firstInc.ordinal)
            _ <- logger.info(s"Consensus cache available: ${consensusCache.isDefined}")

            // Build trie from GlobalSnapshotInfo and extract field digests
            (rebuiltTrie, rebuiltDigests) <- HasherSelector[F].withCurrent { implicit hasher =>
              import io.constellationnetwork.security.mpt.MerklePatriciaTrie
              for {
                entries <- firstInfo.allStateEntries[F]
                _ <- logger.info(s"Retraversal entry count: ${entries.size}")
                hexMap <- entries.toList.traverse {
                  case (k, v) => GlobalStateKey.toHex[F](k).map(_ -> v)
                }.map(_.toMap)
                trie <- MerklePatriciaTrie.makeParallel[F, Json](hexMap)
                digests <- MptFieldDigests.extractAllFieldDigests[F](trie)
              } yield (trie, digests)
            }

            // Log retraversal field digests
            _ <- logger.info(s"--- Retraversal Field Digests ---")
            _ <- MptFieldDigests.logFieldDigests[F](rebuiltTrie, s"ordinal=${firstInc.ordinal.show}/retraversal")

            // Compare field-by-field if we have consensus cache
            _ <- consensusCache.traverse_ { cache =>
              logger.info(s"--- Consensus Field Digests ---") >>
                cache.fieldDigests.toList.sortBy(_._1.toInt).traverse_ {
                  case (fieldId, hash) =>
                    logger.info(s"[consensus] ${MptFieldDigests.fieldIdToName(fieldId)}: ${hash.value.take(16)}...")
                } >>
                logger.info(s"--- Field-by-Field Comparison ---") >>
                MptFieldDigests
                  .compareAndLogDifferences[F](
                    cache.fieldDigests,
                    rebuiltDigests,
                    s"ordinal=${firstInc.ordinal.show}"
                  )
                  .void
            }

            _ <- logger.error(s"========== END STATE PROOF MISMATCH DEBUG ==========")
          } yield ()).whenA(stateProofInvalid)

          _ <- (new Exception(s"Snapshot info does not match the snapshot at ordinal=${firstInc.ordinal.show}"))
            .raiseError[F, Unit]
            .whenA(stateProofInvalid)

          _ <- HasherSelector[F].withCurrent(implicit hasher =>
            initializeStorages[F](
              globalSnapshotStorage,
              lastNGlobalSnapshotStorage,
              lastGlobalSnapshotStorage,
              download,
              hashedFirstInc,
              firstInfo
            )
          )
          (info, lastInc) <- incHashesNec.tail.foldLeftM((firstInfo, firstInc)) {
            case ((lastCtx, lastInc), hash) =>
              for {
                inc <- loadIncOrErr(hash)

                (hashedInc, (updatedState, _)) <- HasherSelector[F].withCurrent { implicit hasher =>
                  for {
                    hashed <- inc.toHashed
                    context <- contextFns
                      .createContext(lastCtx, lastInc, inc, getGlobalSnapshotByOrdinal)
                      .map(_ -> inc)
                  } yield (hashed, context)
                }
                _ <-
                  if (hashedInc.ordinal > hashedFirstInc.ordinal) {
                    lastNGlobalSnapshotStorage.set(hashedInc, updatedState)
                  } else ().pure
                _ <-
                  if (hashedInc.ordinal > hashedFirstInc.ordinal) {
                    lastGlobalSnapshotStorage.set(hashedInc, updatedState)
                  } else ().pure
                _ <-
                  if (hashedInc.ordinal > hashedFirstInc.ordinal) {
                    HasherSelector[F].withCurrent(implicit hasher => globalSnapshotStorage.prepend(inc, updatedState))
                  } else ().pure
              } yield (updatedState, inc)
          }
        } yield (info, lastInc)
      }
    }

  case class RollbackSnapshotNotFound(h: Hash) extends NoStackTrace {
    override def getMessage: String = s"Rollback snapshot with hash=${h.show} not found!"
  }
}
