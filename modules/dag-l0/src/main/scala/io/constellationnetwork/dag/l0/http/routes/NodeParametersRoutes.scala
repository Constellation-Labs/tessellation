package io.constellationnetwork.dag.l0.http.routes

import cats.data.Validated.{Invalid, Valid}
import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.SortedMap

import io.constellationnetwork.ext.http4s.PeerIdVar
import io.constellationnetwork.kernel._
import io.constellationnetwork.node.shared.domain.cluster.services.Cluster
import io.constellationnetwork.node.shared.domain.node.{NodeStorage, UpdateNodeParametersValidator}
import io.constellationnetwork.node.shared.domain.snapshot.storage.SnapshotStorage
import io.constellationnetwork.routes.internal._
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.delegatedStake.DelegatedStakeAmount._
import io.constellationnetwork.schema.delegatedStake.{DelegatedStakeAmount, DelegatedStakeRecord, UpdateDelegatedStake}
import io.constellationnetwork.schema.node._
import io.constellationnetwork.schema.peer.{PeerId, PeerInfo}
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.{Hasher, SecurityProvider}

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.encoder
import derevo.derive
import eu.timepit.refined.auto._
import io.circe.shapes._
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.http4s.dsl.Http4sDsl
import org.http4s.{HttpRoutes, Request}
import org.typelevel.log4cats.slf4j.Slf4jLogger
import shapeless._
import shapeless.syntax.singleton._

