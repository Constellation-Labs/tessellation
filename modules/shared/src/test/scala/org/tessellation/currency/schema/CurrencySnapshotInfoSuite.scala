package org.tessellation.currency.schema

import cats.effect.{IO, Resource}

import scala.collection.immutable.SortedMap

import org.tessellation.currency.schema.currency.CurrencySnapshotInfo
import org.tessellation.ext.crypto._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Balance
import org.tessellation.shared.sharedKryoRegistrar

import weaver.MutableIOSuite

object CurrencySnapshotInfoSuite extends MutableIOSuite {

  type Res = KryoSerializer[IO]

  override def sharedResource: Resource[IO, Res] =
    KryoSerializer.forAsync[IO](sharedKryoRegistrar)

  test("should calculate merkle tree") { implicit ks =>
    for {
      emptyHash <- SortedMap.empty[Address, Balance].hashF

      result <- CurrencySnapshotInfo(SortedMap.empty, SortedMap.empty).stateProof[IO]
      leafHash = result.findPath(emptyHash).flatMap(_.entries.head.sibling.toOption).get

      sameLeafHashesCount = result.nodes.filter(_ == leafHash).size
    } yield expect.same((2, 3, 2), (result.leafCount.value, result.nodes.size, sameLeafHashesCount))
  }

}
