package io.constellationnetwork.node.shared.domain.swap

import cats.data.NonEmptyChain
import cats.effect.IO
import cats.effect.kernel.Resource
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.artifact.{SpendAction, SpendTransaction}
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.swap._
import io.constellationnetwork.schema.{SnapshotOrdinal, _}
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.key.ops.PublicKeyOps
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.Signed._
import io.constellationnetwork.security.{Hasher, SecurityProvider, _}
import io.constellationnetwork.shared.sharedKryoRegistrar

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong
import weaver.MutableIOSuite

object SpendActionValidatorSuite extends MutableIOSuite {

  type Res = (JsonSerializer[IO], Hasher[IO], SecurityProvider[IO])

  def sharedResource: Resource[IO, Res] = for {
    implicit0(ks: KryoSerializer[IO]) <- KryoSerializer.forAsync[IO](sharedKryoRegistrar)
    sp <- SecurityProvider.forAsync[IO]
    implicit0(j: JsonSerializer[IO]) <- JsonSerializer.forSync[IO].asResource
    h = Hasher.forJson[IO]
  } yield (j, h, sp)

  test("should validate spend action with valid allow spend reference for DAG") { res =>
    implicit val (_, hs, sp) = res

    val validator = SpendActionValidator.make

    for {
      keyPair1 <- KeyPairGenerator.makeKeyPair[IO]
      keyPair2 <- KeyPairGenerator.makeKeyPair[IO]

      address = keyPair1.getPublic.toAddress
      ammAddress = keyPair2.getPublic.toAddress

      allowSpend = AllowSpend(
        address,
        ammAddress,
        None,
        SwapAmount(1L),
        AllowSpendFee(1L),
        AllowSpendReference.empty,
        EpochProgress(20L),
        List(ammAddress)
      )
      signedAllowSpend <- Signed.forAsyncHasher(allowSpend, keyPair1)
      hashedAllowSpend <- signedAllowSpend.toHashed

      activeAllowSpends = SortedMap(none[Address] -> SortedMap(address -> SortedSet(signedAllowSpend)))

      userSpendTx = SpendTransaction(hashedAllowSpend.hash.some, None, SwapAmount(1L), ammAddress)
      metagraphSpendTx = SpendTransaction(none, None, SwapAmount(2L), ammAddress)
      spendAction = SpendAction(userSpendTx, metagraphSpendTx)

      result <- validator.validate(spendAction, activeAllowSpends).map(_.isValid)
    } yield expect(result)
  }

  test("should validate spend action with valid allow spend reference for Currency") { res =>
    implicit val (_, hs, sp) = res

    val validator = SpendActionValidator.make

    for {
      keyPair1 <- KeyPairGenerator.makeKeyPair[IO]
      keyPair2 <- KeyPairGenerator.makeKeyPair[IO]
      keyPair3 <- KeyPairGenerator.makeKeyPair[IO]

      address = keyPair1.getPublic.toAddress
      currencyId = CurrencyId(keyPair2.getPublic.toAddress)
      ammAddress = keyPair3.getPublic.toAddress

      allowSpend = AllowSpend(
        address,
        ammAddress,
        currencyId.some,
        SwapAmount(1L),
        AllowSpendFee(1L),
        AllowSpendReference.empty,
        EpochProgress(20L),
        List(ammAddress)
      )
      signedAllowSpend <- Signed.forAsyncHasher(allowSpend, keyPair1)
      hashedAllowSpend <- signedAllowSpend.toHashed

      activeAllowSpends = SortedMap(currencyId.value.some -> SortedMap(address -> SortedSet(signedAllowSpend)))

      userSpendTx = SpendTransaction(hashedAllowSpend.hash.some, currencyId.some, SwapAmount(1L), ammAddress)
      metagraphSpendTx = SpendTransaction(none, currencyId.some, SwapAmount(2L), ammAddress)
      spendAction = SpendAction(userSpendTx, metagraphSpendTx)

      result <- validator.validate(spendAction, activeAllowSpends).map(_.isValid)

    } yield expect(result)
  }

  test("should fail validation when currency not found in active allow spends") { res =>
    implicit val (_, hs, sp) = res

    val validator = SpendActionValidator.make

    for {
      keyPair1 <- KeyPairGenerator.makeKeyPair[IO]
      keyPair2 <- KeyPairGenerator.makeKeyPair[IO]
      keyPair3 <- KeyPairGenerator.makeKeyPair[IO]

      address = keyPair1.getPublic.toAddress
      currencyId = CurrencyId(keyPair2.getPublic.toAddress)
      invalidCurrencyId = CurrencyId(keyPair3.getPublic.toAddress)
      ammAddress = keyPair3.getPublic.toAddress

      allowSpend = AllowSpend(
        address,
        ammAddress,
        currencyId.some,
        SwapAmount(1L),
        AllowSpendFee(1L),
        AllowSpendReference.empty,
        EpochProgress(20L),
        List(ammAddress)
      )
      signedAllowSpend <- Signed.forAsyncHasher(allowSpend, keyPair1)
      hashedAllowSpend <- signedAllowSpend.toHashed

      activeAllowSpends = SortedMap(currencyId.value.some -> SortedMap(address -> SortedSet(signedAllowSpend)))

      userSpendTx = SpendTransaction(hashedAllowSpend.hash.some, invalidCurrencyId.some, SwapAmount(1L), address)
      metagraphSpendTx = SpendTransaction(none, invalidCurrencyId.some, SwapAmount(2L), address)
      spendAction = SpendAction(userSpendTx, metagraphSpendTx)

      result <- validator.validate(spendAction, activeAllowSpends)
    } yield
      expect(result.isInvalid).and(expect(result.toEither.left.map(_.head).left.exists {
        case SpendActionValidator.NoActiveAllowSpends(_) => true
        case _                                           => false
      }))
  }

