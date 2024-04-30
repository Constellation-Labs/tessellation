package org.tessellation.currency.l1.domain.snapshot.programs

import cats.Applicative
import cats.data.{NonEmptyList, Validated, ValidatedNel}
import cats.effect.Async
import cats.effect.std.Random
import cats.syntax.all._

import org.tessellation.currency.schema.currency._
import org.tessellation.dag.l1.domain.address.storage.AddressStorage
import org.tessellation.dag.l1.domain.block.BlockStorage
import org.tessellation.dag.l1.domain.snapshot.programs.SnapshotProcessor
import org.tessellation.dag.l1.domain.snapshot.programs.SnapshotProcessor._
import org.tessellation.dag.l1.domain.transaction.{ContextualTransactionValidator, TransactionLimitConfig, TransactionStorage}
import org.tessellation.dag.l1.infrastructure.address.storage.AddressStorage
import org.tessellation.json.{JsonBrotliBinarySerializer, JsonSerializer}
import org.tessellation.kryo.KryoSerializer
import org.tessellation.node.shared.domain.snapshot.storage.LastSnapshotStorage
import org.tessellation.node.shared.domain.snapshot.{SnapshotContextFunctions, Validator}
import org.tessellation.node.shared.infrastructure.snapshot.storage.LastSnapshotStorage
import org.tessellation.schema.address.Address
import org.tessellation.schema.transaction.TransactionReference
import org.tessellation.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo, SnapshotReference}
import org.tessellation.security.signature.Signed
import org.tessellation.security.signature.Signed.InvalidSignatureForHash
import org.tessellation.security.{Hashed, Hasher, SecurityProvider}

import eu.timepit.refined.auto._
import org.typelevel.log4cats.slf4j.Slf4jLogger

sealed abstract class CurrencySnapshotProcessor[F[_]: Async: SecurityProvider]
    extends SnapshotProcessor[
      F,
      CurrencySnapshotStateProof,
      CurrencyIncrementalSnapshot,
      CurrencySnapshotInfo
    ]

object CurrencySnapshotProcessor {

