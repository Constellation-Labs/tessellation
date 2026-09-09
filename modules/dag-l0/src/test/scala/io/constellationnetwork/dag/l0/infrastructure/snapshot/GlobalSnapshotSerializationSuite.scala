package io.constellationnetwork.dag.l0.infrastructure.snapshot

import java.nio.file.{Paths => JPaths}

import cats.effect.{IO, Resource}
import cats.syntax.all._

import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.ext.kryo._
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.node.shared.nodeSharedKryoRegistrar
import io.constellationnetwork.schema._
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed

import eu.timepit.refined.auto._
import fs2.io.file.{Files, Path}
import io.circe.jawn.CirceSupportParser
import io.circe.syntax._
import io.circe.{Json, Printer}
import io.estatico.newtype.ops._
import org.typelevel.jawn.Facade
import org.typelevel.jawn.fs2._
import weaver.MutableIOSuite
import weaver.scalacheck.Checkers

/** GlobalSnapshot serialization test suite.
  *
  * MIGRATION NOTE: This suite has been migrated from Kryo to JSON serialization.
  *
  * Background:
  *   - Previously used Kryo (Twitter Chill) for snapshot serialization and hashing
  *   - Kryo was problematic due to:
  *     1. Lack of Scala 3 support (Twitter Chill is no longer maintained) 2. Incompatibility with Java 21 (requires --add-opens flags) 3.
  *        Poor cross-language support (difficult to maintain JS/TS client compatibility)
  *
  * New approach:
  *   - Uses JSON serialization via Circe for all snapshot operations
  *   - Provides better cross-language compatibility (easier JS/TS integration)
  *   - Fully compatible with Scala 3 and modern Java versions
  *   - More maintainable and debuggable (human-readable format)
  *
  * Hash change:
  *   - Old Kryo hash: c24121cb3233364d80e80cb473510a4b7ddf4cb47a47a2f84cff8b6fee7f8b1c
  *   - New JSON hash: 09a968140596ee0a48cdb686295cc732ad30ed2bd846da5ee8159b88ba8a0b63
  *   - Hash difference is expected due to different serialization format
  */
object GlobalSnapshotSerializationSuite extends MutableIOSuite with Checkers {

  // Updated hash for JSON-based serialization (changed from Kryo hash)
  val expectedHash: Hash = Hash("09a968140596ee0a48cdb686295cc732ad30ed2bd846da5ee8159b88ba8a0b63")
  val kryoFilename: String = expectedHash.coerce
  val jsonFilename: String = s"${expectedHash.coerce}.json"

  // Simplified resource - only JSON serializer and hasher needed now
  type Res = Hasher[IO]

  def sharedResource: Resource[IO, Res] = for {
    implicit0(j: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO].asResource
    // Using JSON-based hasher instead of Kryo-based hasher
    hk = Hasher.forJson[IO]
  } yield hk

  /** DEPRECATED: Kryo serialization test
    *
    * This test has been commented out as part of the Kryo removal migration.
    *
    * Reasons for removal:
    *   - Kryo (Twitter Chill) is no longer maintained
    *   - No Scala 3 support
    *   - Requires JVM hacks (--add-opens) for Java 21
    *   - Difficult to maintain compatibility with TypeScript/JavaScript clients
    *
    * Historical context:
    *   - This test verified byte-for-byte serialization compatibility
    *   - Used for ensuring snapshot hash consistency across nodes
    *   - Replaced by JSON serialization which provides the same guarantees
    *
    * Migration path:
    *   - If you need to verify old Kryo snapshots, temporarily uncomment this test
    *   - For new code, use the JSON serialization test below
    *   - Old snapshots in production may need migration or re-validation
    */
  //  test("snapshot is successfully deserialized and serialized with kryo") { res =>
  //    implicit val (hk) = res
  //
  //    for {
  //      storedBytes <- getBytesFromClasspath(kryoFilename)
  //      signedSnapshot <- storedBytes.fromBinaryF[Signed[GlobalSnapshot]]
  //      serializedBytes <- signedSnapshot.toBinaryF
  //      hashCompare <- hk.compare(signedSnapshot.value, expectedHash)
  //    } yield expect.eql(serializedBytes, storedBytes).and(expect(hashCompare))
  //  }

  /** JSON serialization test (current approach)
    *
    * This test verifies that:
    *   1. Snapshots can be deserialized from JSON format 2. Serialization is deterministic (same input = same output) 3. Hash calculation
    *      is consistent with the stored snapshot
    */
  test("snapshot is successfully deserialized and serialized with json parser") { implicit res =>
    implicit val (hk) = res
    val productionPrinter = Printer(dropNullValues = true, indent = "", sortKeys = true)

    for {
      storedJson <- getJsonFromClasspath(jsonFilename)
      signedSnapshot <- storedJson.as[Signed[GlobalSnapshot]].leftWiden[Throwable].liftTo[IO]
      serializedJson = signedSnapshot.asJson
      hashCompare <- hk.compare(signedSnapshot.value, expectedHash)

    } yield expect.eql(productionPrinter.print(serializedJson), productionPrinter.print(storedJson)).and(expect(hashCompare))
  }

  private def getJsonFromClasspath(name: String): F[Json] = {
    implicit val facade: Facade[Json] = new CirceSupportParser(None, false).facade

    Files[F]
      .readAll(resourceAsPath(name))
      .chunks
      .parseJsonStream[Json]
      .compile
      .lastOrError
  }

  private def resourceAsPath(name: String): Path =
    Path.fromNioPath(JPaths.get(Thread.currentThread().getContextClassLoader.getResource(name).toURI))

}
