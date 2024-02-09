package org.tessellation.currency.dataApplication

import cats.data._
import cats.effect.Async
import cats.syntax.all._

import org.tessellation.currency.dataApplication.storage.{CalculatedStateLocalFileSystemStorage, TraverseLocalFileSystemTempStorage}
import org.tessellation.currency.schema.currency.CurrencyIncrementalSnapshot
import org.tessellation.cutoff.{LogarithmicOrdinalCutoff, OrdinalCutoff}
import org.tessellation.json.{JsonBrotliBinarySerializer, JsonSerializer}
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.address.Address
import org.tessellation.schema.{GlobalIncrementalSnapshot, SnapshotOrdinal}
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed
import org.tessellation.security.{Hashed, Hasher, SecurityProvider}

import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

trait DataApplicationTraverse[F[_]] {
  def loadChain(): F[Option[(DataState.Base, SnapshotOrdinal)]]
}

object DataApplicationTraverse {
  def make[F[_]: Async: KryoSerializer: JsonSerializer: Hasher: SecurityProvider](
    lastGlobalSnapshot: Hashed[GlobalIncrementalSnapshot],
    fetchSnapshot: Hash => F[Option[Hashed[GlobalIncrementalSnapshot]]],
    dataApplication: BaseDataApplicationL0Service[F],
    calculatedStateStorage: CalculatedStateLocalFileSystemStorage[F],
    identifier: Address
  )(implicit context: L0NodeContext[F]): F[DataApplicationTraverse[F]] =
    JsonBrotliBinarySerializer.forSync[F].map { jsonSerializer =>
      make[F](
        lastGlobalSnapshot,
        fetchSnapshot,
        dataApplication,
        calculatedStateStorage,
        jsonSerializer,
        identifier
      )
    }

  def make[F[_]: Async: KryoSerializer: JsonSerializer: Hasher: SecurityProvider](
    lastGlobalSnapshot: Hashed[GlobalIncrementalSnapshot],
    fetchSnapshot: Hash => F[Option[Hashed[GlobalIncrementalSnapshot]]],
    dataApplication: BaseDataApplicationL0Service[F],
    calculatedStateStorage: CalculatedStateLocalFileSystemStorage[F],
    jsonSerializer: JsonBrotliBinarySerializer[F],
    identifier: Address
  )(implicit context: L0NodeContext[F]): DataApplicationTraverse[F] =
    new DataApplicationTraverse[F] {
      val logger: SelfAwareStructuredLogger[F] = Slf4jLogger.getLogger[F]

      def cutoffLogic: OrdinalCutoff = LogarithmicOrdinalCutoff.make

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

        def applyCache(
          startingState: DataState.Base,
          startingOrdinal: SnapshotOrdinal
        ): F[(DataState.Base, SnapshotOrdinal)] =
          storage.listStoredOrdinals.flatMap(_.compile.toList).flatMap { ordinals =>
            logger.info(s"Applying cache built during traversing, size=${ordinals.size.show}") >>
              ordinals.sorted
                .foldLeftM((startingState, startingOrdinal)) {
                  case ((state, _), currentOrdinal) =>
                    storage.read(currentOrdinal).flatMap { snapshot =>
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
                              calculatedStateStorage.write(currentOrdinal, calculatedState)(dataApplication.serializeCalculatedState) >>
                              cutoffPersistedCalculatedStates(currentOrdinal)
                        }
                        .map((_, currentOrdinal))
                    }
                }
          }

        def discover: F[Option[(DataState.Base, SnapshotOrdinal)]] = {
          type Output = Option[(DataState.Base, SnapshotOrdinal)]
          type Acc = Hashed[GlobalIncrementalSnapshot]

          def nestedRecursion(snapshots: NonEmptyList[Hashed[CurrencyIncrementalSnapshot]]): F[Either[Unit, Output]] = {
            type NestedAcc = List[CurrencyIncrementalSnapshot]

            def sortedSnapshots = snapshots.map(_.signed.value).sortBy(_.ordinal).reverse

            def updateCache(snapshot: CurrencyIncrementalSnapshot): F[Unit] = storage.write(snapshot.ordinal, snapshot)
            def readOnChainState(snapshot: CurrencyIncrementalSnapshot) =
              snapshot.dataApplication.map(_.onChainState).traverse(dataApplication.deserializeState(_).rethrow)

            sortedSnapshots.toList
              .tailRecM[F, Output] {
                case Nil =>
                  none[(DataState.Base, SnapshotOrdinal)].asRight[NestedAcc].pure[F]
                case snapshot :: tail =>
                  if (isIncrementalGenesis(snapshot))
                    logger.debug(s"Found metagraph genesis").as {
                      (dataApplication.genesis, snapshot.ordinal).some.asRight[NestedAcc]
                    }
                  else
                    readCalculatedState(snapshot).flatMap {
                      case Some(calculatedState) =>
                        readOnChainState(snapshot).flatMap {
                          case Some(onChainState) =>
                            (DataState(onChainState, calculatedState), snapshot.ordinal).some.asRight[NestedAcc].pure[F]
                          case _ =>
                            logger
                              .warn(
                                s"Found calculated state, but cannot decode on chain state. Check chain integrity or metagraph implementation. Trying to continue."
                              ) >> updateCache(snapshot).as(tail.asLeft[Output])
                        }

                      case _ =>
                        updateCache(snapshot).as(tail.asLeft[Output])
                    }
              } >>= {
              case Some((state, ordinal)) =>
                (state, ordinal).some.asRight[Unit].pure[F]
              case _ =>
                ().asLeft[Output].pure[F]
            }
          }

          lastGlobalSnapshot.tailRecM { globalSnapshot =>
            fetchCurrencySnapshots(globalSnapshot, jsonSerializer)
              .flatMap(_.traverse {
                case Validated.Invalid(_) =>
                  (new Exception(
                    s"Metagraph snapshots are invalid in global snapshot ordinal=${globalSnapshot.ordinal.show}. Check chain integrity."
                  )).raiseError[F, Either[Acc, Output]]
                case Validated.Valid(snapshots) =>
                  logger.info(
                    s"Found ${snapshots.size.show} snapshots at global snapshot ordinal=${globalSnapshot.ordinal.show}, performing nested recursion."
                  ) >> nestedRecursion(snapshots).flatMap {
                    case Right(Some((state, ordinal))) => (state, ordinal).some.asRight[Acc].pure[F]
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
          case Some((state, ordinal)) =>
            logger.info(s"Discovered calculated state at metagraph ordinal=${ordinal.show}") >>
              applyCache(state, ordinal) >>= {
              case (latestState, latestOrdinal) =>
                dataApplication.setCalculatedState(latestOrdinal, latestState.calculated) >>
                  calculatedStateStorage.deleteAbove(latestOrdinal).as {
                    (state, ordinal).some
                  }
            }
          case _ => none[(DataState.Base, SnapshotOrdinal)].pure[F]
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
