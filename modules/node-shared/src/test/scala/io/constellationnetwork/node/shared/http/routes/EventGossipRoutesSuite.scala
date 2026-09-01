package io.constellationnetwork.node.shared.http.routes

import java.nio.charset.StandardCharsets

import cats.data.NonEmptySet
import cats.effect.IO
import cats.syntax.all._

import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.node.shared.infrastructure.gossip.event._
import io.constellationnetwork.node.shared.infrastructure.mempool.{EventMempool, MempoolConfig, StateKeyExtractor}
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.Signed._
import io.constellationnetwork.security.signature.signature.{Signature, SignatureProof}
import io.constellationnetwork.security.{Hasher, HasherSelector}

import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import io.circe.parser.decode
import org.http4s.Method.{GET, POST}
import org.http4s.Request
import org.http4s.Status.{BadRequest, Ok}
import org.http4s.circe.CirceEntityCodec._
import org.http4s.implicits._
import weaver.SimpleIOSuite

object EventGossipRoutesSuite extends SimpleIOSuite {

  @derive(encoder, decoder)
  final case class TestEvent(value: String)

  private type TestKey = Unit

  private val keyExtractor: StateKeyExtractor[IO, TestEvent, TestKey] =
    _ => Set.empty[TestKey].pure[IO]

  private val proof = SignatureProof(Id(Hex("a" * 128)), Signature(Hex("b" * 128)))

  private def signed(value: String): Signed[TestEvent] =
    Signed(TestEvent(value), NonEmptySet.one(proof))

  test("a successful push stores the canonical hash and another peer can fetch it after the publisher disappears") {
    for {
      implicit0(serializer: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO]
      implicit0(hasher: Hasher[IO]) = Hasher.forJson[IO]
      implicit0(selector: HasherSelector[IO]) = HasherSelector.forSyncAlwaysCurrent[IO](hasher)
      serving <- EventMempool.make[IO, TestEvent, TestKey](keyExtractor, MempoolConfig(10))
      fetching <- EventMempool.make[IO, TestEvent, TestKey](keyExtractor, MempoolConfig(10))
      event = signed("available-after-advertiser-crash")
      hashed <- event.toHashed
      routes = EventGossipRoutes.make[IO, TestEvent, TestKey](serving).p2pRoutes.orNotFound
      pushResponse <- routes.run(Request[IO](POST, uri"/events/push").withEntity(EventPush(hashed.hash, event)))
      storedAfterAck <- serving.contains(hashed.hash)
      fetchResponse <- routes.run(Request[IO](POST, uri"/events/iwant").withEntity(IWantRequest(Set(hashed.hash))))
      fetched <- fetchResponse.as[IWantResponse[TestEvent]]
      _ <- fetched.events.traverse_ { case (_, received) => fetching.add(received).void }
      repaired <- fetching.contains(hashed.hash)
    } yield expect.all(pushResponse.status === Ok, storedAfterAck, fetchResponse.status === Ok, repaired)
  }

  test("a push with a non-canonical declared hash is rejected without mutating the mempool") {
    for {
      implicit0(serializer: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO]
      implicit0(hasher: Hasher[IO]) = Hasher.forJson[IO]
      implicit0(selector: HasherSelector[IO]) = HasherSelector.forSyncAlwaysCurrent[IO](hasher)
      mempool <- EventMempool.make[IO, TestEvent, TestKey](keyExtractor, MempoolConfig(10))
      event = signed("mismatched-label")
      canonical <- event.toHashed
      declared = Hash.fromBytes(Array[Byte](42))
      routes = EventGossipRoutes.make[IO, TestEvent, TestKey](mempool).p2pRoutes.orNotFound
      response <- routes.run(Request[IO](POST, uri"/events/push").withEntity(EventPush(declared, event)))
      stored <- mempool.contains(canonical.hash)
    } yield expect.all(response.status === BadRequest, !stored)
  }

