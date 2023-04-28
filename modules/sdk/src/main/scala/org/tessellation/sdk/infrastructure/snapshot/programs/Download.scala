package org.tessellation.sdk.infrastructure.snapshot.programs

import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.functor._

import scala.concurrent.duration.DurationInt

import org.tessellation.schema.node.NodeState
import org.tessellation.schema.peer.Peer
import org.tessellation.sdk.domain.node.NodeStorage
import org.tessellation.sdk.domain.snapshot.PeerSelect
import org.tessellation.sdk.infrastructure.snapshot.SnapshotConsensus

import org.typelevel.log4cats.slf4j.Slf4jLogger
import retry.{RetryPolicies, noop, retryingOnFailures}

object Download {

  def make[F[_]: Async](
    nodeStorage: NodeStorage[F],
    consensus: SnapshotConsensus[F, _, _, _, _, _],
    peerSelecter: PeerSelect[F],
    getPeers: () => F[Set[Peer]]
  ): Download[F] =
    new Download[F](
      nodeStorage,
      consensus,
      peerSelecter,
      getPeers
    ) {}
}

sealed abstract class Download[F[_]: Async] private (
  nodeStorage: NodeStorage[F],
  consensus: SnapshotConsensus[F, _, _, _, _, _],
  peerSelect: PeerSelect[F],
  getPeers: () => F[Set[Peer]]
) {

  val logger = Slf4jLogger.getLoggerFromClass[F](Download.getClass)

  private val timeout = 15.seconds
  private val minKnownPeers = 50
  private val delayBetweenPeerCountChecks = 1.second

  private def waitUntilMinPeersFound: F[Unit] = {
    val retryPolicy = RetryPolicies.limitRetriesByCumulativeDelay(
      timeout,
      RetryPolicies.constantDelay(delayBetweenPeerCountChecks)
    )

    val wasSuccessful = (n: Int) => Async[F].pure(n >= minKnownPeers)

    retryingOnFailures(retryPolicy, wasSuccessful, noop)(getPeers().map(_.size)).flatMap(numFoundPeers =>
      logger.debug(s"Found $numFoundPeers, need at least $minKnownPeers")
    )
  }

  def download(): F[Unit] =
    nodeStorage.tryModifyState(NodeState.WaitingForDownload, NodeState.WaitingForObserving) >>
      waitUntilMinPeersFound >>
      peerSelect.select.flatMap(consensus.manager.startObserving(_))
}
