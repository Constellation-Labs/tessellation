package io.constellationnetwork.dag.l1.domain.transaction

import java.security.KeyPair

import cats.data.NonEmptyList
import cats.effect.{IO, Resource}
import cats.syntax.all._

import scala.collection.immutable.SortedMap
import scala.concurrent.duration.DurationInt

import io.constellationnetwork.dag.l1.Main
import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.node.shared.nodeSharedKryoRegistrar
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Balance
import io.constellationnetwork.schema.transaction._
import io.constellationnetwork.security._
import io.constellationnetwork.security.key.ops.PublicKeyOps
import io.constellationnetwork.transaction.TransactionGenerator

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
    Hasher[IO],
    KeyPair,
    Address
  )

  def testResources: Resource[IO, TestResources] =
    SecurityProvider.forAsync[IO].flatMap { implicit sp =>
      KryoSerializer.forAsync[IO](Main.kryoRegistrar ++ nodeSharedKryoRegistrar).flatMap { implicit kp =>
        for {
          implicit0(jhs: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO].asResource
          implicit0(h: Hasher[IO]) = Hasher.forJson[IO]
          transactions <- MapRef.ofConcurrentHashMap[IO, Address, SortedMap[TransactionOrdinal, StoredTransaction]]().asResource
          contextualTransactionValidator = ContextualTransactionValidator
            .make(TransactionLimitConfig(Balance(100000000L), 20.hours, TransactionFee(200000L), 43.seconds), None)
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
        } yield (transactionStorage, transactions, key1, address1, key2, address2, sp, h, Hasher.forKryo[IO], key3, address3)
      }
    }

  test("setting initial refs should fail if already set") {
    testResources.use {
      case (transactionStorage, transactionR, _, address1, _, _, _, _, _, _, _) =>
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
      case (transactionStorage, _, _, address1, _, _, _, _, _, _, _) =>
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
      case (transactionStorage, _, key1, address1, key2, address2, sp, h, txHasher, key3, address3) =>
        implicit val securityProvider = sp
        implicit val hasher = h

        for {

          txsA <- generateTransactions(address1, key1, address2, 2, TransactionFee(3L), kHasher = txHasher, jHasher = h)

          txsB <- generateTransactions(address2, key2, address1, 2, TransactionFee(2L), kHasher = txHasher, jHasher = h)

          txsC <- generateTransactions(address3, key3, address2, 2, TransactionFee.zero, kHasher = txHasher, jHasher = h)

          txsA2 <- generateTransactions(
            address1,
            key1,
            address2,
            2,
            TransactionFee(1L),
            Some(TransactionReference(txsA.last.ordinal, txsA.last.hash)),
            kHasher = txHasher,
            jHasher = h
          )
          _ <- (txsC.toList ::: txsA.toList ::: txsA2.toList ::: txsB.toList).distinct
            .traverse(transactionStorage.tryPut(_, SnapshotOrdinal.MinValue, Balance(NonNegLong.MaxValue)))

          pulled <- transactionStorage.pull(6L)

        } yield expect.same(NonEmptyList.fromList(txsA.toList ::: txsB.toList ::: txsA2.toList), pulled)
    }
  }

  test("pull should take parent-closed transactions before higher-fee children") {
    testResources.use {
      case (transactionStorage, _, key1, address1, key2, address2, sp, h, txHasher, key3, address3) =>
        implicit val securityProvider = sp
        implicit val hasher = h

        for {

          txsA <- generateTransactions(address1, key1, address2, 1, TransactionFee(10L), kHasher = txHasher, jHasher = h)

          txsB <- generateTransactions(
            address2,
            key2,
            address3,
            100,
            TransactionFee(1L),
            kHasher = txHasher,
            jHasher = h
          )

          txsBHigherFee <- generateTransactions(
            address2,
            key2,
            address3,
            1,
            TransactionFee(8L),
            Some(TransactionReference(txsB.last.ordinal, txsB.last.hash)),
            kHasher = txHasher,
            jHasher = h
          )

          _ <- (txsA.toList ::: txsB.toList ::: txsBHigherFee.toList).distinct
            .traverse(transactionStorage.tryPut(_, SnapshotOrdinal.MinValue, Balance(NonNegLong.MaxValue)))

          pulled <- transactionStorage.pull(50L)
        } yield expect.same(NonEmptyList.fromList(txsA.toList ::: txsB.take(49)), pulled)
    }
  }

  test("pull should be able to take both fee and feeless transactions in one pull") {
    testResources.use {
      case (transactionStorage, _, key1, address1, key2, address2, sp, h, txHasher, _, _) =>
        implicit val securityProvider = sp
        implicit val hasher = h

        for {
          txsA <- generateTransactions(address1, key1, address2, 2, TransactionFee(1L), kHasher = txHasher, jHasher = h)
          txsB <- generateTransactions(address2, key2, address1, 1, TransactionFee(0L), kHasher = txHasher, jHasher = h)
          _ <- (txsA.toList ::: txsB.toList).distinct
            .traverse(transactionStorage.tryPut(_, SnapshotOrdinal.MinValue, Balance(NonNegLong.MaxValue)))

          pulled <- transactionStorage.pull(10L)

        } yield expect.same(txsA.append(txsB.head).some, pulled)
    }
  }

  test("pull should limit transactions count to specified value") {
    testResources.use {
      case (transactionStorage, _, key1, address1, key2, address2, sp, h, txHasher, _, _) =>
        implicit val securityProvider = sp
        implicit val hasher = h

        for {
          txsA <- generateTransactions(address1, key1, address2, 2, TransactionFee(3L), kHasher = txHasher, jHasher = h)
          txsB <- generateTransactions(address2, key2, address1, 2, TransactionFee(2L), kHasher = txHasher, jHasher = h)
          txsA2 <- generateTransactions(
            address1,
            key1,
            address2,
            2,
            TransactionFee(1L),
            Some(TransactionReference(txsA.last.ordinal, txsA.last.hash)),
            kHasher = txHasher,
            jHasher = h
          )
          _ <- (txsA.toList ::: txsA2.toList ::: txsB.toList).distinct
            .traverse(transactionStorage.tryPut(_, SnapshotOrdinal.MinValue, Balance(NonNegLong.MaxValue)))

          pulled <- transactionStorage.pull(4L)

        } yield expect.same(NonEmptyList.fromList(txsA.toList ::: txsB.toList), pulled)
    }
  }

  // Regression (B3): replaceByRefs (the RedownloadNeeded reset) must PRESERVE in-flight Waiting/Processing txs
  // strictly above the new majority ref. The old blind `.set` wiped a ProcessingTx that a concurrently-cancelled
  // consensus round was about to return via putBack (putBack only restores ProcessingTx), silently losing a valid
  // in-flight client tx.
  test("replaceByRefs preserves an in-flight ProcessingTx above the new majority ref") {
    testResources.use {
      case (transactionStorage, _, key1, address1, _, address2, sp, h, txHasher, _, _) =>
        implicit val securityProvider = sp
        implicit val hasher = h

        for {
          txsA <- generateTransactions(address1, key1, address2, 2, TransactionFee(1L), kHasher = txHasher, jHasher = h)
          _ <- txsA.toList.traverse(transactionStorage.tryPut(_, SnapshotOrdinal.MinValue, Balance(NonNegLong.MaxValue)))
          // Waiting -> Processing for the whole chain (as a started consensus round would do).
          _ <- transactionStorage.pull(10L)
          // Majority advanced only to the FIRST tx; the second is still an in-flight ProcessingTx.
          majorityRef = TransactionReference(txsA.head.ordinal, txsA.head.hash)
          _ <- transactionStorage.replaceByRefs(Map(address1 -> majorityRef), SnapshotOrdinal.MinValue)
          state <- transactionStorage.getState
          stored = state.getOrElse(address1, SortedMap.empty[TransactionOrdinal, StoredTransaction])
        } yield
          expect.all(
            stored.get(txsA.head.ordinal).exists { case _: MajorityTx => true; case _ => false },
            stored.get(txsA.last.ordinal).exists { case _: ProcessingTx => true; case _ => false }
          )
    }
  }
}
