package io.constellationnetwork.node.shared.infrastructure.consensus

import cats.Parallel
import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.node.shared.domain.cluster.storage.ClusterStorage
import io.constellationnetwork.node.shared.domain.gossip.Gossip.DirectPushFn
import io.constellationnetwork.node.shared.infrastructure.consensus.ConsensusLog.{Category, Event}
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.schema.gossip.RumorRaw
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.Hashed

import eu.timepit.refined.auto._
import org.typelevel.log4cats.slf4j.Slf4jLogger

/** Pushes signed rumors directly to target peers via HTTP for low-latency consensus delivery.
  *
  * Used alongside normal gossip propagation. Direct push is fire-and-forget: failures are logged but do not prevent the rumor from reaching
  * peers via the regular gossip anti-entropy protocol.
  */
object ConsensusDirectSender {

  def makeDirectPushFn[F[_]: Async: Parallel: Metrics, Key, Outcome](
    clusterStorage: ClusterStorage[F],
    consensusClient: ConsensusClient[F, Key, Outcome]
  ): DirectPushFn[F] = { (hashedRumor: Hashed[RumorRaw], targets: Set[PeerId]) =>
    val logger = Slf4jLogger.getLoggerFromName[F]("ConsensusDirectSender")

    for {
      peers <- clusterStorage.getResponsivePeers
      targetPeers = peers.filter(p => targets.contains(p.id))
      // parTraverse_: push to all peers concurrently. Sequential traverse_ caused
      // a 15s timeout to an unreachable peer to block pushes to ALL healthy peers,
      // starving them of declarations and triggering cascading evictions.
      _ <- targetPeers.toList.parTraverse_ { peer =>
        consensusClient
          .pushRumor(hashedRumor.signed)
          .run(peer)
          .void
          .handleErrorWith(err =>
            logger.debug(err)(
              ConsensusLog.format(Category.Rumor, "n/a", "n/a", Event.DirectPushFailed, "peer" -> ConsensusLog.pid(peer.id))
            )
          )
      }
      _ <- Metrics[F].incrementCounterBy("dag_consensus_direct_push_total", targetPeers.size.toLong)
    } yield ()
  }
}
