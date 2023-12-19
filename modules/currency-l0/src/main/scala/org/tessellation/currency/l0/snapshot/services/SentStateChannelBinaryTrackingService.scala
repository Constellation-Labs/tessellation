package org.tessellation.currency.l0.snapshot.services

import cats.effect.{Async, Ref}
import cats.syntax.all._

import org.tessellation.currency.l0.node.IdentifierStorage
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.GlobalIncrementalSnapshot
import org.tessellation.security.signature.Signed
import org.tessellation.security.{Hashed, Hasher}
import org.tessellation.statechannel.StateChannelSnapshotBinary

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.{NonNegInt, PosInt}
import org.typelevel.log4cats.slf4j.Slf4jLogger

trait SentStateChannelBinaryTrackingService[F[_]] {
  def setPending(binary: Signed[StateChannelSnapshotBinary]): F[Unit]
  def getRetriable: F[Option[Signed[StateChannelSnapshotBinary]]]
  def updateByGlobalSnapshot(globalSnapshot: GlobalIncrementalSnapshot): F[Unit]
}

object SentStateChannelBinaryTrackingService {
  private val retryOrdinalDelay: PosInt = 3

  def make[F[_]: Async: KryoSerializer: Hasher](
    identifierStorage: IdentifierStorage[F]
  ): F[SentStateChannelBinaryTrackingService[F]] =
    Ref
      .of[F, List[(Hashed[StateChannelSnapshotBinary], NonNegInt)]](
        List.empty
      )
      .map(make[F](_, identifierStorage))

  def make[F[_]: Async: KryoSerializer: Hasher](
    pendingR: Ref[F, List[(Hashed[StateChannelSnapshotBinary], NonNegInt)]],
    identifierStorage: IdentifierStorage[F]
  ): SentStateChannelBinaryTrackingService[F] =
    new SentStateChannelBinaryTrackingService[F] {

      private val logger = Slf4jLogger.getLogger

      private def updateByGlobalSnapshotBinaries(binaries: List[Signed[StateChannelSnapshotBinary]]): F[Unit] = {
        val confirmedHashes = binaries.traverse(_.toHashed).map(_.flatMap(b => List(b.hash, b.lastSnapshotHash)))

        confirmedHashes.flatMap { confirmed =>
          pendingR.update { currPending =>
            val newPending = currPending.zipWithIndex.collectFirst {
              case ((hashed, _), cuttingPoint) if confirmed.contains(hashed.hash) => cuttingPoint
            }.map(currPending.splitAt).map { case (stillPending, _) => stillPending }.getOrElse(currPending)

            newPending.flatMap { case (binary, checks) => NonNegInt.from(checks + 1).map((binary, _)).toOption }
          }
        }
      }

      def setPending(binary: Signed[StateChannelSnapshotBinary]): F[Unit] =
        binary.toHashed.flatMap { hashed =>
          pendingR.modify { pending =>
            val alreadyExists = pending.exists { case (binary, _) => binary.hash === hashed.hash }
            val updated = if (alreadyExists) pending else (hashed, NonNegInt.MinValue) :: pending
            (updated, alreadyExists)
          }.flatTap { alreadyExists =>
            logger.warn(s"Snapshot binary ${hashed.hash} is already enqueued for tracking!").whenA(alreadyExists)
          }.void
        }

      def getRetriable: F[Option[Signed[StateChannelSnapshotBinary]]] =
        pendingR.get.map { pending =>
          pending.findLast { case (_, checks) => checks >= retryOrdinalDelay }.map { case (binary, _) => binary.signed }
        }

      def updateByGlobalSnapshot(globalSnapshot: GlobalIncrementalSnapshot): F[Unit] =
        identifierStorage.get
          .map(globalSnapshot.stateChannelSnapshots.get)
          .map(_.toList.flatMap(_.toList))
          .flatMap(updateByGlobalSnapshotBinaries)
    }
}
