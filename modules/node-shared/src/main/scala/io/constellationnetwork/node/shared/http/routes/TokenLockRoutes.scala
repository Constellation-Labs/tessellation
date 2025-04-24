package io.constellationnetwork.node.shared.http.routes

import cats.data.Validated
import cats.data.Validated.{Invalid, Valid}
import cats.effect.Async
import cats.effect.std.{Queue, Supervisor}
import cats.syntax.all._

import io.constellationnetwork.currency.schema.currency.CurrencySnapshotInfo
import io.constellationnetwork.currency.tokenlock.ConsensusInput
import io.constellationnetwork.ext.http4s.{AddressVar, HashVar}
import io.constellationnetwork.node.shared.cli.CliMethod
import io.constellationnetwork.node.shared.config.types.DelegatedStakingConfig
import io.constellationnetwork.node.shared.domain.cluster.storage.L0ClusterStorage
import io.constellationnetwork.node.shared.domain.collateral.LatestBalances
import io.constellationnetwork.node.shared.domain.snapshot.storage.LastSnapshotStorage
import io.constellationnetwork.node.shared.domain.tokenlock._
import io.constellationnetwork.routes.internal._
import io.constellationnetwork.schema.http.{ErrorCause, ErrorResponse}
import io.constellationnetwork.schema.snapshot.{Snapshot, SnapshotInfo, StateProof}
import io.constellationnetwork.schema.tokenLock.{TokenLock, TokenLockStatus, TokenLockView}
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo}
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.signature.Signed

import eu.timepit.refined.auto._
import io.circe.shapes._
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec.{circeEntityDecoder, circeEntityEncoder}
import org.http4s.dsl.Http4sDsl
import org.typelevel.log4cats.slf4j.Slf4jLogger
import shapeless._
import shapeless.syntax.singleton._

final case class TokenLockRoutes[
  F[_]: Async: Hasher,
  P <: StateProof,
  S <: Snapshot,
  SI <: SnapshotInfo[P]
](
  tokenLockConsensusInput: Queue[F, Signed[ConsensusInput.PeerConsensusInput]],
  l0ClusterStorage: L0ClusterStorage[F],
  tokenLockService: TokenLockService[F],
  tokenLockStorage: TokenLockStorage[F],
  lastSnapshotStorage: LastSnapshotStorage[F, S, SI] with LatestBalances[F],
  validator: TokenLockValidator[F],
  maybeDelegatedStakingCfg: Option[DelegatedStakingConfig]
)(implicit S: Supervisor[F])
    extends Http4sDsl[F]
    with PublicRoutes[F]
    with P2PRoutes[F] {

  def logger = Slf4jLogger.getLogger[F]

  private val tokenLockLogger = Slf4jLogger.getLoggerFromName[F](tokenLockLoggerName)

  protected val prefixPath: InternalUrlPrefix = "/"

  protected val public: HttpRoutes[F] = HttpRoutes.of[F] {
    case req @ POST -> Root / "token-locks" =>
      for {
        transaction <- req.as[Signed[TokenLock]]
        hashedTransaction <- transaction.toHashed[F]
        result <- maybeDelegatedStakingCfg match {
          case Some(value) =>
            lastSnapshotStorage.getCombined.map {
              case Some((_, state)) =>
                state match {
                  case currencyState: CurrencySnapshotInfo => currencyState.activeTokenLocks
                  case globalState: GlobalSnapshotInfo     => globalState.activeTokenLocks
                  case _                                   => none
                }
              case None =>
                throw new IllegalStateException("Expected a last snapshot, but none was found.")
            }.flatMap { activeTokenLocks =>
              validator.validateWithDelegatedStakeInfo(transaction, value, activeTokenLocks)
            }
          case None => transaction.validNec.pure
        }
        response <- result match {
          case Valid(_) =>
            tokenLockService
              .offer(hashedTransaction)
              .flatTap {
                case Left(errors) =>
                  tokenLockLogger.warn(
                    s"Received tokenLock hash=${hashedTransaction.hash} is invalid: ${transaction.show}, reason: ${errors.show}"
                  )
                case Right(hash) => tokenLockLogger.info(s"Received valid TokenLock: ${hash.show}")
              }
              .flatMap {
                case Left(errors) => BadRequest(ErrorResponse(errors.map(e => ErrorCause(e.show))))
                case Right(hash)  => Ok(("hash" ->> hash.value) :: HNil)
              }
          case Invalid(errors) =>
            logger.warn(s"Invalid create token lock: $errors") >>
              BadRequest(errors.mkString_("\n"))
          case _ => throw new RuntimeException("Unexpected Validated value")
        }
      } yield response

    case GET -> Root / "token-locks" / HashVar(hash) =>
      tokenLockStorage.findWaiting(hash).flatMap {
        case Some(WaitingTokenLock(tx)) => Ok(TokenLockView(tx.signed.value, tx.hash, TokenLockStatus.Waiting))
        case None                       => NotFound()
      }

    case GET -> Root / "token-locks" / "last-reference" / AddressVar(address) =>
      tokenLockStorage
        .getLastProcessedTokenLock(address)
        .map(_.ref)
        .flatMap(Ok(_))
  }

  protected val p2p: HttpRoutes[F] = HttpRoutes.of[F] {
    case req @ POST -> Root / "consensus" / "token-locks" =>
      req
        .as[Signed[ConsensusInput.Proposal]]
        .map(_.asInstanceOf[Signed[ConsensusInput.PeerConsensusInput]])
        .handleErrorWith { _ =>
          req
            .as[Signed[ConsensusInput.SignatureProposal]]
            .map(_.asInstanceOf[Signed[ConsensusInput.PeerConsensusInput]])
            .handleErrorWith { _ =>
              req
                .as[Signed[ConsensusInput.CancelledCreationRound]]
                .map(_.asInstanceOf[Signed[ConsensusInput.PeerConsensusInput]])

            }
        }
        .flatMap { consensusInput =>
          S.supervise(tokenLockConsensusInput.offer(consensusInput))
        }
        .flatMap(_ => Ok())
        .handleErrorWith { err =>
          logger.error(err)("An error occured") >> InternalServerError()
        }
  }
}
