package org.tessellation.schema

import cats.Parallel
import cats.effect.kernel.Sync
import cats.syntax.contravariantSemigroupal._
import cats.syntax.flatMap._

import scala.collection.immutable.SortedMap

import org.tessellation.currency.schema.currency._
import org.tessellation.ext.crypto._
import org.tessellation.merkletree.syntax._
import org.tessellation.merkletree.{MerkleRoot, MerkleTree, Proof}
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Balance
import org.tessellation.schema.snapshot.{SnapshotInfo, StateProof}
import org.tessellation.schema.transaction.TransactionReference
import org.tessellation.security._
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import io.circe.disjunctionCodecs._

@derive(encoder, decoder, eqv, show)
case class GlobalSnapshotInfoV1(
  lastStateChannelSnapshotHashes: SortedMap[Address, Hash],
  lastTxRefs: SortedMap[Address, TransactionReference],
  balances: SortedMap[Address, Balance]
) extends SnapshotInfo[GlobalSnapshotStateProof] {
  def stateProof[F[_]: Parallel: Sync: Hasher](ordinal: SnapshotOrdinal): F[GlobalSnapshotStateProof] =
    GlobalSnapshotInfoV1.toGlobalSnapshotInfo(this).stateProof[F](ordinal)
}

object GlobalSnapshotInfoV1 {
  implicit def toGlobalSnapshotInfo(gsi: GlobalSnapshotInfoV1): GlobalSnapshotInfo =
    GlobalSnapshotInfo(
      gsi.lastStateChannelSnapshotHashes,
      gsi.lastTxRefs,
      gsi.balances,
      SortedMap.empty,
      SortedMap.empty
    )
}

@derive(encoder, decoder, eqv, show)
case class GlobalSnapshotStateProof(
  lastStateChannelSnapshotHashesProof: Hash,
  lastTxRefsProof: Hash,
  balancesProof: Hash,
  lastCurrencySnapshotsProof: Option[MerkleRoot]
) extends StateProof

object GlobalSnapshotStateProof {
  def apply: ((Hash, Hash, Hash, Option[MerkleRoot])) => GlobalSnapshotStateProof = {
    case (x1, x2, x3, x4) => GlobalSnapshotStateProof.apply(x1, x2, x3, x4)
  }
}

@derive(encoder, decoder, eqv, show)
case class GlobalSnapshotInfoV2(
  lastStateChannelSnapshotHashes: SortedMap[Address, Hash],
  lastTxRefs: SortedMap[Address, TransactionReference],
  balances: SortedMap[Address, Balance],
  lastCurrencySnapshots: SortedMap[Address, Either[Signed[
    CurrencySnapshot
  ], (Signed[CurrencyIncrementalSnapshotV1], CurrencySnapshotInfoV1)]],
  lastCurrencySnapshotsProofs: SortedMap[Address, Proof]
) extends SnapshotInfo[GlobalSnapshotStateProof] {
  def toGlobalSnapshotInfo: GlobalSnapshotInfo =
    GlobalSnapshotInfo(
      lastStateChannelSnapshotHashes,
      lastTxRefs,
      balances,
      lastCurrencySnapshots.view.mapValues {
        _.map { case (Signed(inc, proofs), info) => (Signed(inc.toCurrencyIncrementalSnapshot, proofs), info.toCurrencySnapshotInfo) }
      }.to(lastCurrencySnapshots.sortedMapFactory),
      lastCurrencySnapshotsProofs
    )

  def stateProof[F[_]: Parallel: Sync: Hasher](ordinal: SnapshotOrdinal): F[GlobalSnapshotStateProof] =
    lastCurrencySnapshots.merkleTree[F].flatMap(stateProof(_))

  def stateProof[F[_]: Sync: Hasher](lastCurrencySnapshots: Option[MerkleTree]): F[GlobalSnapshotStateProof] =
    (
      lastStateChannelSnapshotHashes.hash,
      lastTxRefs.hash,
      balances.hash
    ).mapN(GlobalSnapshotStateProof.apply(_, _, _, lastCurrencySnapshots.map(_.getRoot)))

}

object GlobalSnapshotInfoV2 {
  def fromGlobalSnapshotInfo(gs: GlobalSnapshotInfo): GlobalSnapshotInfoV2 =
    GlobalSnapshotInfoV2(
      gs.lastStateChannelSnapshotHashes,
      gs.lastTxRefs,
      gs.balances,
      gs.lastCurrencySnapshots.view.mapValues {
        _.map {
          case (Signed(inc, proofs), info) =>
            (
              Signed(CurrencyIncrementalSnapshotV1.fromCurrencyIncrementalSnapshot(inc), proofs),
              CurrencySnapshotInfoV1.fromCurrencySnapshotInfo(info)
            )
        }
      }.to(gs.lastCurrencySnapshots.sortedMapFactory),
      gs.lastCurrencySnapshotsProofs
    )
}

@derive(encoder, decoder, eqv, show)
case class GlobalSnapshotInfo(
  lastStateChannelSnapshotHashes: SortedMap[Address, Hash],
  lastTxRefs: SortedMap[Address, TransactionReference],
  balances: SortedMap[Address, Balance],
  lastCurrencySnapshots: SortedMap[Address, Either[Signed[CurrencySnapshot], (Signed[CurrencyIncrementalSnapshot], CurrencySnapshotInfo)]],
  lastCurrencySnapshotsProofs: SortedMap[Address, Proof]
) extends SnapshotInfo[GlobalSnapshotStateProof] {
  def stateProof[F[_]: Parallel: Sync: Hasher](ordinal: SnapshotOrdinal): F[GlobalSnapshotStateProof] =
    lastCurrencySnapshots.merkleTree[F].flatMap(stateProof(_))

  def stateProof[F[_]: Sync: Hasher](lastCurrencySnapshots: Option[MerkleTree]): F[GlobalSnapshotStateProof] =
    (
      lastStateChannelSnapshotHashes.hash,
      lastTxRefs.hash,
      balances.hash
    ).mapN(GlobalSnapshotStateProof.apply(_, _, _, lastCurrencySnapshots.map(_.getRoot)))

}

object GlobalSnapshotInfo {
  def empty = GlobalSnapshotInfo(SortedMap.empty, SortedMap.empty, SortedMap.empty, SortedMap.empty, SortedMap.empty)
}
