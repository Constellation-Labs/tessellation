package org.tessellation.dag.block.processing

import cats.data.{EitherT, NonEmptyList}
import cats.effect.Async
import cats.syntax.bifunctor._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.foldable._
import cats.syntax.functor._
import cats.syntax.option._
import cats.syntax.order._
import cats.syntax.semigroup._
import cats.syntax.traverse._
import cats.syntax.traverseFilter._

import org.tessellation.dag.domain.block.DAGBlock
import org.tessellation.dag.transaction.TransactionChainValidator.TransactionNel
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.{Amount, Balance, BalanceArithmeticError}
import org.tessellation.schema.security.SecurityProvider
import org.tessellation.schema.security.signature.Signed
import org.tessellation.schema.transaction.{Transaction, TransactionReference}

import eu.timepit.refined.auto._
import eu.timepit.refined.cats._

private[processing] trait BlockAcceptanceLogic[F[_]] {

  def acceptBlock(
    block: Signed[DAGBlock],
    txChains: Map[Address, TransactionNel],
    context: BlockAcceptanceContext[F],
    contextUpdate: BlockAcceptanceContextUpdate
  ): EitherT[F, BlockNotAcceptedReason, (BlockAcceptanceContextUpdate, UsageCount)]

}

object BlockAcceptanceLogic {

