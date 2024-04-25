package org.tessellation.node.shared.infrastructure.block.processing

import cats.data.Validated.{Invalid, Valid}
import cats.effect.Async
import cats.syntax.all._

import org.tessellation.node.shared.domain.block.processing.{TxChains, _}
import org.tessellation.node.shared.infrastructure.block.processing.BlockAcceptanceLogic
import org.tessellation.schema.{Block, BlockReference, SnapshotOrdinal}
import org.tessellation.security.signature.Signed
import org.tessellation.security.{Hasher, SecurityProvider}

import eu.timepit.refined.cats._
import eu.timepit.refined.types.numeric.NonNegLong
import monocle.syntax.all._
import org.typelevel.log4cats.slf4j.Slf4jLogger

object BlockAcceptanceManager {

  def make[F[_]: Async: SecurityProvider](
    blockValidator: BlockValidator[F],
    txHasher: Hasher[F]
  ): BlockAcceptanceManager[F] = make(BlockAcceptanceLogic.make[F](txHasher), blockValidator, txHasher)

  def make[F[_]: Async](
    logic: BlockAcceptanceLogic[F],
    blockValidator: BlockValidator[F],
    txHasher: Hasher[F]
  ): BlockAcceptanceManager[F] =
    new BlockAcceptanceManager[F] {
      private val logger = Slf4jLogger.getLoggerFromClass[F](BlockAcceptanceManager.getClass)

      def acceptBlocksIteratively(
        blocks: List[Signed[Block]],
        context: BlockAcceptanceContext[F],
        snapshotOrdinal: SnapshotOrdinal
      )(implicit hasher: Hasher[F]): F[BlockAcceptanceResult] = {

        def go(
          initState: BlockAcceptanceState,
          toProcess: List[(Signed[Block], TxChains)]
        ): F[BlockAcceptanceState] =
          for {
            currState <- toProcess.foldLeftM[F, BlockAcceptanceState](initState.copy(awaiting = List.empty)) { (acc, blockAndTxChains) =>
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
          .foldLeftM((List.empty[(Signed[Block], TxChains)], List.empty[(Signed[Block], ValidationFailed)])) { (acc, block) =>
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
              go(BlockAcceptanceState.withRejectedBlocks(invalidList), validList)
                .map(_.toBlockAcceptanceResult)
                .flatTap { result =>
                  result.accepted.traverse(logAcceptedBlock) >>
                    result.notAccepted.traverse(logNotAcceptedBlock)
                }
          }
      }

      def acceptBlock(
        block: Signed[Block],
        context: BlockAcceptanceContext[F],
        snapshotOrdinal: SnapshotOrdinal
      )(implicit hasher: Hasher[F]): F[Either[BlockNotAcceptedReason, (BlockAcceptanceContextUpdate, NonNegLong)]] =
        blockValidator.validate(block, snapshotOrdinal).flatMap {
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

      private def logAcceptedBlock(tuple: (Signed[Block], NonNegLong))(implicit hasher: Hasher[F]): F[Unit] = {
        val (signedBlock, blockUsages) = tuple
        BlockReference.of(signedBlock).flatMap { blockRef =>
          logger.info(s"Accepted block: ${blockRef.show}, usages: ${blockUsages.show}")
        }
      }

      private def logNotAcceptedBlock(tuple: (Signed[Block], BlockNotAcceptedReason))(implicit hasher: Hasher[F]): F[Unit] = {
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
