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
import io.constellationnetwork.node.shared.infrastructure.node.RestartService
import io.constellationnetwork.node.shared.infrastructure.snapshot.storage.IdentifierStorage
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.cluster._
import io.constellationnetwork.schema.node.NodeState
import io.constellationnetwork.schema.peer._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.{Hasher, SecurityProvider}

object Cluster {

  def make[F[_]: Async: SecurityProvider](
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
    wedgeMaxRefuseDuration: FiniteDuration = 1.hour
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

      // States in which the leave-guard refuses (peer is part of the consensus committee).
      // Other states (WaitingForDownload, DownloadInProgress, ReadyToJoin, etc.) always allow
      // leaves - those are legitimate restart-needed transitions, not wedge symptoms.
      private val guardedStates: Set[NodeState] =
        Set(NodeState.Observing, NodeState.WaitingForReady, NodeState.Ready)

      // Returns Some(refusalReason) when the leave should be refused, None to proceed.
      // Three-layer gate:
      //   1. Force bypass: `force=true` always permits.
      //   2. State gate: only refuse in guardedStates (in-committee states).
      //   3. Wedge gate: requires consensusHealth to report wedgeDetectedAtMs set
      //      AND elapsed-since-detection < wedgeMaxRefuseDuration.
      // Wedge signal is owned by AbandonmentTracker; see ConsensusHealthStatus.wedgeDetectedAtMs.
      private def evaluateLeaveGuard(force: Boolean): F[Option[String]] =
        if (force) Async[F].pure(None)
        else
          nodeStorage.getNodeState.flatMap { state =>
            if (!guardedStates.contains(state)) Async[F].pure(None)
            else
              consensusHealth match {
                case None => Async[F].pure(None)
                case Some(getHealth) =>
                  for {
                    health <- getHealth
                    now <- Async[F].monotonic
                    refusal = health.wedgeDetectedAtMs.flatMap { wedgeAtMs =>
                      val elapsedMs = now.toMillis - wedgeAtMs
                      if (elapsedMs < wedgeMaxRefuseDuration.toMillis)
                        Some(
                          s"cluster in sustained wedge (state=$state, " +
                            s"reason=${health.lastAbandonReason.getOrElse("unknown")}, " +
                            s"peersAtHigherKey=${health.peersAtHigherKey}, " +
                            s"consecutiveAbandonments=${health.consecutiveAbandonments}, " +
                            s"wedgeForMs=$elapsedMs)"
                        )
                      else None
                    }
                  } yield refusal
              }
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
