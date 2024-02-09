package org.tessellation.schema

import cats.effect.kernel.Sync
import cats.syntax.contravariantSemigroupal._
import cats.syntax.flatMap._

import scala.collection.immutable.SortedMap

import org.tessellation.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshot, CurrencySnapshotInfo}
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
  def stateProof[F[_]: Sync: Hasher](ordinal: SnapshotOrdinal, hashSelect: HashSelect): F[GlobalSnapshotStateProof] =
    GlobalSnapshotInfoV1.toGlobalSnapshotInfo(this).stateProof[F](ordinal, hashSelect)
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
  lastCurrencySnapshots: SortedMap[Address, Either[Signed[CurrencySnapshot], (Signed[CurrencyIncrementalSnapshot], CurrencySnapshotInfo)]],
  lastCurrencySnapshotsProofs: SortedMap[Address, Proof]
) extends SnapshotInfo[GlobalSnapshotStateProof] {
  def stateProof[F[_]: Sync: Hasher](ordinal: SnapshotOrdinal, hashSelect: HashSelect): F[GlobalSnapshotStateProof] =
    hashSelect.select(ordinal) match {
      case KryoHash =>
        lastCurrencySnapshots.compatibleMerkleTree[F].flatMap(compatibleStateProof(_))
      case JsonHash =>
        lastCurrencySnapshots.merkleTree[F].flatMap(stateProof(_))
    }

  def stateProof[F[_]: Sync: Hasher](lastCurrencySnapshots: Option[MerkleTree]): F[GlobalSnapshotStateProof] =
    (
      lastStateChannelSnapshotHashes.hash,
      lastTxRefs.hash,
      balances.hash
    ).mapN(GlobalSnapshotStateProof.apply(_, _, _, lastCurrencySnapshots.map(_.getRoot)))

  def compatibleStateProof[F[_]: Sync: Hasher](lastCurrencySnapshots: Option[MerkleTree]): F[GlobalSnapshotStateProof] = {
    def compatibleHash[A](data: A) = Hasher[F].hashKryo[A](data)
    (
      compatibleHash(lastStateChannelSnapshotHashes),
      compatibleHash(lastTxRefs),
      compatibleHash(balances)
    ).mapN(GlobalSnapshotStateProof.apply(_, _, _, lastCurrencySnapshots.map(_.getRoot)))
  }

}

object GlobalSnapshotInfo {
  def empty = GlobalSnapshotInfo(SortedMap.empty, SortedMap.empty, SortedMap.empty, SortedMap.empty, SortedMap.empty)
}
