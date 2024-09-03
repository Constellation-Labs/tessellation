package io.constellationnetwork.dag.l1.domain.block

import cats.effect.{IO, Resource}

import io.constellationnetwork.block.generators._
import io.constellationnetwork.dag.l1.Main
import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.node.shared.nodeSharedKryoRegistrar
import io.constellationnetwork.schema.{Block, transaction}
import io.constellationnetwork.security._
import io.constellationnetwork.security.signature.Signed

import weaver.MutableIOSuite
import weaver.scalacheck.Checkers

object BlockRelationsSuite extends MutableIOSuite with Checkers {

  type Res = (Hasher[IO], Hasher[IO])

  def sharedResource: Resource[IO, Res] = for {
    implicit0(ks: KryoSerializer[IO]) <- KryoSerializer.forAsync[IO](Main.kryoRegistrar ++ nodeSharedKryoRegistrar)
    implicit0(j: JsonSerializer[IO]) <- JsonSerializer.forSync[IO].asResource
    hj = Hasher.forJson[IO]
    hk = Hasher.forKryo[IO]
  } yield (hj, hk)

  test("when no relation between blocks then block is independent") {
    case (currentHasher, txHasher) =>
      implicit val hasher = currentHasher

      forall { (block: Signed[Block], notRelatedBlock: Signed[Block]) =>
        for {
          hashedBlock <- block.toHashed[IO]
          isRelated = (block: Signed[Block]) => BlockRelations.dependsOn[IO](hashedBlock, txHasher = txHasher)(block)
          actual <- isRelated(notRelatedBlock)
        } yield expect.same(false, actual)
      }
  }

  test("when first block is parent of second then block is dependent") {
    case (currentHasher, txHasher) =>
      implicit val hasher = currentHasher

      forall { (block: Signed[Block], notRelatedBlock: Signed[Block]) =>
        for {
          hashedBlock <- block.toHashed[IO]
          isRelated = (block: Signed[Block]) => BlockRelations.dependsOn[IO](hashedBlock, txHasher = txHasher)(block)
          relatedBlock = notRelatedBlock.copy(value =
            notRelatedBlock.value.copy(parent = hashedBlock.ownReference :: notRelatedBlock.value.parent)
          )
          actual <- isRelated(relatedBlock)
        } yield expect.same(true, actual)
      }
  }

  test("when second block is parent of first then block is independent") {
    case (currentHasher, txHasher) =>
      implicit val hasher = currentHasher

      forall { (block: Signed[Block], notRelatedBlock: Signed[Block]) =>
        for {
          notRelatedHashedBlock <- notRelatedBlock.toHashed[IO]
          hashedBlock <- block
            .copy(value = block.value.copy(parent = notRelatedHashedBlock.ownReference :: block.value.parent))
            .toHashed[IO]
          isRelated = (block: Signed[Block]) => BlockRelations.dependsOn[IO](hashedBlock, txHasher = txHasher)(block)
          actual <- isRelated(notRelatedBlock)
        } yield expect.same(false, actual)
      }
  }

  test("when first block has transaction reference used in second then block is dependent") {
    case (currentHasher, txHasher) =>
      forall { (block: Signed[Block], notRelatedBlock: Signed[Block]) =>
        for {
          hashedBlock <- {
            implicit val hasher = currentHasher
            block.toHashed[IO]
          }
          isRelated = (block: Signed[Block]) => BlockRelations.dependsOn[IO](hashedBlock, txHasher = txHasher)(block)
          hashedTxn <- {
            implicit val hasher = txHasher
            block.transactions.head.toHashed[IO]
          }
          relatedTxn = notRelatedBlock.transactions.head.copy(value =
            notRelatedBlock.transactions.head.value.copy(parent = transaction.TransactionReference(hashedTxn.ordinal, hashedTxn.hash))
          )
          relatedBlock = notRelatedBlock.copy(value =
            notRelatedBlock.value.copy(transactions = notRelatedBlock.transactions.add(relatedTxn))
          )
          actual <- isRelated(relatedBlock)
        } yield expect.same(true, actual)
      }
  }

  test("when second block has transaction reference used in first then block is independent") {
    case (currentHasher, txHasher) =>
      forall { (block: Signed[Block], notRelatedBlock: Signed[Block]) =>
        for {
          hashedTxn <- {
            implicit val hasher = txHasher
            notRelatedBlock.transactions.head.toHashed[IO]
          }
          blockTxn = block.transactions.head
            .copy(value = block.transactions.head.value.copy(parent = transaction.TransactionReference(hashedTxn.ordinal, hashedTxn.hash)))
          hashedBlock <- {
            implicit val hasher = currentHasher
            block.copy(value = block.value.copy(transactions = block.transactions.add(blockTxn))).toHashed[IO]
          }
          isRelated = (block: Signed[Block]) => BlockRelations.dependsOn[IO](hashedBlock, txHasher = txHasher)(block)
          actual <- isRelated(notRelatedBlock)
        } yield expect.same(false, actual)
      }
  }

  test("when first block sends transaction to second then block is dependent") {
    case (currentHasher, txHasher) =>
      implicit val hasher = currentHasher

      forall { (block: Signed[Block], notRelatedBlock: Signed[Block]) =>
        for {
          hashedBlock <- block.toHashed[IO]
          isRelated = (block: Signed[Block]) => BlockRelations.dependsOn[IO](hashedBlock, txHasher = txHasher)(block)
          txn = block.transactions.head
          relatedTxn = notRelatedBlock.transactions.head
            .copy(value = notRelatedBlock.transactions.head.value.copy(source = txn.destination))
          relatedBlock = notRelatedBlock.copy(value =
            notRelatedBlock.value.copy(transactions = notRelatedBlock.transactions.add(relatedTxn))
          )
          actual <- isRelated(relatedBlock)
        } yield expect.same(true, actual)
      }
  }

  test("when blocks is related with block reference then block is dependent") {
    case (currentHasher, txHasher) =>
      implicit val hasher = currentHasher

      forall { (block: Signed[Block], notRelatedBlock: Signed[Block]) =>
        for {
          hashedBlock <- block.toHashed[IO]
          isRelated = (block: Signed[Block]) =>
            BlockRelations.dependsOn[IO](Set.empty[Hashed[Block]], Set(hashedBlock.ownReference), txHasher = txHasher)(block)
          relatedBlock = notRelatedBlock.copy(value =
            notRelatedBlock.value.copy(parent = hashedBlock.ownReference :: notRelatedBlock.value.parent)
          )
          actual <- isRelated(relatedBlock)
        } yield expect.same(true, actual)
      }
  }

}
