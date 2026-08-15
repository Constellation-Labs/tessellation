package io.constellationnetwork.dag.l0.infrastructure.snapshot

import cats.effect.{IO, Resource}
import cats.syntax.all._

import scala.collection.immutable.SortedSet

import io.constellationnetwork.dag.l0.infrastructure.snapshot.GlobalCertifiedDownloadValidator.TrustedParentKind
import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, GlobalSnapshot, SnapshotOrdinal}
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed

import weaver.MutableIOSuite

object GlobalCertifiedDownloadValidatorSuite extends MutableIOSuite {

  type Res = (JsonSerializer[IO], Hasher[IO], SecurityProvider[IO])

  override def sharedResource: Resource[IO, Res] = for {
    implicit0(securityProvider: SecurityProvider[IO]) <- SecurityProvider.forAsync[IO]
    implicit0(jsonSerializer: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO].asResource
    hasher = Hasher.forJson[IO]
  } yield (jsonSerializer, hasher, securityProvider)

  private def canonicalRoot(
    implicit jsonSerializer: JsonSerializer[IO],
    hasher: Hasher[IO],
    securityProvider: SecurityProvider[IO]
  ) =
    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      genesis = GlobalSnapshot.mkGenesis(Map.empty, EpochProgress.MinValue)
      signedGenesis <- Signed.forAsyncHasher[IO, GlobalSnapshot](genesis, keyPair)
      incremental <- GlobalIncrementalSnapshot.fromGlobalSnapshot[IO](signedGenesis.value)
      signedIncremental <- Signed.forAsyncHasher[IO, GlobalIncrementalSnapshot](incremental, keyPair)
      snapshotHash <- signedIncremental.toHashed.map(_.hash)
      committee = SortedSet.from(signedIncremental.proofs.toList.map(_.id.toPeerId))
    } yield
      GlobalRecoveryPlanOutcome.seed(
        signedIncremental,
        signedGenesis.value.info.toGlobalSnapshotInfo,
        snapshotHash,
        committee
      )

  test("only a canonical locally persisted uncertified root receives root authority") { implicit res =>
    implicit val (jsonSerializer, hasher, securityProvider) = res

    canonicalRoot.map { root =>
      val wrongEligible = root.copy(eligibleFacilitators = io.constellationnetwork.node.shared.infrastructure.consensus.state.EligibleFacilitators.empty)
      val wrongHash = root.copy(finished = root.finished.copy(facilitatorsHash = Hash.fromBytes(Array[Byte](1))))
      val missingProofWindow = root.copy(recentProofSizes = scala.collection.immutable.SortedMap.empty)

      expect.same(Right(TrustedParentKind.AuthorizedRoot), GlobalCertifiedDownloadValidator.trustedParentKind(root)) &&
      expect(GlobalCertifiedDownloadValidator.trustedParentKind(wrongEligible).isLeft) &&
      expect(GlobalCertifiedDownloadValidator.trustedParentKind(wrongHash).isLeft) &&
      expect(GlobalCertifiedDownloadValidator.trustedParentKind(missingProofWindow).isLeft)
    }
  }

  pureTest("predecessor binding validation fails at the first mismatched independent binding") {
    val valid = GlobalCertifiedDownloadValidator.validatePredecessorBindings(
      keyMatches = true,
      artifactMatches = true,
      contextMatches = true,
      hashMatches = true
    )
    val wrongKey = GlobalCertifiedDownloadValidator.validatePredecessorBindings(false, true, true, true)
    val wrongArtifact = GlobalCertifiedDownloadValidator.validatePredecessorBindings(true, false, true, true)
    val wrongContext = GlobalCertifiedDownloadValidator.validatePredecessorBindings(true, true, false, true)
    val wrongHash = GlobalCertifiedDownloadValidator.validatePredecessorBindings(true, true, true, false)

    expect.same(Right(()), valid) &&
    expect.same(Left("trusted_predecessor_key_mismatch"), wrongKey) &&
    expect.same(Left("trusted_predecessor_artifact_mismatch"), wrongArtifact) &&
    expect.same(Left("trusted_predecessor_context_mismatch"), wrongContext) &&
    expect.same(Left("trusted_predecessor_hash_mismatch"), wrongHash)
  }

  pureTest("a configured recovery plan validates only its authorized anchor, not later certified downloads") {
    val anchor = SnapshotOrdinal.unsafeApply(100L)

    expect(GlobalSnapshotConsensus.recoveryPlanPreflightRequired(anchor, anchor)) &&
    expect(!GlobalSnapshotConsensus.recoveryPlanPreflightRequired(SnapshotOrdinal.unsafeApply(101L), anchor))
  }
}