  def make[F[_]: Async: KryoSerializer: SecurityProvider]: BlockAcceptanceLogic[F] = new BlockAcceptanceLogic[F] {

    def acceptBlock(
      signedBlock: Signed[DAGBlock],
      txChains: Map[Address, TransactionNel],
      context: BlockAcceptanceContext[F],
      contextUpdate: BlockAcceptanceContextUpdate
    ): EitherT[F, BlockNotAcceptedReason, (BlockAcceptanceContextUpdate, UsageCount)] =
      for {
        _ <- processSignatures(signedBlock, context)
        (contextUpdate1, blockUsages) <- processParents(signedBlock, context, contextUpdate)
        contextUpdate2 <- processLastTxRefs(txChains, context, contextUpdate1)
        contextUpdate3 <- processBalances(signedBlock, context, contextUpdate2)
      } yield (contextUpdate3, blockUsages)

    private def processParents(
      signedBlock: Signed[DAGBlock],
      context: BlockAcceptanceContext[F],
      contextUpdate: BlockAcceptanceContextUpdate
    ): EitherT[F, BlockNotAcceptedReason, (BlockAcceptanceContextUpdate, UsageCount)] =
      signedBlock.value.parent
        .foldLeft((contextUpdate.parentUsages, initUsageCount).asRight[BlockRejectionReason].toEitherT[F]) { (acc, parent) =>
          acc.flatMap {
            case (parentUsagesUpdate, blockUsages) =>
              parentUsagesUpdate
                .get(parent)
                .toOptionT[F]
                .orElseF(context.getParentUsage(parent))
                .map { parentUsages =>
                  val newBlockUsages =
                    if (parentUsages >= deprecationThreshold)
                      blockUsages |+| usageIncrement
                    else
                      blockUsages
                  val newParentUsagesUpdate = parentUsagesUpdate.updated(parent, parentUsages |+| usageIncrement)

                  (newParentUsagesUpdate, newBlockUsages)
                }
                .toRight(ParentNotFound(parent))
          }
        }
        .leftWiden[BlockNotAcceptedReason]
        .map {
          case (parentUsagesUpdate, blockUsages) =>
            (contextUpdate.copy(parentUsages = parentUsagesUpdate), blockUsages)
        }

    def processLastTxRefs(
      txChains: Map[Address, NonEmptyList[Signed[Transaction]]],
      context: BlockAcceptanceContext[F],
      contextUpdate: BlockAcceptanceContextUpdate
    ): EitherT[F, BlockNotAcceptedReason, BlockAcceptanceContextUpdate] =
      txChains.toList
        .foldLeft((contextUpdate.lastTxRefs, none[BlockAwaitReason]).asRight[RejectedTransaction].toEitherT[F]) { (acc, tup) =>
          acc.flatMap {
            case (lastTxRefsUpdate, maybeAwaitingBlock) =>
              val (address, txChain) = tup

              val rejectionOrUpdate: F[Either[RejectedTransaction, (Map[Address, TransactionReference], Option[BlockAwaitReason])]] =
                for {
                  lastTxRef <- contextUpdate.lastTxRefs
                    .get(address)
                    .toOptionT[F]
                    .orElseF(context.getLastTxRef(address))
                    .getOrElse(TransactionReference.empty)

                  headTxChainRef <- TransactionReference.of(txChain.head)
                  lastTxChainRef <- TransactionReference.of(txChain.last)

                  result =
                    if (txChain.head.parent.ordinal < lastTxRef.ordinal)
                      RejectedTransaction(
                        headTxChainRef,
                        ParentOrdinalBelowLastTxOrdinal(txChain.head.parent.ordinal, lastTxRef.ordinal)
                      ).asLeft
                    else if (txChain.head.parent.ordinal > lastTxRef.ordinal)
                      (
                        lastTxRefsUpdate,
                        maybeAwaitingBlock.orElse(
                          AwaitingTransaction(
                            headTxChainRef,
                            ParentOrdinalAboveLastTxOrdinal(txChain.head.parent.ordinal, lastTxRef.ordinal)
                          ).some
                        )
                      ).asRight
                    else if (txChain.head.parent.hash =!= lastTxRef.hash) // ordinals are equal
                      RejectedTransaction(
                        headTxChainRef,
                        ParentHashNotEqLastTxHash(txChain.head.parent.hash, lastTxRef.hash)
                      ).asLeft
                    else // hashes and ordinals are equal
                      (lastTxRefsUpdate.updated(address, lastTxChainRef), maybeAwaitingBlock).asRight

                } yield result

              EitherT(rejectionOrUpdate)
          }
        }
        .leftWiden[BlockNotAcceptedReason]
        .flatMap {
          case (update, maybeAwaitReason) =>
            maybeAwaitReason
              .widen[BlockNotAcceptedReason]
              .toLeft(update)
              .toEitherT[F]
        }
        .map { lastTxRefsUpdate =>
          contextUpdate.copy(lastTxRefs = lastTxRefsUpdate)
        }

    private def processBalances(
      block: Signed[DAGBlock],
      context: BlockAcceptanceContext[F],
      contextUpdate: BlockAcceptanceContextUpdate
    ): EitherT[F, BlockNotAcceptedReason, BlockAcceptanceContextUpdate] = {
      val minusFn: Amount => Balance => Either[BalanceArithmeticError, Balance] = a => _.minus(a)
      val plusFn: Amount => Balance => Either[BalanceArithmeticError, Balance] = a => _.plus(a)

      val sortedTxs = block.transactions.toNonEmptyList
      val minusAmountOps = sortedTxs.groupMap(_.source)(tx => minusFn(tx.amount))
      val minusFeeOps = sortedTxs.groupMap(_.source)(tx => minusFn(tx.fee))
      val plusAmountOps = sortedTxs.groupMap(_.destination)(tx => plusFn(tx.amount))

      val allOps = minusAmountOps |+| minusFeeOps |+| plusAmountOps

      allOps
        .foldLeft(contextUpdate.balances.asRight[AddressBalanceOutOfRange].toEitherT[F]) { (acc, addressAndOps) =>
          acc.flatMap { balancesUpdate =>
            val (address, ops) = addressAndOps

            EitherT(
              balancesUpdate
                .get(address)
                .toOptionT[F]
                .orElseF(context.getBalance(address))
                .getOrElse(Balance.empty)
                .map { balance =>
                  ops
                    .foldLeft(balance.asRight[BalanceArithmeticError]) { (acc, op) =>
                      acc.flatMap(op)
                    }
                    .leftMap(AddressBalanceOutOfRange(address, _))
                    .map(balancesUpdate.updated(address, _))
                }
            )
          }
        }
        .leftWiden[BlockNotAcceptedReason]
        .map { balancesUpdate =>
          contextUpdate.copy(balances = balancesUpdate)
        }
    }

  }

  def processSignatures[F[_]: Async: SecurityProvider](
    signedBlock: Signed[DAGBlock],
    context: BlockAcceptanceContext[F]
  ): EitherT[F, BlockNotAcceptedReason, Unit] =
    EitherT(
      signedBlock.proofs
        .map(_.id.toPeerId)
        .toList
        .traverse(_.toAddress)
        .flatMap(
          _.filterA(address =>
            context.getBalance(address).map { balances =>
              !balances.getOrElse(Balance.empty).satisfiesCollateral(context.getCollateral)
            }
          )
        )
        .map(list =>
          NonEmptyList
            .fromList(list)
            .map(nel => SigningPeerBelowCollateral(nel).asLeft[Unit])
            .getOrElse(().asRight[BlockNotAcceptedReason])
        )
    )
}
