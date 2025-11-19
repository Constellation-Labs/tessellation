package io.constellationnetwork.schema

import io.constellationnetwork.merkletree.MerkleRoot
import io.constellationnetwork.schema.snapshot.StateProof
import io.constellationnetwork.security.hash.Hash

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
  def toGlobalSnapshotStateProof: GlobalSnapshotStateProofV2 =
    GlobalSnapshotStateProofV2(
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
      None
    )
}

object GlobalSnapshotStateProofV1 {
  def apply: ((Hash, Hash, Hash, Option[MerkleRoot])) => GlobalSnapshotStateProofV1 = {
    case (x1, x2, x3, x4) => GlobalSnapshotStateProofV1.apply(x1, x2, x3, x4)
  }

  def fromGlobalSnapshotStateProof(proof: GlobalSnapshotStateProofV2): GlobalSnapshotStateProofV1 =
    GlobalSnapshotStateProofV1(
      proof.lastStateChannelSnapshotHashesProof,
      proof.lastTxRefsProof,
      proof.balancesProof,
      proof.lastCurrencySnapshotsProof
    )
}

@derive(encoder, decoder, eqv, show)
case class GlobalSnapshotStateProofV2(
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
  lastGlobalSnapshotsWithCurrency: Option[Hash]
) extends StateProof

object GlobalSnapshotStateProofV2 {
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
      Option[Hash]
    )
  ) => GlobalSnapshotStateProofV2 = {
    case (x1, x2, x3, x4, x5, x6, x7, x8, x9, x10, x11, x12, x13, x14, x15, x16) =>
      GlobalSnapshotStateProofV2.apply(x1, x2, x3, x4, x5, x6, x7, x8, x9, x10, x11, x12, x13, x14, x15, x16)
  }
}

@derive(encoder, decoder, eqv, show)
case class GlobalSnapshotStateProof(
  lastStateChannelSnapshotHashesProof: Hash,
  lastTxRefsProof: Hash,
  balancesProof: Hash,
  lastCurrencySnapshotsProof: Hash,
  auxiliaryProof: Hash
) extends StateProof {
  def toLegacyProof: GlobalSnapshotStateProofV1 =
    GlobalSnapshotStateProofV1(
      lastStateChannelSnapshotHashesProof,
      lastTxRefsProof,
      balancesProof,
      None
    )
}

object GlobalSnapshotStateProof {
  def fromLegacyProof(proof: GlobalSnapshotStateProofV2): GlobalSnapshotStateProof =
    GlobalSnapshotStateProof(
      proof.lastStateChannelSnapshotHashesProof,
      proof.lastTxRefsProof,
      proof.balancesProof,
      proof.lastCurrencySnapshotsProof.map(_.hash).getOrElse(Hash.empty),
      Hash.empty
    )
}
