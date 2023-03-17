package org.tessellation.schema

import cats.effect.{IO, Resource}

import scala.collection.immutable.SortedMap

import org.tessellation.ext.crypto._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.address.Address
import org.tessellation.security.hash.Hash
import org.tessellation.shared.sharedKryoRegistrar

import weaver.MutableIOSuite

object GlobalSnapshotInfoSuite extends MutableIOSuite {

  type Res = KryoSerializer[IO]

  override def sharedResource: Resource[IO, Res] =
    KryoSerializer.forAsync[IO](sharedKryoRegistrar)

  test("should calculate merkle tree") { implicit ks =>
    for {
      emptyHash <- SortedMap.empty[Address, Hash].hashF

      result <- GlobalSnapshotInfo.empty.stateProof[IO]
      leafHash = result.findPath(emptyHash).flatMap(_.entries.head.sibling.toOption).get

      sameLeafHashesCount = result.nodes.filter(_ == leafHash).size
    } yield expect.same((4, 7, 4), (result.leafCount.value, result.nodes.size, sameLeafHashesCount))
  }

}
