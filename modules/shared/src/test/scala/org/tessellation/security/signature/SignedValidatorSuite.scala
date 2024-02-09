package org.tessellation.security.signature

import cats.data.{NonEmptySet, Validated}
import cats.effect.{IO, Resource}
import cats.syntax.validated._

import org.tessellation.ext.cats.effect.ResourceIO
import org.tessellation.ext.kryo._
import org.tessellation.json.JsonSerializer
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.SnapshotOrdinal
import org.tessellation.schema.peer.PeerId
import org.tessellation.security._
import org.tessellation.security.signature.Signed.forAsyncHasher
import org.tessellation.shared.sharedKryoRegistrar

import derevo.circe.magnolia.encoder
import derevo.derive
import eu.timepit.refined.auto._
import eu.timepit.refined.numeric.Interval
import weaver.MutableIOSuite

object SignedValidatorSuite extends MutableIOSuite {

  type Res = (Hasher[IO], SecurityProvider[IO])

  override def sharedResource: Resource[IO, SignedValidatorSuite.Res] =
    KryoSerializer
      .forAsync[IO](sharedKryoRegistrar.union(Map[Class[_], KryoRegistrationId[Interval.Closed[5000, 5001]]](classOf[TestObject] -> 5000)))
      .flatMap { implicit res =>
        JsonSerializer.forSync[IO].asResource.map { implicit json =>
          Hasher.forSync[IO](new HashSelect { def select(ordinal: SnapshotOrdinal): HashLogic = JsonHash })
        }
      }
      .flatMap { h =>
        SecurityProvider.forAsync[IO].map((h, _))
      }

  test("should succeed when object is signed only by one peer from seedlist") { res =>
    implicit val (h, sp) = res

    for {
      keyPair1 <- KeyPairGenerator.makeKeyPair[IO]
      peerId1 = PeerId.fromPublic(keyPair1.getPublic)
      keyPair2 <- KeyPairGenerator.makeKeyPair[IO]
      peerId2 = PeerId.fromPublic(keyPair2.getPublic)
      input = TestObject("Test")
      signedInput <- forAsyncHasher(input, keyPair1)
      validator = mkValidator()
      result = validator.validateSignaturesWithSeedlist(Some(Set(peerId1, peerId2)), signedInput)
    } yield expect.same(Validated.Valid(signedInput), result)
  }

  test("should succeed when all signers are on the seedlist") { res =>
    implicit val (h, sp) = res

    for {
      keyPair1 <- KeyPairGenerator.makeKeyPair[IO]
      peerId1 = PeerId.fromPublic(keyPair1.getPublic)
      keyPair2 <- KeyPairGenerator.makeKeyPair[IO]
      peerId2 = PeerId.fromPublic(keyPair2.getPublic)
      input = TestObject("Test")
      signedInput <- forAsyncHasher(input, keyPair1).flatMap(_.signAlsoWith(keyPair2))
      validator = mkValidator()
      result = validator.validateSignaturesWithSeedlist(Some(Set(peerId1, peerId2)), signedInput)
    } yield expect.same(Validated.Valid(signedInput), result)
  }

  test("should succeed when there is no seedlist provided") { res =>
    implicit val (h, sp) = res

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      input = TestObject("Test")
      signedInput <- forAsyncHasher(input, keyPair)
      validator = mkValidator()
      result = validator.validateSignaturesWithSeedlist(None, signedInput)
    } yield expect.same(Validated.Valid(signedInput), result)
  }

  test("should fail when there is at least one signature not in seedlist") { res =>
    implicit val (h, sp) = res

    for {
      keyPair1 <- KeyPairGenerator.makeKeyPair[IO]
      peerId1 = PeerId.fromPublic(keyPair1.getPublic)
      keyPair2 <- KeyPairGenerator.makeKeyPair[IO]
      peerId2 = PeerId.fromPublic(keyPair2.getPublic)
      input = TestObject("Test")
      signedInput <- forAsyncHasher(input, keyPair1).flatMap(_.signAlsoWith(keyPair2))
      validator = mkValidator()
      result = validator.validateSignaturesWithSeedlist(Some(Set(peerId1)), signedInput)
    } yield expect.same(SignedValidator.SignersNotInSeedlist(NonEmptySet.one(peerId2.toId)).invalidNec, result)
  }

  private def mkValidator()(
    implicit S: SecurityProvider[IO],
    H: Hasher[IO]
  ) = SignedValidator.make[IO]

  @derive(encoder)
  case class TestObject(value: String)

}
