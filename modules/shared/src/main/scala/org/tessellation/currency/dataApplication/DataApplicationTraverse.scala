package org.tessellation.currency.dataApplication

import cats.data._
import cats.effect.Async
import cats.kernel.Order
import cats.syntax.all._

import org.tessellation.currency.dataApplication.storage.CalculatedStateLocalFileSystemStorage
import org.tessellation.currency.schema.currency.CurrencyIncrementalSnapshot
import org.tessellation.cutoff.{LogarithmicOrdinalCutoff, OrdinalCutoff}
import org.tessellation.json.JsonBrotliBinarySerializer
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.address.Address
import org.tessellation.schema.{GlobalIncrementalSnapshot, SnapshotOrdinal}
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed
import org.tessellation.security.{Hashed, SecurityProvider}

import org.typelevel.log4cats.slf4j.Slf4jLogger

trait DataApplicationTraverse[F[_]] {
  def loadChain(): F[Option[(DataState.Base, SnapshotOrdinal)]]
}

object DataApplicationTraverse {
  def make[F[_]: Async: KryoSerializer: SecurityProvider](
    lastGlobalSnapshot: Hashed[GlobalIncrementalSnapshot],
    fetchSnapshot: Hash => F[Option[Hashed[GlobalIncrementalSnapshot]]],
    dataApplication: BaseDataApplicationL0Service[F],
    calculatedStateStorage: CalculatedStateLocalFileSystemStorage[F],
    identifier: Address
  )(implicit context: L0NodeContext[F]): F[DataApplicationTraverse[F]] =
    JsonBrotliBinarySerializer.make[F]().map { jsonSerializer =>
      make[F](
        lastGlobalSnapshot,
        fetchSnapshot,
        dataApplication,
        calculatedStateStorage,
        jsonSerializer,
        identifier
      )
    }

