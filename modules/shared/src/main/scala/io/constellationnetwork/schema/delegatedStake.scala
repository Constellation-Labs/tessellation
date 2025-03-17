package io.constellationnetwork.schema

import cats.Order._
import cats.effect.kernel.Async
import cats.syntax.functor._
import cats.syntax.semigroup._

import io.constellationnetwork.ext.crypto._
import io.constellationnetwork.ext.derevo.ordering
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Amount
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.{Hashed, Hasher}

import derevo.cats.{eqv, order, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import eu.timepit.refined.auto.{autoRefineV, _}
import eu.timepit.refined.cats._
import eu.timepit.refined.types.numeric.NonNegLong
import io.estatico.newtype.macros.newtype

object delegatedStake {
  @derive(decoder, encoder, order, show)
  @newtype
  case class DelegatedStakeAmount(value: NonNegLong) {
    def plus(a: DelegatedStakeAmount): DelegatedStakeAmount = DelegatedStakeAmount(NonNegLong.unsafeFrom(value.value + a.value.value))
  }
  object DelegatedStakeAmount {
    implicit def toAmount(amount: DelegatedStakeAmount): Amount = Amount(amount.value)
    val empty = DelegatedStakeAmount(NonNegLong(0L))
  }

  @derive(decoder, encoder, order, show)
  @newtype
  case class DelegatedStakeFee(value: NonNegLong)
  object DelegatedStakeFee {
    implicit def toAmount(fee: DelegatedStakeFee): Amount = Amount(fee.value)
  }

  @derive(decoder, encoder, show, order, ordering)
  @newtype
  case class DelegatedStakeOrdinal(value: NonNegLong) {
    def next: DelegatedStakeOrdinal = DelegatedStakeOrdinal(value |+| 1L)
  }

  object DelegatedStakeOrdinal {
    val first: DelegatedStakeOrdinal = DelegatedStakeOrdinal(1L)
  }

  @derive(eqv, show, encoder, decoder)
  case class DelegatedStakeReference(ordinal: DelegatedStakeOrdinal, hash: Hash)
  object DelegatedStakeReference {
    val empty = DelegatedStakeReference(DelegatedStakeOrdinal(NonNegLong(0L)), Hash.empty)

    def of(hashedTransaction: Hashed[UpdateDelegatedStake.Create]): DelegatedStakeReference =
      DelegatedStakeReference(hashedTransaction.ordinal, hashedTransaction.hash)

    def of[F[_]: Async: Hasher](signedTransaction: Signed[UpdateDelegatedStake.Create]): F[DelegatedStakeReference] =
      signedTransaction.value.hash.map(DelegatedStakeReference(signedTransaction.ordinal, _))
  }

  @derive(eqv, show, encoder, decoder)
  sealed trait UpdateDelegatedStake
  object UpdateDelegatedStake {
    @derive(eqv, show, encoder, decoder)
    case class Create(
      nodeId: PeerId,
      amount: DelegatedStakeAmount,
      fee: DelegatedStakeFee = DelegatedStakeFee(NonNegLong(0L)),
      tokenLockRef: Hash,
      parent: DelegatedStakeReference = DelegatedStakeReference.empty
    ) extends UpdateDelegatedStake {
      def ordinal: DelegatedStakeOrdinal = parent.ordinal.next
    }

    @derive(eqv, show, encoder, decoder)
    case class Withdraw(stakeRef: Hash) extends UpdateDelegatedStake
  }
  @derive(eqv, show, encoder)
  case class DelegatedStakeInfo(
    acceptedOrdinal: SnapshotOrdinal,
    tokenLockRef: Hash,
    amount: DelegatedStakeAmount,
    fee: DelegatedStakeFee,
    withdrawalStartEpoch: Option[EpochProgress],
    withdrawalEndEpoch: Option[EpochProgress]
  )

  @derive(eqv, show, encoder)
  case class DelegatedStakesInfo(
    address: Address,
    activeDelegatedStakes: List[DelegatedStakeInfo],
    pendingWithdrawals: List[DelegatedStakeInfo]
  )
}
