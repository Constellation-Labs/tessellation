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
import io.constellationnetwork.routes.internal._
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.delegatedStake._
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.signature.Signed

import eu.timepit.refined.auto._
import io.circe.shapes._
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.http4s.dsl.Http4sDsl
import org.typelevel.log4cats.slf4j.Slf4jLogger
import shapeless._
import shapeless.syntax.singleton._

final case class DelegatedStakesRoutes[F[_]: Async: Hasher](
  mkCell: DelegatedStakeOutput => Cell[F, StackF, _, Either[CellError, Ω], _],
  validator: UpdateDelegatedStakeValidator[F],
  snapshotStorage: SnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
  nodeStorage: NodeStorage[F],
  withdrawalTimeLimit: EpochProgress
) extends Http4sDsl[F]
    with PublicRoutes[F] {

  import org.http4s.circe.CirceEntityCodec.circeEntityDecoder

  private val logger = Slf4jLogger.getLoggerFromName[F]("DelegatedStakesLogger")

  protected val prefixPath: InternalUrlPrefix = "/delegated-stakes"

  private def getDelegatedStakesInfo(address: Address, info: GlobalSnapshotInfo): F[DelegatedStakesInfo] = {
    val lastStakes: List[DelegatedStakeRecord] =
      info.activeDelegatedStakes
        .getOrElse(SortedMap.empty[Address, List[DelegatedStakeRecord]])
        .getOrElse(address, List.empty)
    val lastWithdrawals: List[PendingWithdrawal] =
      info.delegatedStakesWithdrawals
        .getOrElse(SortedMap.empty[Address, List[PendingWithdrawal]])
        .getOrElse(address, List.empty)

    for {
      stakes <- lastStakes.traverse {
        case DelegatedStakeRecord(stake, ord, _, _) =>
          DelegatedStakeReference
            .of(stake)
            .map(ref =>
              ((stake, ord, ref), lastWithdrawals.find { case PendingWithdrawal(withdrawal, _, _) => withdrawal.stakeRef === ref.hash })
            )
      }
      infos = stakes.map {
        case ((stake, acceptedOrdinal, delegatedStakeRef), maybeWithdraw) =>
          DelegatedStakeInfo(
            nodeId = stake.nodeId,
            acceptedOrdinal = acceptedOrdinal,
            tokenLockRef = stake.tokenLockRef,
            amount = stake.amount,
            fee = stake.fee,
            hash = delegatedStakeRef.hash,
            withdrawalStartEpoch = maybeWithdraw.map { case PendingWithdrawal(_, _, epochProgress) => epochProgress },
            withdrawalEndEpoch = maybeWithdraw.map { case PendingWithdrawal(_, _, epochProgress) => epochProgress |+| withdrawalTimeLimit }
          )
      }
    } yield
      DelegatedStakesInfo(
        address = address,
        activeDelegatedStakes = infos,
        pendingWithdrawals = infos.filter(_.withdrawalStartEpoch.nonEmpty)
      )
  }

  private def getLastReference(
    address: Address,
    info: GlobalSnapshotInfo
  ): F[DelegatedStakeReference] =
    info.activeDelegatedStakes
      .getOrElse(SortedMap.empty[Address, List[DelegatedStakeRecord]])
      .get(address)
      .flatMap(stakes =>
        Option.when(stakes.nonEmpty)(stakes.maxBy { case DelegatedStakeRecord(delegatedStaking, _, _, _) => delegatedStaking.ordinal })
      )
      .traverse { case DelegatedStakeRecord(delegatedStaking, _, _, _) => DelegatedStakeReference.of(delegatedStaking) }
      .map(_.getOrElse(DelegatedStakeReference.empty))

  protected val public: HttpRoutes[F] = HttpRoutes.of[F] {
    case req @ POST -> Root =>
      snapshotStorage.head.flatMap {
        case None => ServiceUnavailable()
        case Some((_, info)) =>
          for {
            signed <- req.as[Signed[UpdateDelegatedStake.Create]]
            result <- validator.validateCreateDelegatedStake(signed, info)
            response <- result match {
              case Valid(validSigned) =>
                logger.info(s"Accepted create delegated stake from ${validSigned.proofs.map(_.id).map(PeerId.fromId)}") >>
                  mkCell(CreateDelegatedStakeOutput(validSigned)).run().flatMap {
                    case Right(_) => validSigned.toHashed.flatMap(hashed => Ok(("hash" ->> hashed.hash) :: HNil))
                    case Left(_)  => InternalServerError("Failed to update cell.")
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
            signed <- req.as[Signed[UpdateDelegatedStake.Withdraw]]
            result <- validator.validateWithdrawDelegatedStake(signed, info)
            response <- result match {
              case Valid(validSigned) =>
                logger.info(s"Accepted withdraw delegated stake from ${validSigned.proofs.map(_.id).map(PeerId.fromId)}") >>
                  mkCell(WithdrawDelegatedStakeOutput(validSigned)).run().flatMap {
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
          Ok(getDelegatedStakesInfo(address, info))
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
