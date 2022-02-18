package org.tessellation.infrastructure.snapshot

import cats.Applicative
import cats.data.NonEmptyList
import cats.effect.{Async, Ref}
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.order._

import scala.util.control.NoStackTrace

import org.tessellation.dag.snapshot.{GlobalSnapshot, SnapshotOrdinal, StateChannelSnapshotBinary}
import org.tessellation.domain.snapshot.GlobalSnapshotStorage
import org.tessellation.ext.cats.syntax.next._
import org.tessellation.ext.crypto._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.address._
import org.tessellation.security.hash.Hash

object GlobalSnapshotStorage {

  def make[F[_]: Async: KryoSerializer](genesis: GlobalSnapshot): F[GlobalSnapshotStorage[F]] =
    Ref[F].of[NonEmptyList[GlobalSnapshot]](NonEmptyList.of(genesis)).map(make(_))

  def make[F[_]: Async: KryoSerializer](
    snapshotsRef: Ref[F, NonEmptyList[GlobalSnapshot]]
  ): GlobalSnapshotStorage[F] = new GlobalSnapshotStorage[F] {

    def save(snapshot: GlobalSnapshot): F[Unit] =
      snapshotsRef.modify { snapshots =>
        val lastSnapshot = snapshots.head
        lastSnapshot.hash match {
          case Left(error) => (snapshots, error.raiseError[F, Unit])
          case Right(lastSnapshotHash) =>
            val expectedLink = (lastSnapshotHash, lastSnapshot.ordinal.next)
            val actualLink = (snapshot.lastSnapshotHash, snapshot.ordinal)
            if (expectedLink === actualLink) {
              (snapshot :: snapshots, Applicative[F].unit)
            } else {
              (snapshots, InvalidGlobalSnapshotChain(expectedLink, actualLink).raiseError[F, Unit])
            }
        }
      }.flatten

    def get(ordinal: SnapshotOrdinal): F[Option[GlobalSnapshot]] =
      snapshotsRef.get.map(_.find(_.ordinal === ordinal))

    def getLast: F[GlobalSnapshot] = snapshotsRef.get.map(_.head)

    def getStateChannelSnapshotUntilOrdinal(
      ordinal: SnapshotOrdinal
    )(address: Address): F[Option[StateChannelSnapshotBinary]] =
      snapshotsRef.get.map { snapshots =>
        snapshots.find { snapshot =>
          snapshot.ordinal <= ordinal && snapshot.stateChannelSnapshots.contains(address)
        }.flatMap { snapshot =>
          snapshot.stateChannelSnapshots.get(address).map(_.head)
        }
      }
  }

  type GlobalSnapshotLink = (Hash, SnapshotOrdinal)

  case class InvalidGlobalSnapshotChain(expected: GlobalSnapshotLink, actual: GlobalSnapshotLink) extends NoStackTrace

}
