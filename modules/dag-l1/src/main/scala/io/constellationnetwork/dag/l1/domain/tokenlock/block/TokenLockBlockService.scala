package io.constellationnetwork.dag.l1.domain.tokenlock.block

import cats.data.EitherT
import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.option._
import cats.syntax.show._

import scala.util.control.NoStackTrace

import io.constellationnetwork.dag.l1.domain.address.storage.AddressStorage
import io.constellationnetwork.node.shared.domain.tokenlock.TokenLockStorage
import io.constellationnetwork.node.shared.domain.tokenlock.block._
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.{Amount, Balance}
import io.constellationnetwork.schema.tokenLock.{TokenLockBlock, TokenLockReference}
import io.constellationnetwork.security.hash.ProofsHash
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.{Hashed, Hasher}

trait TokenLockBlockService[F[_]] {
  def accept(signedBlock: Signed[TokenLockBlock], snapshotOrdinal: SnapshotOrdinal)(
    implicit hasher: Hasher[F]
  ): F[Unit]
}

object TokenLockBlockService {

  def make[F[_]: Async](
    tokenLockBlockAcceptanceManager: TokenLockBlockAcceptanceManager[F],
    addressStorage: AddressStorage[F],
    tokenLockBlockStorage: TokenLockBlockStorage[F],
    tokenLockStorage: TokenLockStorage[F],
    collateral: Amount
  ): TokenLockBlockService[F] =
    new TokenLockBlockService[F] {

      def accept(signedBlock: Signed[TokenLockBlock], snapshotOrdinal: SnapshotOrdinal)(
        implicit hasher: Hasher[F]
      ): F[Unit] =
        signedBlock.toHashed.flatMap { hashedBlock =>
          EitherT(tokenLockBlockAcceptanceManager.acceptBlock(signedBlock, context, snapshotOrdinal))
            .leftSemiflatMap(processAcceptanceError(hashedBlock))
            .semiflatMap(processAcceptanceSuccess(hashedBlock))
            .rethrowT
        }
      private val context: TokenLockBlockAcceptanceContext[F] = new TokenLockBlockAcceptanceContext[F] {

        def getBalance(address: Address): F[Option[Balance]] =
          addressStorage.getBalance(address).map(_.some)

        def getLastTxRef(address: Address): F[Option[TokenLockReference]] =
          tokenLockStorage.getLastProcessedTokenLock(address).map(_.ref.some)

        def getInitialTxRef: TokenLockReference =
          tokenLockStorage.getInitialTx.ref

        def getCollateral: Amount = collateral
      }

      private def processAcceptanceSuccess(
        hashedBlock: Hashed[TokenLockBlock]
      )(contextUpdate: TokenLockBlockAcceptanceContextUpdate)(implicit hasher: Hasher[F]): F[Unit] =
        for {
          hashedTransactions <- hashedBlock.signed.tokenLocks.toNonEmptyList
            .sortBy(_.ordinal)
            .traverse(_.toHashed)

          _ <- hashedTransactions.traverse(tokenLockStorage.accept)
          _ <- tokenLockBlockStorage.accept(hashedBlock)
          _ <- addressStorage.updateBalances(contextUpdate.balances)
        } yield ()

      private def processAcceptanceError(
        hashedBlock: Hashed[TokenLockBlock]
      )(reason: TokenLockBlockNotAcceptedReason): F[TokenLockBlockAcceptanceError] =
        tokenLockBlockStorage
          .postpone(hashedBlock)
          .as(TokenLockBlockAcceptanceError(hashedBlock.proofsHash, reason))

    }

  case class TokenLockBlockAcceptanceError(blockReference: ProofsHash, reason: TokenLockBlockNotAcceptedReason) extends NoStackTrace {
    override def getMessage: String = s"Block ${blockReference.show} could not be accepted ${reason.show}"
  }
}
