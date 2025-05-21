package io.constellationnetwork.dag.l0.http.routes

import cats.data.Validated.{Invalid, Valid}
import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.dag.l0.domain.nodeCollateral.{CreateNodeCollateralOutput, NodeCollateralOutput, WithdrawNodeCollateralOutput}
import io.constellationnetwork.ext.http4s.AddressVar
import io.constellationnetwork.kernel._
import io.constellationnetwork.node.shared.domain.node.NodeStorage
import io.constellationnetwork.node.shared.domain.nodeCollateral.UpdateNodeCollateralValidator
import io.constellationnetwork.node.shared.domain.snapshot.storage.SnapshotStorage
import io.constellationnetwork.routes.internal._
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.nodeCollateral._
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.signature.Signed

import eu.timepit.refined.auto._
import eu.timepit.refined.types.all.NonNegLong
import io.circe.shapes._
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.http4s.dsl.Http4sDsl
import org.typelevel.log4cats.slf4j.Slf4jLogger
import shapeless._
import shapeless.syntax.singleton._

final case class NodeCollateralRoutes[F[_]: Async: Hasher](
  mkCell: NodeCollateralOutput => Cell[F, StackF, _, Either[CellError, Ω], _],
  validator: UpdateNodeCollateralValidator[F],
  snapshotStorage: SnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
  nodeStorage: NodeStorage[F],
  withdrawalTimeLimit: EpochProgress
) extends Http4sDsl[F]
    with PublicRoutes[F] {

  import org.http4s.circe.CirceEntityCodec.circeEntityDecoder

  private val logger = Slf4jLogger.getLoggerFromName[F]("NodeCollateralsLogger")

  protected val prefixPath: InternalUrlPrefix = "/node-collateral"

  private def getNodeCollateralInfo(address: Address, info: GlobalSnapshotInfo): NodeCollateralsInfo = {
    val lastCollaterals: SortedSet[NodeCollateralRecord] =
      info.activeNodeCollaterals
        .getOrElse(SortedMap.empty[Address, SortedSet[NodeCollateralRecord]])
        .getOrElse(address, SortedSet.empty[NodeCollateralRecord])
    val lastWithdrawals: SortedSet[PendingNodeCollateralWithdrawal] =
      info.nodeCollateralWithdrawals
        .getOrElse(SortedMap.empty[Address, SortedSet[PendingNodeCollateralWithdrawal]])
        .getOrElse(address, SortedSet.empty[PendingNodeCollateralWithdrawal])

    val active = lastCollaterals.toList.map {
      case NodeCollateralRecord(collateral, acceptedOrdinal) =>
        NodeCollateralInfo(
          nodeId = collateral.nodeId,
          acceptedOrdinal = acceptedOrdinal,
          tokenLockRef = collateral.tokenLockRef,
          amount = collateral.amount,
          fee = collateral.fee,
          withdrawalStartEpoch = None,
          withdrawalEndEpoch = None
        )
    }
    val pending = lastWithdrawals.toList.map {
      case PendingNodeCollateralWithdrawal(collateral, acceptedOrdinal, createdAt) =>
        NodeCollateralInfo(
          nodeId = collateral.nodeId,
          acceptedOrdinal = acceptedOrdinal,
          tokenLockRef = collateral.tokenLockRef,
          amount = collateral.amount,
          fee = collateral.fee,
          withdrawalStartEpoch = createdAt.some,
          withdrawalEndEpoch = (createdAt |+| withdrawalTimeLimit).some
        )
    }
    NodeCollateralsInfo(
      address = address,
      activeNodeCollaterals = active,
      pendingWithdrawals = pending
    )
  }

  private def getLastReference(
    address: Address,
    info: GlobalSnapshotInfo
  ): F[NodeCollateralReference] =
    info.activeNodeCollaterals
      .getOrElse(SortedMap.empty[Address, List[NodeCollateralRecord]])
      .get(address)
      .flatMap(collaterals => Option.when(collaterals.nonEmpty)(collaterals.maxBy(_.event.ordinal)))
      .traverse(collateral => NodeCollateralReference.of(collateral.event))
      .map(_.getOrElse(NodeCollateralReference.empty))

  protected val public: HttpRoutes[F] = HttpRoutes.of[F] {
    case req @ POST -> Root =>
      snapshotStorage.head.flatMap {
        case None => ServiceUnavailable()
        case Some((_, info)) =>
          for {
            signed <- req.as[Signed[UpdateNodeCollateral.Create]]
            result <- validator.validateCreateNodeCollateral(signed, info)
            response <- result match {
              case Valid(validSigned) =>
                logger.info(s"Accepted create node collateral from ${validSigned.proofs.map(_.id).map(PeerId.fromId)}") >>
                  mkCell(CreateNodeCollateralOutput(validSigned)).run().flatMap {
                    case Right(_) =>
                      validSigned.toHashed.flatMap(hashed => Ok(("hash" ->> hashed.hash) :: HNil))
                    case Left(_) =>
                      InternalServerError("Failed to update cell.")
                  }

              case Invalid(errors) =>
                logger.warn(s"Invalid create delegated stake: $errors") >>
                  BadRequest(errors.mkString_("\n"))
            }
          } yield response
      }

    case req @ PUT -> Root =>
      snapshotStorage.head.flatMap {
        case None => ServiceUnavailable()
        case Some((_, info)) =>
          for {
            signed <- req.as[Signed[UpdateNodeCollateral.Withdraw]]
            result <- validator.validateWithdrawNodeCollateral(signed, info)
            response <- result match {
              case Valid(validSigned) =>
                logger.info(s"Accepted withdraw node collateral from ${validSigned.proofs.map(_.id).map(PeerId.fromId)}") >>
                  mkCell(WithdrawNodeCollateralOutput(validSigned)).run().flatMap {
                    case Right(_) =>
                      validSigned.toHashed.flatMap(hashed => Ok(("hash" ->> hashed.hash) :: HNil))
                    case Left(_) =>
                      InternalServerError("Failed to update cell.")
                  }

              case Invalid(errors) =>
                logger.warn(s"Invalid withdraw delegated stake: $errors") >>
                  BadRequest(errors.mkString_("\n"))
            }
          } yield response
      }

    case GET -> Root / AddressVar(address) / "info" =>
      snapshotStorage.head.flatMap {
        case Some((_, info)) =>
          Ok(getNodeCollateralInfo(address, info))
        case None => ServiceUnavailable()
      }

    case GET -> Root / "last-reference" / AddressVar(address) =>
      snapshotStorage.head.flatMap {
        case Some((_, info)) =>
          Ok(getLastReference(address, info))
        case None => ServiceUnavailable()
      }
  }
}
