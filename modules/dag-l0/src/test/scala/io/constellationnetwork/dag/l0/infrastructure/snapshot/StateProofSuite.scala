package io.constellationnetwork.dag.l0.infrastructure.snapshot

import cats.effect.IO
import cats.effect.kernel.Resource
import cats.syntax.all._

import io.constellationnetwork.dag.l0.dagL0KryoRegistrar
import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.ext.kryo._
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.node.shared.nodeSharedKryoRegistrar
import io.constellationnetwork.schema._
import io.constellationnetwork.security._
import io.constellationnetwork.security.signature.Signed

import better.files._
import weaver.MutableIOSuite
import weaver.scalacheck.Checkers

object StateProofSuite extends MutableIOSuite with Checkers {

  type Res = (KryoSerializer[IO], Hasher[IO])

  def sharedResource: Resource[IO, Res] = for {
    implicit0(ks: KryoSerializer[IO]) <- KryoSerializer.forAsync[IO](dagL0KryoRegistrar.union(nodeSharedKryoRegistrar))
    implicit0(j: JsonSerializer[IO]) <- JsonSerializer.forSync[IO].asResource
    h = Hasher.forKryo[IO]
  } yield (ks, h)

  def deserializeInfo(path: File)(implicit k: KryoSerializer[IO]): IO[GlobalSnapshotInfoV2] = {
    val bytes = path.loadBytes

    k.deserialize[GlobalSnapshotInfoV2](bytes).liftTo[IO]
  }

  def deserializeSnapshot(path: File)(implicit k: KryoSerializer[IO]): IO[Signed[GlobalIncrementalSnapshot]] = {
    val bytes = path.loadBytes

    k.deserialize[Signed[GlobalIncrementalSnapshotV1]](bytes).map(_.map(_.toGlobalIncrementalSnapshot)).liftTo[IO]
  }

  test("state proof matches for kryo deserialization") { res =>
    implicit val (kryo, h) = res

    val snapshotFile = File(getClass().getResource("/inc_snapshot_1930000").getPath)
    val snapshotInfoFile = File(getClass().getResource("/snapshot_info_1930000").getPath)

    for {
      info <- deserializeInfo(snapshotInfoFile)
      snap <- deserializeSnapshot(snapshotFile)

      snapshotStateProof = snap.stateProof

      infoStateProof <- info.stateProof[IO](snap.ordinal)

    } yield expect.eql(snapshotStateProof, infoStateProof)

  }
}
