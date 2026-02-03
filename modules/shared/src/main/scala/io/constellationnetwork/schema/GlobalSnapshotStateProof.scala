package io.constellationnetwork.schema

import cats.MonadThrow
import cats.syntax.all._

import io.constellationnetwork.merkletree.MerkleRoot
import io.constellationnetwork.schema.snapshot.StateProof
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.mpt.MptRoot
import io.constellationnetwork.security.mpt.producer.StatefulMerklePatriciaProducer

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

@derive(encoder, decoder, eqv, show)
case class GlobalSnapshotStateProofV1(
  lastStateChannelSnapshotHashesProof: Hash,
  lastTxRefsProof: Hash,
  balancesProof: Hash,
  lastCurrencySnapshotsProof: Option[MerkleRoot]
) extends StateProof {
  def toGlobalSnapshotStateProof: GlobalSnapshotStateProof =
    GlobalSnapshotStateProof(
      lastStateChannelSnapshotHashesProof,
      lastTxRefsProof,
      balancesProof,
      lastCurrencySnapshotsProof,
      None,
      None,
      None,
      None,
      None,
      None,
      None,
      None,
      None,
      None,
      None,
      None,
      None
    )
}

object GlobalSnapshotStateProofV1 {
  def apply: ((Hash, Hash, Hash, Option[MerkleRoot])) => GlobalSnapshotStateProofV1 = {
    case (x1, x2, x3, x4) => GlobalSnapshotStateProofV1.apply(x1, x2, x3, x4)
  }

  def fromGlobalSnapshotStateProof(proof: GlobalSnapshotStateProof): GlobalSnapshotStateProofV1 =
    GlobalSnapshotStateProofV1(
      proof.lastStateChannelSnapshotHashesProof,
      proof.lastTxRefsProof,
      proof.balancesProof,
      proof.lastCurrencySnapshotsProof
    )
}

@derive(encoder, decoder, eqv, show)
case class GlobalSnapshotStateProof(
  lastStateChannelSnapshotHashesProof: Hash,
  lastTxRefsProof: Hash,
  balancesProof: Hash,
  lastCurrencySnapshotsProof: Option[MerkleRoot],
  activeAllowSpends: Option[Hash],
  activeTokenLocks: Option[Hash],
  tokenLockBalances: Option[Hash],
  lastAllowSpendRefs: Option[Hash],
  lastTokenLockRefs: Option[Hash],
  updateNodeParameters: Option[Hash],
  activeDelegatedStakes: Option[Hash],
  delegatedStakesWithdrawals: Option[Hash],
  activeNodeCollaterals: Option[Hash],
  nodeCollateralWithdrawals: Option[Hash],
  priceState: Option[Hash],
  lastGlobalSnapshotsWithCurrency: Option[Hash],
  mptRoot: Option[Hash]
) extends StateProof

object GlobalSnapshotStateProof {
  def apply: (
    (
      Hash,
      Hash,
      Hash,
      Option[MerkleRoot],
      Option[Hash],
      Option[Hash],
      Option[Hash],
      Option[Hash],
      Option[Hash],
      Option[Hash],
      Option[Hash],
      Option[Hash],
      Option[Hash],
      Option[Hash],
      Option[Hash],
      Option[Hash],
      Option[Hash]
    )
  ) => GlobalSnapshotStateProof = {
    case (x1, x2, x3, x4, x5, x6, x7, x8, x9, x10, x11, x12, x13, x14, x15, x16, x17) =>
      GlobalSnapshotStateProof.apply(x1, x2, x3, x4, x5, x6, x7, x8, x9, x10, x11, x12, x13, x14, x15, x16, x17)
  }

  /** Efficiently extract a state proof from an already-synced MPT producer.
    *
    * Use this for snapshot CREATION where the producer's current state matches the snapshot being created. For snapshot VALIDATION, use
    * `GlobalSnapshotInfo.stateProofBuilder` which rebuilds from the snapshot's info.
    *
    * @param producer
    *   The MPT producer whose current state should be used for the proof
    * @return
    *   The state proof with the producer's current MPT root hash
    */
  def fromProducer[F[_]: MonadThrow](producer: StatefulMerklePatriciaProducer[F]): F[GlobalSnapshotStateProof] =
    producer.build.flatMap {
      case Left(err) => err.raiseError[F, GlobalSnapshotStateProof]
      case Right(trie) =>
        GlobalSnapshotStateProof(
          Hash.empty,
          Hash.empty,
          Hash.empty,
          None,
          None,
          None,
          None,
          None,
          None,
          None,
          None,
          None,
          None,
          None,
          None,
          None,
          Some(trie.rootHash.value)
        ).pure[F]
    }
}
