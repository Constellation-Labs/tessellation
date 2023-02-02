package org.tessellation.dag.l1.domain.transaction

import java.security.KeyPair

import cats.data.{NonEmptyList, NonEmptySet}
import cats.effect.{IO, Resource}
import cats.syntax.option._

import org.tessellation.dag.l1.Main
import org.tessellation.dag.l1.domain.transaction.TransactionStorage.{Accepted, LastTransactionReferenceState, Majority}
import org.tessellation.dag.transaction.TransactionGenerator
import org.tessellation.ext.cats.effect.ResourceIO
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.address.Address
import org.tessellation.schema.transaction._
import org.tessellation.sdk.sdkKryoRegistrar
import org.tessellation.security.key.ops.PublicKeyOps
import org.tessellation.security.{Hashed, KeyPairGenerator, SecurityProvider}

import eu.timepit.refined.auto._
import io.chrisdavenport.mapref.MapRef
import weaver.SimpleIOSuite

object TransactionStorageSuite extends SimpleIOSuite with TransactionGenerator {

  type TestResources = (
    TransactionStorage[IO],
    MapRef[IO, Address, Option[LastTransactionReferenceState]],
    MapRef[IO, Address, Option[NonEmptySet[Hashed[DAGTransaction]]]],
    KeyPair,
    Address,
    KeyPair,
    Address,
    SecurityProvider[IO],
    KryoSerializer[IO]
  )

  def testResources: Resource[IO, TestResources] =
    SecurityProvider.forAsync[IO].flatMap { implicit sp =>
      KryoSerializer.forAsync[IO](Main.kryoRegistrar ++ sdkKryoRegistrar).flatMap { implicit kp =>
        for {
          lastAccepted <- MapRef.ofConcurrentHashMap[IO, Address, LastTransactionReferenceState]().asResource
          waitingTransactions <- MapRef.ofConcurrentHashMap[IO, Address, NonEmptySet[Hashed[DAGTransaction]]]().asResource
          transactionStorage = new TransactionStorage[IO](lastAccepted, waitingTransactions)
          key1 <- KeyPairGenerator.makeKeyPair.asResource
          address1 = key1.getPublic.toAddress
          key2 <- KeyPairGenerator.makeKeyPair.asResource
          address2 = key2.getPublic.toAddress
        } yield (transactionStorage, lastAccepted, waitingTransactions, key1, address1, key2, address2, sp, kp)
      }
    }

  test("pull should take transactions in correct order minding the fees") {
    testResources.use {
      case (transactionStorage, _, _, key1, address1, key2, address2, sp, kp) =>
        implicit val securityProvider = sp
        implicit val kryoPool = kp

        for {
          txsA <- generateTransactions(address1, key1, address2, 2, TransactionFee(3L))
          txsB <- generateTransactions(address2, key2, address1, 2, TransactionFee(2L))
          txsA2 <- generateTransactions(
            address1,
            key1,
            address2,
            2,
            TransactionFee(1L),
            Some(TransactionReference(txsA.last.ordinal, txsA.last.hash))
          )
          txsA3 <- generateTransactions(
            address1,
            key1,
            address2,
            2,
            TransactionFee.zero,
            Some(TransactionReference(txsA2.last.ordinal, txsA2.last.hash))
          )
          _ <- transactionStorage.put((txsA.toList ::: txsA2.toList ::: txsA3.toList ::: txsB.toList).toSet)

          pulled <- transactionStorage.pull(10L)

        } yield expect.same(NonEmptyList.fromList(txsA.toList ::: txsB.toList ::: txsA2.toList), pulled)
    }
  }

