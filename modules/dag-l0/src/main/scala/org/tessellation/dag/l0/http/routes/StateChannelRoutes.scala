package org.tessellation.dag.l0.http.routes

import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.traverse._

import org.tessellation.dag.l0.domain.statechannel.StateChannelService
import org.tessellation.ext.http4s.AddressVar
import org.tessellation.node.shared.domain.snapshot.storage.SnapshotStorage
import org.tessellation.routes.internal._
import org.tessellation.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo}
import org.tessellation.security.Hasher
import org.tessellation.security.signature.Signed
import org.tessellation.statechannel.{StateChannelOutput, StateChannelSnapshotBinary}

import eu.timepit.refined.auto._
import io.circe.shapes._
import org.http4s.circe.CirceEntityCodec.{circeEntityDecoder, circeEntityEncoder}
import org.http4s.dsl.Http4sDsl
import org.http4s.{EntityDecoder, HttpRoutes}
import shapeless.HNil
import shapeless.syntax.singleton._

final case class StateChannelRoutes[F[_]: Async: Hasher](
  stateChannelService: StateChannelService[F],
  snapshotStorage: SnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo]
) extends Http4sDsl[F]
    with PublicRoutes[F] {
  protected val prefixPath: InternalUrlPrefix = "/state-channels"
  implicit val decoder: EntityDecoder[F, Array[Byte]] = EntityDecoder.byteArrayDecoder[F]

  protected val public: HttpRoutes[F] = HttpRoutes.of[F] {
    case req @ POST -> Root / AddressVar(address) / "snapshot" =>
      req
        .as[Signed[StateChannelSnapshotBinary]]
        .map(StateChannelOutput(address, _))
        .flatMap { output =>
          snapshotStorage.head.map(_.map((output, _)))
        }
        .flatMap(_.traverse {
          case (output, snapshotAndState) =>
            stateChannelService.process(output, snapshotAndState)
        })
        .flatMap {
          case Some(Left(errors)) => BadRequest(errors)
          case Some(Right(_))     => Ok()
          case None               => ServiceUnavailable(("message" ->> "Node not yet ready to accept metagraph snapshots.") :: HNil)
        }
  }

}