  test("should fail validation when user-issued spend destination does not match allow spend") { res =>
    implicit val (_, hs, sp) = res

    val validator = SpendActionValidator.make

    for {
      keyPair1 <- KeyPairGenerator.makeKeyPair[IO]
      keyPair2 <- KeyPairGenerator.makeKeyPair[IO]
      keyPair3 <- KeyPairGenerator.makeKeyPair[IO]

      address1 = keyPair1.getPublic.toAddress
      address2 = keyPair2.getPublic.toAddress
      currencyId = CurrencyId(keyPair3.getPublic.toAddress)
      ammAddress = keyPair3.getPublic.toAddress

      allowSpend = AllowSpend(
        address1,
        ammAddress,
        currencyId.some,
        SwapAmount(1L),
        AllowSpendFee(1L),
        AllowSpendReference.empty,
        EpochProgress(20L),
        List(ammAddress)
      )
      signedAllowSpend <- Signed.forAsyncHasher(allowSpend, keyPair1)
      hashedAllowSpend <- signedAllowSpend.toHashed

      activeAllowSpends = SortedMap(currencyId.value.some -> SortedMap(address1 -> SortedSet(signedAllowSpend)))

      userSpendTx = SpendTransaction(hashedAllowSpend.hash.some, currencyId.some, SwapAmount(1L), address2)
      metagraphSpendTx = SpendTransaction(none, currencyId.some, SwapAmount(2L), address1)
      spendAction = SpendAction(userSpendTx, metagraphSpendTx)

      result <- validator.validate(spendAction, activeAllowSpends)
    } yield
      expect(result.isInvalid).and(expect(result.toEither.left.map(_.head).left.exists {
        case SpendActionValidator.InvalidDestinationAddress(_) => true
        case _                                                 => false
      }))
  }

  test("should validate spend action with both metagraph-issued transactions") { res =>
    implicit val (_, hs, sp) = res

    val validator = SpendActionValidator.make

    for {
      keyPair1 <- KeyPairGenerator.makeKeyPair[IO]
      keyPair2 <- KeyPairGenerator.makeKeyPair[IO]

      address = keyPair1.getPublic.toAddress
      currencyId = CurrencyId(keyPair2.getPublic.toAddress)

      inputTx = SpendTransaction(none, currencyId.some, SwapAmount(1L), address)
      outputTx = SpendTransaction(none, currencyId.some, SwapAmount(2L), keyPair2.getPublic.toAddress)
      spendAction = SpendAction(inputTx, outputTx)

      activeAllowSpends = SortedMap(currencyId.value.some -> SortedMap(address -> SortedSet.empty[Signed[AllowSpend]]))

      result <- validator.validate(spendAction, activeAllowSpends)
    } yield expect(result.isValid)
  }

  test("should fail validation when allow spend hash not found") { res =>
    implicit val (_, hs, sp) = res

    val validator = SpendActionValidator.make

    for {
      keyPair1 <- KeyPairGenerator.makeKeyPair[IO]
      keyPair2 <- KeyPairGenerator.makeKeyPair[IO]
      keyPair3 <- KeyPairGenerator.makeKeyPair[IO]

      address = keyPair1.getPublic.toAddress
      currencyId = CurrencyId(keyPair2.getPublic.toAddress)
      ammAddress = keyPair3.getPublic.toAddress

      allowSpend = AllowSpend(
        address,
        ammAddress,
        currencyId.some,
        SwapAmount(1L),
        AllowSpendFee(1L),
        AllowSpendReference.empty,
        EpochProgress(20L),
        List(ammAddress)
      )
      signedAllowSpend <- Signed.forAsyncHasher(allowSpend, keyPair1)
      hashedAllowSpend <- signedAllowSpend.toHashed
      invalidHash = Hash.empty

      activeAllowSpends = SortedMap(currencyId.value.some -> SortedMap(address -> SortedSet(signedAllowSpend)))

      userSpendTx = SpendTransaction(invalidHash.some, currencyId.some, SwapAmount(1L), address)
      metagraphSpendTx = SpendTransaction(none, currencyId.some, SwapAmount(2L), address)
      spendAction = SpendAction(userSpendTx, metagraphSpendTx)

      result <- validator.validate(spendAction, activeAllowSpends)
    } yield
      expect(result.isInvalid).and(expect(result.toEither.left.map(_.head).left.exists {
        case SpendActionValidator.AllowSpendNotFound(_) => true
        case _                                          => false
      }))
  }
}
