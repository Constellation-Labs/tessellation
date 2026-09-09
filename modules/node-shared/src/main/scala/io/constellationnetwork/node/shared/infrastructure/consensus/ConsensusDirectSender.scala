package io.constellationnetwork.node.shared.infrastructure.consensus

import java.util.concurrent.TimeoutException

import cats.Parallel
import cats.effect.Async
import cats.effect.std.{Queue, Supervisor}
import cats.syntax.all._

import scala.concurrent.duration._

import io.constellationnetwork.node.shared.domain.cluster.storage.ClusterStorage
import io.constellationnetwork.node.shared.domain.gossip.Gossip.DirectPushFn
import io.constellationnetwork.node.shared.infrastructure.consensus.ConsensusLog.{Category, Event}
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.schema.gossip.RumorRaw
import io.constellationnetwork.schema.peer.{Peer, PeerId}
import io.constellationnetwork.security.Hashed

import eu.timepit.refined.auto._
import org.typelevel.log4cats.slf4j.Slf4jLogger

/** Pushes signed rumors directly to target peers via HTTP for low-latency consensus delivery.
  *
  * Used alongside normal gossip propagation. `Gossip.spreadDirect` has already signed and enqueued the rumor on the regular gossip path
  * before invoking this callback, so direct delivery is an optimization and may be safely dropped under backpressure.
  *
  * The callback MUST return after a bounded local queue offer. It must never await peer HTTP calls on the consensus command fiber. The Aug
  * 11 IntegrationNet halt demonstrated why: one Facility push to a broad signing committee waited for the slowest target's 60-second HTTP
  * timeout, allowing another cohort to move through two views and finalize the same artifact under different local outcome metadata.
  */
object ConsensusDirectSender {

  private final case class DirectPushJob(rumor: Hashed[RumorRaw], targets: Set[PeerId])

  // Direct delivery is best-effort and normal gossip is authoritative. A bounded queue prevents a slow or partitioned fleet from converting
  // this latency optimization into unbounded memory growth. Four workers allow unrelated consensus declarations to make progress while each
  // job fans out concurrently to its target set.
  private[consensus] val QueueCapacity: Int = 256
  private[consensus] val WorkerCount: Int = 4
  private[consensus] val PerPeerTimeout: FiniteDuration = 5.seconds

  def makeDirectPushFn[F[_]: Async: Parallel: Metrics, Key, Outcome](
    clusterStorage: ClusterStorage[F],
    consensusClient: ConsensusClient[F, Key, Outcome]
  )(implicit supervisor: Supervisor[F]): F[DirectPushFn[F]] = {
    val logger = Slf4jLogger.getLoggerFromName[F]("ConsensusDirectSender")

    def recordQueueDepth(queue: Queue[F, DirectPushJob]): F[Unit] =
      queue.size.flatMap(size => Metrics[F].updateGauge("dag_consensus_direct_push_queue_size", size.toLong))

    def pushToPeer(job: DirectPushJob, peer: Peer): F[Unit] =
      for {
        started <- Async[F].monotonic
        result <- Async[F]
          .timeoutTo(
            consensusClient.pushRumor(job.rumor.signed).run(peer).void,
            PerPeerTimeout,
            new TimeoutException(s"Consensus direct push exceeded ${PerPeerTimeout.toMillis}ms").raiseError[F, Unit]
          )
          .attempt
        elapsed <- Async[F].monotonic.map(_ - started)
        outcome = result.fold(
          {
            case _: TimeoutException => "timeout"
            case _                   => "failure"
          },
          _ => "success"
        )
        tags = Seq(Metrics.unsafeLabelName("outcome") -> outcome)
        _ <- Metrics[F].recordTimeHistogram("dag_consensus_direct_push_peer", elapsed, tags)
        _ <- Metrics[F].incrementCounter("dag_consensus_direct_push_peer_total", tags)
        _ <- result.fold(
          err =>
            logger.debug(err)(
              ConsensusLog.format(
                Category.Rumor,
                "n/a",
                "n/a",
                Event.DirectPushFailed,
                "peer" -> ConsensusLog.pid(peer.id),
                "outcome" -> outcome,
                "timeoutMs" -> PerPeerTimeout.toMillis.toString
              )
            ),
          _ => Async[F].unit
        )
      } yield ()

    def deliver(job: DirectPushJob): F[Unit] =
      for {
        peers <- clusterStorage.getResponsivePeers
        targetPeers = peers.filter(p => job.targets.contains(p.id))
        unavailable = job.targets.size - targetPeers.size
        _ <- Metrics[F].incrementCounterBy("dag_consensus_direct_push_target_total", targetPeers.size.toLong)
        _ <- Metrics[F]
          .incrementCounterBy("dag_consensus_direct_push_unavailable_target_total", unavailable.toLong)
          .whenA(unavailable > 0)
        // Each peer has its own short deadline, so a job completes within one direct-push timeout instead of the shared HTTP client's 60s.
        _ <- targetPeers.toList.parTraverse_(peer => pushToPeer(job, peer))
      } yield ()

    def worker(queue: Queue[F, DirectPushJob]): F[Unit] =
      queue.take
        .flatTap(_ => recordQueueDepth(queue))
        .flatMap(deliver)
        .handleErrorWith { err =>
          Metrics[F].incrementCounter("dag_consensus_direct_push_worker_error_total") >>
            logger.warn(err)("Consensus direct-push worker failed a job; regular gossip remains authoritative")
        }
        .foreverM

    for {
      queue <- Queue.bounded[F, DirectPushJob](QueueCapacity)
      _ <- List.fill(WorkerCount)(worker(queue)).traverse_(task => supervisor.supervise(task).void)
    } yield { (hashedRumor: Hashed[RumorRaw], targets: Set[PeerId]) =>
      val job = DirectPushJob(hashedRumor, targets)
      queue.tryOffer(job).flatMap {
        case true =>
          Metrics[F].incrementCounter(
            "dag_consensus_direct_push_job_total",
            Seq(Metrics.unsafeLabelName("outcome") -> "queued")
          ) >> recordQueueDepth(queue)
        case false =>
          // The rumor was already offered to normal gossip before this callback. Dropping only the optimization is therefore safe.
          Metrics[F].incrementCounter(
            "dag_consensus_direct_push_job_total",
            Seq(Metrics.unsafeLabelName("outcome") -> "dropped")
          ) >>
            Metrics[F].incrementCounterBy("dag_consensus_direct_push_dropped_target_total", targets.size.toLong) >>
            recordQueueDepth(queue) >>
            logger.warn(s"Consensus direct-push queue full (capacity=$QueueCapacity); falling back to regular gossip")
      }
    }
  }
}
