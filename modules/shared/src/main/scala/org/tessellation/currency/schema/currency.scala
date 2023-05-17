package org.tessellation.currency.schema

import cats.MonadThrow
import cats.data.{NonEmptyList, NonEmptySet}
import cats.syntax.contravariantSemigroupal._
import cats.syntax.functor._

import scala.collection.immutable.{SortedMap, SortedSet}

import org.tessellation.ext.cats.data.OrderBasedOrdering
import org.tessellation.ext.cats.syntax.next.catsSyntaxNext
import org.tessellation.ext.codecs.NonEmptySetCodec
import org.tessellation.ext.crypto._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.Block.BlockConstructor
import org.tessellation.schema._
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.{Amount, Balance}
import org.tessellation.schema.height.{Height, SubHeight}
import org.tessellation.schema.semver.SnapshotVersion
import org.tessellation.schema.snapshot._
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
import eu.timepit.refined.types.numeric.{NonNegLong, PosInt}
import io.circe.Decoder
import io.estatico.newtype.macros.newtype

object currency {

  @newtype
  case class TokenSymbol(symbol: String Refined MatchesRegex["[A-Z]+"])

  @derive(decoder, encoder, order, show)
  case class CurrencyTransaction(
    source: Address,
    destination: Address,
    amount: TransactionAmount,
    fee: TransactionFee,
    parent: TransactionReference,
    salt: TransactionSalt
  ) extends Transaction

  object CurrencyTransaction {
    implicit object OrderingInstance extends OrderBasedOrdering[CurrencyTransaction]
  }

  @derive(show, eqv, encoder, decoder, order)
  case class CurrencyBlock(
    parent: NonEmptyList[BlockReference],
    transactions: NonEmptySet[Signed[CurrencyTransaction]]
  ) extends Block[CurrencyTransaction]

  object CurrencyBlock {

    implicit object OrderingInstance extends OrderBasedOrdering[CurrencyBlock]

    implicit val transactionsDecoder: Decoder[NonEmptySet[Signed[CurrencyTransaction]]] =
      NonEmptySetCodec.decoder[Signed[CurrencyTransaction]]
    implicit object OrderingInstanceAsActiveTip extends OrderBasedOrdering[BlockAsActiveTip[CurrencyBlock]]

    implicit val blockConstructor = new BlockConstructor[CurrencyTransaction, CurrencyBlock] {
      def create(parents: NonEmptyList[BlockReference], transactions: NonEmptySet[Signed[CurrencyTransaction]]): CurrencyBlock =
        CurrencyBlock(parents, transactions)
    }
  }

  @derive(encoder, decoder, eqv, show)
  case class CurrencySnapshotStateProof(
    lastTxRefsProof: Hash,
    balancesProof: Hash
  ) extends StateProof

  object CurrencySnapshotStateProof {
    def apply(a: (Hash, Hash)): CurrencySnapshotStateProof =
      CurrencySnapshotStateProof(a._1, a._2)
  }

  @derive(encoder, decoder, eqv, show)
  case class CurrencySnapshotInfo(
    lastTxRefs: SortedMap[Address, TransactionReference],
    balances: SortedMap[Address, Balance]
  ) extends SnapshotInfo[CurrencySnapshotStateProof] {
    def stateProof[F[_]: MonadThrow: KryoSerializer]: F[CurrencySnapshotStateProof] =
      (lastTxRefs.hashF, balances.hashF).tupled.map(CurrencySnapshotStateProof.apply)
  }

  @derive(decoder, encoder, order, show)
  @newtype
  case class SnapshotFee(value: NonNegLong)

  object SnapshotFee {
    implicit def toAmount(fee: SnapshotFee): Amount = Amount(fee.value)

    val MinValue: SnapshotFee = SnapshotFee(0L)
  }

  @derive(eqv, show, encoder, decoder)
  case class CurrencySnapshot(
    ordinal: SnapshotOrdinal,
    height: Height,
    subHeight: SubHeight,
    lastSnapshotHash: Hash,
    blocks: SortedSet[BlockAsActiveTip[CurrencyBlock]],
    rewards: SortedSet[RewardTransaction],
    tips: SnapshotTips,
    info: CurrencySnapshotInfo,
    data: Option[Array[Byte]] = None,
    version: SnapshotVersion = SnapshotVersion("0.0.1")
  ) extends FullSnapshot[CurrencyTransaction, CurrencyBlock, CurrencySnapshotStateProof, CurrencySnapshotInfo]

  @derive(eqv, show, encoder, decoder)
  case class CurrencyIncrementalSnapshot(
    ordinal: SnapshotOrdinal,
    height: Height,
    subHeight: SubHeight,
    lastSnapshotHash: Hash,
    blocks: SortedSet[BlockAsActiveTip[CurrencyBlock]],
    rewards: SortedSet[RewardTransaction],
    tips: SnapshotTips,
    stateProof: CurrencySnapshotStateProof,
    data: Option[Array[Byte]] = None,
    version: SnapshotVersion = SnapshotVersion("0.0.1")
  ) extends IncrementalSnapshot[CurrencyTransaction, CurrencyBlock, CurrencySnapshotStateProof]

  object CurrencyIncrementalSnapshot {
    def fromCurrencySnapshot[F[_]: MonadThrow: KryoSerializer](snapshot: CurrencySnapshot): F[CurrencyIncrementalSnapshot] =
      snapshot.info.stateProof[F].map { stateProof =>
        CurrencyIncrementalSnapshot(
          snapshot.ordinal,
          snapshot.height,
          snapshot.subHeight,
          snapshot.lastSnapshotHash,
          snapshot.blocks,
          snapshot.rewards,
          snapshot.tips,
          stateProof
        )
      }
  }

  object CurrencySnapshot {
    def mkGenesis(balances: Map[Address, Balance], dataApplication: Option[Array[Byte]]): CurrencySnapshot =
      CurrencySnapshot(
        SnapshotOrdinal.MinValue,
        Height.MinValue,
        SubHeight.MinValue,
        Hash.empty,
        SortedSet.empty,
        SortedSet.empty,
        SnapshotTips(SortedSet.empty, mkActiveTips(8)),
        CurrencySnapshotInfo(SortedMap.empty, SortedMap.from(balances)),
        dataApplication
      )

    def mkFirstIncrementalSnapshot[F[_]: MonadThrow: KryoSerializer](genesis: Hashed[CurrencySnapshot]): F[CurrencyIncrementalSnapshot] =
      genesis.info.stateProof[F].map { stateProof =>
        CurrencyIncrementalSnapshot(
          genesis.ordinal.next,
          genesis.height,
          genesis.subHeight.next,
          genesis.hash,
          SortedSet.empty,
          SortedSet.empty,
          genesis.tips,
          stateProof,
          genesis.data
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
