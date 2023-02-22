package org.tessellation.currency.schema

import cats.MonadThrow
import cats.data.{NonEmptyList, NonEmptySet}
import cats.syntax.contravariantSemigroupal._
import cats.syntax.functor._
import cats.syntax.reducible._

import scala.collection.immutable.{SortedMap, SortedSet}

import org.tessellation.ext.cats.data.OrderBasedOrdering
import org.tessellation.ext.cats.syntax.next.catsSyntaxNext
import org.tessellation.ext.crypto._
import org.tessellation.ext.derevo.ordering
import org.tessellation.kryo.KryoSerializer
import org.tessellation.merkletree.MerkleTree
import org.tessellation.schema._
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Balance
import org.tessellation.schema.height.{Height, SubHeight}
import org.tessellation.schema.snapshot.{Snapshot, SnapshotInfo}
import org.tessellation.schema.transaction._
import org.tessellation.security.Hashed
import org.tessellation.security.hash.{Hash, ProofsHash}
import org.tessellation.security.signature.Signed
import org.tessellation.syntax.sortedCollection._

import derevo.cats.{eqv, order, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._
import eu.timepit.refined.string.MatchesRegex
import eu.timepit.refined.types.numeric.PosInt
import io.estatico.newtype.macros.newtype

object currency {

  @newtype
  case class TokenSymbol(symbol: String Refined MatchesRegex["[A-Z]+"])

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
    balances: SortedMap[Address, Balance]
  ) extends SnapshotInfo {}

  object CurrencySnapshotInfo {
    def stateProof[F[_]: MonadThrow: KryoSerializer](info: CurrencySnapshotInfo): F[MerkleTree] =
      (info.lastTxRefs.hashF, info.balances.hashF).tupled
        .map(_.toNonEmptyList)
        .map(MerkleTree.from)
  }

  @derive(eqv, show, encoder, decoder)
  case class CurrencySnapshot(
    ordinal: SnapshotOrdinal,
    height: Height,
    subHeight: SubHeight,
    lastSnapshotHash: Hash,
    blocks: SortedSet[BlockAsActiveTip[CurrencyBlock]],
    tips: SnapshotTips,
    info: CurrencySnapshotInfo
  ) extends Snapshot[CurrencyTransaction, CurrencyBlock] {}

  @derive(eqv, show, encoder, decoder)
  case class CurrencyIncrementalSnapshot(
    ordinal: SnapshotOrdinal,
    height: Height,
    subHeight: SubHeight,
    lastSnapshotHash: Hash,
    blocks: SortedSet[BlockAsActiveTip[CurrencyBlock]],
    tips: SnapshotTips,
    stateProof: MerkleTree
  ) extends Snapshot[CurrencyTransaction, CurrencyBlock] {}

  object CurrencyIncrementalSnapshot {
    def fromCurrencySnapshot[F[_]: MonadThrow: KryoSerializer](snapshot: CurrencySnapshot): F[CurrencyIncrementalSnapshot] =
      CurrencySnapshotInfo
        .stateProof[F](snapshot.info)
        .map { stateProof =>
          CurrencyIncrementalSnapshot(
            snapshot.ordinal,
            snapshot.height,
            snapshot.subHeight,
            snapshot.lastSnapshotHash,
            snapshot.blocks,
            snapshot.tips,
            stateProof
          )
        }
  }

  object CurrencySnapshot {
    def mkGenesis(balances: Map[Address, Balance]): CurrencySnapshot =
      CurrencySnapshot(
        SnapshotOrdinal.MinValue,
        Height.MinValue,
        SubHeight.MinValue,
        Hash(""),
        SortedSet.empty,
        SnapshotTips(SortedSet.empty, mkActiveTips(8)),
        CurrencySnapshotInfo(SortedMap.empty, SortedMap.from(balances))
      )

    def mkFirstIncrementalSnapshot[F[_]: MonadThrow: KryoSerializer](genesis: Hashed[CurrencySnapshot]): F[CurrencyIncrementalSnapshot] =
      CurrencySnapshotInfo.stateProof[F](genesis.info).map { stateProof =>
        CurrencyIncrementalSnapshot(
          genesis.ordinal.next,
          genesis.height,
          genesis.subHeight.next,
          genesis.hash,
          SortedSet.empty,
          genesis.tips,
          stateProof
        )
      }

    private def mkActiveTips(n: PosInt): SortedSet[ActiveTip] =
      List
        .range(0, n.value)
        .map { i =>
          ActiveTip(BlockReference(Height.MinValue, ProofsHash(s"%064d".format(i))), 0L, SnapshotOrdinal.MinValue)
        }
        .toSortedSet
  }
}
