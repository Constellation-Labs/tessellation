package org.tessellation.currency.dataApplication

import cats.Applicative
import cats.data._
import cats.effect.Async
import cats.syntax.all._
import org.slf4j.LoggerFactory
import org.tessellation.currency.dataApplication.dataApplication.DataApplicationBlock
import org.tessellation.currency.schema.currency.CurrencyIncrementalSnapshot
import org.tessellation.json.JsonBrotliBinarySerializer
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.address.Address
import org.tessellation.schema.{GlobalIncrementalSnapshot, SnapshotOrdinal}
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed
import org.tessellation.security.{Hashed, SecurityProvider}

trait DataApplicationTraverse[F[_]] {
  def loadChain(): F[(DataState.Base, Option[SnapshotOrdinal])]
}

object DataApplicationTraverse {
  private val logger = LoggerFactory.getLogger("DataApplicationTraverse")
  def make[F[_]: Async: KryoSerializer: SecurityProvider](
    lastGlobalSnapshot: Hashed[GlobalIncrementalSnapshot],
    fetchSnapshot: Hash => F[Option[Hashed[GlobalIncrementalSnapshot]]],
    dataApplication: BaseDataApplicationL0Service[F],
    identifier: Address
  )(implicit context: L0NodeContext[F]): DataApplicationTraverse[F] =
    new DataApplicationTraverse[F] {
      def loadChain(): F[(DataState.Base, Option[SnapshotOrdinal])] = {

        def fetchSnapshotOrErr(h: Hash) = fetchSnapshot(h).flatMap(_.liftTo[F](new Throwable(s"Global snapshot not found, hash=${h.show}")))

        def hashChain(h: Hash): F[NonEmptyChain[Hash]] = fetchSnapshot(h).flatMap {
            _.traverse { snap =>
              hashChain(snap.lastSnapshotHash).map(_.append(h))
            }.map(_.getOrElse(NonEmptyChain.one(h)))
          }

        for {
          _ <- logger.info("Starting to hash the chain").pure
          incHashesNec <- hashChain(lastGlobalSnapshot.lastSnapshotHash).map { nec =>
            NonEmptyChain.fromChainAppend(nec.tail, lastGlobalSnapshot.hash)
          }

          jsonBrotliBinarySerializer <- JsonBrotliBinarySerializer.make[F]()

          (state, ordinal) <- incHashesNec.foldLeftM((dataApplication.genesis, none[SnapshotOrdinal])) { (acc, hash) =>
            acc match {
              case (lastState, _) =>
                fetchSnapshotOrErr(hash).flatMap { inc =>
                  def getStateChannelSnapshots = fetchCurrencySnapshots(inc, jsonBrotliBinarySerializer).map(_.map {
                    case Validated.Invalid(_) => List.empty[Hashed[CurrencyIncrementalSnapshot]]
                    case Validated.Valid(snapshots) => snapshots.toList
                  }.getOrElse(List.empty[Hashed[CurrencyIncrementalSnapshot]]))

                  getStateChannelSnapshots.flatMap { scSnapshots =>
                    if (scSnapshots.isEmpty) {
                      logger.info(s"Snapshot of Metagraph ${identifier.value.value} found at hash: ${hash.value}, skipping")
                      (List.empty[Signed[DataApplicationBlock]], SnapshotOrdinal.MinValue).pure[F]
                    } else {
                      val scSnapshotsOrdinal = scSnapshots.last.ordinal
                      logger.info(s"Snapshot of Metagraph ${identifier.value.value} found at ordinal $scSnapshotsOrdinal and hash ${hash.value}, combining")
                      scSnapshots
                        .flatTraverse(_.dataApplication.map(_.blocks))
                        .traverse(_.traverse(blockBytes => dataApplication.deserializeBlock(blockBytes).flatMap(_.liftTo[F])))
                        .map(_.toList.flatten)
                        .map((_, scSnapshotsOrdinal))
                    }
                  }.flatMap {
                    case (dataBlocks, lastOrdinal) =>
                      if (lastOrdinal > SnapshotOrdinal.MinValue) {
                        val updates = dataBlocks.flatMap(_.updates.toList)
                        dataApplication.combine(lastState, updates).map((_, lastOrdinal.some))
                      } else {
                        acc.pure
                      }
                  }
                }
            }
          }

          _ <- ordinal.map { lastOrdinal =>
            logger.info(s"TESTING2: $lastOrdinal")
            dataApplication.setCalculatedState(lastOrdinal, state.calculated)
          }.getOrElse(Applicative[F].unit)

        } yield (state, ordinal)
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
          case None => Async[F].pure(none)
        }
    }
}
