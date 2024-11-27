package io.constellationnetwork.node.shared.domain.swap.block

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

import io.constellationnetwork.node.shared.domain.swap.AllowSpendChainValidator.AllowSpendNel
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.{Amount, Balance, BalanceArithmeticError}
import io.constellationnetwork.schema.swap.{AllowSpendBlock, AllowSpendReference}
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.{Hasher, SecurityProvider}

import eu.timepit.refined.auto._

trait AllowSpendBlockAcceptanceLogic[F[_]] {
  def acceptBlock(
    block: Signed[AllowSpendBlock],
    txChains: Map[Address, AllowSpendNel],
    context: AllowSpendBlockAcceptanceContext[F],
    contextUpdate: AllowSpendBlockAcceptanceContextUpdate
  )(implicit hasher: Hasher[F]): EitherT[F, AllowSpendBlockNotAcceptedReason, AllowSpendBlockAcceptanceContextUpdate]

}

object AllowSpendBlockAcceptanceLogic {

  def make[F[_]: Async: SecurityProvider]: AllowSpendBlockAcceptanceLogic[F] =
    new AllowSpendBlockAcceptanceLogic[F] {

      def acceptBlock(
        signedBlock: Signed[AllowSpendBlock],
        txChains: Map[Address, AllowSpendNel],
        context: AllowSpendBlockAcceptanceContext[F],
        contextUpdate: AllowSpendBlockAcceptanceContextUpdate
      )(implicit hasher: Hasher[F]): EitherT[F, AllowSpendBlockNotAcceptedReason, AllowSpendBlockAcceptanceContextUpdate] =
        for {
          _ <- processSignatures(signedBlock, context)
          contextUpdate1 <- processLastTxRefs(txChains, context, contextUpdate)
          contextUpdate2 <- processBalances(signedBlock, context, contextUpdate1)
        } yield contextUpdate2

      def processLastTxRefs(
        txChains: Map[Address, AllowSpendNel],
        context: AllowSpendBlockAcceptanceContext[F],
        contextUpdate: AllowSpendBlockAcceptanceContextUpdate
      )(implicit hasher: Hasher[F]): EitherT[F, AllowSpendBlockNotAcceptedReason, AllowSpendBlockAcceptanceContextUpdate] =
        txChains.toList
          .foldLeft((contextUpdate.lastTxRefs, none[AllowSpendBlockAwaitReason]).asRight[RejectedAllowSpend].toEitherT[F]) { (acc, tup) =>
            acc.flatMap {
              case (lastTxRefsUpdate, maybeAwaitingBlock) =>
                val (address, txChain) = tup

                val rejectionOrUpdate
                  : F[Either[RejectedAllowSpend, (Map[Address, AllowSpendReference], Option[AllowSpendBlockAwaitReason])]] =
                  for {
                    lastTxRef <- contextUpdate.lastTxRefs
                      .get(address)
                      .toOptionT[F]
                      .orElseF(context.getLastTxRef(address))
                      .getOrElse(context.getInitialTxRef)

                    headTxChainRef <- AllowSpendReference.of(txChain.head)
                    lastTxChainRef <- AllowSpendReference.of(txChain.last)

                    result =
                      if (txChain.head.parent.ordinal < lastTxRef.ordinal)
                        RejectedAllowSpend(
                          headTxChainRef,
                          ParentOrdinalBelowLastTxOrdinal(txChain.head.parent.ordinal, lastTxRef.ordinal)
                        ).asLeft
                      else if (txChain.head.parent.ordinal > lastTxRef.ordinal)
                        (
                          lastTxRefsUpdate,
                          maybeAwaitingBlock.orElse(
                            AwaitingAllowSpend(
                              headTxChainRef,
                              ParentOrdinalAboveLastTxOrdinal(txChain.head.parent.ordinal, lastTxRef.ordinal)
                            ).some
                          )
                        ).asRight
                      else if (txChain.head.parent.hash =!= lastTxRef.hash) // ordinals are equal
                        RejectedAllowSpend(
                          headTxChainRef,
                          ParentHashNotEqLastTxHash(txChain.head.parent.hash, lastTxRef.hash)
                        ).asLeft
                      else // hashes and ordinals are equal
                        (lastTxRefsUpdate.updated(address, lastTxChainRef), maybeAwaitingBlock).asRight

                  } yield result

                EitherT(rejectionOrUpdate)
            }
          }
          .leftWiden[AllowSpendBlockNotAcceptedReason]
          .flatMap {
            case (update, maybeAwaitReason) =>
              maybeAwaitReason
                .widen[AllowSpendBlockNotAcceptedReason]
                .toLeft(update)
                .toEitherT[F]
          }
          .map { lastTxRefsUpdate =>
            contextUpdate.copy(lastTxRefs = lastTxRefsUpdate)
          }

      private def processBalances(
        block: Signed[AllowSpendBlock],
        context: AllowSpendBlockAcceptanceContext[F],
        contextUpdate: AllowSpendBlockAcceptanceContextUpdate
      ): EitherT[F, AllowSpendBlockNotAcceptedReason, AllowSpendBlockAcceptanceContextUpdate] = {
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
          .leftWiden[AllowSpendBlockNotAcceptedReason]
          .map { balancesUpdate =>
            contextUpdate.copy(balances = balancesUpdate)
          }
      }

    }

  def processSignatures[F[_]: Async: SecurityProvider](
    signedBlock: Signed[AllowSpendBlock],
    context: AllowSpendBlockAcceptanceContext[F]
  ): EitherT[F, AllowSpendBlockNotAcceptedReason, Unit] =
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
            .getOrElse(().asRight[AllowSpendBlockNotAcceptedReason])
        )
    )
}
