package org.tessellation.dag.l0.infrastructure.snapshot

import cats.effect.IO
import cats.effect.kernel.Resource
import cats.syntax.all._

import org.tessellation.dag.l0.dagL0KryoRegistrar
import org.tessellation.ext.cats.effect.ResourceIO
import org.tessellation.ext.kryo._
import org.tessellation.json.JsonSerializer
import org.tessellation.kryo.KryoSerializer
import org.tessellation.node.shared.nodeSharedKryoRegistrar
import org.tessellation.schema._
import org.tessellation.security._
import org.tessellation.security.signature.Signed

import better.files._
import weaver.MutableIOSuite
import weaver.scalacheck.Checkers

object StateProofSuite extends MutableIOSuite with Checkers {

  type Res = (KryoSerializer[IO], Hasher[IO])

  val hashSelect = new HashSelect { def select(ordinal: SnapshotOrdinal): HashLogic = KryoHash }

  def sharedResource: Resource[IO, Res] = for {
    implicit0(ks: KryoSerializer[IO]) <- KryoSerializer.forAsync[IO](dagL0KryoRegistrar.union(nodeSharedKryoRegistrar))
    implicit0(j: JsonSerializer[IO]) <- JsonSerializer.forSync[IO].asResource
    h = Hasher.forSync[IO](hashSelect)
  } yield (ks, h)

  def deserializeInfo(path: File)(implicit k: KryoSerializer[IO]): IO[GlobalSnapshotInfoV2] = {
    val bytes = path.loadBytes

    k.deserialize[GlobalSnapshotInfoV2](bytes).liftTo[IO]
  }

  def deserializeSnapshot(path: File)(implicit k: KryoSerializer[IO]): IO[Signed[GlobalIncrementalSnapshot]] = {
    val bytes = path.loadBytes

    k.deserialize[Signed[GlobalIncrementalSnapshot]](bytes).liftTo[IO]
  }

  test("state proof matches for kryo deserialization") { res =>
    implicit val (kryo, h) = res

    val snapshotFile = File(getClass().getResource("/inc_snapshot_1930000").getPath)
    val snapshotInfoFile = File(getClass().getResource("/snapshot_info_1930000").getPath)

    for {
      info <- deserializeInfo(snapshotInfoFile)
      snap <- deserializeSnapshot(snapshotFile)

      snapshotStateProof = snap.stateProof
      infoStateProof <- info.stateProof(snap.ordinal, hashSelect)

    } yield expect.eql(snapshotStateProof, infoStateProof)

  }
}
