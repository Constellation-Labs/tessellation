package io.constellationnetwork.currency.l0.snapshot.services

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

  /** Decide whether THIS node should post `binary`.
    *
    *   - Honours the (unchanged) allowance gate: a node not permitted to send for this metagraph returns false.
    *   - `force = true` (retry-mode escalation): every permitted node sends, guaranteeing the stalled binary reaches the global L0 even if
    *     its deterministic owner is unavailable. The global L0 de-dups by hash.
    *   - `force = false` (normal mode): only the deterministically-chosen, currently-alive owner sends.
    */
  def shouldSelfSend(
    binary: Hashed[StateChannelSnapshotBinary],
    alivePeers: Option[Set[PeerId]],
    force: Boolean
  ): F[Boolean] =
    allowedPeersForSelection(binary).map {
      case None => false // not permitted to send for this metagraph / environment
      case Some(allowedPeers) =>
        if (force) true
        else {
          val binarySigners = binary.signed.proofs.map(_.id.toPeerId).toList
          val owner = PeerSelector.pickDeterministicPeer(binarySigners, allowedPeers, selfId, binary.lastSnapshotHash, alivePeers)
          owner === selfId
        }
    }

  /** Post the binary to a global L0 peer, with bounded retries. Marks the binary as transport-sent on success. */
  def sendSelf(
    binary: Hashed[StateChannelSnapshotBinary],
    lastGlobalSnapshotSigners: Option[NonEmptySet[PeerId]]
  ): F[Unit] =
    performPost(binary, lastGlobalSnapshotSigners)

  /** Returns Some(peers usable for deterministic selection) when this node is permitted to send, else None. Mirrors the previous
    * allowance-gating semantics exactly.
    */
  private def allowedPeersForSelection(binary: Hashed[StateChannelSnapshotBinary]): F[Option[List[PeerId]]] = {
    val customPeersAllowed: List[PeerId] =
      customPeersAllowanceList.map(_.toList.map(_.peerId)).getOrElse(Nil)

    stateChannelAllowanceLists match {
      case Some(allowanceLists) =>
        identifierStorage.get.map { metagraphId =>
          allowanceLists.get(metagraphId) match {
            case Some(allowedPeers) =>
              if (allowedPeers.contains(selfId)) customPeersAllowed.filter(allowedPeers.contains).some
              else none
            case None => emptyAllowancePeers(customPeersAllowed)
          }
        }
      case None =>
        emptyAllowancePeers(customPeersAllowed).pure[F]
    }
  }

  private def emptyAllowancePeers(customPeersAllowed: List[PeerId]): Option[List[PeerId]] =
    if (allowedEmptyAllowanceList.contains(environment)) customPeersAllowed.some
    else none

  private def performPost(
    binary: Hashed[StateChannelSnapshotBinary],
    lastGlobalSnapshotSigners: Option[NonEmptySet[PeerId]]
  ): F[Unit] = {
    val retryPolicy: RetryPolicy[F] = RetryPolicies.limitRetries(sendRetries)

    def wasSuccessful: Either[NonEmptyList[StateChannelValidationError], Unit] => F[Boolean] =
      _.isRight.pure[F]

    def onFailure = (_: Either[NonEmptyList[StateChannelValidationError], Unit], details: RetryDetails) =>
      logger.warn(s"[Queue] Retrying ${binary.hash.show} after rejection (attempt ${details.retriesSoFar})")

    def onError = (_: Throwable, details: RetryDetails) =>
      logger.warn(s"[Queue] Retrying ${binary.hash.show} after error (attempt ${details.retriesSoFar})")

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
          .onError(e => logger.warn(e)(s"[Queue] Send to ${l0Peer.show} failed for ${binary.hash.show}"))
          .flatTap {
            case Right(_) =>
              logger.info(s"[Queue] ✓ Sent ${binary.hash.show} to ${l0Peer.show}") >>
                binaryTracker.markAsSent(binary.hash)
            case Left(errors) =>
              logger.error(s"[Queue] ✗ Binary ${binary.hash.show} rejected by ${l0Peer.show}: ${errors.show}")
          }
      }
    }

  private def selectPeer(lastGlobalSnapshotSigners: Option[NonEmptySet[PeerId]]) =
    lastGlobalSnapshotSigners.fold {
      logger.info("[Queue] No signers provided, selecting random peer") >>
        globalL0ClusterStorage.getRandomPeer
    } { lastSigners =>
      for {
        _ <- logger.info(s"[Queue] Selecting from ${lastSigners.size} signers")
        maybeL0Peer <- globalL0ClusterStorage.getRandomPeerExistentOnList(lastSigners.toList)
        l0Peer <- maybeL0Peer match {
          case Some(peer) =>
            logger.info(s"[Queue] Selected ${peer.show} from signers") >> peer.pure
          case None =>
            for {
              randomPeer <- globalL0ClusterStorage.getRandomPeer
              _ <- logger.warn(s"[Queue] No signers in cluster, using random peer ${randomPeer.show}")
            } yield randomPeer
        }
      } yield l0Peer
    }
}
