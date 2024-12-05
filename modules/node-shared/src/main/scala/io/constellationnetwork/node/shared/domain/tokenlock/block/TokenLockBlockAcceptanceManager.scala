package io.constellationnetwork.node.shared.domain.tokenlock.block

import cats.data.Validated.{Invalid, Valid}
import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.node.shared.domain.tokenlock.TokenLockChainValidator.TokenLockNel
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.tokenLock.TokenLockBlock
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.{Hasher, SecurityProvider}

import monocle.syntax.all._
import org.typelevel.log4cats.slf4j.Slf4jLogger

trait TokenLockBlockAcceptanceManager[F[_]] {

  def acceptBlocksIteratively(
    blocks: List[Signed[TokenLockBlock]],
    context: TokenLockBlockAcceptanceContext[F],
    snapshotOrdinal: SnapshotOrdinal
  )(implicit hasher: Hasher[F]): F[TokenLockBlockAcceptanceResult]

  def acceptBlock(
    block: Signed[TokenLockBlock],
    context: TokenLockBlockAcceptanceContext[F],
    snapshotOrdinal: SnapshotOrdinal
  )(implicit hasher: Hasher[F]): F[Either[TokenLockBlockNotAcceptedReason, TokenLockBlockAcceptanceContextUpdate]]

}

object TokenLockBlockAcceptanceManager {

  def make[F[_]: Async: SecurityProvider](
    blockValidator: TokenLockBlockValidator[F]
  ): TokenLockBlockAcceptanceManager[F] = make(TokenLockBlockAcceptanceLogic.make[F], blockValidator)

  def make[F[_]: Async](
    logic: TokenLockBlockAcceptanceLogic[F],
    blockValidator: TokenLockBlockValidator[F]
  ): TokenLockBlockAcceptanceManager[F] =
    new TokenLockBlockAcceptanceManager[F] {
      private val logger = Slf4jLogger.getLoggerFromClass[F](TokenLockBlockAcceptanceManager.getClass)

      def acceptBlocksIteratively(
        blocks: List[Signed[TokenLockBlock]],
        context: TokenLockBlockAcceptanceContext[F],
        snapshotOrdinal: SnapshotOrdinal
      )(implicit hasher: Hasher[F]): F[TokenLockBlockAcceptanceResult] = {

        def go(
          initState: TokenLockBlockAcceptanceState,
          toProcess: List[(Signed[TokenLockBlock], Map[Address, TokenLockNel])]
        ): F[TokenLockBlockAcceptanceState] =
          for {
            currState <- toProcess.foldLeftM[F, TokenLockBlockAcceptanceState](initState.copy(awaiting = List.empty)) {
              (acc, blockAndTxChains) =>
                blockAndTxChains match {
                  case (block, txChains) =>
                    logic
                      .acceptBlock(block, txChains, context, acc.contextUpdate)
                      .map {
                        case contextUpdate =>
                          acc
                            .focus(_.contextUpdate)
                            .replace(contextUpdate)
                            .focus(_.accepted)
                            .modify(block :: _)
                      }
                      .leftMap {
                        case reason: TokenLockBlockRejectionReason =>
                          acc
                            .focus(_.rejected)
                            .modify(_ :+ (block, reason))
                        case reason: TokenLockBlockAwaitReason =>
                          acc
                            .focus(_.awaiting)
                            .modify(_ :+ (blockAndTxChains, reason))
                      }
                      .merge
                }
            }
            result <-
              if (initState === currState) currState.pure[F]
              else go(currState, currState.awaiting.map(_._1))
          } yield result

        blocks.sorted
          .foldLeftM(
            (List.empty[(Signed[TokenLockBlock], Map[Address, TokenLockNel])], List.empty[(Signed[TokenLockBlock], ValidationFailed)])
          ) { (acc, block) =>
            acc match {
              case (validList, invalidList) =>
                blockValidator.validate(block, snapshotOrdinal).map {
                  case Valid(blockAndTxChains) => (blockAndTxChains :: validList, invalidList)
                  case Invalid(errors)         => (validList, (block, ValidationFailed(errors.toNonEmptyList)) :: invalidList)
                }
            }
          }
          .flatMap {
            case (validList, invalidList) =>
              go(TokenLockBlockAcceptanceState.withRejectedBlocks(invalidList), validList)
                .map(_.toBlockAcceptanceResult)
          }
      }

      def acceptBlock(
        block: Signed[TokenLockBlock],
        context: TokenLockBlockAcceptanceContext[F],
        snapshotOrdinal: SnapshotOrdinal
      )(implicit hasher: Hasher[F]): F[Either[TokenLockBlockNotAcceptedReason, TokenLockBlockAcceptanceContextUpdate]] =
        blockValidator.validate(block, snapshotOrdinal).flatMap {
          _.toEither
            .leftMap(errors => ValidationFailed(errors.toNonEmptyList))
            .toEitherT[F]
            .flatMap {
              case (block, txChains) => logic.acceptBlock(block, txChains, context, TokenLockBlockAcceptanceContextUpdate.empty)
            }
            .value
        }
    }

}
