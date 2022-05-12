package org.tessellation.dag.block.processing

import cats.data.EitherT
import cats.effect.Async
import cats.syntax.applicative._
import cats.syntax.eq._
import cats.syntax.flatMap._
import cats.syntax.foldable._
import cats.syntax.functor._
import cats.syntax.show._
import cats.syntax.traverse._

import org.tessellation.dag.domain.block.{BlockReference, DAGBlock}
import org.tessellation.kryo.KryoSerializer
import org.tessellation.security.signature.Signed

import eu.timepit.refined.cats._
import eu.timepit.refined.types.numeric.NonNegLong
import monocle.syntax.all._
import org.typelevel.log4cats.slf4j.Slf4jLogger

trait BlockAcceptanceManager[F[_]] {

  def acceptBlocks(
    blocks: List[Signed[DAGBlock]],
    context: BlockAcceptanceContext[F]
  ): F[BlockAcceptanceResult]

}

object BlockAcceptanceManager {

  def make[F[_]: Async: KryoSerializer](logic: BlockAcceptanceLogic[F]): BlockAcceptanceManager[F] =
    new BlockAcceptanceManager[F] {
      private val logger = Slf4jLogger.getLoggerFromClass[F](BlockAcceptanceManager.getClass)

      def acceptBlocks(
        blocks: List[Signed[DAGBlock]],
        context: BlockAcceptanceContext[F]
      ): F[BlockAcceptanceResult] = {

        def go(
          initState: BlockAcceptanceState,
          toProcess: List[Signed[DAGBlock]]
        ): F[BlockAcceptanceState] =
          for {
            currState <- toProcess.foldLeftM(initState.copy(awaiting = List.empty)) { (acc, block) =>
              EitherT(
                logic.acceptBlock(block, context, acc.contextUpdate)
              ).map {
                case (contextUpdate, blockUsages) =>
                  acc
                    .focus(_.contextUpdate)
                    .replace(contextUpdate)
                    .focus(_.accepted)
                    .modify((block, blockUsages) :: _)
              }.leftMap {
                case reason: BlockRejectionReason =>
                  acc
                    .focus(_.rejected)
                    .modify(_ :+ (block, reason))
                case reason: BlockAwaitReason =>
                  acc
                    .focus(_.awaiting)
                    .modify(_ :+ (block, reason))
              }.merge
            }
            result <- if (initState === currState) currState.pure[F]
            else go(currState, currState.awaiting.map(_._1))
          } yield result

        go(BlockAcceptanceState.empty, blocks.sorted)
          .map(_.toBlockAcceptanceResult)
          .flatTap { result =>
            result.accepted.traverse(logAcceptedBlock) >>
              result.notAccepted.traverse(logNotAcceptedBlock)
          }
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
              logger.info(s"Rejected block: ${blockRef.show}, reason: ${reason.show}")

          }
        }
      }
    }

}
