package org.tessellation.currency.schema

import cats.data.{NonEmptyList, NonEmptySet}

import scala.collection.immutable.{SortedMap, SortedSet}

import org.tessellation.ext.cats.data.OrderBasedOrdering
import org.tessellation.ext.derevo.ordering
import org.tessellation.schema._
import org.tessellation.schema.address.Address
import org.tessellation.schema.height.Height
import org.tessellation.schema.snapshot.{Snapshot, SnapshotInfo}
import org.tessellation.schema.transaction._
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed

import derevo.cats.{eqv, order, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

object currency {

  @derive(decoder, encoder, order, ordering, show)
  case class CurrencyTransaction(
    source: Address,
    destination: Address,
    amount: TransactionAmount,
    fee: TransactionFee,
    parent: TransactionReference,
    salt: TransactionSalt,
    ordinal: TransactionOrdinal
  ) extends Transaction {}

  @derive(show, eqv, encoder, decoder, order, ordering)
  case class CurrencyBlock(
    parent: NonEmptyList[BlockReference],
    transactions: NonEmptySet[Signed[CurrencyTransaction]]
  ) extends Block[CurrencyTransaction] {}

  object CurrencyBlock {
    implicit object OrderingInstanceAsActiveTip extends OrderBasedOrdering[BlockAsActiveTip[CurrencyBlock]]
  }

  @derive(encoder, decoder, eqv, show)
  case class CurrencySnapshotInfo(
    lastTxRefs: SortedMap[Address, TransactionReference],
    balances: SortedMap[Address, balance.Balance]
  ) extends SnapshotInfo {}

  @derive(eqv, show, encoder, decoder)
  case class CurrencySnapshot(
    ordinal: SnapshotOrdinal,
    height: Height,
    lastSnapshotHash: Hash,
    blocks: SortedSet[BlockAsActiveTip[CurrencyBlock]],
    tips: SnapshotTips,
    info: CurrencySnapshotInfo
  ) extends Snapshot[CurrencyTransaction, CurrencyBlock] {}
}
