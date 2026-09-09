package io.constellationnetwork.node.shared.infrastructure.snapshot.managers.currency

import cats.data.{NonEmptySet, Validated}
import cats.effect.IO
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.currency.schema.globalSnapshotSync.{GlobalSnapshotSync, GlobalSnapshotSyncOrdinal}
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.node.shared.infrastructure.metrics.{Metrics, NoOpMetrics}
import io.constellationnetwork.node.shared.infrastructure.snapshot.{GlobalSnapshotSyncValidator, RecoveryGlobalSnapshotSync}
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.cluster.SessionToken
import io.constellationnetwork.schema.generation.Generation
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature.{Signature, SignatureProof}

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.PosLong
import weaver.SimpleIOSuite

object MessageValidationOpsManagerRecoverySuite extends SimpleIOSuite {

  private implicit val metrics: Metrics[IO] = NoOpMetrics.make

  private val metagraphId = Address("DAG0y4eLqhhXUafeE3mgBstezPTnr8L3tZjAtMWB")

  private def peer(value: Int): PeerId = PeerId(Hex(s"peer$value".padTo(64, '0')))
  private def session(value: Long): SessionToken = SessionToken(Generation(PosLong.unsafeFrom(value)))

  private def signedSync(signer: PeerId, anchor: Long, anchorHash: String, sessionValue: Long): Signed[GlobalSnapshotSync] =
    Signed(
      GlobalSnapshotSync(
        GlobalSnapshotSyncOrdinal.MinValue,
        SnapshotOrdinal.unsafeApply(anchor),
        Hash(anchorHash),
        session(sessionValue)
      ),
      NonEmptySet.one(SignatureProof(signer.toId, Signature(Hex(s"sig-$anchorHash"))))
    )

  private val acceptingValidator = new GlobalSnapshotSyncValidator[IO] {
    def validate(
      globalSnapshotSync: Signed[GlobalSnapshotSync],
      metagraphId: Address,
      facilitators: Set[PeerId],
      lastGlobalSnapshotSyncs: SortedMap[PeerId, Signed[GlobalSnapshotSync]],
      validationMode: GlobalSnapshotSyncValidator.ValidationMode
    )(implicit hasher: Hasher[IO]): IO[GlobalSnapshotSyncValidator.GlobalSnapshotSyncOrError] =
      Validated.validNec(globalSnapshotSync).pure[IO]
  }

  private def manager = new MessageValidationOpsManager[IO](null, acceptingValidator)

  private def context(signer: PeerId): RecoveryGlobalSnapshotSync.ValidationContext =
    RecoveryGlobalSnapshotSync.ValidationContext(
      currentSigners = Set(signer),
      inheritedPeerIds = Set(signer, peer(2)),
      inheritedSessions = SortedMap(signer -> session(1L), peer(2) -> session(1L)),
      currentGlobalParent = SnapshotOrdinal.unsafeApply(100L),
      recentGlobalSnapshots = SortedMap.from(
        (51L to 100L).map(value => SnapshotOrdinal.unsafeApply(value) -> Hash(s"global-$value"))
      ),
      retainedCount = 50,
      syncOffset = 2L,
      metagraphLastAcceptedOn = SnapshotOrdinal.unsafeApply(10L),
      unappliedGlobalChangeOrdinals = SortedSet.empty,
      snapshotProtocolV1ActivationOrdinal = SnapshotOrdinal.unsafeApply(51L)
    )

  test("a malformed reset-shaped declaration cannot poison the one valid reset") {
    val signer = peer(1)
    val inherited = SortedMap(
      signer -> signedSync(signer, 20L, "old-self", 1L),
      peer(2) -> signedSync(peer(2), 20L, "old-peer", 1L)
    )
    val valid = signedSync(signer, 100L, "global-100", 2L)
    val malformed = signedSync(signer, 100L, "not-canonical", 3L)

    JsonSerializer.forAsync[IO].flatMap { implicit serializer =>
      implicit val hasher: Hasher[IO] = Hasher.forJson[IO]

      manager
        .acceptGlobalSnapshotSyncs(
          Some(inherited),
          List(malformed, valid),
          metagraphId,
          Set(signer),
          context(signer).some,
          resetRecognitionEnabled = true
        )
        .map { result =>
          expect(result.isRecoveryReset) &&
          expect.same(List(valid), result.accepted) &&
          expect.same(SortedMap(signer -> valid), result.contextUpdate) &&
          expect(result.notAccepted.contains(malformed))
        }
    }
  }

  test("before snapshot protocol v1 activation the rc.12 ordinary interpretation is preserved") {
    val signer = peer(1)
    val other = peer(2)
    val inherited = SortedMap(other -> signedSync(other, 20L, "old-peer", 1L))
    val firstDeclaration = signedSync(signer, 100L, "global-100", 2L)

    JsonSerializer.forAsync[IO].flatMap { implicit serializer =>
      implicit val hasher: Hasher[IO] = Hasher.forJson[IO]

      manager
        .acceptGlobalSnapshotSyncs(
          Some(inherited),
          List(firstDeclaration),
          metagraphId,
          Set(signer),
          context(signer).some,
          resetRecognitionEnabled = false
        )
        .map { result =>
          expect(!result.isRecoveryReset) &&
          expect.same(List(firstDeclaration), result.accepted) &&
          expect.same(inherited.updated(signer, firstDeclaration), result.contextUpdate)
        }
    }
  }
}
