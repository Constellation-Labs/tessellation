package org.tessellation.node.shared.infrastructure.cluster.services

import java.security.KeyPair

import cats.effect.{Async, Temporal}
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.{Applicative, MonadThrow}

import scala.concurrent.duration._

import org.tessellation.env.AppEnvironment
import org.tessellation.ext.crypto._
import org.tessellation.node.shared.config.types.HttpConfig
import org.tessellation.node.shared.domain.cluster.services.Cluster
import org.tessellation.node.shared.domain.cluster.storage.{ClusterStorage, SessionStorage}
import org.tessellation.node.shared.domain.node.NodeStorage
import org.tessellation.node.shared.domain.seedlist.SeedlistEntry
import org.tessellation.schema.cluster._
import org.tessellation.schema.node.NodeState
import org.tessellation.schema.peer._
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed
import org.tessellation.security.{Hasher, SecurityProvider}

import fs2.concurrent.SignallingRef

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
    restartSignal: SignallingRef[F, Unit],
    versionHash: Hash,
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
            restartSignal.set(())

        Temporal[F].start(process).void
      }

      def info(implicit hasher: Hasher[F]): F[Set[PeerInfo]] =
        getRegistrationRequest.flatMap { req =>
          def self = PeerInfo(
            req.id,
            req.ip,
            req.publicPort,
            req.p2pPort,
            req.session.value.toString,
            req.state
          )

          clusterStorage.getResponsivePeers.map(_.map(PeerInfo.fromPeer) + self)
        }

      def createSession: F[ClusterSessionToken] =
        clusterStorage.createToken

    }

}
