package io.constellationnetwork.dag.l0.infrastructure.snapshot

import cats.Parallel
import cats.data.NonEmptyChain
import cats.effect.kernel.Async
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}
import scala.util.control.NoStackTrace

import io.constellationnetwork.dag.l0.StoragesInitializer.initializeStorages
import io.constellationnetwork.ext.cats.syntax.partialPrevious.catsSyntaxPartialPrevious
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.node.shared.domain.snapshot.SnapshotContextFunctions
import io.constellationnetwork.node.shared.domain.snapshot.programs.Download
import io.constellationnetwork.node.shared.domain.snapshot.storage.{LastNGlobalSnapshotStorage, LastSnapshotStorage, SnapshotStorage}
import io.constellationnetwork.schema.mpt.GlobalStateConverter.syntax._
import io.constellationnetwork.schema.mpt.{GlobalStateKey, MptStore}
import io.constellationnetwork.schema.{address, delegatedStake, _}
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash
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
    mptStore: MptStore[F, GlobalStateKey],
    incrementalDelegatedStakingStartingOrdinal: SnapshotOrdinal
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

          firstInfoLoaded <- loadFullOrIncOrErr(hashCandidate).flatMap {
            case Left(globalIncrementalSnapshot) => loadInfoOrErr(globalIncrementalSnapshot.ordinal)
            case Right(globalSnapshot)           => globalSnapshot.info.toGlobalSnapshotInfo.pure[F]
          }

          // Apply same transformation as consensus path for ordinals > incrementalDelegatedStakingStartingOrdinal
          // This ensures DelegatedStakeRecord has currentTokenLockRef and currentAmount set for MPT calculation
          firstInfo =
            if (firstInc.ordinal > incrementalDelegatedStakingStartingOrdinal) {
              firstInfoLoaded.activeDelegatedStakes match {
                case Some(delegatedStakes) =>
                  val transformed = delegatedStakes.view.mapValues { records =>
                    SortedSet.from(records.toList.map { r =>
                      r.copy(
                        currentTokenLockRef = r.currentTokenLockRef.orElse(Some(r.tokenLockRef)),
                        currentAmount = r.currentAmount.orElse(Some(r.amount))
                      )
                    })
                  }.to(SortedMap)
                  firstInfoLoaded.copy(activeDelegatedStakes = Some(transformed))
                case None => firstInfoLoaded
              }
            } else firstInfoLoaded

          // Diagnostic logging for MPT state comparison (enabled via MPT_STATE_DEBUG=true)
          _ <- Async[F].delay(Option(System.getenv("MPT_STATE_DEBUG")).contains("true")).flatMap { debugEnabled =>
            if (debugEnabled) {
              val delegatedStakes = firstInfo.getActiveDelegatedStakes
              val tokenLocks = firstInfo.getActiveTokenLocks
              val allowSpendsOpt = firstInfo.activeAllowSpends
              val balances = firstInfo.balances
              val lastTxRefs = firstInfo.lastTxRefs

              logger.info(s"[MPT_DEBUG][RETRAVERSAL] ordinal=${firstInc.ordinal}") >>
                // Balances summary
                logger.info(s"[MPT_DEBUG][RETRAVERSAL] balances entries: ${balances.size}") >>
                // LastTxRefs summary
                logger.info(s"[MPT_DEBUG][RETRAVERSAL] lastTxRefs entries: ${lastTxRefs.size}") >>
                // Token locks
                logger.info(s"[MPT_DEBUG][RETRAVERSAL] activeTokenLocks entries: ${tokenLocks.size}") >>
                logger.info(s"[MPT_DEBUG][RETRAVERSAL] activeTokenLocks keys: ${tokenLocks.keys.toList}") >>
                tokenLocks.toList.traverse_ {
                  case (addr, locks) =>
                    logger.info(s"[MPT_DEBUG][RETRAVERSAL] activeTokenLocks[$addr]: ${locks.size} locks")
                } >>
                // Allow spends
                logger.info(s"[MPT_DEBUG][RETRAVERSAL] activeAllowSpends present: ${allowSpendsOpt.isDefined}") >>
                allowSpendsOpt.fold(Async[F].unit) { allowSpends =>
                  logger.info(s"[MPT_DEBUG][RETRAVERSAL] activeAllowSpends entries: ${allowSpends.size}") >>
                    allowSpends.toList.traverse_ {
                      case (key, spends) =>
                        logger.info(s"[MPT_DEBUG][RETRAVERSAL] activeAllowSpends[$key]: ${spends.size} spends")
                    }
                } >>
                // Delegated stakes
                logger.info(s"[MPT_DEBUG][RETRAVERSAL] delegatedStakes entries: ${delegatedStakes.size}") >>
                logger.info(s"[MPT_DEBUG][RETRAVERSAL] delegatedStakes keys: ${delegatedStakes.keys.toList}") >>
                delegatedStakes.toList.traverse_ {
                  case (addr, records) =>
                    logger.info(s"[MPT_DEBUG][RETRAVERSAL] delegatedStakes[$addr]: ${records.size} records, hashes=${records.toList
                        .map(r => r.event.proofs.head.signature.value.value.take(16))
                        .mkString(",")}")
                }
            } else Async[F].unit
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

          // Log the computed proof root on re-traversal
          _ <- Async[F].delay(Option(System.getenv("MPT_STATE_DEBUG")).contains("true")).flatMap { debugEnabled =>
            if (debugEnabled) {
              firstInfoCalculatedProof match {
                case p: GlobalSnapshotStateProof =>
                  p.mptRoot match {
                    case Some(root) => logger.info(s"[MPT_DEBUG][RETRAVERSAL] calculated mptRoot=$root")
                    case None       => logger.info(s"[MPT_DEBUG][RETRAVERSAL] calculated mptRoot=None (legacy proof)")
                  }
                case _ => Async[F].unit
              }
            } else Async[F].unit
          }

          // Log the expected proof root from snapshot
          _ <- Async[F].delay(Option(System.getenv("MPT_STATE_DEBUG")).contains("true")).flatMap { debugEnabled =>
            if (debugEnabled) {
              firstInc.stateProof match {
                case p: GlobalSnapshotStateProof =>
                  p.mptRoot match {
                    case Some(root) => logger.info(s"[MPT_DEBUG][RETRAVERSAL] expected mptRoot=$root")
                    case None       => logger.info(s"[MPT_DEBUG][RETRAVERSAL] expected mptRoot=None (legacy proof)")
                  }
                case _ => Async[F].unit
              }
            } else Async[F].unit
          }

          hashedFirstInc <- HasherSelector[F].withCurrent(implicit hasher => firstInc.toHashed)
          stateProofInvalid <- StateProofValidator.validateProof(hashedFirstInc, firstInfoCalculatedProof).map(_.isInvalid)

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
