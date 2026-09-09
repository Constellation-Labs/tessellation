package io.constellationnetwork.node.shared.infrastructure.consensus

import io.constellationnetwork.schema.SnapshotOrdinal

import weaver.SimpleIOSuite

object CertifiedConsensusGenesisSuite extends SimpleIOSuite {

  pureTest("genesis activation recognizes only the first incremental consensus root") {
    val root = CertifiedConsensusGenesis.FirstIncrementalOrdinal
    val afterRoot = SnapshotOrdinal.unsafeApply(root.value.value + 1L)

    expect(CertifiedConsensusGenesis.isActiveFromGenesis(0L)) &&
    expect(CertifiedConsensusGenesis.isActiveFromGenesis(root.value.value)) &&
    expect(!CertifiedConsensusGenesis.isActiveFromGenesis(root.value.value + 1L)) &&
    expect(CertifiedConsensusGenesis.allowsSingletonBootstrapExpansion(true, 0L, 1, expandedBeyondSingleton = false)) &&
    expect(!CertifiedConsensusGenesis.allowsSingletonBootstrapExpansion(true, 0L, 1, expandedBeyondSingleton = true)) &&
    expect(!CertifiedConsensusGenesis.allowsSingletonBootstrapExpansion(true, 0L, 2, expandedBeyondSingleton = false)) &&
    expect(
      !CertifiedConsensusGenesis.allowsSingletonBootstrapExpansion(
        true,
        root.value.value + 1L,
        1,
        expandedBeyondSingleton = false
      )
    ) &&
    expect(!CertifiedConsensusGenesis.allowsSingletonBootstrapExpansion(false, 0L, 1, expandedBeyondSingleton = false)) &&
    expect(!CertifiedConsensusGenesis.hasExpandedBeyondSingleton(0L, root, 1, Some(false))) &&
    expect(CertifiedConsensusGenesis.hasExpandedBeyondSingleton(0L, root, 1, Some(true))) &&
    expect(!CertifiedConsensusGenesis.hasExpandedBeyondSingleton(0L, root, 1, None)) &&
    expect(CertifiedConsensusGenesis.hasExpandedBeyondSingleton(0L, afterRoot, 1, None)) &&
    expect(CertifiedConsensusGenesis.nextExpandedBeyondSingleton(0L, root, 1, Some(false), 2)) &&
    expect(CertifiedConsensusGenesis.nextExpandedBeyondSingleton(0L, afterRoot, 1, Some(true), 1)) &&
    expect(CertifiedConsensusGenesis.isRootKey(0L, root)) &&
    expect(CertifiedConsensusGenesis.isRootKey(root.value.value, root)) &&
    expect(!CertifiedConsensusGenesis.isRootKey(root.value.value + 1L, root)) &&
    expect(!CertifiedConsensusGenesis.isRootKey(0L, SnapshotOrdinal.MinValue)) &&
    expect(!CertifiedConsensusGenesis.isRootKey(0L, afterRoot))
  }
}
