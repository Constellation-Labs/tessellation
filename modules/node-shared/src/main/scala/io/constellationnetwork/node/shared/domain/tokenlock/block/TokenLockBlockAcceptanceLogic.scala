package io.constellationnetwork.node.shared.domain.tokenlock.block

import cats.data.{EitherT, NonEmptyList}
import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.node.shared.domain.tokenlock.TokenLockChainValidator.TokenLockNel
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.{Amount, Balance, BalanceArithmeticError}
import io.constellationnetwork.schema.tokenLock.{TokenLockBlock, TokenLockReference}
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.{Hasher, SecurityProvider}

trait TokenLockBlockAcceptanceLogic[F[_]] {
  def acceptBlock(
    block: Signed[TokenLockBlock],
    txChains: Map[Address, TokenLockNel],
    context: TokenLockBlockAcceptanceContext[F],
    contextUpdate: TokenLockBlockAcceptanceContextUpdate
  )(implicit hasher: Hasher[F]): EitherT[F, TokenLockBlockNotAcceptedReason, TokenLockBlockAcceptanceContextUpdate]

}

object TokenLockBlockAcceptanceLogic {

  def make[F[_]: Async: SecurityProvider]: TokenLockBlockAcceptanceLogic[F] =
    new TokenLockBlockAcceptanceLogic[F] {

      def acceptBlock(
        signedBlock: Signed[TokenLockBlock],
        txChains: Map[Address, TokenLockNel],
        context: TokenLockBlockAcceptanceContext[F],
        contextUpdate: TokenLockBlockAcceptanceContextUpdate
      )(implicit hasher: Hasher[F]): EitherT[F, TokenLockBlockNotAcceptedReason, TokenLockBlockAcceptanceContextUpdate] =
        for {
          _ <- processSignatures(signedBlock, context)
          contextUpdate1 <- processLastTxRefs(txChains, context, contextUpdate)
          contextUpdate2 <- processBalances(signedBlock, context, contextUpdate1)
        } yield contextUpdate2

      def processLastTxRefs(
        txChains: Map[Address, TokenLockNel],
        context: TokenLockBlockAcceptanceContext[F],
        contextUpdate: TokenLockBlockAcceptanceContextUpdate
      )(implicit hasher: Hasher[F]): EitherT[F, TokenLockBlockNotAcceptedReason, TokenLockBlockAcceptanceContextUpdate] =
        txChains.toList
          .foldLeft((contextUpdate.lastTokenLocksRefs, none[TokenLockBlockAwaitReason]).asRight[RejectedTokenLock].toEitherT[F]) {
            case (acc, (address, txChain)) =>
              acc.flatMap {
                case (lastTxRefsUpdate, maybeAwaitingBlock) =>
                  val rejectionOrUpdate
                    : F[Either[RejectedTokenLock, (Map[Address, TokenLockReference], Option[TokenLockBlockAwaitReason])]] =
                    for {
                      lastTxRef <- contextUpdate.lastTokenLocksRefs
                        .get(address)
                        .toOptionT[F]
                        .orElseF(context.getLastTxRef(address))
                        .getOrElse(context.getInitialTxRef)

                      headTxChainRef <- TokenLockReference.of(txChain.head)
                      lastTxChainRef <- TokenLockReference.of(txChain.last)

                      result =
                        if (txChain.head.parent.ordinal < lastTxRef.ordinal)
                          RejectedTokenLock(
                            headTxChainRef,
                            ParentOrdinalBelowLastTxOrdinal(txChain.head.parent.ordinal, lastTxRef.ordinal)
                          ).asLeft
                        else if (txChain.head.parent.ordinal > lastTxRef.ordinal)
                          (
                            lastTxRefsUpdate,
                            maybeAwaitingBlock.orElse(
                              AwaitingTokenLock(
                                headTxChainRef,
                                ParentOrdinalAboveLastTxOrdinal(txChain.head.parent.ordinal, lastTxRef.ordinal)
                              ).some
                            )
                          ).asRight
                        else if (txChain.head.parent.hash =!= lastTxRef.hash) // ordinals are equal
                          RejectedTokenLock(
                            headTxChainRef,
                            ParentHashNotEqLastTxHash(txChain.head.parent.hash, lastTxRef.hash)
                          ).asLeft
                        else // hashes and ordinals are equal
                          (lastTxRefsUpdate.updated(address, lastTxChainRef), maybeAwaitingBlock).asRight

                    } yield result

                  EitherT(rejectionOrUpdate)
              }
          }
          .leftWiden[TokenLockBlockNotAcceptedReason]
          .flatMap {
            case (update, maybeAwaitReason) =>
              maybeAwaitReason
                .widen[TokenLockBlockNotAcceptedReason]
                .toLeft(update)
                .toEitherT[F]
          }
          .map { lastTxRefsUpdate =>
            contextUpdate.copy(lastTokenLocksRefs = lastTxRefsUpdate)
          }

      private def processBalances(
        block: Signed[TokenLockBlock],
        context: TokenLockBlockAcceptanceContext[F],
        contextUpdate: TokenLockBlockAcceptanceContextUpdate
      ): EitherT[F, TokenLockBlockNotAcceptedReason, TokenLockBlockAcceptanceContextUpdate] = {
        val minusFn: Amount => Balance => Either[BalanceArithmeticError, Balance] = a => _.minus(a)

        val sortedTxs = block.tokenLocks.toNonEmptyList
        val minusAmountOps = sortedTxs.groupMap(_.source)(tx => minusFn(tx.amount))

        val balancesUpdate = minusAmountOps
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
          .leftWiden[TokenLockBlockNotAcceptedReason]

        balancesUpdate.map { balances =>
          contextUpdate.copy(
            balances = balances
          )
        }
      }
    }

  def processSignatures[F[_]: Async: SecurityProvider](
    signedBlock: Signed[TokenLockBlock],
    context: TokenLockBlockAcceptanceContext[F]
  ): EitherT[F, TokenLockBlockNotAcceptedReason, Unit] =
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
            .getOrElse(().asRight[TokenLockBlockNotAcceptedReason])
        )
    )
}
