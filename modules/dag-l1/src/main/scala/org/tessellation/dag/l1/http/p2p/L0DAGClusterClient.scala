package org.tessellation.dag.l1.http.p2p

import org.tessellation.dag.domain.block.L1Output
import org.tessellation.sdk.http.p2p.PeerResponse
import org.tessellation.sdk.http.p2p.PeerResponse.PeerResponse
import org.tessellation.security.signature.Signed

import org.http4s.Method.POST
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.http4s.client.Client

trait L0DAGClusterClient[F[_]] {
  def sendL1Output(output: Signed[L1Output]): PeerResponse[F, Boolean]
}

object L0DAGClusterClient {

  def make[F[_]](client: Client[F]): L0DAGClusterClient[F] =
    new L0DAGClusterClient[F] {

      def sendL1Output(output: Signed[L1Output]): PeerResponse[F, Boolean] =
        PeerResponse[F, Boolean]("dag/l1-output", POST)(client) { (req, c) =>
          c.successful(req.withEntity(output))
        }
    }
}
