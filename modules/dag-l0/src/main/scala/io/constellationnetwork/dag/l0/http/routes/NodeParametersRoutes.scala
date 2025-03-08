package io.constellationnetwork.dag.l0.http.routes

import cats.data.Validated.{Invalid, Valid}
import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.ext.http4s.PeerIdVar
import io.constellationnetwork.kernel._
import io.constellationnetwork.node.shared.domain.cluster.services.Cluster
import io.constellationnetwork.node.shared.domain.node.{NodeStorage, UpdateNodeParametersValidator}
import io.constellationnetwork.node.shared.domain.snapshot.storage.SnapshotStorage
import io.constellationnetwork.node.shared.infrastructure.snapshot.storage.SnapshotLocalFileSystemStorage
import io.constellationnetwork.routes.internal._
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.node._
import io.constellationnetwork.schema.peer.{PeerId, PeerInfo}
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.{Hasher, SecurityProvider}

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.encoder
import derevo.derive
import eu.timepit.refined.auto._
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.http4s.dsl.Http4sDsl
import org.http4s.{HttpRoutes, Request}
import org.typelevel.log4cats.slf4j.Slf4jLogger

final case class NodeParametersRoutes[F[_]: Async: Hasher: SecurityProvider](
  mkCell: Signed[UpdateNodeParameters] => Cell[F, StackF, _, Either[CellError, Ω], _],
  snapshotStorage: SnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
  fullGlobalSnapshotStorage: SnapshotLocalFileSystemStorage[F, GlobalSnapshot],
  nodeStorage: NodeStorage[F],
  cluster: Cluster[F],
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

  @derive(eqv, show, encoder)
  case class NodeParametersInfo(
    node: PeerInfo,
    delegatedStakeRewardParameters: DelegatedStakeRewardParameters,
    nodeMetadataParameters: NodeMetadataParameters
  )

  object SortOrder extends Enumeration {
    type SortOrder = Value
    val Asc, Desc = Value

    def fromRequest(req: Request[F]): Either[String, SortOrder] =
      req.params.get("sortOrder") match {
        case None => Right(Asc)
        case Some(s) =>
          val res = SortOrder.values.find(_.toString.toLowerCase == s.toLowerCase)
          Either.cond(res.isDefined, res.get, "Unsupported sort order")
      }
  }

  object Sort extends Enumeration {
    type Sort = Value
    val Address, Name, PeerId = Value

    def fromRequest(req: Request[F]): Either[String, Sort] =
      req.params.get("sort") match {
        case None => Right(PeerId)
        case Some(s) =>
          val res = Sort.values.find(_.toString.toLowerCase == s.toLowerCase)
          Either.cond(res.isDefined, res.get, "Unsupported sort")
      }

    import SortOrder._

    def sort(list: List[NodeParametersInfo], sortOrder: SortOrder, sort: Sort): F[List[NodeParametersInfo]] = {
      def applyOrder(sorted: List[NodeParametersInfo]): List[NodeParametersInfo] =
        sortOrder match {
          case Desc => sorted.reverse
          case _    => sorted
        }

      (sort match {
        case Sort.Address => list.traverse(info => info.node.id.toAddress.map(addr => (info, addr))).map(_.sortBy(_._2).map(_._1))
        case Sort.Name    => list.sortBy(_.nodeMetadataParameters.name).pure[F]
        case Sort.PeerId  => list.sortBy(_.node.id.value.value).pure[F]
        case _            => list.pure[F] // default case for any unexpected values

      }).map(sorted => applyOrder(sorted))
    }
  }

  private def filter(list: List[NodeParametersInfo], search: Option[String]): List[NodeParametersInfo] = search match {
    case None    => list
    case Some(s) => list.filter(info => info.nodeMetadataParameters.name.contains(s) || info.node.id.value.value.contains(s))
  }

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
            case Some(params) => Ok(params._1.value)
            case _            => NotFound()
          },
          ServiceUnavailable()
        )
    case req @ GET -> Root =>
      nodeStorage.getNodeState
        .map(validStateForSnapshotReturn)
        .ifM(
          (for {
            sortOrder <- SortOrder.fromRequest(req)
            sort <- Sort.fromRequest(req)
          } yield (sortOrder, sort)) match {
            case Left(err) => BadRequest(err)
            case Right((sortOrder, sort)) =>
              cluster.info.flatMap { clusterInfo =>
                clusterInfo.toList.traverse(peerInfo =>
                  getLatestNodeParameters(peerInfo.id.toId).map(_.map(params => (peerInfo, params._1)))
                )
              }.flatMap { infoWithNodeParams =>
                val filtered = filter(
                  infoWithNodeParams.collect {
                    case Some((node, params)) =>
                      NodeParametersInfo(
                        node = node,
                        delegatedStakeRewardParameters = params.delegatedStakeRewardParameters,
                        nodeMetadataParameters = params.nodeMetadataParameters
                      )
                  },
                  req.params.get("search")
                )
                Ok(Sort.sort(filtered, sortOrder, sort))
              }
          },
          ServiceUnavailable()
        )
  }
}
