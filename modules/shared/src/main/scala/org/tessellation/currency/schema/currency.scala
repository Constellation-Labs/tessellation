package org.tessellation.currency.schema

import cats.MonadThrow
import cats.syntax.contravariantSemigroupal._
import cats.syntax.functor._

import scala.collection.immutable.{SortedMap, SortedSet}

import org.tessellation.ext.cats.syntax.next.catsSyntaxNext
import org.tessellation.ext.crypto._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema._
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.{Amount, Balance}
import org.tessellation.schema.epoch.EpochProgress
import org.tessellation.schema.height.{Height, SubHeight}
import org.tessellation.schema.semver.SnapshotVersion
import org.tessellation.schema.snapshot._
import org.tessellation.schema.transaction.TransactionReference
import org.tessellation.security.Hashed
import org.tessellation.security.hash.{Hash, ProofsHash}
import org.tessellation.syntax.sortedCollection._

import derevo.cats.{eqv, order, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.{NonNegLong, PosInt}
import io.estatico.newtype.macros.newtype

object currency {

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
    currencyData: CurrencyDataInfo
  ) {
    def stateProof[F[_]: MonadThrow: KryoSerializer]: F[CurrencySnapshotStateProof] =
      (currencyData.lastTxRefs.hashF, currencyData.balances.hashF).tupled.map(CurrencySnapshotStateProof.apply)
  }

  object CurrencySnapshotInfo {
    def apply(lastTxRefs: SortedMap[Address, TransactionReference], balances: SortedMap[Address, Balance]): CurrencySnapshotInfo =
      CurrencySnapshotInfo(CurrencyDataInfo(lastTxRefs, balances))
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
    lastSnapshotHash: Hash,
    currencyData: CurrencyData,
    info: CurrencySnapshotInfo,
    data: Option[Array[Byte]] = None,
    version: SnapshotVersion = SnapshotVersion("0.0.1")
  ) extends Snapshot

  @derive(eqv, show, encoder, decoder)
  case class CurrencyIncrementalSnapshot(
    ordinal: SnapshotOrdinal,
    lastSnapshotHash: Hash,
    currencyData: CurrencyData,
    stateProof: CurrencySnapshotStateProof,
    data: Option[Array[Byte]] = None,
    version: SnapshotVersion = SnapshotVersion("0.0.1")
  ) extends Snapshot

  object CurrencyIncrementalSnapshot {
    def fromCurrencySnapshot[F[_]: MonadThrow: KryoSerializer](snapshot: CurrencySnapshot): F[CurrencyIncrementalSnapshot] =
      snapshot.info.stateProof[F].map { stateProof =>
        CurrencyIncrementalSnapshot(
          snapshot.ordinal,
          snapshot.lastSnapshotHash,
          snapshot.currencyData,
          stateProof
        )
      }
  }

  object CurrencySnapshot {
    def mkGenesis(balances: Map[Address, Balance]): CurrencySnapshot =
      CurrencySnapshot(
        SnapshotOrdinal.MinValue,
        Hash.empty,
        CurrencyData(
          Height.MinValue,
          SubHeight.MinValue,
          SortedSet.empty,
          SortedSet.empty,
          SnapshotTips(SortedSet.empty, mkActiveTips(8)),
          EpochProgress.MinValue
        ),
        CurrencySnapshotInfo(SortedMap.empty, SortedMap.from(balances))
      )

    def mkFirstIncrementalSnapshot[F[_]: MonadThrow: KryoSerializer](genesis: Hashed[CurrencySnapshot]): F[CurrencyIncrementalSnapshot] =
      genesis.info.stateProof[F].map { stateProof =>
        CurrencyIncrementalSnapshot(
          genesis.ordinal.next,
          genesis.hash,
          genesis.currencyData,
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
