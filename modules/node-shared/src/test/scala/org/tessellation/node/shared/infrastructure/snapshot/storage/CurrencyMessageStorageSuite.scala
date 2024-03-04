package org.tessellation.node.shared.infrastructure.snapshot.storage

import cats.effect.IO
import cats.syntax.option._
import cats.syntax.semigroup._
import cats.syntax.traverse._

import org.tessellation.ext.cats.syntax.next.catsSyntaxNext
import org.tessellation.schema.currencyMessage._
import org.tessellation.schema.generators._

import eu.timepit.refined.types.numeric.NonNegLong
import org.scalacheck.Gen
import weaver.SimpleMutableIOSuite
import weaver.scalacheck.Checkers

object CurrencyMessageStorageSuite extends SimpleMutableIOSuite with Checkers {

  def ordinalGen(min: Long = 0, max: Long = 10_000): Gen[MessageOrdinal] =
    Gen.chooseNum(min, max).map(n => MessageOrdinal(NonNegLong.unsafeFrom(n)))

  val currencyMessageGen: Gen[CurrencyMessage] =
    for {
      typ <- Gen.oneOf(MessageType.values)
      addr <- addressGen
      ord <- ordinalGen()
    } yield CurrencyMessage(typ, addr, ord)

  test("storage is initially empty") {
    for {
      storage <- CurrencyMessageStorage.make[IO]
      storedValues <- MessageType.values.toList.traverse(storage.get)
    } yield storedValues.map(expect.same(None, _)).reduce(_ |+| _)
  }

  test("set always succeeds on empty storage") {
    forall(signedOf(currencyMessageGen)) { nextMessage =>
      for {
        storage <- CurrencyMessageStorage.make[IO]
        result <- storage.set(nextMessage)
        actual <- storage.get(nextMessage.value.messageType)
      } yield expect.same(true, result) |+| expect.same(nextMessage.some, actual)
    }
  }

  test("set succeeds if new message parent ordinal is next of existing message parent ordinal") {
    val gen = for {
      a <- currencyMessageGen
      b = a.copy(parentOrdinal = a.parentOrdinal.next)
      sa <- signedOf(Gen.const(a))
      sb <- signedOf(Gen.const(b))
    } yield (sa, sb)

    forall(gen) {
      case (initialMsg, nextMessage) =>
        for {
          storage <- CurrencyMessageStorage.make[IO]
          _ <- storage.set(initialMsg)

          result <- storage.set(nextMessage)
          actual <- storage.get(initialMsg.value.messageType)
        } yield
          expect.same(true, result) |+|
            expect.same(nextMessage.some, actual)
    }
  }

  test("set fails for next message with lower or equal ordinal ") {
    val gen = for {
      a <- currencyMessageGen
      ord <- ordinalGen(0, a.parentOrdinal.value.value)
      b = a.copy(parentOrdinal = ord)
      sa <- signedOf(Gen.const(a))
      sb <- signedOf(Gen.const(b))
    } yield (sa, sb)

    forall(gen) {
      case (initialMsg, nextMessage) =>
        for {
          storage <- CurrencyMessageStorage.make[IO]
          _ <- storage.set(initialMsg)
          result <- storage.set(nextMessage)

          actual <- storage.get(nextMessage.value.messageType)
        } yield
          expect.same(false, result) |+|
            expect.same(initialMsg.some, actual)
    }
  }

  test("set fails for next message with parent ordinal too high ") {
    val gen = for {
      a <- currencyMessageGen
      ord <- ordinalGen(a.parentOrdinal.value.value + 2, 20_000)
      b = a.copy(parentOrdinal = ord)
      sa <- signedOf(Gen.const(a))
      sb <- signedOf(Gen.const(b))
    } yield (sa, sb)

    forall(gen) {
      case (initialMsg, nextMessage) =>
        for {
          storage <- CurrencyMessageStorage.make[IO]
          _ <- storage.set(initialMsg)
          result <- storage.set(nextMessage)

          actual <- storage.get(nextMessage.value.messageType)
        } yield
          expect.same(false, result) |+|
            expect.same(initialMsg.some, actual)
    }
  }
}
