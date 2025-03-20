package io.constellationnetwork.dag.l0.http.routes

import cats.data.Validated.{Invalid, Valid}
import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.SortedMap

import io.constellationnetwork.dag.l0.domain.nodeCollateral.{CreateNodeCollateralOutput, NodeCollateralOutput, WithdrawNodeCollateralOutput}
import io.constellationnetwork.ext.http4s.{AddressVar, HashVar}
import io.constellationnetwork.kernel._
import io.constellationnetwork.node.shared.domain.node.NodeStorage
import io.constellationnetwork.node.shared.domain.nodeCollateral.UpdateNodeCollateralValidator
import io.constellationnetwork.node.shared.domain.snapshot.storage.SnapshotStorage
import io.constellationnetwork.node.shared.infrastructure.snapshot.storage.SnapshotLocalFileSystemStorage
import io.constellationnetwork.routes.internal._
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.delegatedStake._
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.nodeCollateral._
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed

import eu.timepit.refined.auto._
import eu.timepit.refined.types.all.NonNegLong
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.http4s.dsl.Http4sDsl
import org.typelevel.log4cats.slf4j.Slf4jLogger

final case class NodeCollateralRoutes[F[_]: Async: Hasher](
  mkCell: NodeCollateralOutput => Cell[F, StackF, _, Either[CellError, Ω], _],
  validator: UpdateNodeCollateralValidator[F],
  snapshotStorage: SnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
  fullGlobalSnapshotStorage: SnapshotLocalFileSystemStorage[F, GlobalSnapshot],
  nodeStorage: NodeStorage[F],
  withdrawalTimeLimit: NonNegLong
) extends Http4sDsl[F]
    with PublicRoutes[F] {

  import org.http4s.circe.CirceEntityCodec.circeEntityDecoder

  private val logger = Slf4jLogger.getLoggerFromName[F]("NodeCollateralsLogger")

  protected val prefixPath: InternalUrlPrefix = "/node-collateral"

  private def readLatestFullSnapshot(maybeOrdinal: Option[SnapshotOrdinal]): F[Option[Signed[GlobalSnapshot]]] =
    maybeOrdinal.traverse(fullGlobalSnapshotStorage.read).map(_.flatten)

  private def getLatestFullSnapshot: F[Option[Signed[GlobalSnapshot]]] =
    for {
      maybeOrdinal <- snapshotStorage.headSnapshot.map(_.map(_.ordinal))
      maybeSnapshot <- readLatestFullSnapshot(maybeOrdinal)
    } yield maybeSnapshot

  private def getNodeCollateralInfo(address: Address, lastSnapshot: Signed[GlobalSnapshot]): F[NodeCollateralsInfo] = {
    val lastCollaterals: List[(Signed[UpdateNodeCollateral.Create], SnapshotOrdinal)] =
      lastSnapshot.info.activeNodeCollaterals
        .getOrElse(SortedMap.empty[Address, List[(Signed[UpdateNodeCollateral.Create], SnapshotOrdinal)]])
        .getOrElse(address, List.empty)
    val lastWithdrawals: List[(Signed[UpdateNodeCollateral.Withdraw], EpochProgress)] =
      lastSnapshot.info.nodeCollateralWithdrawals
        .getOrElse(SortedMap.empty[Address, List[(Signed[UpdateNodeCollateral.Withdraw], EpochProgress)]])
        .getOrElse(address, List.empty)

    for {
      collaterals <- lastCollaterals.traverse {
        case (collateral, ord) =>
          NodeCollateralReference.of(collateral).map(ref => ((collateral, ord), lastWithdrawals.find(_._1.collateralRef === ref.hash)))
      }
      infos = collaterals.map {
        case ((collateral, acceptedOrdinal), maybeWithdraw) =>
          NodeCollateralInfo(
            nodeId = collateral.nodeId,
            acceptedOrdinal = acceptedOrdinal,
            tokenLockRef = collateral.tokenLockRef,
            amount = collateral.amount,
            fee = collateral.fee,
            withdrawalStartEpoch = maybeWithdraw.map(_._2),
            withdrawalEndEpoch = maybeWithdraw.map(_._2 |+| EpochProgress(withdrawalTimeLimit))
          )
      }
    } yield
      NodeCollateralsInfo(
        address = address,
        activeNodeCollaterals = infos,
        pendingWithdrawals = infos.filter(_.withdrawalStartEpoch.nonEmpty)
      )
  }

  private def getLastReference(
    address: Address,
    lastSnapshot: Signed[GlobalSnapshot]
  ): F[NodeCollateralReference] =
    lastSnapshot.info.activeNodeCollaterals
      .getOrElse(SortedMap.empty[Address, List[(Signed[UpdateNodeCollateral.Create], SnapshotOrdinal)]])
      .get(address)
      .flatMap(collaterals => Option.when(collaterals.nonEmpty)(collaterals.maxBy(_._1.ordinal)))
      .traverse(collateral => NodeCollateralReference.of(collateral._1))
      .map(_.getOrElse(NodeCollateralReference.empty))

  protected val public: HttpRoutes[F] = HttpRoutes.of[F] {
    case req @ POST -> Root =>
      getLatestFullSnapshot.flatMap {
        case Some(lastSnapshot) =>
          req
            .as[Signed[UpdateNodeCollateral.Create]]
            .flatMap(signed => validator.validateCreateNodeCollateral(signed, lastSnapshot.info))
            .flatTap {
              case Valid(signed) =>
                logger.info(s"Accepted create node collateral from ${signed.proofs.map(_.id).map(PeerId.fromId(_))}")
              case Invalid(errors) =>
                logger.warn(s"Invalid create delegated stake: $errors")
            }
            .flatMap {
              case Valid(signed)   => mkCell(CreateNodeCollateralOutput(signed)).run()
              case Invalid(errors) => CellError(errors.toString).asLeft[Ω].pure[F]
            }
            .flatMap {
              case Left(_)  => BadRequest()
              case Right(_) => Ok()
            }
        case None => ServiceUnavailable()
      }

    case req @ PUT -> Root =>
      getLatestFullSnapshot.flatMap {
        case Some(lastSnapshot) =>
          req
            .as[Signed[UpdateNodeCollateral.Withdraw]]
            .flatMap(signed => validator.validateWithdrawNodeCollateral(signed, lastSnapshot.info))
            .flatTap {
              case Valid(signed) =>
                logger.info(s"Accepted withdraw node collateral from ${signed.proofs.map(_.id).map(PeerId.fromId(_))}")
              case Invalid(errors) =>
                logger.warn(s"Invalid withdraw delegated stake: $errors")
            }
            .flatMap {
              case Valid(signed)   => mkCell(WithdrawNodeCollateralOutput(signed)).run()
              case Invalid(errors) => CellError(errors.toString).asLeft[Ω].pure[F]
            }
            .flatMap {
              case Left(_)  => BadRequest()
              case Right(_) => Ok()
            }
        case None => ServiceUnavailable()
      }

    case GET -> Root / AddressVar(address) / "info" =>
      getLatestFullSnapshot.flatMap {
        case Some(lastSnapshot) =>
          Ok(getNodeCollateralInfo(address, lastSnapshot))
        case None => ServiceUnavailable()
      }

    case GET -> Root / "last-reference" / AddressVar(address) =>
      getLatestFullSnapshot.flatMap {
        case Some(lastSnapshot) =>
          Ok(getLastReference(address, lastSnapshot))
        case None => ServiceUnavailable()
      }
  }
}
