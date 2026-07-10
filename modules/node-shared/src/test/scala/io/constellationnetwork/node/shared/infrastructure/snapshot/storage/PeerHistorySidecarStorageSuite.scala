package io.constellationnetwork.node.shared.infrastructure.snapshot.storage

import cats.effect.{IO, Resource}
import cats.syntax.all._

import scala.collection.immutable.SortedMap

import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.{ConsensusOperationalState, PerPeerOperationalRecord, SnapshotOrdinal}
import io.constellationnetwork.security.hex.Hex

import eu.timepit.refined.auto._
import fs2.Stream
import fs2.io.file.{Files, Path}
import weaver.MutableIOSuite

/** Round-trip + degraded-input coverage for the alpha.94 peerHistory sidecar.
  *
  * The sidecar is the post-finalization companion to the snapshot file (see `PeerHistorySidecarStorage` scaladoc). Tests verify:
  *   - write/read returns the same `ConsensusOperationalState` we wrote;
  *   - missing-file returns None (rollback falls through to `snapshot.peerHistory`, pre-alpha.94 behavior);
  *   - structurally-defective payload returns None (no fabricated value);
  *   - delete is best-effort and idempotent.
  *
  * No mock storage layer is needed -- `Files[F]` (fs2.io.file) operates on a real temp directory and matches the production write path
  * byte-for-byte.
  */
object PeerHistorySidecarStorageSuite extends MutableIOSuite {

  override type Res = Path

  override def sharedResource: Resource[IO, Res] =
    Files[IO].tempDirectory(None, "peer-history-sidecar-test-", None)

  private val ord100: SnapshotOrdinal = SnapshotOrdinal.unsafeApply(100L)
  private val ord200: SnapshotOrdinal = SnapshotOrdinal.unsafeApply(200L)

  private val peerA: PeerId = PeerId(Hex("aa" * 64))
  private val peerB: PeerId = PeerId(Hex("bb" * 64))

  private def sample(perPeer: (PeerId, PerPeerOperationalRecord)*): ConsensusOperationalState =
    ConsensusOperationalState(
      perPeer = SortedMap.from(perPeer),
      recentProofSizes = SortedMap(ord100 -> 3, ord200 -> 5),
      recentSigners = SortedMap[SnapshotOrdinal, scala.collection.immutable.SortedSet[PeerId]](
        ord100 -> scala.collection.immutable.SortedSet[PeerId](peerA, peerB)
      ).some,
      recentRoundEndTimes = SortedMap(ord100 -> 1_700_000_000_000L).some
    )

  private val nonTrivial: ConsensusOperationalState = sample(
    peerA -> PerPeerOperationalRecord(
      quality = (12, 15),
      removalPenalty = 2,
      cumulativeMissCount = 7L,
      readmissionCountdown = 0,
      deferralCountdown = 0,
      viewChangesCaused = 4L.some,
      tier = None
    ),
    peerB -> PerPeerOperationalRecord(
      quality = (0, 8),
      removalPenalty = 0,
      cumulativeMissCount = 1L,
      readmissionCountdown = 5,
      deferralCountdown = 1,
      viewChangesCaused = None,
      tier = None
    )
  )

  test("write then read returns the same ConsensusOperationalState") { base =>
    for {
      sidecar <- PeerHistorySidecarStorage.make[IO](base)
      _ <- sidecar.write(ord100, nonTrivial)
      out <- sidecar.read(ord100)
    } yield expect(out.contains(nonTrivial))
  }

  test("read returns None when sidecar file is absent (missing-fallback path)") { base =>
    for {
      sidecar <- PeerHistorySidecarStorage.make[IO](base)
      out <- sidecar.read(SnapshotOrdinal.unsafeApply(9999L))
    } yield expect(out.isEmpty)
  }

  test("read returns None when sidecar file is structurally defective (parse-defect-fallback path)") { base =>
    val ord = SnapshotOrdinal.unsafeApply(4242L)
    val target = base / s"${ord.value.value}.peerHistory.meta"
    val bytes = "{ not valid json".getBytes("UTF-8")
    for {
      _ <- Stream.emits(bytes).through(Files[IO].writeAll(target)).compile.drain
      sidecar <- PeerHistorySidecarStorage.make[IO](base)
      out <- sidecar.read(ord)
    } yield expect(out.isEmpty)
  }

  test("multiple ordinals coexist independently") { base =>
    val sampleA = nonTrivial
    val sampleB = sample(
      peerB -> PerPeerOperationalRecord(
        quality = (3, 3),
        removalPenalty = 0,
        cumulativeMissCount = 0L,
        readmissionCountdown = 0,
        deferralCountdown = 0,
        viewChangesCaused = None,
        tier = None
      )
    )
    for {
      sidecar <- PeerHistorySidecarStorage.make[IO](base)
      _ <- sidecar.write(ord100, sampleA)
      _ <- sidecar.write(ord200, sampleB)
      outA <- sidecar.read(ord100)
      outB <- sidecar.read(ord200)
    } yield expect.all(outA.contains(sampleA), outB.contains(sampleB))
  }

  test("write is overwriting for the same ordinal (re-write replaces prior content)") { base =>
    val ord = SnapshotOrdinal.unsafeApply(555L)
    val first = nonTrivial
    val second = sample(
      peerA -> PerPeerOperationalRecord(
        quality = (99, 99),
        removalPenalty = 0,
        cumulativeMissCount = 0L,
        readmissionCountdown = 0,
        deferralCountdown = 0,
        viewChangesCaused = None,
        tier = None
      )
    )
    for {
      sidecar <- PeerHistorySidecarStorage.make[IO](base)
      _ <- sidecar.write(ord, first)
      _ <- sidecar.write(ord, second)
      out <- sidecar.read(ord)
    } yield expect(out.contains(second))
  }

  test("delete removes the sidecar; subsequent read returns None") { base =>
    val ord = SnapshotOrdinal.unsafeApply(777L)
    for {
      sidecar <- PeerHistorySidecarStorage.make[IO](base)
      _ <- sidecar.write(ord, nonTrivial)
      before <- sidecar.read(ord)
      _ <- sidecar.delete(ord)
      after <- sidecar.read(ord)
    } yield expect.all(before.contains(nonTrivial), after.isEmpty)
  }

  test("delete is idempotent / no-op on missing ordinal") { base =>
    for {
      sidecar <- PeerHistorySidecarStorage.make[IO](base)
      _ <- sidecar.delete(SnapshotOrdinal.unsafeApply(1_000_000L))
      _ <- sidecar.delete(SnapshotOrdinal.unsafeApply(1_000_000L))
    } yield success
  }
}
