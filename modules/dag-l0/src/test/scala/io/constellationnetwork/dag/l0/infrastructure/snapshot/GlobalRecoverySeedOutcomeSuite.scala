package io.constellationnetwork.dag.l0.infrastructure.snapshot

import cats.effect.{IO, Resource}

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.node.shared.infrastructure.consensus.state.{EligibleFacilitators, Facilitators}
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security._
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.signature.Signed

import eu.timepit.refined.types.numeric.NonNegLong
import weaver.MutableIOSuite

object GlobalRecoverySeedOutcomeSuite extends MutableIOSuite {

  implicit val globalStateProofSelector: GlobalStateProofSelector =
    GlobalStateProofSelector(SnapshotOrdinal(NonNegLong(Long.MaxValue)))

  type Res = (JsonSerializer[IO], Hasher[IO], SecurityProvider[IO])

  override def sharedResource: Resource[IO, Res] = for {
    implicit0(json: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO].asResource
    securityProvider <- SecurityProvider.forAsync[IO]
  } yield (json, Hasher.forJson[IO], securityProvider)

  private def peer(char: Char): PeerId = PeerId(Hex(char.toString * 128))

  test("recovery seed uses the existing typed outcome and flushes every operational window") { res =>
    implicit val jsonSerializer: JsonSerializer[IO] = res._1
    implicit val hasher: Hasher[IO] = res._2
    implicit val securityProvider: SecurityProvider[IO] = res._3
    val committee = SortedSet(peer('c'), peer('a'), peer('b'))

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      genesis = GlobalSnapshot.mkGenesis(Map.empty, EpochProgress.MinValue)
      signedGenesis <- Signed.forAsyncHasher[IO, GlobalSnapshot](genesis, keyPair)
      incremental <- GlobalIncrementalSnapshot.fromGlobalSnapshot[IO](signedGenesis.value)
      signedIncremental <- Signed.forAsyncHasher[IO, GlobalIncrementalSnapshot](incremental, keyPair)
      snapshotHash <- signedIncremental.toHashed[IO].map(_.hash)
      context = signedGenesis.value.info.toGlobalSnapshotInfo
      outcome = GlobalRecoverySeedOutcome.seed(signedIncremental, context, snapshotHash, committee)
      operational = outcome.toOperationalState
    } yield
      expect.same(Facilitators(committee.toList), outcome.facilitators) &&
        expect.same(EligibleFacilitators(committee.toList), outcome.eligibleFacilitators) &&
        expect(outcome.removedFacilitators.value.isEmpty) &&
        expect(outcome.withdrawnFacilitators.value.isEmpty) &&
        expect.same(signedIncremental, outcome.finished.signedMajorityArtifact) &&
        expect.same(context, outcome.finished.context) &&
        expect.same(snapshotHash, outcome.finished.snapshotHash) &&
        expect.same(
          ConsensusOperationalState.empty.copy(
            recentProofSizes = SortedMap(signedIncremental.ordinal -> committee.size)
          ),
          operational
        ) &&
        expect(GlobalRecoverySeedOutcome.isCanonicalRoot(outcome)) &&
        expect(!GlobalRecoverySeedOutcome.isCanonicalRoot(outcome.copy(expandedBeyondSingleton = Some(true))))
  }

  test("recovery seed is permutation-invariant and a different committee is a different outcome") { res =>
    implicit val jsonSerializer: JsonSerializer[IO] = res._1
    implicit val hasher: Hasher[IO] = res._2
    implicit val securityProvider: SecurityProvider[IO] = res._3
    val a = peer('a')
    val b = peer('b')
    val c = peer('c')

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      genesis = GlobalSnapshot.mkGenesis(Map.empty, EpochProgress.MinValue)
      signedGenesis <- Signed.forAsyncHasher[IO, GlobalSnapshot](genesis, keyPair)
      incremental <- GlobalIncrementalSnapshot.fromGlobalSnapshot[IO](signedGenesis.value)
      signedIncremental <- Signed.forAsyncHasher[IO, GlobalIncrementalSnapshot](incremental, keyPair)
      snapshotHash <- signedIncremental.toHashed[IO].map(_.hash)
      context = signedGenesis.value.info.toGlobalSnapshotInfo
      first = GlobalRecoverySeedOutcome.seed(signedIncremental, context, snapshotHash, SortedSet(c, a, b))
      same = GlobalRecoverySeedOutcome.seed(signedIncremental, context, snapshotHash, SortedSet(b, c, a))
      different = GlobalRecoverySeedOutcome.seed(signedIncremental, context, snapshotHash, SortedSet(a, b))
    } yield expect.same(first, same) && expect(first != different)
  }

}
