package io.constellationnetwork.node.shared.http.routes

import cats.data.NonEmptySet
import cats.effect.IO

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Balance
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.height.{Height, SubHeight}
import io.constellationnetwork.schema.snapshot.{Snapshot, SnapshotInfo, StateProof}
import io.constellationnetwork.schema.transaction.TransactionReference
import io.constellationnetwork.schema.{BlockAsActiveTip, SnapshotOrdinal, SnapshotTips}
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature.{Signature, SignatureProof}

import io.circe.Encoder
import weaver.SimpleIOSuite

object CachedCombinedResponseSuite extends SimpleIOSuite {

  private final case class TestSnapshot(
    ordinal: SnapshotOrdinal,
    lastSnapshotHash: Hash,
    height: Height = Height.MinValue,
    subHeight: SubHeight = SubHeight.MinValue,
    blocks: SortedSet[BlockAsActiveTip] = SortedSet.empty,
    tips: SnapshotTips = SnapshotTips(SortedSet.empty, SortedSet.empty),
    epochProgress: EpochProgress = EpochProgress.MinValue
  ) extends Snapshot

  private final case class TestState(
    lastTxRefs: SortedMap[Address, TransactionReference],
    balances: SortedMap[Address, Balance],
    marker: String
  ) extends SnapshotInfo[StateProof]

  private implicit val testSnapshotEncoder: Encoder[TestSnapshot] =
    Encoder.forProduct2("ordinal", "lastSnapshotHash")(snapshot => snapshot.ordinal.value.value -> snapshot.lastSnapshotHash.value)

  private implicit val testStateEncoder: Encoder[TestState] =
    Encoder.forProduct1("marker")(_.marker)

  private def signed(value: TestSnapshot, signature: String): Signed[TestSnapshot] =
    Signed(
      value,
      NonEmptySet.one(SignatureProof(Id(Hex("signer")), Signature(Hex(signature))))
    )

  test("same-ordinal canonical proof replacement invalidates the live combined response cache") {
    val ordinal = SnapshotOrdinal.unsafeApply(17L)
    val value = TestSnapshot(ordinal, Hash("parent"))
    val first = signed(value, "first-proof")
    val replacement = signed(value, "replacement-proof")
    val firstState = TestState(SortedMap.empty, SortedMap.empty, "first-state")
    val replacementState = TestState(SortedMap.empty, SortedMap.empty, "replacement-state")

    for {
      cache <- CachedCombinedResponse.make[IO, TestSnapshot, TestState]
      firstBytes <- cache.get(ordinal, first, firstState)
      replacementBytes <- cache.get(ordinal, replacement, replacementState)
      firstJson = new String(firstBytes, java.nio.charset.StandardCharsets.UTF_8)
      replacementJson = new String(replacementBytes, java.nio.charset.StandardCharsets.UTF_8)
    } yield
      expect(firstJson.contains("first-state")) &&
        expect(replacementJson.contains("replacement-state")) &&
        expect(firstJson != replacementJson)
  }
}
