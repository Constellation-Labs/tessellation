package io.constellationnetwork.dag.l0.http.routes

import cats.data.Validated.{Invalid, Valid}
import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.ext.http4s.PeerIdVar
import io.constellationnetwork.kernel._
import io.constellationnetwork.node.shared.domain.node.{NodeStorage, UpdateNodeParametersValidator}
import io.constellationnetwork.node.shared.domain.snapshot.storage.SnapshotStorage
import io.constellationnetwork.node.shared.infrastructure.snapshot.storage.SnapshotLocalFileSystemStorage
import io.constellationnetwork.routes.internal._
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.node.{NodeState, UpdateNodeParameters}
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.signature.Signed

import eu.timepit.refined.auto._
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.http4s.dsl.Http4sDsl
import org.typelevel.log4cats.slf4j.Slf4jLogger

final case class NodeParametersRoutes[F[_]: Async](
  mkCell: Signed[UpdateNodeParameters] => Cell[F, StackF, _, Either[CellError, Ω], _],
  snapshotStorage: SnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
  fullGlobalSnapshotStorage: SnapshotLocalFileSystemStorage[F, GlobalSnapshot],
  nodeStorage: NodeStorage[F],
  validator: UpdateNodeParametersValidator[F]
) extends Http4sDsl[F]
    with PublicRoutes[F] {
  import org.http4s.circe.CirceEntityCodec.circeEntityDecoder

  private val logger = Slf4jLogger.getLoggerFromName[F]("NodeParametersLogger")

  protected val prefixPath: InternalUrlPrefix = "/node-params"

  private def validStateForSnapshotReturn(state: NodeState): Boolean = state === NodeState.Ready

  private def readLatestFullSnapshot(maybeOrdinal: Option[SnapshotOrdinal]): F[Option[Signed[GlobalSnapshot]]] =
    maybeOrdinal.traverse(fullGlobalSnapshotStorage.read).map(_.flatten)

  private def getLatestFullSnapshot: F[Option[Signed[GlobalSnapshot]]] =
    for {
      maybeOrdinal <- snapshotStorage.headSnapshot.map(_.map(_.ordinal))
      maybeSnapshot <- readLatestFullSnapshot(maybeOrdinal)
    } yield maybeSnapshot

  private def getLatestNodeParameters(nodeId: Id): F[Option[(Signed[UpdateNodeParameters], SnapshotOrdinal)]] =
    for {
      maybeSnapshot <- getLatestFullSnapshot
    } yield maybeSnapshot.flatMap(_.value.info.updateNodeParameters.flatMap(_.get(nodeId)))

  protected val public: HttpRoutes[F] = HttpRoutes.of[F] {
    case req @ POST -> Root =>
      getLatestFullSnapshot.flatMap {
        case Some(lastSnapshot) =>
          req
            .as[Signed[UpdateNodeParameters]]
            .flatMap(signed => validator.validate(signed, lastSnapshot.value.info))
            .flatTap {
              case Valid(signed) =>
                logger.info(s"Accepted node parameters from ${signed.proofs.map(_.id).map(PeerId.fromId(_))}")
              case Invalid(errors) =>
                logger.warn(s"Invalid node parameters: $errors")
            }
            .flatMap {
              case Valid(signed)   => mkCell(signed).run()
              case Invalid(errors) => CellError(errors.toString).asLeft[Ω].pure[F]
            }
            .flatMap {
              case Left(_)  => BadRequest()
              case Right(_) => Ok()
            }
        case None => ServiceUnavailable()
      }
    case GET -> Root / PeerIdVar(nodeId) =>
      nodeStorage.getNodeState
        .map(validStateForSnapshotReturn)
        .ifM(
          getLatestNodeParameters(nodeId.toId).flatMap {
            case Some(params) => Ok(params)
            case _            => NotFound()
          },
          ServiceUnavailable()
        )
  }

}
