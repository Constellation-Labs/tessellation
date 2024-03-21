package org.tessellation.currency.schema

import cats.effect.kernel.Sync
import cats.syntax.contravariantSemigroupal._
import cats.syntax.functor._

import scala.collection.immutable.{SortedMap, SortedSet}

import org.tessellation.ext.cats.syntax.next.catsSyntaxNext
import org.tessellation.ext.crypto._
import org.tessellation.schema._
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.{Amount, Balance}
import org.tessellation.schema.currencyMessage.CurrencyMessage
import org.tessellation.schema.epoch.EpochProgress
import org.tessellation.schema.height.{Height, SubHeight}
import org.tessellation.schema.semver.SnapshotVersion
import org.tessellation.schema.snapshot._
import org.tessellation.schema.transaction._
import org.tessellation.security._
import org.tessellation.security.hash.{Hash, ProofsHash}
import org.tessellation.syntax.sortedCollection._

import derevo.cats.{eqv, order, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import derevo.scalacheck.arbitrary
import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._
import eu.timepit.refined.cats._
import eu.timepit.refined.scalacheck.all._
import eu.timepit.refined.string.MatchesRegex
import eu.timepit.refined.types.numeric.{NonNegLong, PosInt}
import io.estatico.newtype.macros.newtype

object currency {

  @newtype
  case class TokenSymbol(symbol: String Refined MatchesRegex["[A-Z]+"])

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
    def stateProof[F[_]: Sync: Hasher](ordinal: SnapshotOrdinal, hashSelect: HashSelect): F[CurrencySnapshotStateProof] =
      hashSelect.select(ordinal) match {
        case KryoHash =>
          (Hasher[F].hashKryo(lastTxRefs), Hasher[F].hashKryo(balances)).tupled.map(CurrencySnapshotStateProof.apply)
        case JsonHash =>
          (lastTxRefs.hash, balances.hash).tupled.map(CurrencySnapshotStateProof.apply)
      }
  }

  @derive(decoder, encoder, order, show, arbitrary)
  @newtype
  case class SnapshotFee(value: NonNegLong)

  object SnapshotFee {
    implicit def toAmount(fee: SnapshotFee): Amount = Amount(fee.value)

    val MinValue: SnapshotFee = SnapshotFee(0L)
  }

  @derive(eqv, show, encoder, decoder)
  case class DataApplicationPart(
    onChainState: Array[Byte],
    blocks: List[Array[Byte]],
    calculatedStateProof: Hash
  )

  object DataApplicationPart {
    def empty: DataApplicationPart = DataApplicationPart(Array.empty, List.empty, Hash.empty)
  }

  @derive(eqv, show, encoder, decoder)
  case class CurrencySnapshot(
    ordinal: SnapshotOrdinal,
    height: Height,
    subHeight: SubHeight,
    lastSnapshotHash: Hash,
    blocks: SortedSet[BlockAsActiveTip],
    rewards: SortedSet[RewardTransaction],
    tips: SnapshotTips,
    info: CurrencySnapshotInfo,
    epochProgress: EpochProgress,
    dataApplication: Option[DataApplicationPart] = None,
    version: SnapshotVersion = SnapshotVersion("0.0.1")
  ) extends FullSnapshot[CurrencySnapshotStateProof, CurrencySnapshotInfo]

  @derive(eqv, show, encoder, decoder)
  case class CurrencyIncrementalSnapshot(
    ordinal: SnapshotOrdinal,
    height: Height,
    subHeight: SubHeight,
    lastSnapshotHash: Hash,
    blocks: SortedSet[BlockAsActiveTip],
    rewards: SortedSet[RewardTransaction],
    tips: SnapshotTips,
    stateProof: CurrencySnapshotStateProof,
    epochProgress: EpochProgress,
    dataApplication: Option[DataApplicationPart] = None,
    messages: Option[SortedSet[CurrencyMessage]] = None,
    version: SnapshotVersion = SnapshotVersion("0.0.1")
  ) extends IncrementalSnapshot[CurrencySnapshotStateProof]

  object CurrencyIncrementalSnapshot {
    def fromCurrencySnapshot[F[_]: Sync: Hasher](snapshot: CurrencySnapshot, hashSelect: HashSelect): F[CurrencyIncrementalSnapshot] =
      snapshot.info.stateProof[F](snapshot.ordinal, hashSelect).map { stateProof =>
        CurrencyIncrementalSnapshot(
          snapshot.ordinal,
          snapshot.height,
          snapshot.subHeight,
          snapshot.lastSnapshotHash,
          snapshot.blocks,
          snapshot.rewards,
          snapshot.tips,
          stateProof,
          snapshot.epochProgress,
          snapshot.dataApplication,
          None,
          snapshot.version
        )
      }
  }

  object CurrencySnapshot {
    def mkGenesis(balances: Map[Address, Balance], dataApplicationPart: Option[DataApplicationPart]): CurrencySnapshot =
      CurrencySnapshot(
        SnapshotOrdinal.MinValue,
        Height.MinValue,
        SubHeight.MinValue,
        Hash.empty,
        SortedSet.empty,
        SortedSet.empty,
        SnapshotTips(SortedSet.empty, mkActiveTips(8)),
        CurrencySnapshotInfo(SortedMap.empty, SortedMap.from(balances)),
        EpochProgress.MinValue,
        dataApplicationPart
      )

    def mkFirstIncrementalSnapshot[F[_]: Sync: Hasher](
      genesis: Hashed[CurrencySnapshot],
      hashSelect: HashSelect
    ): F[CurrencyIncrementalSnapshot] =
      genesis.info.stateProof[F](genesis.ordinal, hashSelect).map { stateProof =>
        CurrencyIncrementalSnapshot(
          genesis.ordinal.next,
          genesis.height,
          genesis.subHeight.next,
          genesis.hash,
          SortedSet.empty,
          SortedSet.empty,
          genesis.tips,
          stateProof,
          genesis.epochProgress,
          genesis.dataApplication,
          None,
          genesis.version
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

  @derive(eqv, encoder, decoder)
  case class CurrencySnapshotContext(address: Address, snapshotInfo: CurrencySnapshotInfo)
}
