package io.constellationnetwork.node.shared.domain.swap.block

import cats.data.Validated.{Invalid, Valid}
import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.node.shared.domain.swap.AllowSpendChainValidator.AllowSpendNel
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.swap.AllowSpendBlock
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.{Hasher, SecurityProvider}

import monocle.syntax.all._
import org.typelevel.log4cats.slf4j.Slf4jLogger

trait AllowSpendBlockAcceptanceManager[F[_]] {

  def acceptBlocksIteratively(
    blocks: List[Signed[AllowSpendBlock]],
    context: AllowSpendBlockAcceptanceContext[F],
    snapshotOrdinal: SnapshotOrdinal
  )(implicit hasher: Hasher[F]): F[AllowSpendBlockAcceptanceResult]

  def acceptBlock(
    block: Signed[AllowSpendBlock],
    context: AllowSpendBlockAcceptanceContext[F],
    snapshotOrdinal: SnapshotOrdinal
  )(implicit hasher: Hasher[F]): F[Either[AllowSpendBlockNotAcceptedReason, AllowSpendBlockAcceptanceContextUpdate]]

}

object AllowSpendBlockAcceptanceManager {

  def make[F[_]: Async: SecurityProvider](
    blockValidator: AllowSpendBlockValidator[F]
  ): AllowSpendBlockAcceptanceManager[F] = make(AllowSpendBlockAcceptanceLogic.make[F], blockValidator)

  def make[F[_]: Async](
    logic: AllowSpendBlockAcceptanceLogic[F],
    blockValidator: AllowSpendBlockValidator[F]
  ): AllowSpendBlockAcceptanceManager[F] =
    new AllowSpendBlockAcceptanceManager[F] {
      private val logger = Slf4jLogger.getLoggerFromClass[F](AllowSpendBlockAcceptanceManager.getClass)

      def acceptBlocksIteratively(
        blocks: List[Signed[AllowSpendBlock]],
        context: AllowSpendBlockAcceptanceContext[F],
        snapshotOrdinal: SnapshotOrdinal
      )(implicit hasher: Hasher[F]): F[AllowSpendBlockAcceptanceResult] = {

        def go(
          initState: AllowSpendBlockAcceptanceState,
          toProcess: List[(Signed[AllowSpendBlock], Map[Address, AllowSpendNel])]
        ): F[AllowSpendBlockAcceptanceState] =
          for {
            currState <- toProcess.foldLeftM[F, AllowSpendBlockAcceptanceState](initState.copy(awaiting = List.empty)) {
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
                        case reason: AllowSpendBlockRejectionReason =>
                          acc
                            .focus(_.rejected)
                            .modify(_ :+ (block, reason))
                        case reason: AllowSpendBlockAwaitReason =>
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
            (List.empty[(Signed[AllowSpendBlock], Map[Address, AllowSpendNel])], List.empty[(Signed[AllowSpendBlock], ValidationFailed)])
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
              go(AllowSpendBlockAcceptanceState.withRejectedBlocks(invalidList), validList)
                .map(_.toBlockAcceptanceResult)
                .flatTap { result =>
                  result.accepted.traverse(logAcceptedBlock) >>
                    result.notAccepted.traverse(logNotAcceptedBlock)
                }
          }
      }

      def acceptBlock(
        block: Signed[AllowSpendBlock],
        context: AllowSpendBlockAcceptanceContext[F],
        snapshotOrdinal: SnapshotOrdinal
      )(implicit hasher: Hasher[F]): F[Either[AllowSpendBlockNotAcceptedReason, AllowSpendBlockAcceptanceContextUpdate]] =
        blockValidator.validate(block, snapshotOrdinal).flatMap {
          _.toEither
            .leftMap(errors => ValidationFailed(errors.toNonEmptyList))
            .toEitherT[F]
            .flatMap {
              case (block, txChains) => logic.acceptBlock(block, txChains, context, AllowSpendBlockAcceptanceContextUpdate.empty)
            }
            .leftSemiflatTap(reason => logNotAcceptedBlock((block, reason)))
            .semiflatTap(_ => logAcceptedBlock(block))
            .value
        }

      private def logAcceptedBlock(signedBlock: Signed[AllowSpendBlock])(implicit hasher: Hasher[F]): F[Unit] =
        signedBlock.toHashed.map(_.hash).flatMap { blockRef =>
          logger.info(s"Accepted allow spend block: ${blockRef.show}")
        }

      private def logNotAcceptedBlock(
        tuple: (Signed[AllowSpendBlock], AllowSpendBlockNotAcceptedReason)
      )(implicit hasher: Hasher[F]): F[Unit] = {
        val (signedBlock, reason) = tuple
        signedBlock.toHashed.map(_.hash).flatMap { blockRef =>
          reason match {
            case reason: AllowSpendBlockRejectionReason =>
              logger.info(s"Rejected allow spend block: ${blockRef.show}, reason: ${reason.show}")
            case reason: AllowSpendBlockAwaitReason =>
              logger.info(s"Awaiting allow spend block: ${blockRef.show}, reason: ${reason.show}")

          }
        }
      }
    }

}
