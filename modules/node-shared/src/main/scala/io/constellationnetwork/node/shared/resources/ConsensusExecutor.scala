package io.constellationnetwork.node.shared.resources

import java.util.concurrent.{ExecutorService, Executors}

import cats.effect.kernel.{Async, Resource}

import scala.concurrent.ExecutionContext

import org.typelevel.log4cats.slf4j.Slf4jLogger

/** Dedicated `ExecutionContext` for the consensus event loop.
  *
  * Pinning the ConsensusEventLoop consume fiber onto its own work-stealing pool isolates round-timing from HTTP serving load. With PR-1 the
  * snapshot routes stream bodies from disk and bound concurrent heavy serves, but the cats-effect compute pool is still shared with HTTP
  * handlers and every other background fiber. Under a burst of legitimate fetches, GC pressure or scheduler contention can delay the
  * consume loop's `queue.take` -> `fsm.handle` step, which in turn delays declaration emission, signature collection, and proposal
  * acceptance. The consequence is a stretched per-round duration, observed downstream as elevated `dag_consensus_round_duration_seconds`
  * and round abandonments on otherwise-healthy nodes.
  *
  * The pool is intentionally small (default 2 threads): the consume loop is single-threaded by construction; the additional threads are
  * headroom for ad-hoc effects that elect to shift onto this EC. Downstream effects (gossip emits, P2P HTTP calls) explicitly shift back to
  * the default runtime via the existing cats-effect machinery; we do NOT want every effect pinned to the consensus pool, only the FSM
  * consume path.
  *
  * Lifecycle: returned as a Resource so the pool is shut down on app exit. The `Executors.newWorkStealingPool` implementation does not
  * respond to interruption; the Resource finalizer calls `shutdownNow` to release threads cleanly.
  */
object ConsensusExecutor {

  /** Build a dedicated `ExecutionContext` for consensus work.
    *
    * @param requestedThreads
    *   Desired parallelism. Values < 1 are clamped to 1. A value of 0 from config still produces a single-thread EC (callers that want the
    *   default global runtime should not call this resource at all -- see `optional` below).
    */
  def make[F[_]: Async](requestedThreads: Int): Resource[F, ExecutionContext] = {
    val threads = math.max(1, requestedThreads)
    val log = Slf4jLogger.getLogger[F]
    Resource
      .make(Async[F].delay[ExecutorService](Executors.newWorkStealingPool(threads)))(es => Async[F].delay { val _ = es.shutdownNow(); () })
      .evalTap(_ => log.info(s"Consensus executor pool started threads=$threads"))
      .map(ExecutionContext.fromExecutorService)
  }

  /** Optional flavour: returns `Some(ec)` when `requestedThreads > 0`, `None` otherwise. Callers that handle the `None` case typically fall
    * back to the default global runtime.
    */
  def optional[F[_]: Async](requestedThreads: Int): Resource[F, Option[ExecutionContext]] =
    if (requestedThreads > 0) make[F](requestedThreads).map(Option(_))
    else Resource.pure[F, Option[ExecutionContext]](None)
}