final case class NodeParametersRoutes[F[_]: Async: Hasher: SecurityProvider](
  mkCell: Signed[UpdateNodeParameters] => Cell[F, StackF, _, Either[CellError, Ω], _],
  snapshotStorage: SnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
  nodeStorage: NodeStorage[F],
  cluster: Cluster[F],
  validator: UpdateNodeParametersValidator[F]
) extends Http4sDsl[F]
    with PublicRoutes[F] {
  import org.http4s.circe.CirceEntityCodec.circeEntityDecoder

  private val logger = Slf4jLogger.getLoggerFromName[F]("NodeParametersLogger")

  protected val prefixPath: InternalUrlPrefix = "/node-params"

  private def validStateForSnapshotReturn(state: NodeState): Boolean = state === NodeState.Ready

  private def getLatestNodeParameters(nodeId: Id): F[Option[NodeParamsInfo]] =
    for {
      maybeSnapshot <- snapshotStorage.head
      paramsAndOrdinal = maybeSnapshot.flatMap(_._2.updateNodeParameters.flatMap(_.get(nodeId)))
      info <- paramsAndOrdinal.traverse {
        case (signed, ord) =>
          UpdateNodeParametersReference
            .of(signed)
            .map(ref =>
              NodeParamsInfo(
                latest = signed,
                lastRef = ref,
                acceptedOrdinal = ord
              )
            )
      }
    } yield info

  private def getNodeParameters(snapshotWithInfo: SnapshotWithInfo): F[List[NodeParametersInfo]] =
    for {
      peerIdToNodeParams <- getAllLatestNodeParameters(snapshotWithInfo)
      peerIdToPeerInfo <- cluster.info.map(_.toList.map(peerInfo => peerInfo.id -> peerInfo).toMap)
      peerIdToStake <- activeDelegatedStakesAndAddressesWithDefault(snapshotWithInfo)
      peerIdToNodeParameters <- peerIdToNodeParams.map {
        case (peerId, nodeParams) =>
          val (totalAmount, totalAddresses) = peerIdToStake(peerId)
          NodeParametersInfo(
            peerId = peerId,
            node = peerIdToPeerInfo.get(peerId),
            delegatedStakeRewardParameters = nodeParams.latest.delegatedStakeRewardParameters,
            nodeMetadataParameters = nodeParams.latest.nodeMetadataParameters,
            totalAmountDelegated = totalAmount,
            totalAddressesAssigned = totalAddresses
          )
      }.toList.pure[F]
    } yield peerIdToNodeParameters

  private def getAllLatestNodeParameters(snapshotWithInfo: SnapshotWithInfo): F[Map[PeerId, NodeParamsInfo]] =
    for {
      idToNodeParametersData <- snapshotWithInfo._2.updateNodeParameters.getOrElse(Map.empty).pure[F]
      idToNodeParamsInfo <- idToNodeParametersData.toList.traverse {
        case (id, (signed, ord)) =>
          UpdateNodeParametersReference.of(signed).map { ref =>
            val peerId = PeerId.fromId(id)
            val nodeParamsInfo = NodeParamsInfo(latest = signed, lastRef = ref, acceptedOrdinal = ord)
            peerId -> nodeParamsInfo
          }
      }
    } yield idToNodeParamsInfo.toMap

  private def activeDelegatedStakesAndAddressesWithDefault(snapshotWithInfo: SnapshotWithInfo) =
    snapshotWithInfo._2.activeDelegatedStakes
      .getOrElse(SortedMap.empty[Address, List[DelegatedStakeRecord]])
      .toList
      .flatMap { case (address, stakes) => stakes.map(s => (s.event.value, address)) }
      .groupBy {
        case (stake, _) => stake.nodeId
      }
      .map {
        case (peerId, stakesAndAddresses) =>
          val (stakes, addresses) = stakesAndAddresses.unzip
          val totalAmount = stakes.map(_.amount).combineAll
          val totalAddresses = addresses.size
          peerId -> (totalAmount, totalAddresses)
      }
      .withDefaultValue((emptyAmount, 0))
      .pure[F]

  @derive(eqv, show, encoder)
  case class NodeParametersInfo(
    peerId: PeerId,
    node: Option[PeerInfo],
    delegatedStakeRewardParameters: DelegatedStakeRewardParameters,
    nodeMetadataParameters: NodeMetadataParameters,
    totalAmountDelegated: DelegatedStakeAmount,
    totalAddressesAssigned: Int
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
        case Sort.Address =>
          list.traverse(info => info.peerId.toAddress.map(addr => (info, addr))).map(_.sortBy(_._2).map(_._1))
        case Sort.Name =>
          list.sortBy(_.nodeMetadataParameters.name).pure[F]
        case Sort.PeerId =>
          list.sortBy(_.peerId.value.value).pure[F]
        case _ =>
          list.pure[F] // default case for any unexpected values

      }).map(sorted => applyOrder(sorted))
    }
  }

  private def filter(list: List[NodeParametersInfo], search: Option[String]): List[NodeParametersInfo] = search match {
    case None    => list
    case Some(s) => list.filter(info => info.nodeMetadataParameters.name.contains(s) || info.peerId.value.value.contains(s))
  }

  type SnapshotWithInfo = (Signed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)

  protected val public: HttpRoutes[F] = HttpRoutes.of[F] {
    case req @ POST -> Root =>
      snapshotStorage.head.flatMap {
        case None => ServiceUnavailable()
        case Some((_, info)) =>
          for {
            signed <- req.as[Signed[UpdateNodeParameters]]
            result <- validator.validate(signed, info)
            response <- result match {
              case Valid(validSigned) =>
                logger.info(s"Accepted node parameters from ${validSigned.proofs.map(_.id).map(PeerId.fromId)}") >>
                  mkCell(validSigned).run().flatMap {
                    case Right(_) =>
                      validSigned.toHashed.flatMap(hashed => Ok(("hash" ->> hashed.hash) :: HNil))
                    case Left(_) =>
                      InternalServerError("Failed to update cell.")
                  }

              case Invalid(errors) =>
                logger.warn(s"Invalid node parameters: $errors") >>
                  BadRequest(errors.mkString_("\n"))
            }
          } yield response
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
              snapshotStorage.head.flatMap {
                case Some(snapshot) =>
                  for {
                    nodeParameters <- getNodeParameters(snapshot)
                    filtered = filter(nodeParameters, req.params.get("search"))
                    response <- Ok(Sort.sort(filtered, sortOrder, sort))
                  } yield response
                case None => Ok()
              }
          },
          ServiceUnavailable()
        )
  }
}
