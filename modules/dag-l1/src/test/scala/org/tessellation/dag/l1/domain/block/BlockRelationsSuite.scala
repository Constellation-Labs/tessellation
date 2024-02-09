package org.tessellation.dag.l1.domain.block

import cats.effect.{IO, Resource}

import org.tessellation.block.generators._
import org.tessellation.dag.l1.Main
import org.tessellation.ext.cats.effect.ResourceIO
import org.tessellation.json.JsonSerializer
import org.tessellation.kryo.KryoSerializer
import org.tessellation.node.shared.nodeSharedKryoRegistrar
import org.tessellation.schema.{Block, SnapshotOrdinal, transaction}
import org.tessellation.security._
import org.tessellation.security.signature.Signed

import weaver.MutableIOSuite
import weaver.scalacheck.Checkers

object BlockRelationsSuite extends MutableIOSuite with Checkers {

  type Res = Hasher[IO]

  def sharedResource: Resource[IO, Res] = for {
    implicit0(ks: KryoSerializer[IO]) <- KryoSerializer.forAsync[IO](Main.kryoRegistrar ++ nodeSharedKryoRegistrar)
    implicit0(j: JsonSerializer[IO]) <- JsonSerializer.forSync[IO].asResource
    h = Hasher.forSync[IO](new HashSelect { def select(ordinal: SnapshotOrdinal): HashLogic = JsonHash })
  } yield h

  test("when no relation between blocks then block is independent") { implicit res =>
    forall { (block: Signed[Block], notRelatedBlock: Signed[Block]) =>
      for {
        hashedBlock <- block.toHashed[IO]
        isRelated = (block: Signed[Block]) => BlockRelations.dependsOn[IO](hashedBlock)(block)
        actual <- isRelated(notRelatedBlock)
      } yield expect.same(false, actual)
    }
  }

  test("when first block is parent of second then block is dependent") { implicit res =>
    forall { (block: Signed[Block], notRelatedBlock: Signed[Block]) =>
      for {
        hashedBlock <- block.toHashed[IO]
        isRelated = (block: Signed[Block]) => BlockRelations.dependsOn[IO](hashedBlock)(block)
        relatedBlock = notRelatedBlock.copy(value =
          notRelatedBlock.value.copy(parent = hashedBlock.ownReference :: notRelatedBlock.value.parent)
        )
        actual <- isRelated(relatedBlock)
      } yield expect.same(true, actual)
    }
  }

  test("when second block is parent of first then block is independent") { implicit res =>
    forall { (block: Signed[Block], notRelatedBlock: Signed[Block]) =>
      for {
        notRelatedHashedBlock <- notRelatedBlock.toHashed[IO]
        hashedBlock <- block.copy(value = block.value.copy(parent = notRelatedHashedBlock.ownReference :: block.value.parent)).toHashed[IO]
        isRelated = (block: Signed[Block]) => BlockRelations.dependsOn[IO](hashedBlock)(block)
        actual <- isRelated(notRelatedBlock)
      } yield expect.same(false, actual)
    }
  }

  test("when first block has transaction reference used in second then block is dependent") { implicit res =>
    forall { (block: Signed[Block], notRelatedBlock: Signed[Block]) =>
      for {
        hashedBlock <- block.toHashed[IO]
        isRelated = (block: Signed[Block]) => BlockRelations.dependsOn[IO](hashedBlock)(block)
        hashedTxn <- block.transactions.head.toHashed[IO]
        relatedTxn = notRelatedBlock.transactions.head.copy(value =
          notRelatedBlock.transactions.head.value.copy(parent = transaction.TransactionReference(hashedTxn.ordinal, hashedTxn.hash))
        )
        relatedBlock = notRelatedBlock.copy(value = notRelatedBlock.value.copy(transactions = notRelatedBlock.transactions.add(relatedTxn)))
        actual <- isRelated(relatedBlock)
      } yield expect.same(true, actual)
    }
  }

  test("when second block has transaction reference used in first then block is independent") { implicit res =>
    forall { (block: Signed[Block], notRelatedBlock: Signed[Block]) =>
      for {
        hashedTxn <- notRelatedBlock.transactions.head.toHashed[IO]
        blockTxn = block.transactions.head
          .copy(value = block.transactions.head.value.copy(parent = transaction.TransactionReference(hashedTxn.ordinal, hashedTxn.hash)))
        hashedBlock <- block.copy(value = block.value.copy(transactions = block.transactions.add(blockTxn))).toHashed[IO]
        isRelated = (block: Signed[Block]) => BlockRelations.dependsOn[IO](hashedBlock)(block)
        actual <- isRelated(notRelatedBlock)
      } yield expect.same(false, actual)
    }
  }

  test("when first block sends transaction to second then block is dependent") { implicit res =>
    forall { (block: Signed[Block], notRelatedBlock: Signed[Block]) =>
      for {
        hashedBlock <- block.toHashed[IO]
        isRelated = (block: Signed[Block]) => BlockRelations.dependsOn[IO](hashedBlock)(block)
        txn = block.transactions.head
        relatedTxn = notRelatedBlock.transactions.head.copy(value = notRelatedBlock.transactions.head.value.copy(source = txn.destination))
        relatedBlock = notRelatedBlock.copy(value = notRelatedBlock.value.copy(transactions = notRelatedBlock.transactions.add(relatedTxn)))
        actual <- isRelated(relatedBlock)
      } yield expect.same(true, actual)
    }
  }

  test("when blocks is related with block reference then block is dependent") { implicit res =>
    forall { (block: Signed[Block], notRelatedBlock: Signed[Block]) =>
      for {
        hashedBlock <- block.toHashed[IO]
        isRelated = (block: Signed[Block]) => BlockRelations.dependsOn[IO](Set.empty[Hashed[Block]], Set(hashedBlock.ownReference))(block)
        relatedBlock = notRelatedBlock.copy(value =
          notRelatedBlock.value.copy(parent = hashedBlock.ownReference :: notRelatedBlock.value.parent)
        )
        actual <- isRelated(relatedBlock)
      } yield expect.same(true, actual)
    }
  }

}
