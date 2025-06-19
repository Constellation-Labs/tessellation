package io.constellationnetwork.currency.l1.domain.snapshot.programs

import cats.Applicative
import cats.data.{NonEmptyList, Validated, ValidatedNel}
import cats.effect.Async
import cats.effect.std.Random
import cats.syntax.all._

import io.constellationnetwork.currency.schema.currency._
import io.constellationnetwork.dag.l1.domain.address.storage.AddressStorage
import io.constellationnetwork.dag.l1.domain.block.BlockStorage
import io.constellationnetwork.dag.l1.domain.snapshot.programs.SnapshotProcessor
import io.constellationnetwork.dag.l1.domain.snapshot.programs.SnapshotProcessor._
import io.constellationnetwork.dag.l1.domain.transaction.{ContextualTransactionValidator, TransactionLimitConfig, TransactionStorage}
import io.constellationnetwork.dag.l1.infrastructure.address.storage.AddressStorage
import io.constellationnetwork.json.JsonBrotliBinarySerializer
import io.constellationnetwork.node.shared.config.types.{AllowSpendsConfig, TokenLocksConfig}
import io.constellationnetwork.node.shared.domain.snapshot.services.GlobalL0Service
import io.constellationnetwork.node.shared.domain.snapshot.storage._
import io.constellationnetwork.node.shared.domain.snapshot.{SnapshotContextFunctions, Validator}
import io.constellationnetwork.node.shared.domain.swap.{AllowSpendStorage, ContextualAllowSpendValidator}
import io.constellationnetwork.node.shared.domain.tokenlock.{ContextualTokenLockValidator, TokenLockStorage}
import io.constellationnetwork.node.shared.infrastructure.snapshot.storage.LastSnapshotStorage
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.swap.{AllowSpendReference, CurrencyId}
import io.constellationnetwork.schema.tokenLock.TokenLockReference
import io.constellationnetwork.schema.transaction.TransactionReference
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.Signed.InvalidSignatureForHash
import io.constellationnetwork.security.{Hashed, Hasher, SecurityProvider}

import org.typelevel.log4cats.slf4j.Slf4jLogger

sealed abstract class CurrencySnapshotProcessor[F[_]: Async: SecurityProvider]
    extends SnapshotProcessor[
      F,
      CurrencySnapshotStateProof,
      CurrencyIncrementalSnapshot,
      CurrencySnapshotInfo
    ]

object CurrencySnapshotProcessor {

  def make[F[_]: Async: Random: SecurityProvider](
    identifier: Address,
    addressStorage: AddressStorage[F],
    blockStorage: BlockStorage[F],
    lastGlobalSnapshotStorage: LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
    lastNGlobalSnapshotStorage: LastNGlobalSnapshotStorage[F],
    lastCurrencySnapshotStorage: LastSnapshotStorage[F, CurrencyIncrementalSnapshot, CurrencySnapshotInfo],
    transactionStorage: TransactionStorage[F],
    globalSnapshotContextFns: SnapshotContextFunctions[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
    currencySnapshotContextFns: SnapshotContextFunctions[F, CurrencyIncrementalSnapshot, CurrencySnapshotContext],
    jsonBrotliBinarySerializer: JsonBrotliBinarySerializer[F],
    transactionLimitConfig: TransactionLimitConfig,
    allowSpendsConfig: AllowSpendsConfig,
    tokenLocksConfig: TokenLocksConfig,
    txHasher: Hasher[F],
    allowSpendStorage: AllowSpendStorage[F],
    tokenLockStorage: TokenLockStorage[F],
    getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]],
    l0Service: GlobalL0Service[F]
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
                val setNGlobalSnapshots = lastNGlobalSnapshotStorage
                  .setInitialFetchingGL0(globalSnapshot, globalState, l0Service.asLeft.some, none)
                  .as[SnapshotProcessingResult](DownloadPerformed(globalSnapshotReference, Set.empty, Set.empty))

                processCurrencySnapshots(
                  globalSnapshot,
                  globalState,
                  globalSnapshotReference,
                  setGlobalSnapshot,
                  setNGlobalSnapshots,
                  getGlobalSnapshotByOrdinal
                )

