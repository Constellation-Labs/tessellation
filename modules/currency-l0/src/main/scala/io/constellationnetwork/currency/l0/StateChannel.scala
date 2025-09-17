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
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo, SnapshotOrdinal}
import io.constellationnetwork.security._
import io.constellationnetwork.security.key.ops.PublicKeyOps
import io.constellationnetwork.security.signature.Signed

import eu.timepit.refined.types.all.NonNegLong
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
            enqueueConsensusEventFn
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
          Stream.eval(logger.error(error)("Error during global L0 peer discovery"))
        }

    globalL0SnapshotProcessing.merge(globalL0PeerDiscovery)
  }

  def performGlobalL0SnapshotProcess[F[_]: Async: HasherSelector: Metrics: SecurityProvider: Logger](
    storages: Storages[F],
    sharedStorages: SharedStorages[F],
    services: Services[F, Run],
    dataApplicationService: Option[BaseDataApplicationL0Service[F]],
    selfKeyPair: KeyPair,
    enqueueConsensusEventFn: CurrencySnapshotEvent => Cell[F, StackF, _, Either[CellError, Ω], _]
  )(implicit S: Supervisor[F]): F[Unit] = {
    def triggerOnGlobalSnapshotPullHook(snapshot: Hashed[GlobalIncrementalSnapshot], context: GlobalSnapshotInfo) =
      dataApplicationService match {
        case Some(service) =>
          service
            .onGlobalSnapshotPull(snapshot, context)
            .handleErrorWith(error => Logger[F].error(error)("An unexpected error occurred in onGlobalSnapshotPull"))
        case None => Applicative[F].unit
      }

    def checkIfShouldForceEventTrigger(
      snapshot: GlobalIncrementalSnapshot,
      currencyId: Address,
      globalSnapshotInfo: GlobalSnapshotInfo
    ): Boolean = {
      val spendTransactionIssuedFromThisMetagraph: Boolean =
        snapshot.spendActions.exists(_.contains(currencyId))
      val activeAllowSpendsFromThisMetagraphs: Boolean =
        globalSnapshotInfo.activeAllowSpends.exists(_.contains(currencyId.some))

      val spendActions =
        snapshot.spendActions
          .map(_.values.flatten)
          .getOrElse(List.empty)

      val activeAllowSpends =
        globalSnapshotInfo.activeAllowSpends
          .map(_.values.flatten.toList)
          .getOrElse(Nil)
          .flatMap(_._2)

      val spendTransactionsReferencesCurrentMetagraph: Boolean =
        spendActions.exists { spendAction =>
          spendAction.spendTransactions.exists { tx =>
            tx.source === currencyId ||
            tx.destination === currencyId ||
            tx.currencyId.exists(_.value === currencyId)
          }
        }

      val allowSpendsReferencesCurrentMetagraph: Boolean =
        activeAllowSpends.exists { allowSpend =>
          allowSpend.source === currencyId ||
          allowSpend.destination === currencyId
        }

      spendTransactionIssuedFromThisMetagraph || spendTransactionsReferencesCurrentMetagraph || activeAllowSpendsFromThisMetagraphs || allowSpendsReferencesCurrentMetagraph
    }

    def maybeForceEventTrigger(
      currentSnapshot: Hashed[GlobalIncrementalSnapshot],
      currentSnapshotState: GlobalSnapshotInfo
    ): F[Unit] =
      for {
//        currencyId <- storages.identifier.get
//        shouldForceEventTrigger = checkIfShouldForceEventTrigger(currentSnapshot, currencyId, currentSnapshotState)

        // Temporarily disabling the force event trigger
//        _ <-
//          if (shouldForceEventTrigger) {
//            Logger[F].info("Should force event trigger detected!")
//          } else {
//            ().pure
//          }
        _ <- conditionallyTriggerEvent(false)

      } yield ()

    def conditionallyTriggerEvent(shouldTrigger: Boolean) =
      if (shouldTrigger) {
        Logger[F].info("Forcing event trigger due to conditions met") >>
          enqueueConsensusEventFn(ForceEventTrigger()).run()
      } else {
        ().pure[F]
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
          Logger[F].warn("Couldn't send GlobalSnapshotSyncEvent. Session is missing.")
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
          lastNAlreadyInitialized <- sharedStorages.lastNGlobalSnapshot.alreadyInitialized
          _ <-
            if (!lastNAlreadyInitialized) {
              storages.lastSyncGlobalSnapshot.setInitial(snapshot, state) >>
                sharedStorages.lastNGlobalSnapshot.setInitialFetchingGL0(
                  snapshot,
                  state,
                  services.globalL0.asLeft.some,
                  none
                ) >>
                sharedStorages.lastGlobalSnapshot.setInitial(
                  snapshot,
                  state
                )
            } else {
              ().pure
            }
          _ <- triggerOnGlobalSnapshotPullHook(snapshot, state)
          _ <- maybeForceEventTrigger(snapshot, state)
        } yield ()

      case Right(snapshots) =>
        snapshots match {
          case Nil =>
            Applicative[F].unit
          case nonEmptySnapshots =>
            nonEmptySnapshots.tailRecM {
              case Nil =>
                for {
                  lastGlobalSnapshotCombined <- sharedStorages.lastGlobalSnapshot.getCombined

                  _ <- lastGlobalSnapshotCombined.traverse { combinedSnapshot =>
                    val (latestSnapshot, latestState) = combinedSnapshot
                    Logger[F].info("Trying to force event trigger") >>
                      maybeForceEventTrigger(latestSnapshot, latestState)
                  }
                } yield ().asRight[List[Hashed[GlobalIncrementalSnapshot]]]

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
                              _ <- HasherSelector[F].withCurrent { implicit hasher =>
                                sendGlobalSnapshotSyncConsensusEvent(snapshot)
                              }
                              _ <- triggerOnGlobalSnapshotPullHook(snapshot, context)
                              _ <- services.stateChannelBinarySender.confirm(snapshot).handleErrorWith { error =>
                                Logger[F].error(error)("Error when confirming state channel binary") >>
                                  updateFailedConfirmingStateChannelBinaryMetrics() >>
                                  Async[F].unit
                              }
                              _ <- S
                                .supervise(services.stateChannelBinarySender.processPending(snapshot, context))
                                .void
                                .handleErrorWith { error =>
                                  Logger[F].error(error)("Error when process pending state channel binary") >> Async[F].unit
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
