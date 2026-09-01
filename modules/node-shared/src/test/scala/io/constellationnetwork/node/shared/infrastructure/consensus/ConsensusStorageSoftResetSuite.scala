package io.constellationnetwork.node.shared.infrastructure.consensus

import cats.Eq
import cats.data.NonEmptySet
import cats.effect.IO

import scala.concurrent.duration._

import io.constellationnetwork.node.shared.config.types.{ConsensusConfig, EventCutterConfig}
import io.constellationnetwork.node.shared.infrastructure.consensus.declaration.{ViewChangeCertificate, ViewChangeVote}
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature.{Signature, SignatureProof}

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.PosInt
import io.circe.Encoder
import monocle.Lens
import weaver.SimpleIOSuite

object ConsensusStorageSoftResetSuite extends SimpleIOSuite {

  private final case class Outcome(key: SnapshotOrdinal)

  private implicit val outcomeEq: Eq[Outcome] = Eq.fromUniversalEquals

  private implicit val outcomeKey: Lens[Outcome, SnapshotOrdinal] =
    Lens[Outcome, SnapshotOrdinal](_.key)(key => _.copy(key = key))

  private val consensusConfig =
    ConsensusConfig(
      timeTriggerInterval = 10.seconds,
      declarationTimeout = 10.seconds,
      declarationRangeLimit = 100L,
      lockDuration = 10.seconds,
      eventCutter = EventCutterConfig(
        maxBinarySizeBytes = PosInt(1024),
        maxUpdateNodeParametersSize = PosInt(1024)
      )
    )

  private val key = SnapshotOrdinal.unsafeApply(7L)
  private val parentHash = Hash.fromBytes("parent".getBytes("UTF-8"))
  private val facilitatorsHash = Hash.fromBytes("facilitators".getBytes("UTF-8"))

  private val rawInboundVcc = {
    val vote = Signed(
      ViewChangeVote(
        fromView = 0L,
        toView = 1L,
        facilitatorsHash = facilitatorsHash,
        lastSnapshotHash = parentHash,
        highestKnownQc = None
      ),
      NonEmptySet.one(SignatureProof(Id(Hex("01")), Signature(Hex("00"))))
    )
    ViewChangeCertificate(0L, 1L, facilitatorsHash, NonEmptySet.one(vote))
  }

  private def storage =
    ConsensusStorage.make[IO, Unit, SnapshotOrdinal, String, Unit, Unit, Outcome, String](consensusConfig)

  test("a raw inbound VCC cannot suppress a safe pre-vote soft reset") {
    storage.flatMap { consensusStorage =>
      for {
        _ <- consensusStorage.storeAssembledVcc(key, rawInboundVcc)
        reset <- consensusStorage.softResetRoundState(key)
        retainedRawVcc <- consensusStorage.getAssembledVcc(key)
      } yield expect(reset).and(expect(retainedRawVcc.isEmpty))
    }
  }

  test("a validated VCC apply marker suppresses the pre-vote soft reset") {
    storage.flatMap { consensusStorage =>
      for {
        scheduled <- consensusStorage.markAssembledVccApplyScheduled(key, parentHash, fromView = 0L, toView = 1L)
        reset <- consensusStorage.softResetRoundState(key)
        markerRetained <- consensusStorage.isAssembledVccApplyScheduled(key, parentHash, fromView = 0L, toView = 1L)
      } yield expect(scheduled).and(expect(!reset)).and(expect(markerRetained))
    }
  }
}
