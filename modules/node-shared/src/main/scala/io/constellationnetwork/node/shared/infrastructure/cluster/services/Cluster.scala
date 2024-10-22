package io.constellationnetwork.node.shared.infrastructure.cluster.services

import java.security.KeyPair

import cats.effect.{Async, Temporal}
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.{Applicative, MonadThrow}

import scala.concurrent.duration._

import io.constellationnetwork.env.AppEnvironment
import io.constellationnetwork.ext.crypto._
import io.constellationnetwork.node.shared.config.types.HttpConfig
import io.constellationnetwork.node.shared.domain.cluster.services.Cluster
import io.constellationnetwork.node.shared.domain.cluster.storage.{ClusterStorage, SessionStorage}
import io.constellationnetwork.node.shared.domain.node.NodeStorage
import io.constellationnetwork.node.shared.domain.seedlist.SeedlistEntry
import io.constellationnetwork.node.shared.infrastructure.node.RestartService
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
    jarHash: Hash,
    environment: AppEnvironment
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
            jarHash,
            environment
          )

      def signRequest(signRequest: SignRequest)(implicit hasher: Hasher[F]): F[Signed[SignRequest]] =
        signRequest.sign(keyPair)

      def leave(): F[Unit] = {
        def process =
          nodeStorage.setNodeState(NodeState.Leaving) >>
            Temporal[F].sleep(leavingDelay) >>
            nodeStorage.setNodeState(NodeState.Offline) >>
            Temporal[F].sleep(5.seconds) >>
            restartService.signalClusterLeaveRestart()

        Temporal[F].start(process).void
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
