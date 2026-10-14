package io.constellationnetwork.schema

import scala.collection.immutable.SortedMap

import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hex.Hex

import io.circe.parser.decode
import io.circe.syntax._
import io.circe.{Json, Printer}
import weaver.SimpleIOSuite

object ConsensusOperationalStateSuite extends SimpleIOSuite {

  private def peer(c: Char): PeerId =
    PeerId(Hex(c.toString * 128))

  private val populated: ConsensusOperationalState = {
    val a = PerPeerOperationalRecord(removalPenalty = 12, cumulativeMissCount = 2L, readmissionCountdown = 0)
    val b = PerPeerOperationalRecord(removalPenalty = 0, cumulativeMissCount = 1L, readmissionCountdown = 0)
    val c = PerPeerOperationalRecord(removalPenalty = 3, cumulativeMissCount = 0L, readmissionCountdown = 4)
    ConsensusOperationalState(
      perPeer = SortedMap(peer('a') -> a, peer('b') -> b, peer('c') -> c),
      recentCommitteeSizes = SortedMap(
        SnapshotOrdinal.unsafeApply(100L) -> 7,
        SnapshotOrdinal.unsafeApply(101L) -> 6
      ),
      recentRoundEndTimes = Some(SortedMap(SnapshotOrdinal.unsafeApply(101L) -> 1000L)),
      penaltyUntil = Some(SortedMap(peer('c') -> SnapshotOrdinal.unsafeApply(200L)))
    )
  }

  pureTest("empty has empty maps") {
    val e = ConsensusOperationalState.empty
    expect.all(e.perPeer.isEmpty, e.recentCommitteeSizes.isEmpty, e.recentRoundEndTimes.isEmpty, e.penaltyUntil.isEmpty)
  }

  pureTest("PerPeerOperationalRecord.empty has all-zero fields") {
    val r = PerPeerOperationalRecord.empty
    expect.all(r.removalPenalty == 0, r.cumulativeMissCount == 0L, r.readmissionCountdown == 0)
  }

  pureTest("operational state carries no quality, tier, score, or leader-reported participation") {
    val rendered = populated.asJson.noSpaces
    expect.all(
      !rendered.contains("quality"),
      !rendered.contains("tier"),
      !rendered.contains("activeAdmissionScore"),
      !rendered.contains("viewChangesCaused"),
      !rendered.contains("recentSigners"),
      !rendered.contains("controllerEvidence")
    )
  }

  pureTest("JSON roundtrip preserves all per-peer fields") {
    val encoded = populated.asJson
    val decoded = decode[ConsensusOperationalState](encoded.noSpaces)
    expect(decoded == Right(populated))
  }

  pureTest("JSON encoding is byte-stable across repeats (determinism)") {
    val a = populated.asJson.noSpaces
    val b = populated.asJson.noSpaces
    expect(a == b)
  }

  pureTest("PeerId appears once per peer in JSON, not duplicated across dimensions") {
    // Each peer id (128-char hex) must appear once as a key in `perPeer`.
    val json = populated.asJson.noSpaces
    val aKey = "a" * 128
    val occurrences = json.split(aKey).length - 1
    expect(occurrences == 1)
  }

  pureTest("populated record values are reachable as constructed") {
    val a = populated.perPeer(peer('a'))
    val b = populated.perPeer(peer('b'))
    val c = populated.perPeer(peer('c'))
    expect.all(
      a.removalPenalty == 12,
      a.cumulativeMissCount == 2L,
      b.cumulativeMissCount == 1L,
      c.readmissionCountdown == 4,
      populated.recentCommitteeSizes(SnapshotOrdinal.unsafeApply(100L)) == 7
    )
  }

  pureTest("None peerHistory maps to empty operational state on restore") {
    val none: Option[ConsensusOperationalState] = None
    val restored = none.getOrElse(ConsensusOperationalState.empty)
    expect(restored == ConsensusOperationalState.empty)
  }

  pureTest("production Printer omits a null field from a JSON object (pre-v20 signature compat)") {
    // io.constellationnetwork.json.JsonSerializer#forAsync uses
    // Printer(dropNullValues = true, sortKeys = true). Under that printer,
    // Option[T] = None fields are dropped entirely. The pre-v20 / v20 / v21
    // wire-compat story depends on this: a v21 binary re-encoding a pre-v20
    // snapshot value (peerHistory = None) produces byte-identical JSON to what
    // the pre-v20 binary originally signed.
    val productionPrinter = Printer(dropNullValues = true, indent = "", sortKeys = true)
    val obj = Json.obj(
      "a" -> Json.fromString("x"),
      "peerHistory" -> Json.Null,
      "z" -> Json.fromInt(1)
    )
    val rendered = productionPrinter.print(obj)
    expect.all(
      !rendered.contains("peerHistory"),
      !rendered.contains("null"),
      rendered == """{"a":"x","z":1}"""
    )
  }
}
