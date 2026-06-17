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
    val a = PerPeerOperationalRecord(
      quality = (5, 7),
      removalPenalty = 12,
      cumulativeMissCount = 2L,
      readmissionCountdown = 0,
      deferralCountdown = 0
    )
    val b = PerPeerOperationalRecord(
      quality = (9, 9),
      removalPenalty = 0,
      cumulativeMissCount = 1L,
      readmissionCountdown = 0,
      deferralCountdown = 2
    )
    val c = PerPeerOperationalRecord(
      quality = (0, 0),
      removalPenalty = 3,
      cumulativeMissCount = 0L,
      readmissionCountdown = 4,
      deferralCountdown = 0
    )
    ConsensusOperationalState(
      perPeer = SortedMap(peer('a') -> a, peer('b') -> b, peer('c') -> c),
      recentProofSizes = SortedMap(
        SnapshotOrdinal.unsafeApply(100L) -> 7,
        SnapshotOrdinal.unsafeApply(101L) -> 6
      )
    )
  }

  pureTest("empty has empty maps") {
    val e = ConsensusOperationalState.empty
    expect.all(e.perPeer.isEmpty, e.recentProofSizes.isEmpty)
  }

  pureTest("PerPeerOperationalRecord.empty has all-zero fields") {
    val r = PerPeerOperationalRecord.empty
    expect.all(
      r.quality == ((0, 0)),
      r.removalPenalty == 0,
      r.cumulativeMissCount == 0L,
      r.readmissionCountdown == 0,
      r.deferralCountdown == 0,
      r.viewChangesCaused.isEmpty
    )
  }

  pureTest("PerPeerOperationalRecord decodes pre-v16 JSON (no viewChangesCaused key) as None") {
    // Back-compat regression: pre-v16 snapshots have no `viewChangesCaused` field
    // under `peerHistory.perPeer.<pid>`. The decoder MUST accept this and produce
    // `viewChangesCaused = None`, NOT fail with "missing required field". This was
    // the v16 hotfix root cause: a Long with a Scala default was treated as required.
    val preV16Json =
      """{"quality":[5,7],"removalPenalty":12,"cumulativeMissCount":2,"readmissionCountdown":0,"deferralCountdown":0}"""
    val decoded = decode[PerPeerOperationalRecord](preV16Json)
    expect(decoded.isRight, s"pre-v16 JSON failed to decode: $decoded").and(
      expect(decoded.toOption.exists(_.viewChangesCaused.isEmpty), "expected viewChangesCaused = None on pre-v16 JSON")
    )
  }

  pureTest("PerPeerOperationalRecord with None viewChangesCaused drops the key under dropNullValues=true") {
    // Byte-stability check: a v16-encoded record with `viewChangesCaused = None`
    // must produce JSON byte-identical to the pre-v16 layout (no key at all) under
    // the production Printer. Verified for the artifact-hash determinism contract.
    val r = PerPeerOperationalRecord(
      quality = (5, 7),
      removalPenalty = 12,
      cumulativeMissCount = 2L,
      readmissionCountdown = 0,
      deferralCountdown = 0,
      viewChangesCaused = None
    )
    val productionPrinter = Printer(dropNullValues = true, indent = "", sortKeys = true)
    val rendered = productionPrinter.print(r.asJson)
    expect.all(
      !rendered.contains("viewChangesCaused"),
      !rendered.contains("null"),
      rendered.contains("\"removalPenalty\":12")
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
    // Each peer id (128-char hex) must appear once as a key in `perPeer`. The
    // 'a' peer participates in 3 of the 5 per-peer dimensions; if we had kept
    // the prior layout (5 separate PeerId-keyed maps), the 'a' key would appear
    // 3 times in JSON. v21 collapsed those into one record, so we expect 1.
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
      a.quality == ((5, 7)),
      a.removalPenalty == 12,
      a.cumulativeMissCount == 2L,
      b.deferralCountdown == 2,
      c.readmissionCountdown == 4,
      populated.recentProofSizes(SnapshotOrdinal.unsafeApply(100L)) == 7
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
