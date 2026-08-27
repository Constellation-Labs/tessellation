package io.constellationnetwork.node.shared.infrastructure.consensus

import cats.Eq
import cats.effect.IO

import scala.concurrent.duration._

import io.constellationnetwork.node.shared.config.types.{ConsensusConfig, EventCutterConfig}
import io.constellationnetwork.schema.SnapshotOrdinal

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.PosInt
import monocle.Lens
import weaver.SimpleIOSuite

object ConsensusStorageRecoveryResetSuite extends SimpleIOSuite {

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

  private def storage =
    ConsensusStorage.make[IO, Unit, SnapshotOrdinal, String, Unit, Unit, Outcome, String](consensusConfig)

  test("the recovery boundary clears the accepted outcome so a newer certified outcome can be installed") {
    storage.flatMap { consensusStorage =>
      val beforeRecovery = Outcome(SnapshotOrdinal.unsafeApply(26L))
      val recovered = Outcome(SnapshotOrdinal.unsafeApply(67L))

      for {
        installedBefore <- consensusStorage.trySetInitialConsensusOutcome(beforeRecovery)
        _ <- consensusStorage.clearAllConsensusState
        cleared <- consensusStorage.getLastConsensusOutcome
        installedRecovered <- consensusStorage.trySetInitialConsensusOutcome(recovered)
        current <- consensusStorage.getLastConsensusOutcome
      } yield
        expect(installedBefore)
          .and(expect(cleared.isEmpty))
          .and(expect(installedRecovered))
          .and(expect(current.contains(recovered)))
    }
  }
}
