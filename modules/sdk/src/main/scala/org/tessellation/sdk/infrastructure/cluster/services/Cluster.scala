package org.tessellation.sdk.infrastructure.cluster.services

import java.security.KeyPair

import cats.effect.{Async, Temporal}
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.{Applicative, MonadThrow}

import scala.concurrent.duration._

import org.tessellation.ext.crypto._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.cluster._
import org.tessellation.schema.node.NodeState
import org.tessellation.schema.peer.{PeerId, RegistrationRequest, SignRequest}
import org.tessellation.sdk.config.types.HttpConfig
import org.tessellation.sdk.domain.cluster.services.Cluster
import org.tessellation.sdk.domain.cluster.storage.SessionStorage
import org.tessellation.sdk.domain.node.NodeStorage
import org.tessellation.security.SecurityProvider
import org.tessellation.security.signature.Signed

import fs2.concurrent.SignallingRef

object Cluster {

  def make[F[_]: Async: KryoSerializer: SecurityProvider](
    leavingDelay: FiniteDuration,
    httpConfig: HttpConfig,
    nodeId: PeerId,
    keyPair: KeyPair,
    sessionStorage: SessionStorage[F],
    nodeStorage: NodeStorage[F],
    restartSignal: SignallingRef[F, Unit]
  ): Cluster[F] =
    new Cluster[F] {

      def getRegistrationRequest: F[RegistrationRequest] =
        for {
          session <- sessionStorage.getToken.flatMap {
            case Some(s) => Applicative[F].pure(s)
            case None    => MonadThrow[F].raiseError[SessionToken](SessionDoesNotExist)
          }
          state <- nodeStorage.getNodeState
        } yield
          RegistrationRequest(
            nodeId,
            httpConfig.externalIp,
            httpConfig.publicHttp.port,
            httpConfig.p2pHttp.port,
            session,
            state
          )

      def signRequest(signRequest: SignRequest): F[Signed[SignRequest]] =
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
    }

}