  test("pull should take one feeless transaction per address when last tx ref is in majority") {
    testResources.use {
      case (transactionStorage, lastAcceptedR, _, key1, address1, key2, address2, sp, kp) =>
        implicit val securityProvider = sp
        implicit val kryoPool = kp

        for {
          txsA <- generateTransactions(address1, key1, address2, 2, TransactionFee(0L))
          txsB <- generateTransactions(address2, key2, address1, 2, TransactionFee(0L))
          _ <- lastAcceptedR(address1).set(Majority(TransactionReference.of(txsA.head)).some)
          _ <- lastAcceptedR(address2).set(Majority(TransactionReference.of(txsB.head)).some)
          _ <- transactionStorage.put(Set(txsA.last, txsB.last))

          pulled <- transactionStorage.pull(10L)

        } yield expect.same(NonEmptyList.of(txsA.last, txsB.last).sortBy(_.source).some, pulled)
    }
  }

  test("pull should not take the feeless transactions when the last tx ref is not in majority") {
    testResources.use {
      case (transactionStorage, lastAcceptedR, _, key1, address1, key2, address2, sp, kp) =>
        implicit val securityProvider = sp
        implicit val kryoPool = kp

        for {
          txsA <- generateTransactions(address1, key1, address2, 2, TransactionFee(0L))
          txsB <- generateTransactions(address2, key2, address1, 2, TransactionFee(0L))
          _ <- lastAcceptedR(address1).set(Accepted(TransactionReference.of(txsA.head)).some)
          _ <- lastAcceptedR(address2).set(Accepted(TransactionReference.of(txsB.head)).some)
          _ <- transactionStorage.put(Set(txsA.last, txsB.last))

          pulled <- transactionStorage.pull(10L)

        } yield expect.same(None, pulled)
    }
  }

  test("pull should be able to take both fee and feeless transactions in one pull") {
    testResources.use {
      case (transactionStorage, _, _, key1, address1, key2, address2, sp, kp) =>
        implicit val securityProvider = sp
        implicit val kryoPool = kp

        for {
          txsA <- generateTransactions(address1, key1, address2, 2, TransactionFee(1L))
          txsB <- generateTransactions(address2, key2, address1, 1, TransactionFee(0L))
          _ <- transactionStorage.put((txsA.toList ::: txsB.toList).toSet)

          pulled <- transactionStorage.pull(10L)

        } yield expect.same(txsA.append(txsB.head).some, pulled)
    }
  }

  test("pull should favour transactions with higher fees when forming transaction chain") {
    testResources.use {
      case (transactionStorage, _, _, key1, address1, _, address2, sp, kp) =>
        implicit val securityProvider = sp
        implicit val kryoPool = kp

        for {
          txsA <- generateTransactions(address1, key1, address2, 2, TransactionFee(1L))
          txsA2 <- generateTransactions(
            address1,
            key1,
            address2,
            1,
            TransactionFee(2L),
            TransactionReference.of(txsA.head).some
          )
          _ <- transactionStorage.put((txsA.toList ::: txsA2.toList).toSet)

          pulled <- transactionStorage.pull(10L)

        } yield expect.same(NonEmptyList.of(txsA.head, txsA2.head).some, pulled)
    }
  }

  test("pull should limit transactions count to specified value") {
    testResources.use {
      case (transactionStorage, _, _, key1, address1, key2, address2, sp, kp) =>
        implicit val securityProvider = sp
        implicit val kryoPool = kp

        for {
          txsA <- generateTransactions(address1, key1, address2, 2, TransactionFee(3L))
          txsB <- generateTransactions(address2, key2, address1, 2, TransactionFee(2L))
          txsA2 <- generateTransactions(
            address1,
            key1,
            address2,
            2,
            TransactionFee(1L),
            Some(TransactionReference(txsA.last.ordinal, txsA.last.hash))
          )
          _ <- transactionStorage.put((txsA.toList ::: txsA2.toList ::: txsB.toList).toSet)

          pulled <- transactionStorage.pull(4L)

        } yield expect.same(NonEmptyList.fromList(txsA.toList ::: txsB.toList), pulled)
    }
  }
}