  test("request-specific IHAVE sees an event beyond the bounded periodic snapshot") {
    for {
      implicit0(serializer: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO]
      implicit0(hasher: Hasher[IO]) = Hasher.forJson[IO]
      implicit0(selector: HasherSelector[IO]) = HasherSelector.forSyncAlwaysCurrent[IO](hasher)
      mempool <- EventMempool.make[IO, TestEvent, TestKey](
        keyExtractor,
        MempoolConfig(EventMempool.DefaultSnapshotLimit + 1)
      )
      events = (0 to EventMempool.DefaultSnapshotLimit).toList.map(index => signed(s"event-$index"))
      _ <- events.traverse_(mempool.add)
      target <- events.last.toHashed
      routes = EventGossipRoutes.make[IO, TestEvent, TestKey](mempool).p2pRoutes.orNotFound
      periodicResponse <- routes.run(Request[IO](GET, uri"/events/ihave"))
      periodic <- periodicResponse.as[IHave]
      exactResponse <- routes.run(Request[IO](POST, uri"/events/ihave").withEntity(IWantRequest(Set(target.hash))))
      exact <- exactResponse.as[IHave]
    } yield
      expect.all(
        !periodic.hashes.contains(target.hash),
        exactResponse.status === Ok,
        exact.hashes === Set(target.hash)
      )
  }

  test("request-specific event endpoints reject work above the fixed protocol count bound") {
    for {
      implicit0(serializer: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO]
      implicit0(hasher: Hasher[IO]) = Hasher.forJson[IO]
      implicit0(selector: HasherSelector[IO]) = HasherSelector.forSyncAlwaysCurrent[IO](hasher)
      mempool <- EventMempool.make[IO, TestEvent, TestKey](keyExtractor, MempoolConfig(1))
      hashes = (0 to EventMempool.DefaultSnapshotLimit).map(index => Hash.fromBytes(BigInt(index).toByteArray)).toSet
      routes = EventGossipRoutes.make[IO, TestEvent, TestKey](mempool).p2pRoutes.orNotFound
      ihave <- routes.run(Request[IO](POST, uri"/events/ihave").withEntity(IWantRequest(hashes)))
      iwant <- routes.run(Request[IO](POST, uri"/events/iwant").withEntity(IWantRequest(hashes)))
    } yield expect.all(ihave.status === BadRequest, iwant.status === BadRequest)
  }

  test("IWANT response is bounded by event count and encoded bytes") {
    for {
      implicit0(serializer: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO]
      implicit0(hasher: Hasher[IO]) = Hasher.forJson[IO]
      implicit0(selector: HasherSelector[IO]) = HasherSelector.forSyncAlwaysCurrent[IO](hasher)
      mempool <- EventMempool.make[IO, TestEvent, TestKey](keyExtractor, MempoolConfig(32))
      events = (0 until 24).toList.map(index => signed(s"$index-${"x" * (300 * 1024)}"))
      hashes <- events.traverse(_.toHashed.map(_.hash))
      _ <- events.traverse_(mempool.add)
      routes = EventGossipRoutes.make[IO, TestEvent, TestKey](mempool).p2pRoutes.orNotFound
      response <- routes.run(Request[IO](POST, uri"/events/iwant").withEntity(IWantRequest(hashes.toSet)))
      body <- response.body.compile.to(Array)
      decoded <- decode[IWantResponse[TestEvent]](new String(body, StandardCharsets.UTF_8)).liftTo[IO]
    } yield
      expect.all(
        response.status === Ok,
        decoded.events.size <= EventGossipBounds.MaxIWantResponseEvents,
        body.length <= EventGossipBounds.MaxIWantResponseBytes
      )
  }

  pureTest("single-event pullability is inclusive at the encoded response boundary") {
    val hash = Hash("pullability-boundary")
    val empty = signed("")
    val baseBytes = EventGossipBounds.encodedResponseBytes(List(hash -> empty))
    val exactly = signed("x" * 128)
    val exactLimit = baseBytes + 128
    val oneOver = signed("x" * 129)

    expect.all(
      EventGossipBounds.isPullableWithin(hash, exactly, exactLimit),
      EventGossipBounds.encodedResponseBytes(List(hash -> exactly)) === exactLimit,
      !EventGossipBounds.isPullableWithin(hash, oneOver, exactLimit)
    )
  }

  pureTest("the bounded pull response admits one configured 512 kB Currency binary after base64 expansion") {
    val configuredCurrencyBinaryBytes = 512000
    val worstCaseBase64Bytes = ((configuredCurrencyBinaryBytes.toLong * 4L) + 2L) / 3L
    val signedEnvelopeBudget = 256L * 1024L

    expect(EventGossipBounds.MaxIWantResponseBytes.toLong >= worstCaseBase64Bytes + signedEnvelopeBudget)
  }

}
