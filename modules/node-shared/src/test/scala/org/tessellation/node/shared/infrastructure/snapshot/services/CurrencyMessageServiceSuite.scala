package org.tessellation.node.shared.infrastructure.snapshot.services

import cats.Eq
import cats.effect.{IO, Resource}
import cats.syntax.applicative._
import cats.syntax.eq._
import cats.syntax.option._
import cats.syntax.validated._

import org.tessellation.ext.cats.effect.ResourceIO
import org.tessellation.json.JsonSerializer
import org.tessellation.kryo.KryoSerializer
import org.tessellation.node.shared.domain.seedlist.SeedlistEntry
import org.tessellation.node.shared.infrastructure.snapshot.services.CurrencyMessageService.{OlderOrdinalError, ValidationError}
import org.tessellation.node.shared.infrastructure.snapshot.storage.CurrencyMessageStorage
import org.tessellation.schema.currencyMessage.{CurrencyMessage, MessageOrdinal, MessageType}
import org.tessellation.schema.generators.{addressGen, signedOf}
import org.tessellation.schema.{SnapshotOrdinal, currencyMessage}
import org.tessellation.security._
import org.tessellation.security.signature.SignedValidator.NotEnoughSeedlistSignatures
import org.tessellation.security.signature.{Signed, SignedValidator}
import org.tessellation.shared.sharedKryoRegistrar

import eu.timepit.refined.types.numeric.NonNegLong
import org.scalacheck.Gen
import weaver.MutableIOSuite
import weaver.scalacheck.Checkers

object CurrencyMessageServiceSuite extends MutableIOSuite with Checkers {
  type Res = (JsonSerializer[IO], Hasher[IO], SecurityProvider[IO])

  override def sharedResource: Resource[IO, Res] =
    for {
      implicit0(ks: KryoSerializer[IO]) <- KryoSerializer.forAsync[IO](sharedKryoRegistrar)
      implicit0(sp: SecurityProvider[IO]) <- SecurityProvider.forAsync[IO]
      implicit0(j: JsonSerializer[IO]) <- JsonSerializer.forSync[IO].asResource
      h = Hasher.forSync[IO](new HashSelect { def select(ordinal: SnapshotOrdinal): HashLogic = JsonHash })
    } yield (j, h, sp)

  val currencyMessageGen: Gen[Signed[CurrencyMessage]] =
    signedOf(
      for {
        typ <- Gen.oneOf(MessageType.values)
        addr <- addressGen
        ord = MessageOrdinal(NonNegLong.unsafeFrom(0L))
      } yield CurrencyMessage(typ, addr, ord)
    )

  val passingSeedlist = None
  val failingSeedlistSet = Set.empty[SeedlistEntry].some

  test("valid CurrencyMessage is stored") { res =>
    implicit val (_, h, sp) = res

    forall(currencyMessageGen) { message =>
      for {
        storage <- mockStorage(storageResult(message, true)).pure[IO]
        validator = SignedValidator.make[IO]
        service = CurrencyMessageService.make[IO](validator, passingSeedlist, storage)

        actual <- service.offer(message)

      } yield expect.same(().validNec, actual)
    }
  }

  test("invalid CurrencyMessage is rejected") { res =>
    implicit val (_, h, sp) = res

    forall(currencyMessageGen) { message =>
      for {
        storage <- mockStorage().pure[IO]
        validator = SignedValidator.make[IO]
        service = CurrencyMessageService.make[IO](validator, failingSeedlistSet, storage)

        actual <- service.offer(message)
        expected = ValidationError(NotEnoughSeedlistSignatures(0, 1)).invalidNec

      } yield expect.same(expected, actual)
    }
  }

  test("valid CurrencyMessage is not stored if ordinal <= stored value") { res =>
    implicit val (_, h, sp) = res

    forall(currencyMessageGen) { message =>
      for {
        storage <- mockStorage(storageResult(message, false)).pure[IO]
        validator = SignedValidator.make[IO]
        service = CurrencyMessageService.make[IO](validator, passingSeedlist, storage)

        actual <- service.offer(message)
        expected = OlderOrdinalError.invalidNec

      } yield expect.same(expected, actual)
    }
  }

  def storageResult[A: Eq](expected: A, result: Boolean): A => IO[Boolean] =
    actual => if (expected === actual) result.pure[IO] else IO.raiseError(new Exception("unexpected arg"))

  def unexpectedCall[A](fName: String): A => IO[Boolean] =
    _ => IO.raiseError(new Exception(s"$fName call unexpected"))

  def mockStorage(
    setFn: Signed[CurrencyMessage] => IO[Boolean] = unexpectedCall("set")
  ): CurrencyMessageStorage[IO] = new CurrencyMessageStorage[IO] {
    override def get(messageType: currencyMessage.MessageType): IO[Option[Signed[CurrencyMessage]]] = ???

    override def set(message: Signed[CurrencyMessage]): IO[Boolean] = setFn(message)
  }
}
