package io.constellationnetwork.node.shared.infrastructure.cluster.services

import java.security.KeyPair

import cats.effect.{Async, Temporal}
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.traverse._
import cats.{Applicative, MonadThrow}

import scala.concurrent.duration._

import io.constellationnetwork.domain.allowance_list.AllowanceListEntry
import io.constellationnetwork.domain.seedlist.SeedlistEntry
import io.constellationnetwork.env.AppEnvironment
import io.constellationnetwork.ext.crypto._
import io.constellationnetwork.node.shared.config.types.HttpConfig
import io.constellationnetwork.node.shared.domain.cluster.services.Cluster
import io.constellationnetwork.node.shared.domain.cluster.storage.{ClusterStorage, SessionStorage}
import io.constellationnetwork.node.shared.domain.node.NodeStorage
import io.constellationnetwork.node.shared.infrastructure.consensus.engine.ConsensusHealthStatus
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.node.shared.infrastructure.node.RestartService
import io.constellationnetwork.node.shared.infrastructure.snapshot.storage.IdentifierStorage
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.cluster._
import io.constellationnetwork.schema.node.NodeState
import io.constellationnetwork.schema.peer._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.{Hasher, SecurityProvider}

import eu.timepit.refined.auto._

object Cluster {

  def make[F[_]: Async: SecurityProvider: Metrics](
    leavingDelay: FiniteDuration,
    httpConfig: HttpConfig,
    selfId: PeerId,
    keyPair: KeyPair,
    clusterStorage: ClusterStorage[F],
    sessionStorage: SessionStorage[F],
    nodeStorage: NodeStorage[F],
    seedlist: Option[Set[SeedlistEntry]],
    restartService: RestartService[F, _],
    versionHash: Hash,
    metagraphVersionHash: Hash,
    jarHash: Hash,
    environment: AppEnvironment,
    allowanceList: Option[Set[AllowanceListEntry]],
    metagraphId: Option[Address],
    consensusConfigHash: Option[Hash] = None,
    // Optional consensus-health probe. When provided, `leave()` consults it to refuse external
    // leave signals during a sustained quorum-infeasible wedge. None preserves legacy behavior
    // (every `leave()` proceeds unconditionally). Wired in dag-l0 from the consensus engine's
    // healthRef; non-wired in dag-l1 / currency-l0 where the cluster-wedge signal is not produced.
    consensusHealth: Option[F[ConsensusHealthStatus]] = None,
    // Time-based escape hatch for the leave guard. After this duration since wedge first detected,
    // leaves are permitted even if the wedge persists. Prevents permanent lock-out if a wedge
    // becomes terminal and operators legitimately need to leave. Force-flag callers also bypass.
    wedgeMaxRefuseDuration: FiniteDuration = 1.hour,
    // Optional thunk reading the monotonic timestamp of the last NodeState entry. Used by the
    // recovery-dwell guard to refuse external leave POSTs that fire while the local recovery
    // FSM is still inside its own internal recovery budget. None disables the dwell guard.
    lastStateEntryAt: Option[F[FiniteDuration]] = None,
    // Minimum time the node must remain in a recovery-path state before an external leave is
    // permitted. Matches the internal recoveryMaxWallClock so the guard never refuses past the
    // point where the local recovery FSM would itself have given up.
    recoveryDwellTime: FiniteDuration = 10.minutes
  ): Cluster[F] =
    new Cluster[F] {

      def getRegistrationRequest(implicit hasher: Hasher[F]): F[RegistrationRequest] =
        for {
          session <- sessionStorage.getToken.flatMap {
            case Some(s) => Applicative[F].pure(s)
            case None    => MonadThrow[F].raiseError[SessionToken](SessionDoesNotExist)
          }
          clusterSession <- clusterStorage.getToken.flatMap {
            case Some(s) => Applicative[F].pure(s)
            case None    => MonadThrow[F].raiseError[ClusterSessionToken](ClusterSessionDoesNotExist)
          }
          clusterId = clusterStorage.getClusterId
          state <- nodeStorage.getNodeState
          seedlistHash <- seedlist.map(_.map(_.peerId)).hash
          allowanceListHash <- allowanceList.map(_.map(_.peerId)).hash
        } yield
          RegistrationRequest(
            selfId,
            httpConfig.externalIp,
            httpConfig.publicHttp.port,
            httpConfig.p2pHttp.port,
            session,
            clusterSession,
            clusterId,
            state,
            seedlistHash,
            versionHash,
            metagraphVersionHash,
            jarHash,
            environment,
            allowanceListHash,
            metagraphId,
            consensusConfigHash
          )

      def signRequest(signRequest: SignRequest)(implicit hasher: Hasher[F]): F[Signed[SignRequest]] =
        signRequest.sign(keyPair)

      // In-committee states (peer has been admitted to consensus). Wedge gate fires here when
      // local AbandonmentTracker has detected sustained quorum-infeasibility.
      private val committeeGuardedStates: Set[NodeState] =
        Set(NodeState.Observing, NodeState.WaitingForReady, NodeState.Ready)

      // Recovery-path states (peer is downloading + transitioning toward committee). Dwell gate
      // refuses leaves while the local recovery FSM is still inside its internal recovery budget.
      private val recoveryGuardedStates: Set[NodeState] =
        Set(NodeState.WaitingForDownload, NodeState.DownloadInProgress, NodeState.WaitingForObserving)

      // Every state where the dwell gate applies. Includes BOTH recovery-path and committee
      // states: a freshly-arrived peer in WaitingForReady has no local wedge signal yet (its
      // AbandonmentTracker hasn't accumulated abandonments), so the wedge gate would let node-pilot
      // kill it during the settle-in window. The dwell gate covers that gap.
      private val dwellGuardedStates: Set[NodeState] =
        committeeGuardedStates ++ recoveryGuardedStates

      // Returns Some(refusalReason) when the leave should be refused, None to proceed.
      // Force bypass always permits. Gate order: dwell gate first (universal), then wedge gate
      // for committee states. Side-effects emit `dag_cluster_leave_refused_total{reason}` so
      // refusals are visible in Prometheus.
      private def evaluateLeaveGuard(force: Boolean): F[Option[String]] = {
        def recordRefusal(reasonLabel: String, message: String): F[Option[String]] =
          Metrics[F]
            .incrementCounter(
              "dag_cluster_leave_refused_total",
              Seq(Metrics.unsafeLabelName("reason") -> reasonLabel)
            )
            .as(Some(message))

        if (force) Async[F].pure(None)
        else
          nodeStorage.getNodeState.flatMap { state =>
            if (!dwellGuardedStates.contains(state)) Async[F].pure(None)
            else
              checkDwellGate(state, recordRefusal).flatMap {
                case some @ Some(_) => Async[F].pure(some)
                case None =>
                  if (committeeGuardedStates.contains(state)) checkWedgeGate(state, recordRefusal)
                  else Async[F].pure(None)
              }
          }
      }

      private def checkWedgeGate(
        state: NodeState,
        recordRefusal: (String, String) => F[Option[String]]
      ): F[Option[String]] =
        consensusHealth match {
          case None => Async[F].pure(None)
          case Some(getHealth) =>
            for {
              health <- getHealth
              now <- Async[F].monotonic
              result <- health.wedgeDetectedAtMs match {
                case Some(wedgeAtMs) =>
                  val elapsedMs = now.toMillis - wedgeAtMs
                  if (elapsedMs < wedgeMaxRefuseDuration.toMillis)
                    recordRefusal(
                      "wedge",
                      s"cluster in sustained wedge (state=$state, " +
                        s"reason=${health.lastAbandonReason.getOrElse("unknown")}, " +
                        s"peersAtHigherKey=${health.peersAtHigherKey}, " +
                        s"consecutiveAbandonments=${health.consecutiveAbandonments}, " +
                        s"wedgeForMs=$elapsedMs)"
                    )
                  else Async[F].pure(None: Option[String])
                case None => Async[F].pure(None: Option[String])
              }
            } yield result
        }

      private def checkDwellGate(
        state: NodeState,
        recordRefusal: (String, String) => F[Option[String]]
      ): F[Option[String]] =
        lastStateEntryAt match {
          case None => Async[F].pure(None)
          case Some(getEntryAt) =>
            for {
              entryAt <- getEntryAt
              now <- Async[F].monotonic
              dwellMs = now.toMillis - entryAt.toMillis
              result <-
                if (dwellMs < recoveryDwellTime.toMillis)
                  recordRefusal(
                    "recovery_dwell",
                    s"node settling in state (state=$state, " +
                      s"dwellMs=$dwellMs, requiredMs=${recoveryDwellTime.toMillis})"
                  )
                else Async[F].pure(None: Option[String])
            } yield result
        }

      def leave(): F[Unit] = leave(force = false)

      def leave(force: Boolean): F[Unit] = {
        def process =
          nodeStorage.setNodeState(NodeState.Leaving) >>
            Temporal[F].sleep(leavingDelay) >>
            nodeStorage.setNodeState(NodeState.Offline) >>
            Temporal[F].sleep(5.seconds) >>
            restartService.signalClusterLeaveRestart()

        evaluateLeaveGuard(force).flatMap {
          case Some(reason) => MonadThrow[F].raiseError[Unit](ClusterLeaveRefused(reason))
          case None         => Temporal[F].start(process).void
        }
      }

      def info(implicit hasher: Hasher[F]): F[Set[PeerInfo]] =
        getRegistrationRequest.flatMap { req =>
          def self = PeerInfo(
            req.id,
            req.ip,
            req.publicPort,
            req.p2pPort,
            req.clusterSession.value.toString,
            req.session.value.toString,
            req.state,
            req.jar
          )

          clusterStorage.getResponsivePeers.map(_.map(PeerInfo.fromPeer) + self)
        }

      def createSession: F[ClusterSessionToken] =
        clusterStorage.createToken

    }

}
