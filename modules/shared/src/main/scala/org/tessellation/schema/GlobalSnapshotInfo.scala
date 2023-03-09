package org.tessellation.schema

import cats.MonadThrow
import cats.syntax.contravariantSemigroupal._
import cats.syntax.functor._
import cats.syntax.reducible._

import scala.collection.immutable.SortedMap

import org.tessellation.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshotInfo}
import org.tessellation.ext.crypto._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.merkletree.MerkleTree
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Balance
import org.tessellation.schema.snapshot.SnapshotInfo
import org.tessellation.schema.transaction.TransactionReference
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

@derive(encoder, decoder, eqv, show)
case class GlobalSnapshotInfo(
  lastStateChannelSnapshotHashes: SortedMap[Address, Hash],
  lastTxRefs: SortedMap[Address, TransactionReference],
  balances: SortedMap[Address, Balance],
  lastCurrencySnapshots: SortedMap[Address, (Signed[CurrencyIncrementalSnapshot], CurrencySnapshotInfo)]
) extends SnapshotInfo

object GlobalSnapshotInfo {
  def empty = GlobalSnapshotInfo(SortedMap.empty, SortedMap.empty, SortedMap.empty, SortedMap.empty)

  def stateProof[F[_]: MonadThrow: KryoSerializer](info: GlobalSnapshotInfo): F[MerkleTree] =
    (info.lastStateChannelSnapshotHashes.hashF, info.lastTxRefs.hashF, info.balances.hashF).tupled
      .map(_.toNonEmptyList)
      .map(MerkleTree.from)
}
