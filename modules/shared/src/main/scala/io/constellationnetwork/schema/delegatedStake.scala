package io.constellationnetwork.schema

import cats.Order._
import cats.effect.kernel.Async
import cats.kernel._
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}
import scala.math.Ordered.orderingToOrdered
import scala.util.control.NoStackTrace

import io.constellationnetwork.ext.crypto._
import io.constellationnetwork.ext.derevo.ordering
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.{Amount, Balance}
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
    val emptyAmount: DelegatedStakeAmount = DelegatedStakeAmount(NonNegLong(0L))

    implicit val stakeMonoid: Monoid[DelegatedStakeAmount] = new Monoid[DelegatedStakeAmount] {
      override def empty: DelegatedStakeAmount = emptyAmount
      override def combine(x: DelegatedStakeAmount, y: DelegatedStakeAmount): DelegatedStakeAmount = x.plus(y)
    }
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
    @derive(eqv, show, encoder, decoder, order)
    case class Create(
      source: Address,
      nodeId: PeerId,
      amount: DelegatedStakeAmount,
      fee: DelegatedStakeFee = DelegatedStakeFee(NonNegLong(0L)),
      tokenLockRef: Hash,
      parent: DelegatedStakeReference = DelegatedStakeReference.empty
    ) extends UpdateDelegatedStake {
      def ordinal: DelegatedStakeOrdinal = parent.ordinal.next
    }

    @derive(eqv, show, encoder, decoder)
    case class Withdraw(
      source: Address,
      stakeRef: Hash
    ) extends UpdateDelegatedStake
  }
  @derive(eqv, show, encoder)
  case class DelegatedStakeInfo(
    nodeId: PeerId,
    acceptedOrdinal: SnapshotOrdinal,
    tokenLockRef: Hash,
    amount: DelegatedStakeAmount,
    fee: DelegatedStakeFee,
    hash: Hash,
    withdrawalStartEpoch: Option[EpochProgress],
    withdrawalEndEpoch: Option[EpochProgress],
    rewardAmount: Balance,
    totalBalance: Amount
  )

  @derive(eqv, show, encoder)
  case class DelegatedStakesInfo(
    address: Address,
    activeDelegatedStakes: List[DelegatedStakeInfo],
    pendingWithdrawals: List[DelegatedStakeInfo]
  )

  @derive(decoder, encoder, eqv, show)
  case class DelegatedStakeRecord(
    event: Signed[UpdateDelegatedStake.Create],
    createdAt: SnapshotOrdinal,
    rewards: Amount
  )

  object DelegatedStakeRecord {
    implicit val order: Order[DelegatedStakeRecord] = Order[SnapshotOrdinal].contramap(_.createdAt)

    implicit val ordering: Ordering[DelegatedStakeRecord] =
      Ordering.by(r => (r.createdAt, r.rewards, r.event))
  }

  @derive(decoder, encoder, eqv, show)
  case class PendingDelegatedStakeWithdrawal(
    event: Signed[UpdateDelegatedStake.Create],
    rewards: Amount,
    acceptedOrdinal: SnapshotOrdinal,
    createdAt: EpochProgress
  )

  object PendingDelegatedStakeWithdrawal {
    implicit val order: Order[PendingDelegatedStakeWithdrawal] = Order[EpochProgress].contramap(_.createdAt)

    implicit val ordering: Ordering[PendingDelegatedStakeWithdrawal] =
      Ordering.by(r => (r.createdAt, r.rewards, r.event))
  }

  @derive(eqv, show)
  sealed trait DelegatedStakeError extends NoStackTrace
  case class MissingDelegatedStaking(message: String) extends DelegatedStakeError
  case class MissingTokenLock(message: String) extends DelegatedStakeError
}
