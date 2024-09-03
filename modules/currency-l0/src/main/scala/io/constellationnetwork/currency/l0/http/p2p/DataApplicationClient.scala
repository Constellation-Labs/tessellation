package io.constellationnetwork.currency.l0.http.p2p

import cats.effect.Async

import io.constellationnetwork.currency.dataApplication.DataCalculatedState
import io.constellationnetwork.node.shared.domain.cluster.services.Session
import io.constellationnetwork.node.shared.http.p2p.PeerResponse
import io.constellationnetwork.node.shared.http.p2p.PeerResponse.PeerResponse
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.security.SecurityProvider

import io.circe.Decoder
import org.http4s.circe.CirceEntityCodec.circeEntityDecoder
import org.http4s.client.Client
import org.http4s.client.dsl.Http4sClientDsl

trait DataApplicationClient[F[_]] {
  def getCalculatedState(
    implicit decoder: Decoder[DataCalculatedState]
  ): PeerResponse[F, (SnapshotOrdinal, DataCalculatedState)]
}

object DataApplicationClient {
  def make[F[_]: Async: SecurityProvider](client: Client[F], session: Session[F]): DataApplicationClient[F] =
    new DataApplicationClient[F] with Http4sClientDsl[F] {
      def getCalculatedState(
        implicit decoder: Decoder[DataCalculatedState]
      ): PeerResponse[F, (SnapshotOrdinal, DataCalculatedState)] =
        PeerResponse[F, (SnapshotOrdinal, DataCalculatedState)]("currency/state/calculated")(client, session)
    }
}
