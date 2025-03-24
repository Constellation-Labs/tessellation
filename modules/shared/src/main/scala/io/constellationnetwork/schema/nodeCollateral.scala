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

object nodeCollateral {
  @derive(decoder, encoder, order, show)
  @newtype
  case class NodeCollateralAmount(value: NonNegLong) {
    def plus(a: NodeCollateralAmount): NodeCollateralAmount = NodeCollateralAmount(NonNegLong.unsafeFrom(value.value + a.value.value))
  }
  object NodeCollateralAmount {
    implicit def toAmount(amount: NodeCollateralAmount): Amount = Amount(amount.value)

    val empty: NodeCollateralAmount = NodeCollateralAmount(NonNegLong(0L))
  }

  @derive(decoder, encoder, order, show)
  @newtype
  case class NodeCollateralFee(value: NonNegLong)
  object NodeCollateralFee {
    implicit def toAmount(fee: NodeCollateralFee): Amount = Amount(fee.value)
  }

  @derive(decoder, encoder, show, order, ordering)
  @newtype
  case class NodeCollateralOrdinal(value: NonNegLong) {
    def next: NodeCollateralOrdinal = NodeCollateralOrdinal(value |+| 1L)
  }

  object NodeCollateralOrdinal {
    val first: NodeCollateralOrdinal = NodeCollateralOrdinal(1L)
  }

  @derive(eqv, show, encoder, decoder)
  case class NodeCollateralReference(ordinal: NodeCollateralOrdinal, hash: Hash)
  object NodeCollateralReference {
    val empty = NodeCollateralReference(NodeCollateralOrdinal(NonNegLong(0L)), Hash.empty)

    def of(hashedTransaction: Hashed[UpdateNodeCollateral.Create]): NodeCollateralReference =
      NodeCollateralReference(hashedTransaction.ordinal, hashedTransaction.hash)

    def of[F[_]: Async: Hasher](signedTransaction: Signed[UpdateNodeCollateral.Create]): F[NodeCollateralReference] =
      signedTransaction.value.hash.map(NodeCollateralReference(signedTransaction.ordinal, _))
  }

  @derive(eqv, show, encoder, decoder)
  sealed trait UpdateNodeCollateral
  case object UpdateNodeCollateral {
    @derive(eqv, show, encoder, decoder)
    case class Create(
      source: Address,
      nodeId: PeerId,
      amount: NodeCollateralAmount,
      fee: NodeCollateralFee = NodeCollateralFee(NonNegLong(0L)),
      tokenLockRef: Hash,
      parent: NodeCollateralReference = NodeCollateralReference.empty
    ) extends UpdateNodeCollateral {
      def ordinal: NodeCollateralOrdinal = parent.ordinal.next
    }

    @derive(eqv, show, encoder, decoder)
    case class Withdraw(
      source: Address,
      collateralRef: Hash
    ) extends UpdateNodeCollateral
  }
  @derive(eqv, show, encoder)
  case class NodeCollateralInfo(
    nodeId: PeerId,
    acceptedOrdinal: SnapshotOrdinal,
    tokenLockRef: Hash,
    amount: NodeCollateralAmount,
    fee: NodeCollateralFee,
    withdrawalStartEpoch: Option[EpochProgress],
    withdrawalEndEpoch: Option[EpochProgress]
  )

  @derive(eqv, show, encoder)
  case class NodeCollateralsInfo(
    address: Address,
    activeNodeCollaterals: List[NodeCollateralInfo],
    pendingWithdrawals: List[NodeCollateralInfo]
  )
}
