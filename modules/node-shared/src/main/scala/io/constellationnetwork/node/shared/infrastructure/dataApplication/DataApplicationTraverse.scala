package io.constellationnetwork.node.shared.infrastructure.dataApplication

import cats.data._
import cats.effect.{Async, Ref}
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}
import scala.concurrent.duration.DurationInt

import io.constellationnetwork.currency.dataApplication.DataUpdate.getDataUpdates
import io.constellationnetwork.currency.dataApplication._
import io.constellationnetwork.currency.dataApplication.storage._
import io.constellationnetwork.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshotInfo}
import io.constellationnetwork.cutoff.{LogarithmicOrdinalCutoff, OrdinalCutoff}
import io.constellationnetwork.domain.seedlist.SeedlistEntry
import io.constellationnetwork.json.{JsonBrotliBinarySerializer, JsonSerializer}
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.node.shared.domain.snapshot.services.GlobalL0Service
import io.constellationnetwork.node.shared.infrastructure.snapshot.GlobalSnapshotContextFunctions
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.swap.{AllowSpend, CurrencyId}
import io.constellationnetwork.schema.tokenLock.TokenLock
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed

import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import retry.RetryPolicies
import retry.implicits.retrySyntaxError

trait DataApplicationTraverse[F[_]] {
  def loadChain(): F[Option[(DataState.Base, SnapshotOrdinal)]]
}

object DataApplicationTraverse {

