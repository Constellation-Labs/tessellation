package io.constellationnetwork.dag.l0.http.routes

import cats.data.Validated.{Invalid, Valid}
import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.SortedMap

import io.constellationnetwork.dag.l0.domain.delegatedStake.{CreateDelegatedStakeOutput, DelegatedStakeOutput, WithdrawDelegatedStakeOutput}
import io.constellationnetwork.ext.http4s.AddressVar
import io.constellationnetwork.kernel._
import io.constellationnetwork.node.shared.domain.delegatedStake.UpdateDelegatedStakeValidator
import io.constellationnetwork.node.shared.domain.node.NodeStorage
import io.constellationnetwork.node.shared.domain.snapshot.storage.SnapshotStorage
import io.constellationnetwork.node.shared.infrastructure.snapshot.storage.SnapshotLocalFileSystemStorage
import io.constellationnetwork.routes.internal._
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.delegatedStake._
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.signature.Signed

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.http4s.dsl.Http4sDsl
import org.typelevel.log4cats.slf4j.Slf4jLogger

final case class DelegatedStakesRoutes[F[_]: Async: Hasher](
  mkCell: DelegatedStakeOutput => Cell[F, StackF, _, Either[CellError, Ω], _],
  validator: UpdateDelegatedStakeValidator[F],
  snapshotStorage: SnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
  fullGlobalSnapshotStorage: SnapshotLocalFileSystemStorage[F, GlobalSnapshot],
  nodeStorage: NodeStorage[F],
  withdrawalTimeLimit: NonNegLong
) extends Http4sDsl[F]
    with PublicRoutes[F] {

  import org.http4s.circe.CirceEntityCodec.circeEntityDecoder

  private val logger = Slf4jLogger.getLoggerFromName[F]("DelegatedStakesLogger")

  protected val prefixPath: InternalUrlPrefix = "/delegated-stakes"

  private def readLatestFullSnapshot(maybeOrdinal: Option[SnapshotOrdinal]): F[Option[Signed[GlobalSnapshot]]] =
    maybeOrdinal.traverse(fullGlobalSnapshotStorage.read).map(_.flatten)

  private def getLatestFullSnapshot: F[Option[Signed[GlobalSnapshot]]] =
    for {
      maybeOrdinal <- snapshotStorage.headSnapshot.map(_.map(_.ordinal))
      maybeSnapshot <- readLatestFullSnapshot(maybeOrdinal)
    } yield maybeSnapshot

  private def getDelegatedStakesInfo(address: Address, lastSnapshot: Signed[GlobalSnapshot]): F[DelegatedStakesInfo] = {
    val lastStakes: List[(Signed[UpdateDelegatedStake.Create], SnapshotOrdinal)] =
      lastSnapshot.info.activeDelegatedStakes
        .getOrElse(SortedMap.empty[Address, List[(Signed[UpdateDelegatedStake.Create], SnapshotOrdinal)]])
        .getOrElse(address, List.empty)
    val lastWithdrawals: List[(Signed[UpdateDelegatedStake.Withdraw], SnapshotOrdinal)] =
      lastSnapshot.info.delegatedStakesWithdrawals
        .getOrElse(SortedMap.empty[Address, List[(Signed[UpdateDelegatedStake.Withdraw], SnapshotOrdinal)]])
        .getOrElse(address, List.empty)

    for {
      stakes <- lastStakes.traverse {
        case (stake, ord) => DelegatedStakeReference.of(stake).map(ref => ((stake, ord), lastWithdrawals.find(_._1.stakeRef === ref.hash)))
      }
      infos = stakes.map {
        case ((stake, acceptedOrdinal), maybeWithdraw) =>
          DelegatedStakeInfo(
            acceptedOrdinal = acceptedOrdinal,
            tokenLockRef = stake.tokenLockRef,
            amount = stake.stake.amount,
            fee = stake.stake.fee,
            withdrawalStarted = maybeWithdraw.map(_._2),
            withdrawalFinishes = maybeWithdraw.map(_._2.plus(withdrawalTimeLimit))
          )
      }
    } yield
      DelegatedStakesInfo(
        address = address,
        activeDelegatedStakes = infos,
        pendingWithdrawals = infos.filter(_.withdrawalStarted.nonEmpty)
      )
  }

  protected val public: HttpRoutes[F] = HttpRoutes.of[F] {
    case req @ POST -> Root =>
      getLatestFullSnapshot.flatMap {
        case Some(lastSnapshot) =>
          req
            .as[Signed[UpdateDelegatedStake.Create]]
            .flatMap(signed => validator.validateCreateDelegatedStake(signed, lastSnapshot.info))
            .flatTap {
              case Valid(signed) =>
                logger.info(s"Accepted create delegated stake from ${signed.proofs.map(_.id).map(PeerId.fromId(_))}")
              case Invalid(errors) =>
                logger.warn(s"Invalid create delegated stake: $errors")
            }
            .flatMap {
              case Valid(signed)   => mkCell(CreateDelegatedStakeOutput(signed)).run()
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
            .as[Signed[UpdateDelegatedStake.Withdraw]]
            .flatMap(signed => validator.validateWithdrawDelegatedStake(signed, lastSnapshot.info))
            .flatTap {
              case Valid(signed) =>
                logger.info(s"Accepted withdraw delegated stake from ${signed.proofs.map(_.id).map(PeerId.fromId(_))}")
              case Invalid(errors) =>
                logger.warn(s"Invalid withdraw delegated stake: $errors")
            }
            .flatMap {
              case Valid(signed)   => mkCell(WithdrawDelegatedStakeOutput(signed)).run()
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
          Ok(getDelegatedStakesInfo(address, lastSnapshot))
        case None => ServiceUnavailable()
      }
  }
}
