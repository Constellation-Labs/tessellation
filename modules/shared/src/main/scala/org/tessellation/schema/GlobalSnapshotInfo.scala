package org.tessellation.schema

import cats.MonadThrow
import cats.syntax.contravariantSemigroupal._
import cats.syntax.flatMap._

import scala.collection.immutable.SortedMap

import org.tessellation.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshotInfo}
import org.tessellation.ext.crypto._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.merkletree.syntax._
import org.tessellation.merkletree.{MerkleRoot, MerkleTree, Proof}
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Balance
import org.tessellation.schema.snapshot.{SnapshotInfo, StateProof}
import org.tessellation.schema.transaction.TransactionReference
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

@derive(encoder, decoder, eqv, show)
case class GlobalSnapshotInfoV1(
  lastStateChannelSnapshotHashes: SortedMap[Address, Hash],
  lastTxRefs: SortedMap[Address, TransactionReference],
  balances: SortedMap[Address, Balance]
) extends SnapshotInfo[GlobalSnapshotStateProof] {
  def stateProof[F[_]: MonadThrow: KryoSerializer]: F[GlobalSnapshotStateProof] =
    GlobalSnapshotInfoV1.toGlobalSnapshotInfo(this).stateProof[F]
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
case class GlobalSnapshotInfo(
  lastStateChannelSnapshotHashes: SortedMap[Address, Hash],
  lastTxRefs: SortedMap[Address, TransactionReference],
  balances: SortedMap[Address, Balance],
  lastCurrencySnapshots: SortedMap[Address, (Option[Signed[CurrencyIncrementalSnapshot]], CurrencySnapshotInfo)],
  lastCurrencySnapshotsProofs: SortedMap[Address, Proof]
) extends SnapshotInfo[GlobalSnapshotStateProof] {
  def stateProof[F[_]: MonadThrow: KryoSerializer]: F[GlobalSnapshotStateProof] =
    lastCurrencySnapshots.merkleTree[F].flatMap(stateProof(_))

  def stateProof[F[_]: MonadThrow: KryoSerializer](lastCurrencySnapshots: Option[MerkleTree]): F[GlobalSnapshotStateProof] =
    (
      lastStateChannelSnapshotHashes.hashF,
      lastTxRefs.hashF,
      balances.hashF
    ).mapN(GlobalSnapshotStateProof.apply(_, _, _, lastCurrencySnapshots.map(_.getRoot)))

}

object GlobalSnapshotInfo {
  def empty = GlobalSnapshotInfo(SortedMap.empty, SortedMap.empty, SortedMap.empty, SortedMap.empty, SortedMap.empty)
}
