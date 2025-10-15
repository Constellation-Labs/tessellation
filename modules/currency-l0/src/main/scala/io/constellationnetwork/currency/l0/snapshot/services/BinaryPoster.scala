package io.constellationnetwork.currency.l0.snapshot.services

import cats.Applicative
import cats.data.{NonEmptyList, NonEmptySet}
import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.domain.allowance_list.AllowanceListEntry
import io.constellationnetwork.env.AppEnvironment
import io.constellationnetwork.env.AppEnvironment._
import io.constellationnetwork.node.shared.domain.cluster.storage.L0ClusterStorage
import io.constellationnetwork.node.shared.domain.statechannel.StateChannelValidator.StateChannelValidationError
import io.constellationnetwork.node.shared.http.p2p.clients.StateChannelSnapshotClient
import io.constellationnetwork.node.shared.infrastructure.snapshot.storage.IdentifierStorage
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.Hashed
import io.constellationnetwork.statechannel.StateChannelSnapshotBinary

import org.typelevel.log4cats.slf4j.Slf4jLogger
import retry._

class BinaryPoster[F[_]: Async](
  identifierStorage: IdentifierStorage[F],
  globalL0ClusterStorage: L0ClusterStorage[F],
  stateChannelSnapshotClient: StateChannelSnapshotClient[F],
  stateChannelAllowanceLists: Option[Map[Address, NonEmptySet[PeerId]]],
  selfId: PeerId,
  environment: AppEnvironment,
  customPeersAllowanceList: Option[Set[AllowanceListEntry]],
  binaryTracker: BinaryTracker[F]
) {
  private val logger = Slf4jLogger.getLoggerFromName(this.getClass.getName)
  private val sendRetries = 5
  private val allowedEmptyAllowanceList = List(Dev, Testnet, Integrationnet)

  def post(
    binary: Hashed[StateChannelSnapshotBinary],
    lastGlobalSnapshotSigners: Option[NonEmptySet[PeerId]]
  ): F[Option[PeerId]] = {
    val customPeersAllowed: List[PeerId] =
      customPeersAllowanceList.map(_.toList.map(_.peerId)).getOrElse(Nil)

    checkAllowanceAndPost(binary, lastGlobalSnapshotSigners, customPeersAllowed)
  }

  private def checkAllowanceAndPost(
    binary: Hashed[StateChannelSnapshotBinary],
    lastGlobalSnapshotSigners: Option[NonEmptySet[PeerId]],
    customPeersAllowed: List[PeerId]
  ): F[Option[PeerId]] =
    stateChannelAllowanceLists match {
      case Some(allowanceLists) =>
        identifierStorage.get.flatMap { metagraphId =>
          allowanceLists.get(metagraphId) match {
            case Some(allowedPeers) =>
              if (allowedPeers.contains(selfId)) {
                pickPeerAndSend(binary, lastGlobalSnapshotSigners, customPeersAllowed.filter(allowedPeers.contains))
              } else {
                none[PeerId].pure
              }
            case None =>
              handleEmptyAllowanceList(binary, lastGlobalSnapshotSigners, customPeersAllowed)
          }
        }
      case None =>
        handleEmptyAllowanceList(binary, lastGlobalSnapshotSigners, customPeersAllowed)
    }

  private def handleEmptyAllowanceList(
    binary: Hashed[StateChannelSnapshotBinary],
    lastGlobalSnapshotSigners: Option[NonEmptySet[PeerId]],
    customPeersAllowed: List[PeerId]
  ): F[Option[PeerId]] =
    if (allowedEmptyAllowanceList.contains(environment)) {
      pickPeerAndSend(binary, lastGlobalSnapshotSigners, customPeersAllowed)
    } else {
      logger
        .info(s"Empty allowance list and [$environment] is not allowed. Skipping currency snapshot.")
        .as(none[PeerId])
    }

  private def pickPeerAndSend(
    binary: Hashed[StateChannelSnapshotBinary],
    lastGlobalSnapshotSigners: Option[NonEmptySet[PeerId]],
    allowedPeers: List[PeerId]
  ): F[Option[PeerId]] = {
    val binarySigners = binary.signed.proofs.map(_.id.toPeerId).toList
    val peerToSendSnapshot = PeerSelector.pickDeterministicPeer(
      binarySigners,
      allowedPeers,
      selfId,
      binary.lastSnapshotHash
    )

    if (peerToSendSnapshot === selfId) {
      performPost(binary, lastGlobalSnapshotSigners).as(peerToSendSnapshot.some)
    } else {
      peerToSendSnapshot.some.pure
    }
  }

  private def performPost(
    binary: Hashed[StateChannelSnapshotBinary],
    lastGlobalSnapshotSigners: Option[NonEmptySet[PeerId]]
  ): F[Unit] = {
    val retryPolicy: RetryPolicy[F] = RetryPolicies.limitRetries(sendRetries)

    def wasSuccessful: Either[NonEmptyList[StateChannelValidationError], Unit] => F[Boolean] =
      _.isRight.pure[F]

    def onFailure = (_: Either[NonEmptyList[StateChannelValidationError], Unit], details: RetryDetails) =>
      logger.warn(s"Retrying sending ${binary.hash.show} to Global L0 after rejection. Retries so far ${details.retriesSoFar}")

    def onError = (_: Throwable, details: RetryDetails) =>
      logger.warn(s"Retrying sending ${binary.hash.show} to Global L0 after error. Retries so far ${details.retriesSoFar}")

    retryingOnFailuresAndAllErrors[Either[NonEmptyList[StateChannelValidationError], Unit]](
      retryPolicy,
      wasSuccessful,
      onFailure,
      onError
    )(sendToGlobalL0(binary, lastGlobalSnapshotSigners)).void
  }

  private def sendToGlobalL0(
    binary: Hashed[StateChannelSnapshotBinary],
    lastGlobalSnapshotSigners: Option[NonEmptySet[PeerId]]
  ): F[Either[NonEmptyList[StateChannelValidationError], Unit]] =
    selectPeer(lastGlobalSnapshotSigners).flatMap { l0Peer =>
      identifierStorage.get.flatMap { identifier =>
        stateChannelSnapshotClient
          .send(identifier, binary.signed)(l0Peer)
          .onError(e => logger.warn(e)(s"Sending ${binary.hash.show} snapshot to Global L0 peer ${l0Peer.show} failed!"))
          .flatTap {
            case Right(_) =>
              logger.info(s"Sent ${binary.hash.show} to Global L0 peer ${l0Peer.show}") >>
                binaryTracker.markAsSent(binary.hash)
            case Left(errors) =>
              logger.error(s"Snapshot ${binary.hash.show} rejected by Global L0 peer ${l0Peer.show}. Reasons: ${errors.show}")
          }
      }
    }

  private def selectPeer(lastGlobalSnapshotSigners: Option[NonEmptySet[PeerId]]) =
    lastGlobalSnapshotSigners.fold {
      logger.info("No last global snapshot signers provided, selecting random GL0 peer") >>
        globalL0ClusterStorage.getRandomPeer
    } { lastSigners =>
      for {
        _ <- logger.info(s"Attempting to select GL0 peer from ${lastSigners.size} last snapshot signers")
        maybeL0Peer <- globalL0ClusterStorage.getRandomPeerExistentOnList(lastSigners.toList)
        l0Peer <- maybeL0Peer match {
          case Some(peer) =>
            logger.info(s"Selected GL0 peer ${peer.show} from last snapshot signers to send binary") >>
              peer.pure
          case None =>
            for {
              randomPeer <- globalL0ClusterStorage.getRandomPeer
              _ <- logger.warn(
                s"None of the ${lastSigners.size} last snapshot signers found in current GL0 cluster. " +
                  s"Falling back to random peer: ${randomPeer.show}"
              )
            } yield randomPeer
        }
      } yield l0Peer
    }
}
