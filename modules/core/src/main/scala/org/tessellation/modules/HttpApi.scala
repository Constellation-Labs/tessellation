package org.tessellation.modules

import java.security.PrivateKey

import cats.effect.Async
import cats.syntax.functor._
import cats.syntax.semigroupk._

import org.tessellation.http.routes._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.config.AppEnvironment
import org.tessellation.sdk.config.AppEnvironment.{Dev, Testnet}
import org.tessellation.sdk.http.p2p.middleware.{PeerAuthMiddleware, `X-Id-Middleware`}
import org.tessellation.sdk.http.routes._
import org.tessellation.security.SecurityProvider

import com.comcast.ip4s.Host
import org.http4s.implicits.http4sKleisliResponseSyntaxOptionT
import org.http4s.server.middleware.{RequestLogger, ResponseLogger}
import org.http4s.{HttpApp, HttpRoutes}

object HttpApi {

  def make[F[_]: Async: SecurityProvider: KryoSerializer](
    storages: Storages[F],
    queues: Queues[F],
    services: Services[F],
    programs: Programs[F],
    privateKey: PrivateKey,
    environment: AppEnvironment,
    selfId: PeerId
  ): HttpApi[F] =
    new HttpApi[F](storages, queues, services, programs, privateKey, environment, selfId) {}
}

sealed abstract class HttpApi[F[_]: Async: SecurityProvider: KryoSerializer] private (
  storages: Storages[F],
  queues: Queues[F],
  services: Services[F],
  programs: Programs[F],
  privateKey: PrivateKey,
  environment: AppEnvironment,
  selfId: PeerId
) {

  private val clusterRoutes =
    ClusterRoutes[F](programs.joining, programs.peerDiscovery, storages.cluster, services.cluster)
  private val nodeRoutes = NodeRoutes[F](storages.node)
  private val registrationRoutes = RegistrationRoutes[F](services.cluster)
  private val gossipRoutes = GossipRoutes[F](storages.rumor, queues.rumor, services.gossip)
  private val trustRoutes = TrustRoutes[F](storages.trust, programs.trustPush)
  private val stateChannelRoutes = StateChannelRoutes[F](services.stateChannelRunner)
  private val blockRoutes = BlockRoutes[F](queues.l1Output)
  private val globalSnapshotRoutes = GlobalSnapshotRoutes[F](storages.globalSnapshot)
  private val dagRoutes = DagRoutes[F](services.dag)

  private val debugRoutes = DebugRoutes[F](storages, services).routes

  private val metricRoutes = MetricRoutes[F](services).routes

  private val openRoutes: HttpRoutes[F] =
    PeerAuthMiddleware
      .responseSignerMiddleware(privateKey, storages.session) {
        `X-Id-Middleware`.responseMiddleware(selfId) {
          (if (environment == Testnet || environment == Dev) debugRoutes else HttpRoutes.empty) <+>
            metricRoutes <+>
            stateChannelRoutes.publicRoutes <+>
            clusterRoutes.publicRoutes <+>
            globalSnapshotRoutes.publicRoutes <+>
            dagRoutes.publicRoutes
        }
      }

  private val getKnownPeersId: Host => F[Set[PeerId]] = { (host: Host) =>
    storages.cluster.getPeers(host).map(_.map(_.id))
  }

  private val p2pRoutes: HttpRoutes[F] =
    PeerAuthMiddleware.responseSignerMiddleware(privateKey, storages.session)(
      registrationRoutes.p2pPublicRoutes <+>
        clusterRoutes.p2pPublicRoutes <+>
        PeerAuthMiddleware.requestVerifierMiddleware(getKnownPeersId)(
          PeerAuthMiddleware.requestTokenVerifierMiddleware(services.session)(
            clusterRoutes.p2pRoutes <+>
              nodeRoutes.p2pRoutes <+>
              gossipRoutes.p2pRoutes <+>
              trustRoutes.p2pRoutes <+>
              blockRoutes.p2pRoutes <+>
              globalSnapshotRoutes.p2pRoutes
          )
        )
    )

  private val cliRoutes: HttpRoutes[F] =
    clusterRoutes.cliRoutes <+>
      trustRoutes.cliRoutes

  private val loggers: HttpApp[F] => HttpApp[F] = {
    { http: HttpApp[F] =>
      RequestLogger.httpApp(logHeaders = true, logBody = false)(http)
    }.andThen { http: HttpApp[F] =>
      ResponseLogger.httpApp(logHeaders = true, logBody = false)(http)
    }
  }

  val publicApp: HttpApp[F] = loggers(openRoutes.orNotFound)
  val p2pApp: HttpApp[F] = loggers(p2pRoutes.orNotFound)
  val cliApp: HttpApp[F] = loggers(cliRoutes.orNotFound)

}
