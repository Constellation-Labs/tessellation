package io.constellationnetwork.node.shared.infrastructure.consensus

import cats.effect.std.Queue
import cats.effect.{IO, Resource}
import cats.syntax.all._

import scala.concurrent.duration._

import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.node.shared.config.types.{ConsensusConfig, EventCutterConfig}
import io.constellationnetwork.node.shared.infrastructure.consensus.message.GetConsensusOutcomeRequest
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.gossip.RumorRaw
import io.constellationnetwork.security.{Hashed, Hasher, HasherSelector}

import derevo.cats.eqv
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.{NonNegLong, PosInt}
import io.circe.syntax._
import monocle.Lens
import org.http4s.Method.POST
import org.http4s.Request
import org.http4s.Status.{Conflict, Ok}
import org.http4s.circe.CirceEntityCodec._
import org.http4s.implicits._
import weaver.MutableIOSuite

object ConsensusRoutesSuite extends MutableIOSuite {

  @derive(eqv, encoder, decoder)
  final case class TestOutcome(key: SnapshotOrdinal, label: String)

  private implicit val keyLens: Lens[TestOutcome, SnapshotOrdinal] =
    Lens[TestOutcome, SnapshotOrdinal](_.key)(key => _.copy(key = key))

  private val config = ConsensusConfig(
    timeTriggerInterval = 10.seconds,
    declarationTimeout = 10.seconds,
    declarationRangeLimit = 100L,
    lockDuration = 10.seconds,
    eventCutter = EventCutterConfig(PosInt(1024), PosInt(1024))
  )

  override type Res = HasherSelector[IO]

  override def sharedResource: Resource[IO, Res] =
    Resource.eval(JsonSerializer.forAsync[IO]).map { serializer =>
      implicit val json: JsonSerializer[IO] = serializer
      HasherSelector.forSyncAlwaysCurrent[IO](Hasher.forJson[IO])
    }

  private def ordinal(value: Long): SnapshotOrdinal = SnapshotOrdinal(NonNegLong.unsafeFrom(value))

  test("the existing authenticated specific-outcome route serves an older typed sidecar outcome") {
    selector =>
      implicit val hasherSelector: HasherSelector[IO] = selector

      val old = TestOutcome(ordinal(10L), "certified-old")
      val latest = TestOutcome(ordinal(11L), "latest")

      for {
        storage <- ConsensusStorage.make[IO, String, SnapshotOrdinal, String, Unit, String, TestOutcome, String](config)
        _ <- storage.trySetInitialConsensusOutcome(latest)
        queue <- Queue.unbounded[IO, Hashed[RumorRaw]]
        routes = new ConsensusRoutes[IO, SnapshotOrdinal, String, Unit, String, TestOutcome, String](
          storage,
          queue,
          Some(key => old.some.filter(_.key === key).pure[IO])
        )
        response <- routes.p2pRoutes.orNotFound.run(
          Request[IO](POST, uri"/consensus/specific/outcome").withEntity(GetConsensusOutcomeRequest(old.key).asJson)
        )
        body <- response.as[Option[TestOutcome]]
      } yield expect.all(response.status === Ok, body.contains(old))
  }

  test("without an exact sidecar the existing ahead response remains Conflict") {
    selector =>
      implicit val hasherSelector: HasherSelector[IO] = selector

      val requested = ordinal(10L)
      val latest = TestOutcome(ordinal(11L), "latest")

      for {
        storage <- ConsensusStorage.make[IO, String, SnapshotOrdinal, String, Unit, String, TestOutcome, String](config)
        _ <- storage.trySetInitialConsensusOutcome(latest)
        queue <- Queue.unbounded[IO, Hashed[RumorRaw]]
        routes = new ConsensusRoutes[IO, SnapshotOrdinal, String, Unit, String, TestOutcome, String](storage, queue)
        response <- routes.p2pRoutes.orNotFound.run(
          Request[IO](POST, uri"/consensus/specific/outcome").withEntity(GetConsensusOutcomeRequest(requested).asJson)
        )
      } yield expect(response.status === Conflict)
  }
}
