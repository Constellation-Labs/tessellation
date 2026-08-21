package io.constellationnetwork.currency.l0.snapshot.programs

import cats.effect.IO
import cats.syntax.all._

import io.constellationnetwork.node.shared.infrastructure.consensus.CertifiedConsensusGenesis
import io.constellationnetwork.schema.SnapshotOrdinal

import eu.timepit.refined.auto._
import weaver.SimpleIOSuite

object DownloadSuite extends SimpleIOSuite {

  test("certified genesis initializes download at the independently authenticated root") {
    val root = CertifiedConsensusGenesis.FirstIncrementalOrdinal

    (expect.same(root, Download.observationLimit(root, 4L, certifiedConsensusActivationKey = 0L)) &&
      expect(Download.isCertifiedGenesisRoot(root, certifiedConsensusActivationKey = 0L))).pure[IO]
  }

  test("legacy and non-root downloads retain the ordinary observation window") {
    val root = CertifiedConsensusGenesis.FirstIncrementalOrdinal
    val ordinary = SnapshotOrdinal.unsafeApply(10L)

    (expect.same(
      SnapshotOrdinal.unsafeApply(root.value.value + 4L),
      Download.observationLimit(root, 4L, certifiedConsensusActivationKey = Long.MaxValue)
    ) &&
      expect.same(
        SnapshotOrdinal.unsafeApply(ordinary.value.value + 4L),
        Download.observationLimit(ordinary, 4L, certifiedConsensusActivationKey = 0L)
      ) &&
      expect(!Download.isCertifiedGenesisRoot(root, certifiedConsensusActivationKey = Long.MaxValue)) &&
      expect(!Download.isCertifiedGenesisRoot(ordinary, certifiedConsensusActivationKey = 0L))).pure[IO]
  }
}
