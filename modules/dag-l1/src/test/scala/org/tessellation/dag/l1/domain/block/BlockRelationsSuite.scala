package org.tessellation.dag.l1.domain.block

import cats.effect.{IO, Resource}

import org.tessellation.dag.domain.block.DAGBlock
import org.tessellation.dag.domain.block.generators._
import org.tessellation.dag.l1.Main
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.transaction
import org.tessellation.sdk.sdkKryoRegistrar
import org.tessellation.security.signature.Signed

import weaver.MutableIOSuite
import weaver.scalacheck.Checkers

object BlockRelationsSuite extends MutableIOSuite with Checkers {

  type Res = KryoSerializer[IO]

  override def sharedResource: Resource[IO, BlockServiceSuite.Res] =
    KryoSerializer.forAsync[IO](Main.kryoRegistrar ++ sdkKryoRegistrar)

  test("when no relation between blocks then block is independent") { implicit ks =>
    forall { (block: Signed[DAGBlock], notRelatedBlock: Signed[DAGBlock]) =>
      for {
        hashedBlock <- block.toHashed[IO]
        isRelated = (block: Signed[DAGBlock]) => BlockRelations.dependsOn(hashedBlock)(block)
        actual <- isRelated(notRelatedBlock)
      } yield expect.same(false, actual)
    }
  }

  test("when first block is parent of second then block is dependent") { implicit ks =>
    forall { (block: Signed[DAGBlock], notRelatedBlock: Signed[DAGBlock]) =>
      for {
        hashedBlock <- block.toHashed[IO]
        isRelated = (block: Signed[DAGBlock]) => BlockRelations.dependsOn(hashedBlock)(block)
        relatedBlock = notRelatedBlock.copy(value =
          notRelatedBlock.value.copy(parent = hashedBlock.ownReference :: notRelatedBlock.value.parent)
        )
        actual <- isRelated(relatedBlock)
      } yield expect.same(true, actual)
    }
  }

  test("when second block is parent of first then block is independent") { implicit ks =>
    forall { (block: Signed[DAGBlock], notRelatedBlock: Signed[DAGBlock]) =>
      for {
        notRelatedHashedBlock <- notRelatedBlock.toHashed[IO]
        hashedBlock <- block.copy(value = block.value.copy(parent = notRelatedHashedBlock.ownReference :: block.value.parent)).toHashed[IO]
        isRelated = (block: Signed[DAGBlock]) => BlockRelations.dependsOn(hashedBlock)(block)
        actual <- isRelated(notRelatedBlock)
      } yield expect.same(false, actual)
    }
  }

  test("when first block has transaction reference used in second then block is dependent") { implicit ks =>
    forall { (block: Signed[DAGBlock], notRelatedBlock: Signed[DAGBlock]) =>
      for {
        hashedBlock <- block.toHashed[IO]
        isRelated = (block: Signed[DAGBlock]) => BlockRelations.dependsOn(hashedBlock)(block)
        hashedTxn <- block.transactions.head.toHashed[IO]
        relatedTxn = notRelatedBlock.transactions.head.copy(value =
          notRelatedBlock.transactions.head.value.copy(parent = transaction.TransactionReference(hashedTxn.ordinal, hashedTxn.hash))
        )
        relatedBlock = notRelatedBlock.copy(value = notRelatedBlock.value.copy(transactions = notRelatedBlock.transactions.add(relatedTxn)))
        actual <- isRelated(relatedBlock)
      } yield expect.same(true, actual)
    }
  }

  test("when second block has transaction reference used in first then block is independent") { implicit ks =>
    forall { (block: Signed[DAGBlock], notRelatedBlock: Signed[DAGBlock]) =>
      for {
        hashedTxn <- notRelatedBlock.transactions.head.toHashed[IO]
        blockTxn = block.transactions.head
          .copy(value = block.transactions.head.value.copy(parent = transaction.TransactionReference(hashedTxn.ordinal, hashedTxn.hash)))
        hashedBlock <- block.copy(value = block.value.copy(transactions = block.transactions.add(blockTxn))).toHashed[IO]
        isRelated = (block: Signed[DAGBlock]) => BlockRelations.dependsOn(hashedBlock)(block)
        actual <- isRelated(notRelatedBlock)
      } yield expect.same(false, actual)
    }
  }

  test("when first block sends transaction to second then block is dependent") { implicit ks =>
    forall { (block: Signed[DAGBlock], notRelatedBlock: Signed[DAGBlock]) =>
      for {
        hashedBlock <- block.toHashed[IO]
        isRelated = (block: Signed[DAGBlock]) => BlockRelations.dependsOn(hashedBlock)(block)
        txn = block.transactions.head
        relatedTxn = notRelatedBlock.transactions.head.copy(value = notRelatedBlock.transactions.head.value.copy(source = txn.destination))
        relatedBlock = notRelatedBlock.copy(value = notRelatedBlock.value.copy(transactions = notRelatedBlock.transactions.add(relatedTxn)))
        actual <- isRelated(relatedBlock)
      } yield expect.same(true, actual)
    }
  }

  test("when blocks is related with block reference then block is dependent") { implicit ks =>
    forall { (block: Signed[DAGBlock], notRelatedBlock: Signed[DAGBlock]) =>
      for {
        hashedBlock <- block.toHashed[IO]
        isRelated = (block: Signed[DAGBlock]) => BlockRelations.dependsOn(Set.empty, Set(hashedBlock.ownReference))(block)
        relatedBlock = notRelatedBlock.copy(value =
          notRelatedBlock.value.copy(parent = hashedBlock.ownReference :: notRelatedBlock.value.parent)
        )
        actual <- isRelated(relatedBlock)
      } yield expect.same(true, actual)
    }
  }

}
