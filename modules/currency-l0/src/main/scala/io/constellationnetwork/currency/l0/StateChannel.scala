package io.constellationnetwork.currency.l0

import java.security.KeyPair

import cats.Applicative
import cats.data.OptionT
import cats.effect.Async
import cats.effect.std.Supervisor
import cats.syntax.all._

import scala.concurrent.duration._

import io.constellationnetwork.currency.dataApplication.BaseDataApplicationL0Service
import io.constellationnetwork.currency.l0.cli.method.Run
import io.constellationnetwork.currency.l0.metrics.updateFailedConfirmingStateChannelBinaryMetrics
import io.constellationnetwork.currency.l0.modules.{Programs, Services, Storages}
import io.constellationnetwork.currency.schema.globalSnapshotSync.{GlobalSnapshotSync, GlobalSnapshotSyncReference}
import io.constellationnetwork.kernel.{:: => _, _}
import io.constellationnetwork.node.shared.domain.snapshot.Validator
import io.constellationnetwork.node.shared.domain.snapshot.storage.LastNGlobalSnapshotStorage
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.node.shared.snapshot.currency.{CurrencySnapshotEvent, GlobalSnapshotSyncEvent}
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo}
import io.constellationnetwork.security._
import io.constellationnetwork.security.key.ops.PublicKeyOps
import io.constellationnetwork.security.signature.Signed

import fs2.Stream
import org.typelevel.log4cats.slf4j.Slf4jLogger

object StateChannel {

  private val awakePeriod = 10.seconds

  def run[F[_]: Async: HasherSelector: SecurityProvider: Metrics](
    services: Services[F, Run],
    storages: Storages[F],
    programs: Programs[F],
    dataApplicationService: Option[BaseDataApplicationL0Service[F]],
    selfKeyPair: KeyPair,
    enqueueConsensusEventFn: CurrencySnapshotEvent => Cell[F, StackF, _, Either[CellError, Ω], _],
    lastNGlobalSnapshotStorage: LastNGlobalSnapshotStorage[F]
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

      def sendGlobalSnapshotSyncConsensusEvent(snapshot: Hashed[GlobalIncrementalSnapshot])(implicit hs: Hasher[F]) = {
        val selfPeerId = selfKeyPair.getPublic.toId.toPeerId

        val lastSentGlobalSnapshotSync = OptionT(storages.lastGlobalSnapshotSync.get).orElse {
          OptionT(storages.snapshot.head).flatMapF {
            case (_, info) =>
              info.globalSnapshotSyncView
                .flatMap(_.get(selfPeerId))
                .traverse(GlobalSnapshotSyncReference.of[F])
                .map(_.orElse(GlobalSnapshotSyncReference.empty.some))
          }
        }.value

        (lastSentGlobalSnapshotSync, storages.session.getToken).flatMapN {
          case (Some(lastGlobalSnapshotSyncRef), Some(session)) =>
            val sync = GlobalSnapshotSync(lastGlobalSnapshotSyncRef.ordinal, snapshot.ordinal, snapshot.hash, session)
            Signed
              .forAsyncHasher(sync, selfKeyPair)
              .map(GlobalSnapshotSyncEvent(_))
              .flatMap { event =>
                enqueueConsensusEventFn(event).run() >> storages.lastGlobalSnapshotSync.set(event.value)
              }
              .void
          case (Some(_), None) =>
            logger.warn("Couldn't send GlobalSnapshotSyncEvent. Session is missing.")
          case (None, Some(_)) =>
            logger.warn("Couldn't send GlobalSnapshotSyncEvent. Last sent reference is missing")
          case _ =>
            logger.error(
              "Couldn't construct GlobalSnapshotSyncEvent. Last sent reference and session are missing"
            )
        }
      }

      Stream
        .awakeEvery[F](awakePeriod)
        .evalMap(_ => services.globalL0.pullGlobalSnapshots)
        .evalMap {
          case Left((snapshot, state)) =>
            storages.lastGlobalSnapshot.setInitial(snapshot, state) >>
              lastNGlobalSnapshotStorage.setInitial(snapshot, state) >>
              triggerOnGlobalSnapshotPullHook(snapshot, state)

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
                          services.globalSnapshotContextFunctions
                            .createContext(
                              lastState,
                              lastSnapshot.signed,
                              snapshot.signed,
                              lastNGlobalSnapshotStorage.getLastN,
                              services.globalL0.pullGlobalSnapshot
                            )
                        }
                        .flatMap { context =>
                          for {
                            _ <- storages.lastGlobalSnapshot.set(snapshot, context)
                            _ <- lastNGlobalSnapshotStorage.set(snapshot, context)
                            _ <- HasherSelector[F].withCurrent { implicit hasher =>
                              sendGlobalSnapshotSyncConsensusEvent(snapshot)
                            }
                            _ <- triggerOnGlobalSnapshotPullHook(snapshot, context)
                            _ <- services.stateChannelBinarySender.confirm(snapshot).handleErrorWith { error =>
                              logger.error(error)("Error when confirming state channel binary") >>
                                updateFailedConfirmingStateChannelBinaryMetrics() >>
                                Async[F].unit
                            }
                            _ <- S.supervise(services.stateChannelBinarySender.processPending(snapshot, context)).void.handleErrorWith {
                              error =>
                                logger.error(error)("Error when process pending state channel binary") >> Async[F].unit
                            }
                          } yield ()
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
