package io.constellationnetwork.node.shared.infrastructure.consensus

import io.constellationnetwork.schema.SnapshotOrdinal

import weaver.SimpleIOSuite

object CertifiedConsensusGenesisSuite extends SimpleIOSuite {

  pureTest("genesis activation recognizes only the first incremental consensus root") {
    val root = CertifiedConsensusGenesis.FirstIncrementalOrdinal
    val afterRoot = SnapshotOrdinal.unsafeApply(root.value.value + 1L)

    expect(CertifiedConsensusGenesis.isRootKey(0L, root)) &&
    expect(CertifiedConsensusGenesis.isRootKey(root.value.value, root)) &&
    expect(!CertifiedConsensusGenesis.isRootKey(root.value.value + 1L, root)) &&
    expect(!CertifiedConsensusGenesis.isRootKey(0L, SnapshotOrdinal.MinValue)) &&
    expect(!CertifiedConsensusGenesis.isRootKey(0L, afterRoot))
  }
}
