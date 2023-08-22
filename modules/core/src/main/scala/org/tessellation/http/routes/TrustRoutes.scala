package org.tessellation.http.routes

import cats.effect.Async
import cats.syntax.applicativeError._
import cats.syntax.flatMap._

import org.tessellation.domain.cluster.programs.TrustPush
import org.tessellation.ext.codecs.BinaryCodec._
import org.tessellation.http.routes.internal.{CliRoutes, InternalUrlPrefix, P2PRoutes}
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.trust.PeerObservationAdjustmentUpdateBatch
import org.tessellation.sdk.domain.trust.storage.TrustStorage
import org.tessellation.sdk.ext.http4s.refined.RefinedRequestDecoder

import eu.timepit.refined.auto._
import org.http4s._
import org.http4s.dsl.Http4sDsl

final case class TrustRoutes[F[_]: Async: KryoSerializer](
  trustStorage: TrustStorage[F],
  trustPush: TrustPush[F]
) extends Http4sDsl[F]
    with P2PRoutes[F]
    with CliRoutes[F] {
  protected[routes] val prefixPath: InternalUrlPrefix = "/trust"

  protected val p2p: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root =>
      trustStorage.getPublicTrust.flatMap { publicTrust =>
        Ok(publicTrust)
      }
  }

  protected val cli: HttpRoutes[F] = HttpRoutes.of[F] {
    case req @ POST -> Root =>
      req.decodeR[PeerObservationAdjustmentUpdateBatch] { trustUpdates =>
        trustStorage
          .updateTrust(trustUpdates)
          .flatMap(_ => trustPush.publishUpdated())
          .flatMap(_ => Ok())
          .recoverWith {
            case _ =>
              Conflict(s"Internal trust update failure")
          }
      }
  }
}
