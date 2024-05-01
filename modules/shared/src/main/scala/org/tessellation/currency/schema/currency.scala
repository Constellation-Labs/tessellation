package org.tessellation.currency.schema

import cats.effect.kernel.Sync
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import org.tessellation.currency.schema.feeTransaction.FeeTransaction
import org.tessellation.ext.cats.syntax.next.catsSyntaxNext
import org.tessellation.ext.crypto._
import org.tessellation.schema._
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.{Amount, Balance}
import org.tessellation.schema.currencyMessage.{CurrencyMessage, MessageType}
import org.tessellation.schema.epoch.EpochProgress
import org.tessellation.schema.height.{Height, SubHeight}
import org.tessellation.schema.semver.SnapshotVersion
import org.tessellation.schema.snapshot._
import org.tessellation.schema.transaction._
import org.tessellation.security._
import org.tessellation.security.hash.{Hash, ProofsHash}
import org.tessellation.security.signature.Signed
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
    balancesProof: Hash,
    lastMessagesProof: Option[Hash] = None,
    lastFeeTxRefsProof: Option[Hash] = None
  ) extends StateProof

  object CurrencySnapshotStateProof {
    def apply(a: (Hash, Hash, Option[Hash], Option[Hash])): CurrencySnapshotStateProof =
      CurrencySnapshotStateProof(a._1, a._2, a._3, a._4)
  }

  @derive(encoder, decoder, eqv, show)
  case class CurrencySnapshotStateProofV1(
    lastTxRefsProof: Hash,
    balancesProof: Hash
  ) extends StateProof {
    def toCurrencySnapshotStateProof: CurrencySnapshotStateProof = CurrencySnapshotStateProof(lastTxRefsProof, balancesProof, None, None)
  }

  object CurrencySnapshotStateProofV1 {
    def apply(a: (Hash, Hash)): CurrencySnapshotStateProofV1 =
      CurrencySnapshotStateProofV1(a._1, a._2)

    def fromCurrencySnapshotStateProof(proof: CurrencySnapshotStateProof): CurrencySnapshotStateProofV1 =
      CurrencySnapshotStateProofV1(proof.lastTxRefsProof, proof.balancesProof)
  }

  @derive(encoder, decoder, eqv, show)
  case class CurrencySnapshotInfo(
    lastTxRefs: SortedMap[Address, TransactionReference],
    balances: SortedMap[Address, Balance],
    lastMessages: Option[SortedMap[MessageType, Signed[CurrencyMessage]]] = None,
    lastFeeTxRefs: Option[SortedMap[Address, TransactionReference]] = None
  ) extends SnapshotInfo[CurrencySnapshotStateProof] {
    def stateProof[F[_]: Sync: Hasher](ordinal: SnapshotOrdinal): F[CurrencySnapshotStateProof] =
      (lastTxRefs.hash, balances.hash, lastMessages.traverse(_.hash), lastFeeTxRefs.traverse(_.hash)).tupled
        .map(CurrencySnapshotStateProof.apply)
  }

  @derive(encoder, decoder, eqv, show)
  case class CurrencySnapshotInfoV1(
    lastTxRefs: SortedMap[Address, TransactionReference],
    balances: SortedMap[Address, Balance]
  ) extends SnapshotInfo[CurrencySnapshotStateProofV1] {
    def stateProof[F[_]: Sync: Hasher](ordinal: SnapshotOrdinal): F[CurrencySnapshotStateProofV1] =
      (lastTxRefs.hash, balances.hash).tupled.map(CurrencySnapshotStateProofV1.apply)

    def toCurrencySnapshotInfo: CurrencySnapshotInfo = CurrencySnapshotInfo(lastTxRefs, balances, None)
  }

  object CurrencySnapshotInfoV1 {
    def fromCurrencySnapshotInfo(info: CurrencySnapshotInfo): CurrencySnapshotInfoV1 =
      CurrencySnapshotInfoV1(
        info.lastTxRefs,
        info.balances
      )
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
    info: CurrencySnapshotInfoV1,
    epochProgress: EpochProgress,
    dataApplication: Option[DataApplicationPart] = None,
    version: SnapshotVersion = SnapshotVersion("0.0.1")
  ) extends FullSnapshot[CurrencySnapshotStateProofV1, CurrencySnapshotInfoV1]

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
    messages: Option[SortedSet[Signed[CurrencyMessage]]] = None,
    feeTransactions: Option[SortedSet[Signed[FeeTransaction]]] = None,
    version: SnapshotVersion = SnapshotVersion("0.0.1")
  ) extends IncrementalSnapshot[CurrencySnapshotStateProof]

  object CurrencyIncrementalSnapshot {
    def fromCurrencySnapshot[F[_]: Sync: Hasher](snapshot: CurrencySnapshot): F[CurrencyIncrementalSnapshot] =
      snapshot.info.stateProof[F](snapshot.ordinal).map { stateProof =>
        CurrencyIncrementalSnapshot(
          snapshot.ordinal,
          snapshot.height,
          snapshot.subHeight,
          snapshot.lastSnapshotHash,
          snapshot.blocks,
          snapshot.rewards,
          snapshot.tips,
          stateProof.toCurrencySnapshotStateProof,
          snapshot.epochProgress,
          snapshot.dataApplication,
          None,
          None,
          snapshot.version
        )
      }
  }

  @derive(eqv, show, encoder, decoder)
  case class CurrencyIncrementalSnapshotV1(
    ordinal: SnapshotOrdinal,
    height: Height,
    subHeight: SubHeight,
    lastSnapshotHash: Hash,
    blocks: SortedSet[BlockAsActiveTip],
    rewards: SortedSet[RewardTransaction],
    tips: SnapshotTips,
    stateProof: CurrencySnapshotStateProofV1,
    epochProgress: EpochProgress,
    dataApplication: Option[DataApplicationPart] = None,
    version: SnapshotVersion = SnapshotVersion("0.0.1")
  ) extends IncrementalSnapshot[CurrencySnapshotStateProofV1] {
    def toCurrencyIncrementalSnapshot: CurrencyIncrementalSnapshot =
      CurrencyIncrementalSnapshot(
        ordinal,
        height,
        subHeight,
        lastSnapshotHash,
        blocks,
        rewards,
        tips,
        stateProof.toCurrencySnapshotStateProof,
        epochProgress,
        dataApplication,
        None,
        None,
        version
      )
  }

  object CurrencyIncrementalSnapshotV1 {
    def fromCurrencyIncrementalSnapshot(snapshot: CurrencyIncrementalSnapshot): CurrencyIncrementalSnapshotV1 =
      CurrencyIncrementalSnapshotV1(
        snapshot.ordinal,
        snapshot.height,
        snapshot.subHeight,
        snapshot.lastSnapshotHash,
        snapshot.blocks,
        snapshot.rewards,
        snapshot.tips,
        CurrencySnapshotStateProofV1.fromCurrencySnapshotStateProof(snapshot.stateProof),
        snapshot.epochProgress,
        snapshot.dataApplication,
        snapshot.version
      )
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
        CurrencySnapshotInfoV1(SortedMap.empty, SortedMap.from(balances)),
        EpochProgress.MinValue,
        dataApplicationPart
      )

    def mkFirstIncrementalSnapshot[F[_]: Sync: Hasher](
      genesis: Hashed[CurrencySnapshot]
    ): F[CurrencyIncrementalSnapshot] =
      genesis.info.stateProof[F](genesis.ordinal).map { stateProof =>
        CurrencyIncrementalSnapshot(
          genesis.ordinal.next,
          genesis.height,
          genesis.subHeight.next,
          genesis.hash,
          SortedSet.empty,
          SortedSet.empty,
          genesis.tips,
          stateProof.toCurrencySnapshotStateProof,
          genesis.epochProgress,
          genesis.dataApplication,
          None,
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
