package io.constellationnetwork.currency.l0

import java.security.KeyPair

import cats.Applicative
import cats.effect.Async
import cats.effect.std.Supervisor
import cats.syntax.all._

import scala.concurrent.duration._

import io.constellationnetwork.currency.dataApplication.BaseDataApplicationL0Service
import io.constellationnetwork.currency.l0.cli.method.Run
import io.constellationnetwork.currency.l0.modules.{Programs, Services, Storages}
import io.constellationnetwork.currency.schema.globalSnapshotSync.GlobalSnapshotSync
import io.constellationnetwork.kernel.{:: => _, _}
import io.constellationnetwork.node.shared.domain.snapshot.Validator
import io.constellationnetwork.node.shared.snapshot.currency.{CurrencySnapshotEvent, GlobalSnapshotSyncEvent}
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo}
import io.constellationnetwork.security._
import io.constellationnetwork.security.signature.Signed

import eu.timepit.refined.auto._
import fs2.Stream
import org.typelevel.log4cats.slf4j.Slf4jLogger

object StateChannel {

  private val awakePeriod = 10.seconds

  def run[F[_]: Async: HasherSelector: SecurityProvider](
    services: Services[F, Run],
    storages: Storages[F],
    programs: Programs[F],
    dataApplicationService: Option[BaseDataApplicationL0Service[F]],
    selfKeyPair: KeyPair,
    enqueueConsensusEventFn: CurrencySnapshotEvent => Cell[F, StackF, _, Either[CellError, Ω], _]
  )(implicit S: Supervisor[F]): Stream[F, Unit] = {
    val logger = Slf4jLogger.getLogger[F]

    def globalL0SnapshotProcessing: Stream[F, Unit] = {
      def triggerOnGlobalSnapshotPullHook(snapshot: Hashed[GlobalIncrementalSnapshot], context: GlobalSnapshotInfo) =
        dataApplicationService match {
          case Some(service) =>
            service
              .onGlobalSnapshotPull(snapshot, context)
              .handleErrorWith(error => logger.error(error)("An unexpected error occurred in onGlobalSnapshotPull"))
          case None => Applicative[F].unit
        }

      Stream
        .awakeEvery[F](awakePeriod)
        .evalMap(_ => services.globalL0.pullGlobalSnapshots)
        .evalMap {
          case Left((snapshot, state)) =>
            storages.lastGlobalSnapshot.setInitial(snapshot, state) >> triggerOnGlobalSnapshotPullHook(snapshot, state)

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
                      HasherSelector[F]
                        .forOrdinal(snapshot.ordinal) { implicit hasher =>
                          services.globalSnapshotContextFunctions.createContext(lastState, lastSnapshot.signed, snapshot.signed)
                        }
                        .flatMap { context =>
                          storages.lastGlobalSnapshot.set(snapshot, context) >>
                            HasherSelector[F].withCurrent { implicit hasher =>
                              sendGlobalSnapshotSyncConsensusEvent(snapshot)
                            } >>
                            triggerOnGlobalSnapshotPullHook(snapshot, context) >>
                            services.stateChannelBinarySender.confirm(snapshot) >>
                            S.supervise(services.stateChannelBinarySender.processPending).void
                        }
                  },
                  Applicative[F].unit
                ) >> Applicative[F].pure(nextSnapshots.asLeft[Unit])
            }
        }
        .handleErrorWith { error =>
          Stream.eval(logger.error(error)("Error during global L0 snapshot processing"))
        }
    }

    def sendGlobalSnapshotSyncConsensusEvent(snapshot: Hashed[GlobalIncrementalSnapshot])(implicit hs: Hasher[F]) =
      storages.session.getToken.flatMap {
        case Some(session) =>
          val sync = GlobalSnapshotSync(snapshot.ordinal, snapshot.hash, session)

          Signed
            .forAsyncHasher(sync, selfKeyPair)
            .map(GlobalSnapshotSyncEvent(_))
            .map(enqueueConsensusEventFn)
            .flatMap(_.run())
            .void
        case None =>
          logger.error("Couldn't construct GlobalSnapshotSyncEvent. Session does not exist")
      }

    def globalL0PeerDiscovery: Stream[F, Unit] =
      Stream
        .awakeEvery[F](awakePeriod)
        .evalMap { _ =>
          performGlobalL0PeerDiscovery(storages, programs)
        }
        .handleErrorWith { error =>
          Stream.eval(logger.error(error)("Error during global L0 peer discovery"))
        }

    globalL0SnapshotProcessing.merge(globalL0PeerDiscovery)
  }

  def performGlobalL0PeerDiscovery[F[_]: Async](
    storages: Storages[F],
    programs: Programs[F]
  ): F[Unit] =
    storages.lastGlobalSnapshot.get.flatMap {
      case None =>
        storages.globalL0Cluster.getRandomPeer.flatMap(p => programs.globalL0PeerDiscovery.discoverFrom(p))
      case Some(latestSnapshot) =>
        programs.globalL0PeerDiscovery.discover(latestSnapshot.signed.proofs.map(_.id).map(PeerId._Id.reverseGet))
    }
}
