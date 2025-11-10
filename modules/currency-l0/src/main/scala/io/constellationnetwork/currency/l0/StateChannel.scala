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
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.node.shared.modules.SharedStorages
import io.constellationnetwork.node.shared.snapshot.currency.{CurrencySnapshotEvent, ForceEventTrigger, GlobalSnapshotSyncEvent}
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security._
import io.constellationnetwork.security.key.ops.PublicKeyOps
import io.constellationnetwork.security.signature.Signed

import fs2.Stream
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

object StateChannel {

  private val awakePeriod = 10.seconds

  def run[F[_]: Async: HasherSelector: SecurityProvider: Metrics: Logger](
    services: Services[F, Run],
    storages: Storages[F],
    sharedStorages: SharedStorages[F],
    programs: Programs[F],
    dataApplicationService: Option[BaseDataApplicationL0Service[F]],
    selfKeyPair: KeyPair,
    enqueueConsensusEventFn: CurrencySnapshotEvent => Cell[F, StackF, _, Either[CellError, Ω], _]
  )(implicit S: Supervisor[F]): Stream[F, Unit] = {
    val logger = Slf4jLogger.getLoggerFromName[F](this.getClass.getName)

    def globalL0SnapshotProcessing: Stream[F, Unit] =
      Stream
        .awakeEvery[F](awakePeriod)
        .evalMap(_ =>
          performGlobalL0SnapshotProcess(
            storages,
            sharedStorages,
            services,
            dataApplicationService,
            selfKeyPair,
            enqueueConsensusEventFn,
            isStartupCall = false
          )
        )
        .handleErrorWith { error =>
          Stream.eval(logger.error(error)("Error during global L0 snapshot processing"))
        }

    def globalL0PeerDiscovery: Stream[F, Unit] =
      Stream
        .awakeEvery[F](awakePeriod)
        .evalMap { _ =>
          performGlobalL0PeerDiscovery(storages, programs)
        }
        .handleErrorWith { error =>
          Stream.eval(logger.error(error)("Error during global L0 peer discovery")) >>
            Stream.empty
        }

    Stream(
      globalL0SnapshotProcessing,
      globalL0PeerDiscovery
    ).parJoin(2)
  }

  def performGlobalL0SnapshotProcess[F[_]: Async: HasherSelector: Metrics: SecurityProvider: Logger](
    storages: Storages[F],
    sharedStorages: SharedStorages[F],
    services: Services[F, Run],
    dataApplicationService: Option[BaseDataApplicationL0Service[F]],
    selfKeyPair: KeyPair,
    enqueueConsensusEventFn: CurrencySnapshotEvent => Cell[F, StackF, _, Either[CellError, Ω], _],
    isStartupCall: Boolean
  )(implicit S: Supervisor[F]): F[Unit] = {
    def triggerOnGlobalSnapshotPullHook(snapshot: Hashed[GlobalIncrementalSnapshot], context: GlobalSnapshotInfo) =
      dataApplicationService match {
        case Some(service) =>
          service
            .onGlobalSnapshotPull(snapshot, context)
            .handleErrorWith(error => Logger[F].error(error)("An unexpected error occurred in onGlobalSnapshotPull"))
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
          for {
            signedGlobalSnapshotSync <- Signed.forAsyncHasher(sync, selfKeyPair)
            globalSyncEvent = GlobalSnapshotSyncEvent(signedGlobalSnapshotSync)

            _ <- enqueueConsensusEventFn(globalSyncEvent).run()
            _ <- storages.lastGlobalSnapshotSync.set(globalSyncEvent.value)
          } yield ()
        case (Some(_), None) =>
          if (!isStartupCall) {
            Logger[F].warn("Couldn't send GlobalSnapshotSyncEvent. Session is missing.")
          } else {
            ().pure
          }
        case (None, Some(_)) =>
          Logger[F].warn("Couldn't send GlobalSnapshotSyncEvent. Last sent reference is missing")
        case _ =>
          Logger[F].error(
            "Couldn't construct GlobalSnapshotSyncEvent. Last sent reference and session are missing"
          )
      }
    }

    services.globalL0.pullGlobalSnapshots.flatMap {
      case Left((snapshot, state)) =>
        for {
          _ <- triggerOnGlobalSnapshotPullHook(snapshot, state)
        } yield ()

      case Right(snapshots) =>
        snapshots match {
          case Nil =>
            Applicative[F].unit
          case nonEmptySnapshots =>
            nonEmptySnapshots.tailRecM {
              case Nil => ().asRight[List[Hashed[GlobalIncrementalSnapshot]]].pure
              case snapshot :: nextSnapshots =>
                storages.lastSyncGlobalSnapshot.get.map {
                  case Some(lastSnapshot) => Validator.isNextSnapshot(lastSnapshot, snapshot.signed.value)
                  case None               => true
                }.ifM(
                  for {
                    _ <- storages.lastSyncGlobalSnapshot.getCombined.flatMap {
                      case None => Applicative[F].unit
                      case Some((lastSnapshot, lastState)) =>
                        HasherSelector[F]
                          .forOrdinal(snapshot.ordinal) { implicit hasher =>
                            services.globalSnapshotContextFunctions
                              .createContext(
                                lastState,
                                lastSnapshot.signed,
                                snapshot.signed,
                                services.globalL0.pullGlobalSnapshot
                              )
                          }
                          .flatMap { context =>
                            for {
                              _ <- storages.lastSyncGlobalSnapshot.set(snapshot, context)
                              _ <- sharedStorages.lastNGlobalSnapshot.set(snapshot, context)
                              _ <- sharedStorages.lastGlobalSnapshot.set(snapshot, context)
                              _ <- storages.globalSnapshotsWithStateFileStorage
                                .write(snapshot.ordinal, GlobalSnapshotWithState(snapshot.signed, context))
                              _ <- storages.globalSnapshotsWithStateDeltasFileStorage
                                .write(
                                  snapshot.ordinal,
                                  GlobalSnapshotWithStateDeltas(snapshot.signed, context.activeAllowSpends, context.activeTokenLocks)
                                )
                              _ <- HasherSelector[F].withCurrent { implicit hasher =>
                                sendGlobalSnapshotSyncConsensusEvent(snapshot)
                              }
                              _ <- triggerOnGlobalSnapshotPullHook(snapshot, context)

                              _ <- services.stateChannelBinarySender.confirm(snapshot).handleErrorWith { error =>
                                Logger[F].error(error)("Error when confirming state channel binary") >>
                                  updateFailedConfirmingStateChannelBinaryMetrics() >>
                                  Async[F].unit
                              }
                            } yield ()
                          }
                    }
                  } yield (),
                  Applicative[F].unit
                ) >> Applicative[F].pure(nextSnapshots.asLeft[Unit])
            }
        }
    }
  }

  def performGlobalL0PeerDiscovery[F[_]: Async](
    storages: Storages[F],
    programs: Programs[F]
  ): F[Unit] =
    storages.lastSyncGlobalSnapshot.get.flatMap {
      case None =>
        storages.globalL0Cluster.getRandomPeer.flatMap(p => programs.globalL0PeerDiscovery.discoverFrom(p))
      case Some(latestSnapshot) =>
        programs.globalL0PeerDiscovery.discover(latestSnapshot.signed.proofs.map(_.id).map(PeerId._Id.reverseGet))
    }
}
