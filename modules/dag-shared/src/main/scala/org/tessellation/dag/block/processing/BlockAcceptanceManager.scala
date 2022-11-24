package org.tessellation.dag.block.processing

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

import org.tessellation.dag.block.BlockValidator
import org.tessellation.dag.domain.block.DAGBlock
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.BlockReference
import org.tessellation.security.SecurityProvider
import org.tessellation.security.signature.Signed

import eu.timepit.refined.cats._
import eu.timepit.refined.types.numeric.NonNegLong
import monocle.syntax.all._
import org.typelevel.log4cats.slf4j.Slf4jLogger

trait BlockAcceptanceManager[F[_]] {

  def acceptBlocksIteratively(
    blocks: List[Signed[DAGBlock]],
    context: BlockAcceptanceContext[F]
  ): F[BlockAcceptanceResult]

  def acceptBlock(
    block: Signed[DAGBlock],
    context: BlockAcceptanceContext[F]
  ): F[Either[BlockNotAcceptedReason, (BlockAcceptanceContextUpdate, UsageCount)]]

}

object BlockAcceptanceManager {

  def make[F[_]: Async: KryoSerializer: SecurityProvider](
    blockValidator: BlockValidator[F]
  ): BlockAcceptanceManager[F] = make(BlockAcceptanceLogic.make[F], blockValidator)

  def make[F[_]: Async: KryoSerializer](
    logic: BlockAcceptanceLogic[F],
    blockValidator: BlockValidator[F]
  ): BlockAcceptanceManager[F] =
    new BlockAcceptanceManager[F] {
      private val logger = Slf4jLogger.getLoggerFromClass[F](BlockAcceptanceManager.getClass)

      def acceptBlocksIteratively(
        blocks: List[Signed[DAGBlock]],
        context: BlockAcceptanceContext[F]
      ): F[BlockAcceptanceResult] = {

        def go(
          initState: BlockAcceptanceState,
          toProcess: List[(Signed[DAGBlock], TxChains)]
        ): F[BlockAcceptanceState] =
          for {
            currState <- toProcess.foldLeftM(initState.copy(awaiting = List.empty)) { (acc, blockAndTxChains) =>
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
          .foldLeftM((List.empty[(Signed[DAGBlock], TxChains)], List.empty[(Signed[DAGBlock], ValidationFailed)])) { (acc, block) =>
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
        block: Signed[DAGBlock],
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

      private def logAcceptedBlock(tuple: (Signed[DAGBlock], NonNegLong)): F[Unit] = {
        val (signedBlock, blockUsages) = tuple
        BlockReference.of(signedBlock).flatMap { blockRef =>
          logger.info(s"Accepted block: ${blockRef.show}, usages: ${blockUsages.show}")
        }
      }

      private def logNotAcceptedBlock(tuple: (Signed[DAGBlock], BlockNotAcceptedReason)): F[Unit] = {
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
