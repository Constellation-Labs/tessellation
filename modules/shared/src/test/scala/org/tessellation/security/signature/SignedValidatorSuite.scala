package org.tessellation.security.signature

import cats.data.{NonEmptySet, Validated}
import cats.effect.{IO, Resource}
import cats.syntax.option._
import cats.syntax.validated._

import scala.collection.immutable.SortedSet

import org.tessellation.ext.cats.effect.ResourceIO
import org.tessellation.ext.kryo._
import org.tessellation.json.JsonSerializer
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.address.Address
import org.tessellation.schema.generators._
import org.tessellation.schema.peer.PeerId
import org.tessellation.security._
import org.tessellation.security.signature.Signed.forAsyncHasher
import org.tessellation.security.signature.SignedValidator.NotEnoughSeedlistSignatures
import org.tessellation.shared.sharedKryoRegistrar

import derevo.circe.magnolia.encoder
import derevo.derive
import eu.timepit.refined.auto._
import eu.timepit.refined.numeric.Interval
import org.scalacheck.Gen
import weaver.MutableIOSuite
import weaver.scalacheck.Checkers

object SignedValidatorSuite extends MutableIOSuite with Checkers {

  type Res = (Hasher[IO], SecurityProvider[IO])

  override def sharedResource: Resource[IO, SignedValidatorSuite.Res] =
    KryoSerializer
      .forAsync[IO](sharedKryoRegistrar.union(Map[Class[_], KryoRegistrationId[Interval.Closed[5000, 5001]]](classOf[TestObject] -> 5000)))
      .flatMap { implicit res =>
        JsonSerializer.forSync[IO].asResource.map { implicit json =>
          Hasher.forJson[IO]
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

  test("validateSignedBySeedlistMajority should succeed when there is no seedlist") { res =>
    implicit val (h, sp) = res

    forall(signedOf(addressGen)) { signedAddress =>
      IO {
        val actual = mkValidator().validateSignedBySeedlistMajority(None, signedAddress)
        val expected = signedAddress.validNec
        expect.same(actual, expected)
      }
    }
  }

  test("validateSignedBySeedlistMajority should fail on empty seedlist") { res =>
    implicit val (h, sp) = res

    forall(signedOf(addressGen)) { signedAddress =>
      IO {
        val actual = mkValidator().validateSignedBySeedlistMajority(Set.empty[PeerId].some, signedAddress)
        val expected = NotEnoughSeedlistSignatures(0, 1).invalidNec
        expect.same(actual, expected)
      }
    }
  }

  test("validateSignedBySeedlistMajority should fail when <= 50% signatures are in seedlist") { res =>
    implicit val (h, sp) = res

    val gen: Gen[(SortedSet[PeerId], Signed[Address], Int)] =
      for {
        sa <- signedOfN(addressGen, 1, 20)
        proofs = sa.proofs.toSortedSet
        numSignaturesInSeedlist <- Gen.chooseNum(0, proofs.size)
        otherPeers <- Gen.listOfN(1 + 2 * numSignaturesInSeedlist, peerIdGen)
        peers = proofs.take(numSignaturesInSeedlist).map(_.id.toPeerId) ++ otherPeers
      } yield (peers, sa, numSignaturesInSeedlist)

    forall(gen) {
      case (peers, signedAddress, numSignaturesInSeedlist) =>
        IO {
          val actual = mkValidator().validateSignedBySeedlistMajority(peers.some, signedAddress)

          val minSigCount = peers.size / 2 + 1

          val expected = NotEnoughSeedlistSignatures(numSignaturesInSeedlist, minSigCount).invalidNec
          expect.same(actual, expected)
        }
    }
  }

  test("validateSignedBySeedlistMajority should succeed when > 50% signatures are in seedlist") { res =>
    implicit val (h, sp) = res

    val gen: Gen[(SortedSet[PeerId], Signed[Address])] =
      for {
        sa <- signedOfN(addressGen, 1, 20)
        proofs = sa.proofs.toSortedSet
        numSignaturesInSeedlist <- Gen.chooseNum(1, proofs.size)
        otherPeers <- Gen.choose(0, numSignaturesInSeedlist - 1).flatMap(Gen.listOfN(_, peerIdGen))
        peers = proofs.take(numSignaturesInSeedlist).map(_.id.toPeerId) ++ otherPeers
      } yield (peers, sa)

    forall(gen) {
      case (peers, signedAddress) =>
        IO {
          val actual = mkValidator().validateSignedBySeedlistMajority(peers.some, signedAddress)
          val expected = signedAddress.validNec
          expect.same(actual, expected)
        }
    }
  }

  private def mkValidator()(
    implicit S: SecurityProvider[IO],
    H: Hasher[IO]
  ) = SignedValidator.make[IO]

  @derive(encoder)
  case class TestObject(value: String)

}
