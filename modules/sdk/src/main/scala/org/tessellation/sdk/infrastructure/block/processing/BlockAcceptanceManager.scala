package org.tessellation.sdk.infrastructure.block.processing

import cats.Eq
import cats.data.Validated.{Invalid, Valid}
import cats.effect.Async
import cats.syntax.applicative._
import cats.syntax.either._
import cats.syntax.eq._
import cats.syntax.flatMap._
import cats.syntax.foldable._
import cats.syntax.functor._
import cats.syntax.show._
import cats.syntax.traverse._

import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.transaction.Transaction
import org.tessellation.schema.{Block, BlockReference}
import org.tessellation.sdk.domain.block.processing.{TxChains, _}
import org.tessellation.sdk.infrastructure.block.processing.BlockAcceptanceLogic
import org.tessellation.security.SecurityProvider
import org.tessellation.security.signature.Signed

import eu.timepit.refined.cats._
import eu.timepit.refined.types.numeric.NonNegLong
import monocle.syntax.all._
import org.typelevel.log4cats.slf4j.Slf4jLogger

object BlockAcceptanceManager {

  def make[F[_]: Async: KryoSerializer: SecurityProvider, T <: Transaction: Eq, B <: Block[T]: Eq: Ordering](
    blockValidator: BlockValidator[F, T, B]
  ): BlockAcceptanceManager[F, T, B] = make(BlockAcceptanceLogic.make[F, T, B], blockValidator)

  def make[F[_]: Async: KryoSerializer, T <: Transaction: Eq, B <: Block[T]: Eq: Ordering](
    logic: BlockAcceptanceLogic[F, T, B],
    blockValidator: BlockValidator[F, T, B]
  ): BlockAcceptanceManager[F, T, B] =
    new BlockAcceptanceManager[F, T, B] {
      private val logger = Slf4jLogger.getLoggerFromClass[F](BlockAcceptanceManager.getClass)

      def acceptBlocksIteratively(
        blocks: List[Signed[B]],
        context: BlockAcceptanceContext[F]
      ): F[BlockAcceptanceResult[B]] = {

        def go(
          initState: BlockAcceptanceState[T, B],
          toProcess: List[(Signed[B], TxChains[T])]
        ): F[BlockAcceptanceState[T, B]] =
          for {
            currState <- toProcess.foldLeftM[F, BlockAcceptanceState[T, B]](initState.copy(awaiting = List.empty)) {
              (acc, blockAndTxChains) =>
                blockAndTxChains match {
                  case (block, txChains) =>
                    logic
                      .acceptBlock(block, txChains, context, acc.contextUpdate)
                      .map {
                        case (contextUpdate, blockUsages) =>
                          acc
                            .focus(_.contextUpdate)
                            .replace(contextUpdate)
                            .focus(_.accepted)
                            .modify((block, blockUsages) :: _)
                      }
                      .leftMap {
                        case reason: BlockRejectionReason =>
                          acc
                            .focus(_.rejected)
                            .modify(_ :+ (block, reason))
                        case reason: BlockAwaitReason =>
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
          .foldLeftM((List.empty[(Signed[B], TxChains[T])], List.empty[(Signed[B], ValidationFailed)])) { (acc, block) =>
            acc match {
              case (validList, invalidList) =>
                blockValidator.validate(block).map {
                  case Valid(blockAndTxChains) => (blockAndTxChains :: validList, invalidList)
                  case Invalid(errors) =>
                    (validList, (block, ValidationFailed(errors.toNonEmptyList)) :: invalidList)
                }
            }
          }
          .flatMap {
            case (validList, invalidList) =>
              go(BlockAcceptanceState.withRejectedBlocks(invalidList), validList)
                .map(_.toBlockAcceptanceResult)
                .flatTap { result =>
                  result.accepted.traverse(logAcceptedBlock) >>
                    result.notAccepted.traverse(logNotAcceptedBlock)
                }
          }
      }

      def acceptBlock(
        block: Signed[B],
        context: BlockAcceptanceContext[F]
      ): F[Either[BlockNotAcceptedReason, (BlockAcceptanceContextUpdate, NonNegLong)]] =
        blockValidator.validate(block).flatMap {
          _.toEither
            .leftMap(errors => ValidationFailed(errors.toNonEmptyList))
            .toEitherT[F]
            .flatMap {
              case (block, txChains) => logic.acceptBlock(block, txChains, context, BlockAcceptanceContextUpdate.empty)
            }
            .leftSemiflatTap(reason => logNotAcceptedBlock((block, reason)))
            .semiflatTap { case (_, usages) => logAcceptedBlock((block, usages)) }
            .value
        }

      private def logAcceptedBlock(tuple: (Signed[B], NonNegLong)): F[Unit] = {
        val (signedBlock, blockUsages) = tuple
        BlockReference.of(signedBlock).flatMap { blockRef =>
          logger.info(s"Accepted block: ${blockRef.show}, usages: ${blockUsages.show}")
        }
      }

      private def logNotAcceptedBlock(tuple: (Signed[B], BlockNotAcceptedReason)): F[Unit] = {
        val (signedBlock, reason) = tuple
        BlockReference.of(signedBlock).flatMap { blockRef =>
          reason match {
            case reason: BlockRejectionReason =>
              logger.info(s"Rejected block: ${blockRef.show}, reason: ${reason.show}")
            case reason: BlockAwaitReason =>
              logger.info(s"Awaiting block: ${blockRef.show}, reason: ${reason.show}")

          }
        }
      }
    }

}
