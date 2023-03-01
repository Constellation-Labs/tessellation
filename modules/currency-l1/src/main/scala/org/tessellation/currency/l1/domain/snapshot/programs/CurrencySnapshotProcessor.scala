package org.tessellation.currency.l1.domain.snapshot.programs

import cats.Applicative
import cats.data.{NonEmptyList, Validated, ValidatedNel}
import cats.effect.Async
import cats.effect.std.Random
import cats.syntax.all._

import org.tessellation.currency.schema.currency.{CurrencyBlock, CurrencySnapshot, CurrencyTransaction}
import org.tessellation.dag.l1.domain.address.storage.AddressStorage
import org.tessellation.dag.l1.domain.block.BlockStorage
import org.tessellation.dag.l1.domain.snapshot.programs.SnapshotProcessor
import org.tessellation.dag.l1.domain.snapshot.programs.SnapshotProcessor._
import org.tessellation.dag.l1.domain.snapshot.storage.LastSnapshotStorage
import org.tessellation.dag.l1.domain.transaction.TransactionStorage
import org.tessellation.dag.l1.infrastructure.address.storage.AddressStorage
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.address.Address
import org.tessellation.schema.{GlobalSnapshot, SnapshotReference}
import org.tessellation.sdk.domain.snapshot.Validator
import org.tessellation.sdk.domain.snapshot.storage.LastSnapshotStorage
import org.tessellation.security.signature.Signed
import org.tessellation.security.signature.Signed.InvalidSignatureForHash
import org.tessellation.security.{Hashed, SecurityProvider}

import eu.timepit.refined.auto._
import org.typelevel.log4cats.slf4j.Slf4jLogger

sealed abstract class CurrencySnapshotProcessor[F[_]: Async: KryoSerializer: SecurityProvider]
    extends SnapshotProcessor[F, CurrencyTransaction, CurrencyBlock, CurrencySnapshot]

object CurrencySnapshotProcessor {

  def make[F[_]: Async: Random: KryoSerializer: SecurityProvider](
    identifier: Address,
    addressStorage: AddressStorage[F],
    blockStorage: BlockStorage[F, CurrencyBlock],
    lastGlobalSnapshotStorage: LastSnapshotStorage[F, GlobalSnapshot],
    lastCurrencySnapshotStorage: LastSnapshotStorage[F, CurrencySnapshot],
    transactionStorage: TransactionStorage[F, CurrencyTransaction]
  ): CurrencySnapshotProcessor[F] =
    new CurrencySnapshotProcessor[F] {
      def process(globalSnapshot: Hashed[GlobalSnapshot]): F[SnapshotProcessingResult] = {
        val globalSnapshotReference = SnapshotReference.fromHashedSnapshot(globalSnapshot)

        lastGlobalSnapshotStorage.get.flatMap {
          case Some(lastGlobal) =>
            Validator.compare(lastGlobal, globalSnapshot.signed.value) match {
              case _: Validator.Next =>
                val setGlobalSnapshot =
                  lastGlobalSnapshotStorage
                    .set(globalSnapshot)
                    .as[SnapshotProcessingResult](Aligned(globalSnapshotReference, Set.empty))

                processCurrencySnapshots(globalSnapshot, globalSnapshotReference, setGlobalSnapshot)

              case Validator.NotNext =>
                Applicative[F].pure(SnapshotIgnored(globalSnapshotReference))
            }
          case None =>
            val setGlobalSnapshot =
              lastGlobalSnapshotStorage
                .setInitial(globalSnapshot)
                .as[SnapshotProcessingResult](DownloadPerformed(globalSnapshotReference, Set.empty, Set.empty))

            processCurrencySnapshots(globalSnapshot, globalSnapshotReference, setGlobalSnapshot)
        }
      }

      private def processCurrencySnapshots(
        globalSnapshot: Hashed[GlobalSnapshot],
        globalSnapshotReference: SnapshotReference,
        setGlobalSnapshot: F[SnapshotProcessingResult]
      ): F[SnapshotProcessingResult] =
        fetchCurrencySnapshots(globalSnapshot).flatMap {
          case Some(Validated.Valid(hashedSnapshots)) =>
            prepareIntermediateStorages(addressStorage, blockStorage, lastCurrencySnapshotStorage, transactionStorage).flatMap {
              case (as, bs, lcss, ts) =>
                type SuccessT = NonEmptyList[(Alignment, Hashed[CurrencySnapshot])]
                type LeftT = (NonEmptyList[Hashed[CurrencySnapshot]], List[(Alignment, Hashed[CurrencySnapshot])])
                type RightT = Option[SuccessT]

                (hashedSnapshots, List.empty[(Alignment, Hashed[CurrencySnapshot])]).tailRecM {
                  case (NonEmptyList(snapshot, nextSnapshots), agg) =>
                    checkAlignment(snapshot, bs, lcss).flatMap {
                      case _: Ignore =>
                        Applicative[F].pure(none[SuccessT].asRight[LeftT])
                      case alignment =>
                        processAlignment(snapshot, alignment, bs, ts, lcss, as).as {
                          val updatedAgg = agg :+ (alignment, snapshot)

                          NonEmptyList.fromList(nextSnapshots) match {
                            case Some(next) =>
                              (next, updatedAgg).asLeft[RightT]
                            case None =>
                              NonEmptyList
                                .fromList(updatedAgg)
                                .asRight[LeftT]
                          }
                        }
                    }
                }
            }.flatMap {
              case Some(alignmentsAndSnapshots) =>
                alignmentsAndSnapshots.traverse {
                  case (alignment, hashedSnapshot) =>
                    processAlignment(
                      hashedSnapshot,
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
        blockStorage: BlockStorage[F, CurrencyBlock],
        lastCurrencySnapshotStorage: LastSnapshotStorage[F, CurrencySnapshot],
        transactionStorage: TransactionStorage[F, CurrencyTransaction]
      ): F[
        (
          AddressStorage[F],
          BlockStorage[F, CurrencyBlock],
          LastSnapshotStorage[F, CurrencySnapshot],
          TransactionStorage[F, CurrencyTransaction]
        )
      ] = {
        val bs = blockStorage.getState().flatMap(BlockStorage.make[F, CurrencyBlock](_))
        val lcss = lastCurrencySnapshotStorage.get.flatMap(LastSnapshotStorage.make(_))
        val as = addressStorage.getState.flatMap(AddressStorage.make(_))
        val ts =
          transactionStorage.getState().flatMap { case (lastTxs, waitingTxs) => TransactionStorage.make(lastTxs, waitingTxs) }

        (as, bs, lcss, ts).mapN((_, _, _, _))
      }

      // We are extracting all currency snapshots, but we don't assume that all the state channel binaries need to be
      // currency snapshots. Binary that fails to deserialize as currency snapshot is ignored here.
      private def fetchCurrencySnapshots(
        globalSnapshot: GlobalSnapshot
      ): F[Option[ValidatedNel[InvalidSignatureForHash[CurrencySnapshot], NonEmptyList[Hashed[CurrencySnapshot]]]]] =
        globalSnapshot.stateChannelSnapshots
          .get(identifier)
          .map {
            _.toList.flatMap(binary => KryoSerializer[F].deserialize[Signed[CurrencySnapshot]](binary.content).toOption)
          }
          .flatMap(NonEmptyList.fromList)
          .map(_.traverse(_.toHashedWithSignatureCheck))
          .sequence
          .map(_.map(_.traverse(_.toValidatedNel)))
    }
}
