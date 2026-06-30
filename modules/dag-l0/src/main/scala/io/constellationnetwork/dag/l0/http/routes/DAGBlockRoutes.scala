package io.constellationnetwork.dag.l0.http.routes

import cats.effect.Async
import cats.syntax.flatMap._

import io.constellationnetwork.dag.l0.infrastructure.mempool.{DagAwaitingParent, DagAwaitingParentConfig}
import io.constellationnetwork.dag.l0.infrastructure.snapshot.{GlobalSnapshotArtifact, GlobalSnapshotContext}
import io.constellationnetwork.kernel._
import io.constellationnetwork.node.shared.domain.snapshot.storage.SnapshotStorage
import io.constellationnetwork.routes.internal._
import io.constellationnetwork.schema.Block
import io.constellationnetwork.security.signature.Signed

import derevo.circe.magnolia.encoder
import derevo.derive
import eu.timepit.refined.auto._
import org.http4s.HttpRoutes
import org.http4s.dsl.Http4sDsl

@derive(encoder)
final case class AwaitingParentResponse(
  status: String,
  currentLastTxOrdinal: Long,
  parentOrdinal: Long,
  parentOrdinalGap: Long,
  maxAcceptedParentOrdinalGap: Long,
  awaitingParentTtlSeconds: Long
)

final case class DAGBlockRoutes[F[_]: Async](
  mkCell: Signed[Block] => Cell[F, StackF, _, Either[CellError, Ω], _],
  globalSnapshotStorage: SnapshotStorage[F, GlobalSnapshotArtifact, GlobalSnapshotContext],
  awaitingParentConfig: DagAwaitingParentConfig = DagAwaitingParentConfig.default
) extends Http4sDsl[F]
    with PublicRoutes[F] {
  import org.http4s.circe.CirceEntityCodec._

  protected val prefixPath: InternalUrlPrefix = "/dag"

  protected val public: HttpRoutes[F] = HttpRoutes.of[F] {
    case req @ POST -> Root / "l1-output" =>
      req.as[Signed[Block]].flatMap { block =>
        globalSnapshotStorage.head.flatMap {
          case Some((_, context)) =>
            val parentStatus = DagAwaitingParent.status(block, context.lastTxRefs)
            val response = AwaitingParentResponse(
              status = "AwaitingParent",
              currentLastTxOrdinal = parentStatus.currentLastTxOrdinal,
              parentOrdinal = parentStatus.maxParentOrdinal,
              parentOrdinalGap = parentStatus.maxParentOrdinalGap,
              maxAcceptedParentOrdinalGap = awaitingParentConfig.maxParentOrdinalGap,
              awaitingParentTtlSeconds = awaitingParentConfig.ttl.toSeconds
            )

            if (parentStatus.maxParentOrdinalGap > awaitingParentConfig.maxParentOrdinalGap)
              BadRequest(response.copy(status = "ParentOrdinalGapTooLarge"))
            else
              mkCell(block).run().flatMap { _ =>
                if (parentStatus.awaitingParent) Accepted(response)
                else Ok()
              }

          case None =>
            mkCell(block).run().flatMap(_ => Ok())
        }
      }
  }
}
