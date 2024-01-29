package org.tessellation.dag.l1.domain.transaction

import java.security.KeyPair

import cats.data.NonEmptyList
import cats.effect.{IO, Resource}
import cats.syntax.all._

import scala.collection.immutable.SortedMap
import scala.concurrent.duration.DurationInt

import org.tessellation.dag.l1.Main
import org.tessellation.ext.cats.effect.ResourceIO
import org.tessellation.json.JsonSerializer
import org.tessellation.kryo.KryoSerializer
import org.tessellation.node.shared.nodeSharedKryoRegistrar
import org.tessellation.schema.SnapshotOrdinal
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Balance
import org.tessellation.schema.transaction._
import org.tessellation.security._
import org.tessellation.security.key.ops.PublicKeyOps
import org.tessellation.transaction.TransactionGenerator

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong
import io.chrisdavenport.mapref.MapRef
import weaver.SimpleIOSuite

object TransactionStorageSuite extends SimpleIOSuite with TransactionGenerator {

  type TestResources = (
    TransactionStorage[IO],
    MapRef[IO, Address, Option[SortedMap[TransactionOrdinal, StoredTransaction]]],
    KeyPair,
    Address,
    KeyPair,
    Address,
    SecurityProvider[IO],
    Hasher[IO],
    KeyPair,
    Address
  )

  def testResources: Resource[IO, TestResources] =
    SecurityProvider.forAsync[IO].flatMap { implicit sp =>
      KryoSerializer.forAsync[IO](Main.kryoRegistrar ++ nodeSharedKryoRegistrar).flatMap { implicit kp =>
        for {
          implicit0(jhs: JsonSerializer[IO]) <- JsonSerializer.forSync[IO].asResource
          implicit0(h: Hasher[IO]) = Hasher.forSync[IO]((_: SnapshotOrdinal) => JsonHash)
          transactions <- MapRef.ofConcurrentHashMap[IO, Address, SortedMap[TransactionOrdinal, StoredTransaction]]().asResource
          contextualTransactionValidator = ContextualTransactionValidator
            .make(TransactionLimitConfig(Balance(100000000L), 20.hours, TransactionFee(200000L), 43.seconds))
          transactionStorage = new TransactionStorage[IO](
            transactions,
            TransactionReference.empty,
            contextualTransactionValidator
          )
          key1 <- KeyPairGenerator.makeKeyPair.asResource
          address1 = key1.getPublic.toAddress
          key2 <- KeyPairGenerator.makeKeyPair.asResource
          address2 = key2.getPublic.toAddress
          key3 <- KeyPairGenerator.makeKeyPair.asResource
          address3 = key3.getPublic.toAddress
        } yield (transactionStorage, transactions, key1, address1, key2, address2, sp, h, key3, address3)
      }
    }

  test("setting initial refs should fail if already set") {
    testResources.use {
      case (transactionStorage, transactionR, _, address1, _, _, _, _, _, _) =>
        for {
          _ <- transactionR(address1).set(SortedMap.empty[TransactionOrdinal, StoredTransaction].some)

          result <- transactionStorage
            .initByRefs(
              Map(address1 -> TransactionReference.empty),
              SnapshotOrdinal.MinValue
            )
            .attempt

        } yield expect(result.isLeft)
    }
  }

  test("setting initial refs should succeed if not already set") {
    testResources.use {
      case (transactionStorage, _, _, address1, _, _, _, _, _, _) =>
        val ordinal = SnapshotOrdinal.MinValue
        val ref = TransactionReference.empty
        for {
          _ <- transactionStorage
            .initByRefs(Map(address1 -> ref), ordinal)
          txs <- transactionStorage.getState

          expected = Map(address1 -> SortedMap(ref.ordinal -> MajorityTx(ref, ordinal)))
        } yield expect.eql(txs, expected)
    }
  }

  test("pull should take transactions in correct order minding the fees") {
    testResources.use {
      case (transactionStorage, _, key1, address1, key2, address2, sp, h, key3, address3) =>
        implicit val securityProvider = sp
        implicit val hasher = h

        for {

          txsA <- generateTransactions(address1, key1, address2, 2, TransactionFee(3L))

          txsB <- generateTransactions(address2, key2, address1, 2, TransactionFee(2L))

          txsC <- generateTransactions(address3, key3, address2, 2, TransactionFee.zero)

          txsA2 <- generateTransactions(
            address1,
            key1,
            address2,
            2,
            TransactionFee(1L),
            Some(TransactionReference(txsA.last.ordinal, txsA.last.hash))
          )
          _ <- (txsC.toList ::: txsA.toList ::: txsA2.toList ::: txsB.toList).distinct
            .traverse(transactionStorage.tryPut(_, SnapshotOrdinal.MinValue, Balance(NonNegLong.MaxValue)))

          pulled <- transactionStorage.pull(6L)

        } yield expect.same(NonEmptyList.fromList(txsA.toList ::: txsB.toList ::: txsA2.toList), pulled)
    }
  }

  test("pull should be able to take both fee and feeless transactions in one pull") {
    testResources.use {
      case (transactionStorage, _, key1, address1, key2, address2, sp, h, _, _) =>
        implicit val securityProvider = sp
        implicit val hasher = h

        for {
          txsA <- generateTransactions(address1, key1, address2, 2, TransactionFee(1L))
          txsB <- generateTransactions(address2, key2, address1, 1, TransactionFee(0L))
          _ <- (txsA.toList ::: txsB.toList).distinct
            .traverse(transactionStorage.tryPut(_, SnapshotOrdinal.MinValue, Balance(NonNegLong.MaxValue)))

          pulled <- transactionStorage.pull(10L)

        } yield expect.same(txsA.append(txsB.head).some, pulled)
    }
  }

  test("pull should limit transactions count to specified value") {
    testResources.use {
      case (transactionStorage, _, key1, address1, key2, address2, sp, h, _, _) =>
        implicit val securityProvider = sp
        implicit val hasher = h

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
          _ <- (txsA.toList ::: txsA2.toList ::: txsB.toList).distinct
            .traverse(transactionStorage.tryPut(_, SnapshotOrdinal.MinValue, Balance(NonNegLong.MaxValue)))

          pulled <- transactionStorage.pull(4L)

        } yield expect.same(NonEmptyList.fromList(txsA.toList ::: txsB.toList), pulled)
    }
  }
}
