package org.tessellation.infrastructure.trust.storage

import cats.Applicative
import cats.effect.{Async, Ref}
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.functorFilter._

import org.tessellation.domain.trust.storage.{TrustStorage => TST}
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.trust._
import org.tessellation.storage.LocalFileSystemStorage

import fs2.io.file.Path

final class TrustStorage[F[_]: Async: KryoSerializer] private (
  path: Path,
  val trust: Ref[F, Map[PeerId, TrustInfo]],
  diskEnabled: Boolean = false
) extends LocalFileSystemStorage[F, TrustStorageData](path)
    with TST[F] {

  def writeData(): F[Unit] =
    Option
      .when(diskEnabled) {
        getTrust.flatMap { t =>
          val data = TrustStorageData(t)
          write("data", data).rethrowT.handleErrorWith { e =>
            Applicative[F].pure(())
          }
        }
      }
      .getOrElse(Applicative[F].pure(()))

  def readInitial(): F[Unit] =
    read("data").rethrowT
      .flatMap(data => trust.update(_ => data.trustInfo))
      .handleErrorWith { e =>
        Applicative[F].pure(())
      }

  def updateTrust(trustUpdates: InternalTrustUpdateBatch): F[Unit] =
    trust.update { t =>
      t ++ trustUpdates.updates.map { trustUpdate =>
        val capped = Math.max(Math.min(trustUpdate.trust, 1), -1)
        val updated = t.getOrElse(trustUpdate.id, TrustInfo()).copy(trustLabel = Some(capped))
        trustUpdate.id -> updated
      }.toMap
    }.flatTap(_ => writeData())

  def getTrust: F[Map[PeerId, TrustInfo]] = trust.get

  def getPublicTrust: F[PublicTrust] = getTrust.map { trust =>
    PublicTrust(trust.mapFilter { _.publicTrust })
  }

  def updatePeerPublicTrustInfo(peerId: PeerId, publicTrust: PublicTrust): F[Unit] =
    trust.update { t =>
      t.updatedWith(peerId) { trustInfo =>
        trustInfo
          .orElse(Some(TrustInfo()))
          .map(_.copy(peerLabels = publicTrust.labels))
      }
    }.flatTap(_ => writeData())

  override def updatePredictedTrust(trustUpdates: Map[PeerId, Double]): F[Unit] =
    trust.update { t =>
      t ++ trustUpdates.map {
        case (k, v) =>
          k -> t.getOrElse(k, TrustInfo()).copy(predictedTrust = Some(v))
      }
    }.flatTap(_ => writeData())

}

object TrustStorage {

  def make[F[_]: Async: KryoSerializer](path: Path, diskEnabled: Boolean = false): F[TrustStorage[F]] = {
    val res = for {
      trust <- Ref[F].of[Map[PeerId, TrustInfo]](Map.empty)
      t = new TrustStorage[F](path, trust, diskEnabled = diskEnabled)
    } yield {
      t
    }

    res.flatTap { storage =>
      if (diskEnabled) {
        storage.readInitial().flatMap(_ => storage.createDirectoryIfNotExists().rethrowT)
      } else Applicative[F].pure(())
    }
  }
}
