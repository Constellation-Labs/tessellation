package org.tessellation.currency.l0.http.p2p

import cats.effect.Async

import org.tessellation.currency.dataApplication.DataCalculatedState
import org.tessellation.schema.SnapshotOrdinal
import org.tessellation.sdk.domain.cluster.services.Session
import org.tessellation.sdk.http.p2p.PeerResponse
import org.tessellation.sdk.http.p2p.PeerResponse.PeerResponse
import org.tessellation.security.SecurityProvider

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
