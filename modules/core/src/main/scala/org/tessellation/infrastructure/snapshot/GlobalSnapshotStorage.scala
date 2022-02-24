package org.tessellation.infrastructure.snapshot

import cats.effect.std.Queue
import cats.effect.{Async, Ref, Spawn}
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.contravariantSemigroupal._
import cats.syntax.eq._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.option._
import cats.syntax.show._
import cats.{Applicative, MonadThrow}

import org.tessellation.dag.snapshot.{GlobalSnapshot, SnapshotOrdinal}
import org.tessellation.domain.snapshot.GlobalSnapshotStorage
import org.tessellation.ext.cats.syntax.next._
import org.tessellation.ext.cats.syntax.partialPrevious._
import org.tessellation.ext.crypto._
import org.tessellation.kryo.KryoSerializer

import eu.timepit.refined.auto.autoUnwrap
import eu.timepit.refined.types.numeric.NonNegLong
import fs2.Stream
import io.chrisdavenport.mapref.MapRef
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

object GlobalSnapshotStorage {

  def make[F[_]: Async: KryoSerializer](
    globalSnapshotLocalFileSystemStorage: GlobalSnapshotLocalFileSystemStorage[F],
    genesis: GlobalSnapshot,
    inMemoryCapacity: NonNegLong
  ): F[GlobalSnapshotStorage[F]] = {
    def mkHeadRef = Ref.of[F, GlobalSnapshot](genesis)
    def mkCache = MapRef.ofSingleImmutableMap[F, SnapshotOrdinal, GlobalSnapshot](Map.empty)
    def mkOffloadQueue = Queue.unbounded[F, SnapshotOrdinal]

    def mkLogger = Slf4jLogger.create[F]

    mkLogger.flatMap { implicit logger =>
      (mkHeadRef, mkCache, mkOffloadQueue).mapN {
        make(_, _, _, globalSnapshotLocalFileSystemStorage, inMemoryCapacity)
      }.flatten
    }
  }

  def make[F[_]: Async: Logger: KryoSerializer](
    headRef: Ref[F, GlobalSnapshot],
    cache: MapRef[F, SnapshotOrdinal, Option[GlobalSnapshot]],
    offloadQueue: Queue[F, SnapshotOrdinal],
    globalSnapshotLocalFileSystemStorage: GlobalSnapshotLocalFileSystemStorage[F],
    inMemoryCapacity: NonNegLong
  ): F[GlobalSnapshotStorage[F]] = {

    def offloadProcess =
      Stream
        .fromQueueUnterminated(offloadQueue)
        .evalTap { ordinal =>
          cache(ordinal).get.flatMap {
            case Some(snapshot) =>
              globalSnapshotLocalFileSystemStorage.write(snapshot).rethrowT.handleErrorWith { e =>
                Logger[F].error(e)(s"Failed writing global snapshot to disk! Snapshot ordinal=${snapshot.ordinal.show}")
              } >> cache(ordinal).set(none)
            case None => MonadThrow[F].raiseError[Unit](new Throwable("Unexpected state!"))
          }
        }
        .compile
        .drain

    Spawn[F].start { offloadProcess }.flatMap { _ =>
      headRef.get.flatMap { h =>
        cache(h.ordinal).set(h.some)
      }
    }.map { _ =>
      new GlobalSnapshotStorage[F] {
        def prepend(snapshot: GlobalSnapshot) =
          headRef.modify { current =>
            isNextSnapshot(current, snapshot) match {
              case Left(e) =>
                (current, e.raiseError[F, Boolean])
              case Right(isNext) if isNext =>
                def run =
                  cache(snapshot.ordinal).set(snapshot.some) >>
                    snapshot.ordinal
                      .partialPreviousN(inMemoryCapacity)
                      .fold(Applicative[F].unit)(offloadQueue.offer(_))

                (snapshot, run.map(_ => true))

              case _ => (current, false.pure[F])
            }
          }.flatten

        def head = headRef.get

        def get(ordinal: SnapshotOrdinal) = cache(ordinal).get.flatMap {
          case Some(s) => s.some.pure[F]
          case None    => globalSnapshotLocalFileSystemStorage.read(ordinal).value.map(_.toOption)
        }

        private def isNextSnapshot(current: GlobalSnapshot, snapshot: GlobalSnapshot): Either[Throwable, Boolean] =
          current.hash.map { hash =>
            hash === snapshot.lastSnapshotHash && current.ordinal.next === snapshot.ordinal
          }

      }
    }
  }

}