              case _ => (new Throwable("unexpected state")).raiseError[F, SnapshotProcessingResult]
            }
          case Right(globalSnapshot) =>
            val globalSnapshotReference = SnapshotReference.fromHashedSnapshot(globalSnapshot)
            lastGlobalSnapshotStorage.getCombined.flatMap {
              case Some((lastGlobalSnapshot, lastGlobalState)) =>
                Validator.compare(lastGlobalSnapshot, globalSnapshot.signed.value) match {
                  case _: Validator.Next =>
                    applyGlobalSnapshotFn(
                      lastGlobalState,
                      lastGlobalSnapshot.signed,
                      globalSnapshot.signed,
                      getGlobalSnapshotByOrdinal
                    ).flatMap { state =>
                      val setGlobalSnapshot = lastGlobalSnapshotStorage
                        .set(globalSnapshot, state)
                        .as[SnapshotProcessingResult](Aligned(globalSnapshotReference, Set.empty))

                      val setNGlobalSnapshots = lastNGlobalSnapshotStorage
                        .set(globalSnapshot, state)
                        .as[SnapshotProcessingResult](DownloadPerformed(globalSnapshotReference, Set.empty, Set.empty))

                      processCurrencySnapshots(
                        globalSnapshot,
                        state,
                        globalSnapshotReference,
                        setGlobalSnapshot,
                        setNGlobalSnapshots,
                        getGlobalSnapshotByOrdinal
                      )

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
        snapshot: Signed[GlobalIncrementalSnapshot],
        getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]]
      )(implicit hasher: Hasher[F]): F[GlobalSnapshotInfo] =
        globalSnapshotContextFns.createContext(lastState, lastSnapshot, snapshot, getGlobalSnapshotByOrdinal)

      def applySnapshotFn(
        lastState: CurrencySnapshotInfo,
        lastSnapshot: Signed[CurrencyIncrementalSnapshot],
        snapshot: Signed[CurrencyIncrementalSnapshot],
        getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]]
      )(implicit hasher: Hasher[F]): F[CurrencySnapshotInfo] =
        currencySnapshotContextFns
          .createContext(
            CurrencySnapshotContext(identifier, lastState),
            lastSnapshot,
            snapshot,
            getGlobalSnapshotByOrdinal
          )
          .map(_.snapshotInfo)

      override def onDownload(snapshot: Hashed[CurrencyIncrementalSnapshot], state: CurrencySnapshotInfo): F[Unit] =
        allowSpendStorage.initByRefs(state.lastAllowSpendRefs.map(_.toMap).getOrElse(Map.empty), snapshot.ordinal) >>
          tokenLockStorage.initByRefs(state.lastTokenLockRefs.map(_.toMap).getOrElse(Map.empty), snapshot.ordinal)

      override def onRedownload(snapshot: Hashed[CurrencyIncrementalSnapshot], state: CurrencySnapshotInfo): F[Unit] =
        allowSpendStorage.replaceByRefs(state.lastAllowSpendRefs.map(_.toMap).getOrElse(Map.empty), snapshot.ordinal) >>
          tokenLockStorage.replaceByRefs(state.lastTokenLockRefs.map(_.toMap).getOrElse(Map.empty), snapshot.ordinal)

      private def processCurrencySnapshots(
        globalSnapshot: Hashed[GlobalIncrementalSnapshot],
        globalState: GlobalSnapshotInfo,
        globalSnapshotReference: SnapshotReference,
        setGlobalSnapshot: F[SnapshotProcessingResult],
        setNGlobalSnapshot: F[SnapshotProcessingResult],
        getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]]
      )(implicit hasher: Hasher[F]): F[SnapshotProcessingResult] =
        fetchCurrencySnapshots(globalSnapshot).flatMap {
          case Some(Validated.Valid(hashedSnapshots)) =>
            prepareIntermediateStorages(
              addressStorage,
              blockStorage,
              lastCurrencySnapshotStorage,
              transactionStorage,
              allowSpendStorage,
              tokenLockStorage
            ).flatMap {
              case (as, bs, lcss, ts, als, tls) =>
                type Success = NonEmptyList[Alignment]
                type Agg = (NonEmptyList[Hashed[CurrencyIncrementalSnapshot]], List[Alignment])
                type Result = Option[Success]

                lastCurrencySnapshotStorage.getCombined.flatMap {
                  case None =>
                    val snapshotToDownload = hashedSnapshots.last
                    globalState.lastCurrencySnapshots.get(identifier) match {
                      case Some(Right((_, stateToDownload))) =>
                        val toPass = (snapshotToDownload, stateToDownload).asLeft[Hashed[CurrencyIncrementalSnapshot]]

                        checkAlignment(toPass, bs, lcss, txHasher, getGlobalSnapshotByOrdinal).map { alignment =>
                          NonEmptyList.one(alignment).some
                        }
                      case _ => (new Throwable("unexpected state")).raiseError[F, Option[Success]]
                    }

                  case Some((_, _)) =>
                    (hashedSnapshots, List.empty[Alignment]).tailRecM {
                      case (NonEmptyList(snapshot, nextSnapshots), agg) =>
                        val toPass = snapshot.asRight[(Hashed[CurrencyIncrementalSnapshot], CurrencySnapshotInfo)]

                        checkAlignment(toPass, bs, lcss, txHasher, getGlobalSnapshotByOrdinal).flatMap {
                          case _: Ignore =>
                            Applicative[F].pure(none[Success].asRight[Agg])
                          case alignment =>
                            processAlignment(alignment, bs, ts, als, tls, lcss, as).as {
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
                    allowSpendStorage,
                    tokenLockStorage,
                    lastCurrencySnapshotStorage,
                    addressStorage
                  )
                }.flatMap { results =>
                  setGlobalSnapshot >>
                    setNGlobalSnapshot
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
            setGlobalSnapshot >>
              setNGlobalSnapshot
        }

      private def prepareIntermediateStorages(
        addressStorage: AddressStorage[F],
        blockStorage: BlockStorage[F],
        lastCurrencySnapshotStorage: LastSnapshotStorage[F, CurrencyIncrementalSnapshot, CurrencySnapshotInfo],
        transactionStorage: TransactionStorage[F],
        allowSpendStorage: AllowSpendStorage[F],
        tokenLockStorage: TokenLockStorage[F]
      )(implicit hasher: Hasher[F]): F[
        (
          AddressStorage[F],
          BlockStorage[F],
          LastSnapshotStorage[F, CurrencyIncrementalSnapshot, CurrencySnapshotInfo],
          TransactionStorage[F],
          AllowSpendStorage[F],
          TokenLockStorage[F]
        )
      ] = {
        val bs = blockStorage.getState().flatMap(BlockStorage.make[F](_))
        val lcss =
          lastCurrencySnapshotStorage.getCombined.flatMap(LastSnapshotStorage.make[F, CurrencyIncrementalSnapshot, CurrencySnapshotInfo](_))
        val as = addressStorage.getState.flatMap { state =>
          val (balances) = state
          AddressStorage.make(balances)
        }
        val cv = ContextualTransactionValidator.make(transactionLimitConfig, None)
        val capv = ContextualAllowSpendValidator.make(CurrencyId(identifier).some, None, allowSpendsConfig)
        val ctlv = ContextualTokenLockValidator.make(None, tokenLocksConfig, CurrencyId(identifier).some)
        val ts =
          transactionStorage.getState.flatMap {
            case (lastTxs) =>
              TransactionReference.emptyCurrency(identifier).flatMap {
                TransactionStorage.make(lastTxs, _, cv)
              }
          }

        val als =
          allowSpendStorage.getState.flatMap {
            case (lastTxs) =>
              AllowSpendReference.emptyCurrency(identifier).flatMap {
                AllowSpendStorage.make(lastTxs, _, capv)
              }
          }

        val tls =
          tokenLockStorage.getState.flatMap {
            case (lastTxs) =>
              TokenLockReference.emptyCurrency(identifier).flatMap {
                TokenLockStorage.make(lastTxs, _, ctlv)
              }
          }
        (as, bs, lcss, ts, als, tls).mapN((_, _, _, _, _, _))
      }

      // We are extracting all currency snapshots, but we don't assume that all the state channel binaries need to be
      // currency snapshots. Binary that fails to deserialize as currency snapshot are ignored here.
      private def fetchCurrencySnapshots(
        globalSnapshot: GlobalIncrementalSnapshot
      )(
        implicit hasher: Hasher[F]
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
              .flatMap(_.map(_.traverse(_.toHashedWithSignatureCheck)).sequence)
              .map(_.map(_.traverse(_.toValidatedNel)))
          case None => Async[F].pure(none)
        }
    }
}
