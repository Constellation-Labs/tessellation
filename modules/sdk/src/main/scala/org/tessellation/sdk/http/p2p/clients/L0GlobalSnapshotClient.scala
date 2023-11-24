package org.tessellation.sdk.http.p2p.clients

import cats.effect.Async

import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema._
import org.tessellation.sdk.domain.cluster.services.Session
import org.tessellation.sdk.http.p2p.PeerResponse
import org.tessellation.sdk.http.p2p.PeerResponse.PeerResponse
import org.tessellation.security.SecurityProvider
import org.tessellation.security.signature.Signed

import org.http4s.Uri
import org.http4s.client.Client

trait L0GlobalSnapshotClient[F[_]] extends SnapshotClient[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo] {
  def getFull(ordinal: SnapshotOrdinal): PeerResponse[F, Signed[GlobalSnapshot]]
}

object L0GlobalSnapshotClient {
  def make[F[_]: Async: SecurityProvider: KryoSerializer](
    _client: Client[F],
    maybeSession: Option[Session[F]] = None
  ): L0GlobalSnapshotClient[F] =
    new L0GlobalSnapshotClient[F] {
      val client = _client
      val optionalSession = maybeSession
      val urlPrefix = "global-snapshots"

      def getFull(ordinal: SnapshotOrdinal): PeerResponse[F, Signed[GlobalSnapshot]] = {
        import org.tessellation.ext.codecs.BinaryCodec.decoder

        PeerResponse[F, Signed[GlobalSnapshot]]((uri: Uri) => uri.addPath(s"$urlPrefix/${ordinal.value.value}").withQueryParam("full"))(
          client
        )
      }
    }
}
