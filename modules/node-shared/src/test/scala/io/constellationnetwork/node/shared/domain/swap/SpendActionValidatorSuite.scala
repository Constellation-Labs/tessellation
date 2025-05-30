package io.constellationnetwork.node.shared.domain.swap

import cats.data.{NonEmptyChain, NonEmptyList}
import cats.effect.IO
import cats.effect.kernel.Resource
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.node.shared.domain.swap.SpendActionValidator._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.artifact.{SpendAction, SpendTransaction}
import io.constellationnetwork.schema.balance.Balance
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

      userSpendTx = SpendTransaction(hashedAllowSpend.hash.some, None, SwapAmount(1L), address, ammAddress)
      metagraphSpendTx = SpendTransaction(none, None, SwapAmount(2L), ammAddress, ammAddress)
      spendAction = SpendAction(NonEmptyList.of(userSpendTx, metagraphSpendTx))
      balances = Map(none[Address] -> SortedMap(ammAddress -> Balance(NonNegLong(1000L))))

      result <- validator.validate(spendAction, activeAllowSpends, balances, ammAddress).map(_.isValid)
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

      userSpendTx = SpendTransaction(hashedAllowSpend.hash.some, currencyId.some, SwapAmount(1L), address, ammAddress)
      metagraphSpendTx = SpendTransaction(none, currencyId.some, SwapAmount(2L), ammAddress, ammAddress)
      spendAction = SpendAction(NonEmptyList.of(userSpendTx, metagraphSpendTx))
      balances = Map(currencyId.value.some -> SortedMap(ammAddress -> Balance(NonNegLong(1000L))))

      result <- validator.validate(spendAction, activeAllowSpends, balances, ammAddress).map(_.isValid)

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

      userSpendTx = SpendTransaction(hashedAllowSpend.hash.some, invalidCurrencyId.some, SwapAmount(1L), currencyId.value, address)
      metagraphSpendTx = SpendTransaction(none, invalidCurrencyId.some, SwapAmount(2L), currencyId.value, address)
      spendAction = SpendAction(NonEmptyList.of(userSpendTx, metagraphSpendTx))
      balances = Map(none[Address] -> SortedMap(ammAddress -> Balance(NonNegLong(1000L))))

      result <- validator.validate(spendAction, activeAllowSpends, balances, ammAddress)
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

      userSpendTx = SpendTransaction(hashedAllowSpend.hash.some, currencyId.some, SwapAmount(1L), address1, address2)
      metagraphSpendTx = SpendTransaction(none, currencyId.some, SwapAmount(2L), address2, address1)
      spendAction = SpendAction(NonEmptyList.of(userSpendTx, metagraphSpendTx))
      balances = Map(none[Address] -> SortedMap(ammAddress -> Balance(NonNegLong(1000L))))

      result <- validator.validate(spendAction, activeAllowSpends, balances, ammAddress)
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

      inputTx = SpendTransaction(none, currencyId.some, SwapAmount(1L), currencyId.value, address)
      outputTx = SpendTransaction(none, currencyId.some, SwapAmount(2L), currencyId.value, keyPair2.getPublic.toAddress)
      spendAction = SpendAction(NonEmptyList.of(inputTx, outputTx))

      activeAllowSpends = SortedMap(currencyId.value.some -> SortedMap(address -> SortedSet.empty[Signed[AllowSpend]]))
      balances: Map[Option[Address], SortedMap[Address, Balance]] = Map(
        currencyId.value.some -> SortedMap(currencyId.value -> Balance(NonNegLong(1000L)))
      )

      result <- validator.validate(spendAction, activeAllowSpends, balances, currencyId.value)
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

      userSpendTx = SpendTransaction(invalidHash.some, currencyId.some, SwapAmount(1L), ammAddress, address)
      metagraphSpendTx = SpendTransaction(none, currencyId.some, SwapAmount(2L), ammAddress, address)
      spendAction = SpendAction(NonEmptyList.of(userSpendTx, metagraphSpendTx))
      balances = Map(none[Address] -> SortedMap(ammAddress -> Balance(NonNegLong(1000L))))

      result <- validator.validate(spendAction, activeAllowSpends, balances, ammAddress)
    } yield
      expect(result.isInvalid).and(expect(result.toEither.left.map(_.head).left.exists {
        case SpendActionValidator.AllowSpendNotFound(_) => true
        case _                                          => false
      }))
  }

  test("should fail when currencyId doesn't have enough balance") { res =>
    implicit val (_, hs, sp) = res

    val validator = SpendActionValidator.make

    for {
      keyPair1 <- KeyPairGenerator.makeKeyPair[IO]
      keyPair2 <- KeyPairGenerator.makeKeyPair[IO]

      address = keyPair1.getPublic.toAddress
      currencyId = CurrencyId(keyPair2.getPublic.toAddress)

      inputTx = SpendTransaction(none, currencyId.some, SwapAmount(1L), keyPair2.getPublic.toAddress, address)
      outputTx = SpendTransaction(none, currencyId.some, SwapAmount(2L), address, keyPair2.getPublic.toAddress)
      spendAction = SpendAction(NonEmptyList.of(inputTx, outputTx))

      activeAllowSpends = SortedMap(currencyId.value.some -> SortedMap(address -> SortedSet.empty[Signed[AllowSpend]]))
      balances = Map.empty[Option[Address], SortedMap[Address, Balance]]

      result <- validator.validate(spendAction, activeAllowSpends, balances, currencyId.value)
    } yield
      expect(result.isInvalid).and(expect(result.toEither.left.map(_.head).left.exists {
        case SpendActionValidator.NotEnoughCurrencyIdBalance(_) => true
        case _                                                  => false
      }))
  }

  test("Should get accepted and rejected SpendTransactions - All accepted") { res =>
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

      metagraphSpendTx = SpendTransaction(none, None, SwapAmount(2L), ammAddress, ammAddress)
      userSpendTx = SpendTransaction(hashedAllowSpend.hash.some, None, SwapAmount(1L), address, ammAddress)
      spendAction = SpendAction(NonEmptyList.of(metagraphSpendTx, userSpendTx))
      spendActions = Map(ammAddress -> List(spendAction))
      balances = Map(none[Address] -> SortedMap(ammAddress -> Balance(NonNegLong(1000L))))

      (acceptedSpendActions, rejectedSpendActions) <- validator.validateReturningAcceptedAndRejected(
        spendActions,
        activeAllowSpends,
        balances
      )
    } yield
      expect.all(
        rejectedSpendActions.isEmpty,
        acceptedSpendActions.nonEmpty,
        acceptedSpendActions.contains(ammAddress),
        acceptedSpendActions(ammAddress) === List(spendAction)
      )
  }

  test("Should get accepted and rejected SpendTransactions - Accept only first SpendAction when referencing the same allow spend") { res =>
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

      metagraphSpendTx = SpendTransaction(none, None, SwapAmount(2L), ammAddress, ammAddress)
      userSpendTx = SpendTransaction(hashedAllowSpend.hash.some, None, SwapAmount(1L), address, ammAddress)
      spendAction = SpendAction(NonEmptyList.of(metagraphSpendTx, userSpendTx))
      duplicatedAction = SpendAction(NonEmptyList.of(userSpendTx))
      spendActions = Map(ammAddress -> List(spendAction, duplicatedAction))
      balances = Map(none[Address] -> SortedMap(ammAddress -> Balance(NonNegLong(1000L))))

      (acceptedSpendActions, rejectedSpendActions) <- validator.validateReturningAcceptedAndRejected(
        spendActions,
        activeAllowSpends,
        balances
      )
    } yield
      expect.all(
        rejectedSpendActions.nonEmpty,
        rejectedSpendActions.size === 1,
        acceptedSpendActions.nonEmpty,
        acceptedSpendActions.size === 1,
        acceptedSpendActions.contains(ammAddress),
        rejectedSpendActions.contains(ammAddress),
        acceptedSpendActions(ammAddress) === List(spendAction),
        rejectedSpendActions(ammAddress)._1 === duplicatedAction,
        rejectedSpendActions(ammAddress)._2 === List(NoActiveAllowSpends("Currency None not found in active allow spends"))
      )
  }

  test("Should get accepted and rejected SpendTransactions - Reject entire SpendAction when 2 SpendTxn references same allowSpend") { res =>
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

      metagraphSpendTx = SpendTransaction(none, None, SwapAmount(2L), ammAddress, ammAddress)
      userSpendTx = SpendTransaction(hashedAllowSpend.hash.some, None, SwapAmount(1L), address, ammAddress)
      spendAction = SpendAction(NonEmptyList.of(metagraphSpendTx, userSpendTx, userSpendTx))
      spendActions = Map(ammAddress -> List(spendAction))
      balances = Map(none[Address] -> SortedMap(ammAddress -> Balance(NonNegLong(1000L))))

      (acceptedSpendActions, rejectedSpendActions) <- validator.validateReturningAcceptedAndRejected(
        spendActions,
        activeAllowSpends,
        balances
      )
    } yield
      expect.all(
        acceptedSpendActions.isEmpty,
        rejectedSpendActions.nonEmpty,
        rejectedSpendActions.size === 1,
        rejectedSpendActions.contains(ammAddress),
        rejectedSpendActions(ammAddress)._1 === spendAction,
        rejectedSpendActions(ammAddress)._2 === List(
          DuplicatedAllowSpendReference("Duplicated allow spend reference in the same SpendAction")
        )
      )
  }

  test("Should accept spendTransactions without allowSpendRef skipping allowSpendRefValidation") { res =>
    implicit val (_, hs, sp) = res

    val validator = SpendActionValidator.make

    for {
      keyPair1 <- KeyPairGenerator.makeKeyPair[IO]
      keyPair2 <- KeyPairGenerator.makeKeyPair[IO]

      address = keyPair1.getPublic.toAddress
      ammAddress = keyPair2.getPublic.toAddress

      activeAllowSpends = SortedMap.empty[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]]

      metagraphSpendTx = SpendTransaction(none, None, SwapAmount(2L), ammAddress, ammAddress)
      userSpendTx = SpendTransaction(none, None, SwapAmount(1L), ammAddress, ammAddress)
      spendAction = SpendAction(NonEmptyList.of(metagraphSpendTx, userSpendTx))
      spendActions = Map(ammAddress -> List(spendAction))
      balances = Map(none[Address] -> SortedMap(ammAddress -> Balance(NonNegLong(1000L))))

      (acceptedSpendActions, rejectedSpendActions) <- validator.validateReturningAcceptedAndRejected(
        spendActions,
        activeAllowSpends,
        balances
      )
    } yield
      expect.all(
        rejectedSpendActions.isEmpty,
        acceptedSpendActions.nonEmpty,
        acceptedSpendActions.contains(ammAddress),
        acceptedSpendActions(ammAddress) === List(spendAction)
      )
  }

  test("Should reject spendTransactions with wrong allowSpendRef") { res =>
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
      activeAllowSpends = SortedMap(none[Address] -> SortedMap(address -> SortedSet(signedAllowSpend)))

      metagraphSpendTx = SpendTransaction(none, None, SwapAmount(2L), ammAddress, ammAddress)
      userSpendTx = SpendTransaction(Hash.empty.some, None, SwapAmount(1L), address, ammAddress)
      spendAction = SpendAction(NonEmptyList.of(metagraphSpendTx, userSpendTx))
      spendActions = Map(ammAddress -> List(spendAction))
      balances = Map(none[Address] -> SortedMap(ammAddress -> Balance(NonNegLong(1000L))))

      (acceptedSpendActions, rejectedSpendActions) <- validator.validateReturningAcceptedAndRejected(
        spendActions,
        activeAllowSpends,
        balances
      )
    } yield
      expect.all(
        rejectedSpendActions.nonEmpty,
        acceptedSpendActions.isEmpty,
        rejectedSpendActions.contains(ammAddress),
        rejectedSpendActions(ammAddress)._2 === List(
          AllowSpendNotFound(s"Allow spend ${Hash.empty} not found in currency active allow spends")
        )
      )
  }
}