  def make[F[_]: Async: KryoSerializer: SecurityProvider](
    lastGlobalSnapshot: Hashed[GlobalIncrementalSnapshot],
    fetchSnapshot: Hash => F[Option[Hashed[GlobalIncrementalSnapshot]]],
    dataApplication: BaseDataApplicationL0Service[F],
    calculatedStateStorage: CalculatedStateLocalFileSystemStorage[F],
    jsonSerializer: JsonBrotliBinarySerializer[F],
    identifier: Address
  )(implicit context: L0NodeContext[F]): DataApplicationTraverse[F] =
    new DataApplicationTraverse[F] {
      val logger = Slf4jLogger.getLogger[F]

      def cutoffLogic: OrdinalCutoff = LogarithmicOrdinalCutoff.make

      def loadChain(): F[Option[(DataState.Base, SnapshotOrdinal)]] = {

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
                  .map(hash => Option.when(hash === da.calculatedStateProof)(calculatedState))
              })
          }

        type Cache = Map[SnapshotOrdinal, CurrencyIncrementalSnapshot]

        def cutoffPersistedCalculatedStates(ordinal: SnapshotOrdinal) =
          logger.info(s"Cleaning persisted calculated states using logarithmic cutoff for ordinal=${ordinal.show}") >> {
            val toKeep = cutoffLogic.cutoff(SnapshotOrdinal.MinValue, ordinal)

            calculatedStateStorage.listStoredOrdinals.flatMap {
              _.compile.toList
                .map(_.toSet.diff(toKeep).toList)
                .flatMap(_.traverse(calculatedStateStorage.delete))
            }
          }

        def applyCache(
          cache: Cache,
          startingState: DataState.Base,
          startingOrdinal: SnapshotOrdinal
        ): F[(DataState.Base, SnapshotOrdinal)] = {
          implicit val ordering = Order[SnapshotOrdinal].toOrdering

          logger.info(s"Applying cache built during traversing, size=${cache.size.show}") >>
            cache.toList.sortBy { case (ordinal, _) => ordinal }
              .foldLeftM((startingState, startingOrdinal)) {
                case ((state, _), (currentOrdinal, snapshot)) =>
                  snapshot.dataApplication
                    .map(_.blocks)
                    .traverse {
                      _.traverse { blockBytes =>
                        dataApplication.deserializeBlock(blockBytes).flatMap(_.liftTo[F])
                      }
                    }
                    .map(_.toList.flatten)
                    .map(_.flatMap(_.updates.toList))
                    .flatMap(dataApplication.combine(state, _))
                    .flatTap {
                      case DataState(_, calculatedState) =>
                        logger.info(s"Persisting calculated state for ordinal=${currentOrdinal.show}") >>
                          calculatedStateStorage.write(currentOrdinal, calculatedState)(dataApplication.serializeCalculatedState(_)) >>
                          cutoffPersistedCalculatedStates(currentOrdinal)
                    }
                    .map((_, currentOrdinal))
              }
        }

        def discover: F[(Cache, Option[(DataState.Base, SnapshotOrdinal)])] = {

          type Output = (Cache, Option[(DataState.Base, SnapshotOrdinal)])
          type Acc = (Cache, Hashed[GlobalIncrementalSnapshot])

          def nestedRecursion(cache: Cache, snapshots: NonEmptyList[Hashed[CurrencyIncrementalSnapshot]]): F[Either[Cache, Output]] = {
            type NestedAcc = (List[CurrencyIncrementalSnapshot], Cache)

            def sortedSnapshots = snapshots.map(_.signed.value).sortBy(_.ordinal).reverse

            def updateCache(cache: Cache, snapshot: CurrencyIncrementalSnapshot) = cache.updated(snapshot.ordinal, snapshot)
            def readOnChainState(snapshot: CurrencyIncrementalSnapshot) =
              snapshot.dataApplication.map(_.onChainState).traverse(dataApplication.deserializeState(_).rethrow)

            (sortedSnapshots.toList, cache)
              .tailRecM[F, Output] {
                case (Nil, stepCache) =>
                  (stepCache, none[(DataState.Base, SnapshotOrdinal)]).asRight[NestedAcc].pure[F]
                case (snapshot :: tail, stepCache) =>
                  if (isIncrementalGenesis(snapshot))
                    logger.debug(s"Found metagraph genesis").as {
                      (stepCache, (dataApplication.genesis, snapshot.ordinal).some).asRight[NestedAcc]
                    }
                  else
                    readCalculatedState(snapshot).flatMap {
                      case Some(calculatedState) =>
                        readOnChainState(snapshot).flatMap {
                          case Some(onChainState) =>
                            ((stepCache, ((DataState(onChainState, calculatedState), snapshot.ordinal)).some)).asRight[NestedAcc].pure[F]
                          case _ =>
                            logger
                              .warn(
                                s"Found calculated state, but cannot decode on chain state. Check chain integrity or metagraph implementation. Trying to continue."
                              )
                              .as {
                                (tail, updateCache(stepCache, snapshot)).asLeft[Output]
                              }

                        }

                      case _ =>
                        (tail, updateCache(stepCache, snapshot)).asLeft[Output].pure[F]
                    }
              } >>= {
              case (stepCache, Some((state, ordinal))) =>
                (cache ++ stepCache, (state, ordinal).some).asRight[Cache].pure[F]
              case (stepCache, _) =>
                (cache ++ stepCache).asLeft[Output].pure[F]
            }
          }

          (Map.empty[SnapshotOrdinal, CurrencyIncrementalSnapshot], lastGlobalSnapshot).tailRecM {
            case (cache, globalSnapshot) =>
              fetchCurrencySnapshots(globalSnapshot, jsonSerializer)
                .flatMap(_.traverse {
                  case Validated.Invalid(_) =>
                    logger.warn(
                      s"Metagraph snapshots are invalid in global snapshot ordinal=${globalSnapshot.ordinal.show}. Check chain integrity. Trying to continue."
                    ) >>
                      fetchSnapshotOrErr(globalSnapshot.lastSnapshotHash).map {
                        (cache, _).asLeft[Output]
                      }
                  case Validated.Valid(snapshots) =>
                    logger.info(
                      s"Found ${snapshots.size.show} snapshots at global snapshot ordinal=${globalSnapshot.ordinal.show}, performing nested recursion."
                    ) >> nestedRecursion(cache, snapshots).flatMap {
                      case Right((newCache, Some((state, ordinal)))) => (newCache, (state, ordinal).some).asRight[Acc].pure[F]
                      case Right((newCache, None)) =>
                        fetchSnapshotOrErr(globalSnapshot.lastSnapshotHash).map((newCache, _).asLeft[Output])
                      case Left(newCache) => fetchSnapshotOrErr(globalSnapshot.lastSnapshotHash).map((newCache, _).asLeft[Output])
                    }
                })
                .flatMap {
                  _.map(_.pure[F]).getOrElse(
                    logger
                      .debug(s"Metagraph snapshots are not found in global snapshot ordinal=${globalSnapshot.ordinal.show}, continuing.") >>
                      fetchSnapshotOrErr(globalSnapshot.lastSnapshotHash).map {
                        (cache, _).asLeft[Output]
                      }
                  )
                }
          }
        }

        discover >>= {
          case (cache, Some((state, ordinal))) =>
            logger.info(s"Discovered calculated state at metagraph ordinal=${ordinal.show}") >>
              applyCache(cache, state, ordinal) >>= {
              case (latestState, latestOrdinal) =>
                dataApplication.setCalculatedState(latestOrdinal, latestState.calculated) >>
                  calculatedStateStorage.deleteAbove(latestOrdinal).as {
                    (state, ordinal).some
                  }
            }
          case (_, _) => none[(DataState.Base, SnapshotOrdinal)].pure[F]
        }
      }

      private def fetchCurrencySnapshots(
        globalSnapshot: GlobalIncrementalSnapshot,
        jsonBrotliBinarySerializer: JsonBrotliBinarySerializer[F]
      ): F[
        Option[ValidatedNel[Signed.InvalidSignatureForHash[CurrencyIncrementalSnapshot], NonEmptyList[Hashed[CurrencyIncrementalSnapshot]]]]
      ] =
        globalSnapshot.stateChannelSnapshots
          .get(identifier) match {
          case Some(snapshots) =>
            snapshots.toList.traverse { binary =>
              jsonBrotliBinarySerializer.deserialize[Signed[CurrencyIncrementalSnapshot]](binary.content)
            }
              .map(_.flatMap(_.toOption))
              .map(NonEmptyList.fromList)
              .map(_.map(_.sortBy(_.value.ordinal)))
              .flatMap(_.map(_.traverse(_.toHashedWithSignatureCheck)).sequence)
              .map(_.map(_.traverse(_.toValidatedNel)))
          case None => none.pure[F]
        }
    }
}
