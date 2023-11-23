package org.tessellation.currency.l0

import cats.Applicative
import cats.effect.Async
import cats.syntax.all._

import scala.concurrent.duration._

import org.tessellation.currency.l0.modules.{Programs, Services, Storages}
import org.tessellation.node.shared.domain.snapshot.Validator
import org.tessellation.schema.GlobalIncrementalSnapshot
import org.tessellation.schema.peer.PeerId
import org.tessellation.security.Hashed

import fs2.Stream
import org.typelevel.log4cats.slf4j.Slf4jLogger

object StateChannel {

  private val awakePeriod = 10.seconds

  def run[F[_]: Async](
    services: Services[F],
    storages: Storages[F],
    programs: Programs[F]
  ): Stream[F, Unit] = {
    val logger = Slf4jLogger.getLogger[F]

    def globalL0SnapshotProcessing: Stream[F, Unit] =
      Stream
        .awakeEvery[F](awakePeriod)
        .evalMap(_ => services.globalL0.pullGlobalSnapshots)
        .evalMap {
          case Left((snapshot, state)) => storages.lastGlobalSnapshot.setInitial(snapshot, state)

          case Right(snapshots) =>
            snapshots.tailRecM {
              case Nil => Applicative[F].pure(().asRight[List[Hashed[GlobalIncrementalSnapshot]]])

              case snapshot :: nextSnapshots =>
                storages.lastGlobalSnapshot.get.map {
                  case Some(lastSnapshot) => Validator.isNextSnapshot(lastSnapshot, snapshot.signed.value)
                  case None               => true
                }.ifM(
                  storages.lastGlobalSnapshot.getCombined.flatMap {
                    case None => Applicative[F].unit
                    case Some((lastSnapshot, lastState)) =>
                      services.globalSnapshotContextFunctions.createContext(lastState, lastSnapshot.signed, snapshot.signed).flatMap {
                        context =>
                          storages.lastGlobalSnapshot.set(snapshot, context)
                      }
                  },
                  Applicative[F].unit
                ) >> Applicative[F].pure(nextSnapshots.asLeft[Unit])
            }
        }
        .handleErrorWith { error =>
          Stream.eval(logger.error(error)("Error during global L0 snapshot processing"))
        }

    def globalL0PeerDiscovery: Stream[F, Unit] =
      Stream
        .awakeEvery[F](awakePeriod)
        .evalMap { _ =>
          storages.lastGlobalSnapshot.get.flatMap {
            case None =>
              storages.globalL0Cluster.getRandomPeer.flatMap(p => programs.globalL0PeerDiscovery.discoverFrom(p))
            case Some(latestSnapshot) =>
              programs.globalL0PeerDiscovery.discover(latestSnapshot.signed.proofs.map(_.id).map(PeerId._Id.reverseGet))
          }
        }
        .handleErrorWith { error =>
          Stream.eval(logger.error(error)("Error during global L0 peer discovery"))
        }

    globalL0SnapshotProcessing.merge(globalL0PeerDiscovery)
  }
}