  def make[F[_]: Async: KryoSerializer: JsonSerializer: SecurityProvider: HasherSelector](
    lastGlobalSnapshot: Hashed[GlobalIncrementalSnapshot],
    fetchSnapshot: Hash => F[Option[Hashed[GlobalIncrementalSnapshot]]],
    dataApplication: BaseDataApplicationL0Service[F],
    calculatedStateStorage: CalculatedStateLocalFileSystemStorage[F],
    globalSnapshotsWithStateLocalFileSystemStorage: GlobalSnapshotsWithStateLocalFileSystemStorage[F],
    globalSnapshotsWithStateDeltasLocalFileSystemStorage: GlobalSnapshotsWithStateDeltasLocalFileSystemStorage[F],
    identifier: Address,
    globalSnapshotContextFunctions: GlobalSnapshotContextFunctions[F],
    globalL0Service: GlobalL0Service[F]
  )(implicit context: L0NodeContext[F]): DataApplicationTraverse[F] =
    new DataApplicationTraverse[F] {
      val logger: SelfAwareStructuredLogger[F] = Slf4jLogger.getLogger[F]

      def cutoffLogic: OrdinalCutoff = LogarithmicOrdinalCutoff.make

      private def getGlobalSnapshotWithRetry(
        ordinal: SnapshotOrdinal,
        getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]]
      ): F[Hashed[GlobalIncrementalSnapshot]] = {
        val retryPolicy = RetryPolicies.exponentialBackoff[F](1.second).join(RetryPolicies.limitRetries(5))
        getGlobalSnapshotByOrdinal(ordinal)
          .retryingOnFailuresAndAllErrors(
            wasSuccessful = maybeSnapshot => maybeSnapshot.isDefined.pure[F],
            policy = retryPolicy,
            onFailure = (_, retryDetails) =>
              logger.warn(s"Got None when trying to fetch incremental global snapshot $ordinal {attempt=${retryDetails.retriesSoFar}}"),
            onError = (err, retryDetails) =>
              logger.error(err)(s"Error when trying to fetch incremental global snapshot $ordinal {attempt=${retryDetails.retriesSoFar}}")
          )
          .flatMap {
            case Some(snapshot) => snapshot.pure[F]
            case None =>
              new RuntimeException(s"Global snapshot not found for ordinal $ordinal after retries")
                .raiseError[F, Hashed[GlobalIncrementalSnapshot]]
          }
      }

      private def backfillMissingSnapshots(
        latestStoredOrdinal: SnapshotOrdinal,
        targetOrdinal: SnapshotOrdinal
      )(implicit hasher: Hasher[F]): F[Unit] = {
        val maxBackwardFetch = 50L

        if (
          targetOrdinal > latestStoredOrdinal &&
          targetOrdinal.value.value - latestStoredOrdinal.value.value < maxBackwardFetch
        ) {

          val ordinalsToFetch = (latestStoredOrdinal.value.value + 1 to targetOrdinal.value.value).toList

          // Read the initial last snapshot once
          for {
            initialLastSnapshot <- globalSnapshotsWithStateLocalFileSystemStorage.read(latestStoredOrdinal)
            lastSnapshotRef <- Ref[F].of(initialLastSnapshot)
            _ <- ordinalsToFetch.traverse_ { ordinalValue =>
              SnapshotOrdinal(ordinalValue) match {
                case Some(ordinal) =>
                  for {
                    lastSnapshot <- OptionT(lastSnapshotRef.get)
                      .getOrRaise(new IllegalStateException("Last currency snapshot unavailable"))
                    newSnapshot <- fetchAndStoreSnapshot(ordinal, lastSnapshot)
                    _ <- lastSnapshotRef.set(Some(newSnapshot))
                  } yield ()
                case None =>
                  Async[F].unit
              }
            }
          } yield ()
        } else if (targetOrdinal > latestStoredOrdinal) {
          logger.warn(
            s"Backfill gap too large (${targetOrdinal.value.value - latestStoredOrdinal.value.value} > $maxBackwardFetch), skipping backfill from ${latestStoredOrdinal.show} to ${targetOrdinal.show}"
          )
        } else {
          Async[F].unit
        }
      }

      private def fetchAndStoreSnapshot(
        ordinal: SnapshotOrdinal,
        lastSnapshot: GlobalSnapshotWithState
      )(implicit hasher: Hasher[F]): F[GlobalSnapshotWithState] =
        getGlobalSnapshotWithRetry(ordinal, globalL0Service.pullGlobalSnapshot).flatMap { snapshot =>
          globalSnapshotContextFunctions
            .createContext(
              context = lastSnapshot.state,
              lastArtifact = lastSnapshot.snapshot,
              signedArtifact = snapshot.signed,
              globalL0Service.pullGlobalSnapshot
            )
            .flatMap { updatedContext =>
              val snapshotWithState = GlobalSnapshotWithState(snapshot.signed, updatedContext)
              val snapshotWithStateDeltas =
                GlobalSnapshotWithStateDeltas(snapshot.signed, updatedContext.activeAllowSpends, updatedContext.activeTokenLocks)

              globalSnapshotsWithStateLocalFileSystemStorage
                .write(ordinal, snapshotWithState) >>
                globalSnapshotsWithStateDeltasLocalFileSystemStorage
                  .write(ordinal, snapshotWithStateDeltas)
                  .as(snapshotWithState)
            }
        }.handleErrorWith { error =>
          logger.error(error)(s"Failed to fetch and store snapshot for ordinal $ordinal")
          error.raiseError[F, GlobalSnapshotWithState]
        }

      def loadChain(): F[Option[(DataState.Base, SnapshotOrdinal)]] = TraverseLocalFileSystemTempStorage.forAsync.use { storage =>
        def fetchSnapshotOrErr(h: Hash) = fetchSnapshot(h).flatMap(_.liftTo[F](new Exception(s"Global snapshot not found, hash=${h.show}")))

        def isIncrementalGenesis(snapshot: CurrencyIncrementalSnapshot): Boolean = snapshot.ordinal === SnapshotOrdinal.MinIncrementalValue

        def readCalculatedState(snapshot: CurrencyIncrementalSnapshot): F[Option[DataCalculatedState]] =
          snapshot.dataApplication.flatTraverse { da =>
            calculatedStateStorage
              .read[DataCalculatedState](snapshot.ordinal) { a =>
                dataApplication.deserializeCalculatedState(a).rethrow
              }
              .flatMap(_.flatTraverse { calculatedState =>
                dataApplication
                  .hashCalculatedState(calculatedState)
                  .flatTap { hash =>
                    logger
                      .warn(
                        s"Calculated state proof mismatch at ordinal=${snapshot.ordinal.show}: computed=${hash.show} expected=${da.calculatedStateProof.show}"
                      )
                      .whenA(hash =!= da.calculatedStateProof)
                  }
                  .map(hash => Option.when(hash === da.calculatedStateProof)(calculatedState))
              })
          }

        def cutoffPersistedCalculatedStates(ordinal: SnapshotOrdinal) =
          logger.info(s"Cleaning persisted calculated states using logarithmic cutoff for ordinal=${ordinal.show}") >> {
            val toKeep = cutoffLogic.cutoff(SnapshotOrdinal.MinValue, ordinal)

            calculatedStateStorage.listStoredOrdinals.flatMap {
              _.compile.toList
                .map(_.toSet.diff(toKeep).toList)
                .flatMap(_.traverse(calculatedStateStorage.delete))
            }
          }

        // Serves each replayed snapshot's true predecessor as getLastCurrencySnapshot, so a
        // metagraph's combine that reads only the plain accessor sees the same last snapshot
        // it saw at consensus time and its recomputed calculated state matches the original
        // byte for byte. getLastCurrencySnapshotCombined is pinned to the true predecessor's
        // ordinal/hash as well, but its CurrencySnapshotInfo still comes from the live node
        // context (the rollback tip) because the traverse doesn't reconstruct historical
        // CurrencySnapshotInfo. Metagraphs whose combine derives state from that info (e.g.
        // amm-metagraph, voting-poll via L0CombinerService.combine) are therefore NOT
        // byte-exact under replay - this is a known, currently out-of-scope limitation.
        // Everything else delegates to the node context.
        def replayScopedContext(predecessor: Signed[CurrencyIncrementalSnapshot]): F[L0NodeContext[F]] =
          HasherSelector[F].forOrdinal(predecessor.value.ordinal) { implicit hasher =>
            predecessor.toHashed.map { hashedPredecessor =>
              new L0NodeContext[F] {
                def getLastCurrencySnapshot: F[Option[Hashed[CurrencyIncrementalSnapshot]]] = hashedPredecessor.some.pure[F]
                def getCurrencySnapshot(ordinal: SnapshotOrdinal): F[Option[Hashed[CurrencyIncrementalSnapshot]]] =
                  context.getCurrencySnapshot(ordinal)
                def getLastCurrencySnapshotCombined: F[Option[(Hashed[CurrencyIncrementalSnapshot], CurrencySnapshotInfo)]] =
                  // Pins the snapshot half to the true predecessor so its ordinal/hash agree with
                  // getLastCurrencySnapshot above. The info half is still the tip's, not the
                  // predecessor's - see the limitation noted in the comment above.
                  context.getLastCurrencySnapshotCombined.map(_.map { case (_, info) => (hashedPredecessor, info) })
                def getLastSynchronizedGlobalSnapshot: F[Option[GlobalIncrementalSnapshot]] =
                  context.getLastSynchronizedGlobalSnapshot
                def getLastSynchronizedGlobalSnapshotCombined: F[Option[(GlobalIncrementalSnapshot, GlobalSnapshotInfo)]] =
                  context.getLastSynchronizedGlobalSnapshotCombined
                def getLastSynchronizedAllowSpends
                  : F[Option[SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]]]] =
                  context.getLastSynchronizedAllowSpends
                def getLastSynchronizedTokenLocks: F[Option[SortedMap[Address, SortedSet[Signed[TokenLock]]]]] =
                  context.getLastSynchronizedTokenLocks
                def securityProvider: SecurityProvider[F] = context.securityProvider
                def getCurrencyId: F[CurrencyId] = context.getCurrencyId
                def getMetagraphL0Seedlist: Option[Set[SeedlistEntry]] = context.getMetagraphL0Seedlist
              }
            }
          }

        def applyCache(
          startingState: DataState.Base,
          startingSnapshot: Signed[CurrencyIncrementalSnapshot]
        ): F[(DataState.Base, SnapshotOrdinal)] =
          storage.listStoredOrdinals.flatMap(_.compile.toList).flatMap { ordinals =>
            logger.info(s"Applying cache built during traversing, size=${ordinals.size.show}") >>
              ordinals.sorted
                .foldLeftM((startingState, startingSnapshot)) {
                  case ((state, predecessor), currentOrdinal) =>
                    storage.read(currentOrdinal).flatMap { snapshot =>
                      if (snapshot.value.dataApplication.isEmpty) {
                        logger.debug(s"Skipping ordinal=${currentOrdinal.show} with no data application section") >>
                          (state, snapshot).pure[F]
                      } else
                        snapshot.value.dataApplication
                          .map(_.blocks)
                          .traverse {
                            _.traverse { blockBytes =>
                              dataApplication.deserializeBlock(blockBytes).flatMap(_.liftTo[F])
                            }
                          }
                          .map(_.toList.flatten)
                          .map(_.flatMap(_.dataTransactions.toList))
                          .map(getDataUpdates)
                          .flatMap { dataUpdates =>
                            replayScopedContext(predecessor).flatMap { replayContext =>
                              dataApplication.combine(state, dataUpdates)(replayContext)
                            }
                          }
                          .flatTap {
                            case DataState(_, calculatedState, _) =>
                              logger.info(s"Persisting calculated state for ordinal=${currentOrdinal.show}") >>
                                calculatedStateStorage.write(currentOrdinal, calculatedState)(dataApplication.serializeCalculatedState) >>
                                cutoffPersistedCalculatedStates(currentOrdinal)
                          }
                          .map((_, snapshot))
                    }
                }
                .map { case (state, lastSnapshot) => (state, lastSnapshot.value.ordinal) }
          }

        def discover: F[Option[(DataState.Base, Signed[CurrencyIncrementalSnapshot], SnapshotOrdinal)]] = {
          type Output = Option[(DataState.Base, Signed[CurrencyIncrementalSnapshot], SnapshotOrdinal)]
          type Acc = Hashed[GlobalIncrementalSnapshot]

          def nestedRecursion(
            snapshots: NonEmptyList[Hashed[CurrencyIncrementalSnapshot]],
            globalOrdinal: SnapshotOrdinal
          ): F[Either[Unit, Output]] = {
            type NestedAcc = List[Signed[CurrencyIncrementalSnapshot]]

            def sortedSnapshots = snapshots.map(_.signed).sortBy(_.value.ordinal).reverse

            def updateCache(snapshot: Signed[CurrencyIncrementalSnapshot]): F[Unit] = storage.write(snapshot.value.ordinal, snapshot)

            def readOnChainState(snapshot: CurrencyIncrementalSnapshot) =
              snapshot.dataApplication.map(_.onChainState).traverse(dataApplication.deserializeState(_).rethrow)

            sortedSnapshots.toList
              .tailRecM[F, Output] {
                case Nil =>
                  none[(DataState.Base, Signed[CurrencyIncrementalSnapshot], SnapshotOrdinal)].asRight[NestedAcc].pure[F]
                case snapshot :: tail =>
                  if (isIncrementalGenesis(snapshot.value))
                    logger.debug(s"Found metagraph genesis").as {
                      (dataApplication.genesis, snapshot, globalOrdinal).some.asRight[NestedAcc]
                    }
                  else
                    readCalculatedState(snapshot.value).flatMap {
                      case Some(calculatedState) =>
                        readOnChainState(snapshot.value).flatMap {
                          case Some(onChainState) =>
                            (
                              DataState(onChainState, calculatedState, snapshot.value.artifacts.getOrElse(SortedSet.empty)),
                              snapshot,
                              globalOrdinal
                            ).some
                              .asRight[NestedAcc]
                              .pure[F]
                          case _ =>
                            logger
                              .warn(
                                s"Found calculated state, but cannot decode on chain state. Check chain integrity or metagraph implementation. Trying to continue."
                              ) >> updateCache(snapshot).as(tail.asLeft[Output])
                        }

                      case _ =>
                        logger.info(s"Could not get calculated state of ordinal=${snapshot.value.ordinal.show}, updating cache") >>
                          updateCache(snapshot).as(tail.asLeft[Output])
                    }
              } >>= {
              case Some((state, currencyIncrementalSnapshot, globalOrdinal)) =>
                (state, currencyIncrementalSnapshot, globalOrdinal).some.asRight[Unit].pure[F]
              case _ =>
                ().asLeft[Output].pure[F]
            }
          }

          lastGlobalSnapshot.tailRecM { globalSnapshot =>
            if (globalSnapshot.ordinal === SnapshotOrdinal.MinValue) {
              logger.warn(
                s"Reached global genesis (ordinal=${globalSnapshot.ordinal.show}) without finding a valid metagraph starting state"
              ) >>
                none[(DataState.Base, Signed[CurrencyIncrementalSnapshot], SnapshotOrdinal)].asRight[Acc].pure[F]
            } else
              fetchCurrencySnapshots(globalSnapshot)
                .flatMap(_.traverse {
                  case Validated.Invalid(_) =>
                    (new Exception(
                      s"Metagraph snapshots are invalid in global snapshot ordinal=${globalSnapshot.ordinal.show}. Check chain integrity."
                    )).raiseError[F, Either[Acc, Output]]
                  case Validated.Valid(snapshots) =>
                    logger.info(
                      s"Found ${snapshots.size.show} snapshots at global snapshot ordinal=${globalSnapshot.ordinal.show}, ordinals=${snapshots.map(_.ordinal).show}. Performing nested recursion."
                    ) >> nestedRecursion(snapshots, globalSnapshot.ordinal).flatMap {
                      case Right(Some((state, ordinal, globalOrdinal))) => (state, ordinal, globalOrdinal).some.asRight[Acc].pure[F]
                      case _ =>
                        fetchSnapshotOrErr(globalSnapshot.lastSnapshotHash).map(_.asLeft[Output])
                    }
                })
                .flatMap {
                  _.map(_.pure[F]).getOrElse(
                    logger
                      .debug(s"Metagraph snapshots are not found in global snapshot ordinal=${globalSnapshot.ordinal.show}, continuing.") >>
                      fetchSnapshotOrErr(globalSnapshot.lastSnapshotHash).map {
                        _.asLeft[Output]
                      }
                  )
                }
          }
        }

        discover >>= {
          case Some((state, currencyIncrementalSnapshot, globalOrdinal)) =>
            for {
              _ <- logger.info(s"Discovered calculated state at metagraph ordinal=${currencyIncrementalSnapshot.value.ordinal.show}")
              latestStoredOrdinal <- globalSnapshotsWithStateLocalFileSystemStorage.getLatestOrdinal
              lastSnapshotGlobalSyncView = currencyIncrementalSnapshot.value.globalSyncView
              _ <- HasherSelector[F].withCurrent { implicit hasher =>
                (latestStoredOrdinal, lastSnapshotGlobalSyncView).mapN { (latestOrdinal, lastGlobalSyncView) =>
                  backfillMissingSnapshots(latestOrdinal, lastGlobalSyncView.ordinal)
                }.getOrElse(Async[F].unit)
              }
              result <- applyCache(state, currencyIncrementalSnapshot) >>= {
                case (latestState, latestOrdinal) =>
                  dataApplication.setCalculatedState(latestOrdinal, latestState.calculated).flatMap {
                    case true =>
                      globalSnapshotsWithStateLocalFileSystemStorage.deleteAbove(globalOrdinal) >>
                        globalSnapshotsWithStateDeltasLocalFileSystemStorage.deleteAbove(globalOrdinal) >>
                        calculatedStateStorage.deleteAbove(latestOrdinal).as {
                          (latestState, latestOrdinal).some
                        }
                    case false =>
                      (new Exception(
                        s"setCalculatedState returned false for ordinal=${latestOrdinal.show}, aborting traversal to avoid inconsistency"
                      )).raiseError[F, Option[(DataState.Base, SnapshotOrdinal)]]
                  }
              }
            } yield result

          case _ => none[(DataState.Base, SnapshotOrdinal)].pure[F]
        }
      }

      private def fetchCurrencySnapshots(
        globalSnapshot: GlobalIncrementalSnapshot
      ): F[
        Option[ValidatedNel[Signed.InvalidSignatureForHash[CurrencyIncrementalSnapshot], NonEmptyList[Hashed[CurrencyIncrementalSnapshot]]]]
      ] =
        globalSnapshot.stateChannelSnapshots
          .get(identifier) match {
          case Some(snapshots) =>
            HasherSelector[F].withCurrent { implicit hasher =>
              snapshots.toList.traverse { binary =>
                JsonSerializer[F].deserialize[Signed[CurrencyIncrementalSnapshot]](binary.content)
              }
                .map(_.flatMap(_.toOption))
                .map(NonEmptyList.fromList)
                .map(_.map(_.sortBy(_.value.ordinal)))
                .flatMap(_.map(_.traverse(_.toHashedWithSignatureCheck)).sequence)
                .map(_.map(_.traverse(_.toValidatedNel)))
            }
          case None => Async[F].pure(none)
        }
    }
}