  def make[F[_]: Async: Random: SecurityProvider: KryoSerializer: JsonSerializer](
    identifier: Address,
    addressStorage: AddressStorage[F],
    blockStorage: BlockStorage[F],
    lastGlobalSnapshotStorage: LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
    lastCurrencySnapshotStorage: LastSnapshotStorage[F, CurrencyIncrementalSnapshot, CurrencySnapshotInfo],
    transactionStorage: TransactionStorage[F],
    globalSnapshotContextFns: SnapshotContextFunctions[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
    currencySnapshotContextFns: SnapshotContextFunctions[F, CurrencyIncrementalSnapshot, CurrencySnapshotContext],
    jsonBrotliBinarySerializer: JsonBrotliBinarySerializer[F],
    transactionLimitConfig: TransactionLimitConfig,
    txHasher: Hasher[F]
  ): CurrencySnapshotProcessor[F] =
    new CurrencySnapshotProcessor[F] {
      def process(
        snapshot: Either[(Hashed[GlobalIncrementalSnapshot], GlobalSnapshotInfo), Hashed[GlobalIncrementalSnapshot]]
      )(implicit hasher: Hasher[F]): F[SnapshotProcessingResult] =
        snapshot match {
          case Left((globalSnapshot, globalState)) =>
            val globalSnapshotReference = SnapshotReference.fromHashedSnapshot(globalSnapshot)
            lastGlobalSnapshotStorage.getCombined.flatMap {
              case None =>
                val setGlobalSnapshot = lastGlobalSnapshotStorage
                  .setInitial(globalSnapshot, globalState)
                  .as[SnapshotProcessingResult](DownloadPerformed(globalSnapshotReference, Set.empty, Set.empty))

                processCurrencySnapshots(globalSnapshot, globalState, globalSnapshotReference, setGlobalSnapshot)
              case _ => (new Throwable("unexpected state")).raiseError[F, SnapshotProcessingResult]
            }
          case Right(globalSnapshot) =>
            val globalSnapshotReference = SnapshotReference.fromHashedSnapshot(globalSnapshot)
            lastGlobalSnapshotStorage.getCombined.flatMap {
              case Some((lastGlobalSnapshot, lastGlobalState)) =>
                Validator.compare(lastGlobalSnapshot, globalSnapshot.signed.value) match {
                  case _: Validator.Next =>
                    applyGlobalSnapshotFn(lastGlobalState, lastGlobalSnapshot.signed, globalSnapshot.signed).flatMap { state =>
                      val setGlobalSnapshot = lastGlobalSnapshotStorage
                        .set(globalSnapshot, state)
                        .as[SnapshotProcessingResult](Aligned(globalSnapshotReference, Set.empty))

                      processCurrencySnapshots(globalSnapshot, state, globalSnapshotReference, setGlobalSnapshot)
                    }

                  case Validator.NotNext =>
                    Applicative[F].pure(SnapshotIgnored(globalSnapshotReference))
                }
              case None => (new Throwable("unexpected state")).raiseError[F, SnapshotProcessingResult]
            }
        }

      def applyGlobalSnapshotFn(
        lastState: GlobalSnapshotInfo,
        lastSnapshot: Signed[GlobalIncrementalSnapshot],
        snapshot: Signed[GlobalIncrementalSnapshot]
      )(implicit hasher: Hasher[F]): F[GlobalSnapshotInfo] = globalSnapshotContextFns.createContext(lastState, lastSnapshot, snapshot)

      def applySnapshotFn(
        lastState: CurrencySnapshotInfo,
        lastSnapshot: Signed[CurrencyIncrementalSnapshot],
        snapshot: Signed[CurrencyIncrementalSnapshot]
      )(implicit hasher: Hasher[F]): F[CurrencySnapshotInfo] =
        currencySnapshotContextFns.createContext(CurrencySnapshotContext(identifier, lastState), lastSnapshot, snapshot).map(_.snapshotInfo)

      private def processCurrencySnapshots(
        globalSnapshot: Hashed[GlobalIncrementalSnapshot],
        globalState: GlobalSnapshotInfo,
        globalSnapshotReference: SnapshotReference,
        setGlobalSnapshot: F[SnapshotProcessingResult]
      )(implicit hasher: Hasher[F]): F[SnapshotProcessingResult] =
        fetchCurrencySnapshots(globalSnapshot).flatMap {
          case Some(Validated.Valid(hashedSnapshots)) =>
            prepareIntermediateStorages(addressStorage, blockStorage, lastCurrencySnapshotStorage, transactionStorage).flatMap {
              case (as, bs, lcss, ts) =>
                type Success = NonEmptyList[Alignment]
                type Agg = (NonEmptyList[Hashed[CurrencyIncrementalSnapshot]], List[Alignment])
                type Result = Option[Success]

                lastCurrencySnapshotStorage.getCombined.flatMap {
                  case None =>
                    val snapshotToDownload = hashedSnapshots.last
                    globalState.lastCurrencySnapshots.get(identifier) match {
                      case Some(Right((_, stateToDownload))) =>
                        val toPass = (snapshotToDownload, stateToDownload).asLeft[Hashed[CurrencyIncrementalSnapshot]]

                        checkAlignment(toPass, bs, lcss, txHasher).map { alignment =>
                          NonEmptyList.one(alignment).some
                        }
                      case _ => (new Throwable("unexpected state")).raiseError[F, Option[Success]]
                    }

                  case Some((_, _)) =>
                    (hashedSnapshots, List.empty[Alignment]).tailRecM {
                      case (NonEmptyList(snapshot, nextSnapshots), agg) =>
                        val toPass = snapshot.asRight[(Hashed[CurrencyIncrementalSnapshot], CurrencySnapshotInfo)]

                        checkAlignment(toPass, bs, lcss, txHasher).flatMap {
                          case _: Ignore =>
                            Applicative[F].pure(none[Success].asRight[Agg])
                          case alignment =>
                            processAlignment(alignment, bs, ts, lcss, as).as {
                              val updatedAgg = agg :+ alignment

                              NonEmptyList.fromList(nextSnapshots) match {
                                case Some(next) =>
                                  (next, updatedAgg).asLeft[Result]
                                case None =>
                                  NonEmptyList
                                    .fromList(updatedAgg)
                                    .asRight[Agg]
                              }
                            }
                        }

                    }
                }
            }.flatMap {
              case Some(alignments) =>
                alignments.traverse { alignment =>
                  processAlignment(
                    alignment,
                    blockStorage,
                    transactionStorage,
                    lastCurrencySnapshotStorage,
                    addressStorage
                  )
                }.flatMap { results =>
                  setGlobalSnapshot
                    .map(BatchResult(_, results))
                }
              case None => Applicative[F].pure(SnapshotIgnored(globalSnapshotReference))
            }

          case Some(Validated.Invalid(_)) =>
            Slf4jLogger
              .getLogger[F]
              .warn(s"Not all currency snapshots are signed correctly! Ignoring global snapshot: $globalSnapshotReference")
              .as(SnapshotIgnored(globalSnapshotReference))

          case None =>
            setGlobalSnapshot
        }

      private def prepareIntermediateStorages(
        addressStorage: AddressStorage[F],
        blockStorage: BlockStorage[F],
        lastCurrencySnapshotStorage: LastSnapshotStorage[F, CurrencyIncrementalSnapshot, CurrencySnapshotInfo],
        transactionStorage: TransactionStorage[F]
      )(implicit hasher: Hasher[F]): F[
        (
          AddressStorage[F],
          BlockStorage[F],
          LastSnapshotStorage[F, CurrencyIncrementalSnapshot, CurrencySnapshotInfo],
          TransactionStorage[F]
        )
      ] = {
        val bs = blockStorage.getState().flatMap(BlockStorage.make[F](_))
        val lcss =
          lastCurrencySnapshotStorage.getCombined.flatMap(LastSnapshotStorage.make[F, CurrencyIncrementalSnapshot, CurrencySnapshotInfo](_))
        val as = addressStorage.getState.flatMap(AddressStorage.make(_))
        val cv = ContextualTransactionValidator.make(transactionLimitConfig, None)
        val ts =
          transactionStorage.getState.flatMap {
            case (lastTxs) =>
              TransactionReference.emptyCurrency(identifier).flatMap {
                TransactionStorage.make(lastTxs, _, cv)
              }
          }

        (as, bs, lcss, ts).mapN((_, _, _, _))
      }

      // We are extracting all currency snapshots, but we don't assume that all the state channel binaries need to be
      // currency snapshots. Binary that fails to deserialize as currency snapshot are ignored here.
      private def fetchCurrencySnapshots(
        globalSnapshot: GlobalIncrementalSnapshot
      ): F[Option[ValidatedNel[InvalidSignatureForHash[CurrencyIncrementalSnapshot], NonEmptyList[Hashed[CurrencyIncrementalSnapshot]]]]] =
        globalSnapshot.stateChannelSnapshots
          .get(identifier) match {
          case Some(snapshots) =>
            snapshots.toList.traverse { binary =>
              jsonBrotliBinarySerializer.deserialize[Signed[CurrencyIncrementalSnapshot]](binary.content)
            }
              .map(_.flatMap(_.toOption))
              .map(NonEmptyList.fromList)
              .map(_.map(_.sortBy(_.value.ordinal)))
              .flatMap(_.map(_.traverse { snapshot =>
                def usingKryo = {
                  implicit val hasher = Hasher.forKryo[F]
                  snapshot.toHashedWithSignatureCheck
                }

                def usingJson = {
                  implicit val hasher = Hasher.forJson[F]
                  snapshot.toHashedWithSignatureCheck
                }

                usingJson.flatMap {
                  case Left(_)  => usingKryo
                  case Right(s) => s.asRight[Signed.InvalidSignatureForHash[CurrencyIncrementalSnapshot]].pure[F]
                }
              }).sequence)
              .map(_.map(_.traverse(_.toValidatedNel)))
          case None => Async[F].pure(none)
        }
    }
}
