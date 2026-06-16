package io.constellationnetwork.node.shared.domain.swap

import cats.data.NonEmptyList
import cats.effect.IO
import cats.effect.kernel.Resource
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.node.shared.domain.swap.BurnActionValidator._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.artifact.{BurnAction, BurnTransaction}
import io.constellationnetwork.schema.balance.Balance
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.swap._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.key.ops.PublicKeyOps
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.Signed._
import io.constellationnetwork.security.{Hasher, SecurityProvider, _}
import io.constellationnetwork.shared.sharedKryoRegistrar

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong
import weaver.MutableIOSuite

object BurnActionValidatorSuite extends MutableIOSuite {

  type Res = (JsonSerializer[IO], Hasher[IO], SecurityProvider[IO])

  def sharedResource: Resource[IO, Res] = for {
    implicit0(ks: KryoSerializer[IO]) <- KryoSerializer.forAsync[IO](sharedKryoRegistrar)
    sp <- SecurityProvider.forAsync[IO]
    implicit0(j: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO].asResource
    h = Hasher.forJson[IO]
  } yield (j, h, sp)

  test("should validate burnFrom with valid allow spend reference for DAG") { res =>
    implicit val (_, hs, sp) = res

    val validator = BurnActionValidator.make

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

      userBurnTx = BurnTransaction(hashedAllowSpend.hash.some, None, SwapAmount(1L), address)
      metagraphBurnTx = BurnTransaction(none, None, SwapAmount(2L), ammAddress)
      burnAction = BurnAction(NonEmptyList.of(userBurnTx, metagraphBurnTx))
      balances = Map(none[Address] -> SortedMap(ammAddress -> Balance(NonNegLong(1000L))))

      result <- validator.validate(burnAction, activeAllowSpends, balances, ammAddress)
    } yield expect(result.isValid)
  }

  test("should validate burnFrom with valid allow spend reference for Currency") { res =>
    implicit val (_, hs, sp) = res

    val validator = BurnActionValidator.make

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

      userBurnTx = BurnTransaction(hashedAllowSpend.hash.some, currencyId.some, SwapAmount(1L), address)
      metagraphBurnTx = BurnTransaction(none, currencyId.some, SwapAmount(2L), ammAddress)
      burnAction = BurnAction(NonEmptyList.of(userBurnTx, metagraphBurnTx))
      balances = Map(currencyId.value.some -> SortedMap(ammAddress -> Balance(NonNegLong(1000L))))

      result <- validator.validate(burnAction, activeAllowSpends, balances, ammAddress)
    } yield expect(result.isValid)
  }

  test("should validate self-burn (no ref, source == currencyId)") { res =>
    implicit val (_, hs, sp) = res

    val validator = BurnActionValidator.make

    for {
      keyPair1 <- KeyPairGenerator.makeKeyPair[IO]
      keyPair2 <- KeyPairGenerator.makeKeyPair[IO]

      address = keyPair1.getPublic.toAddress
      currencyId = CurrencyId(keyPair2.getPublic.toAddress)

      selfBurnTx = BurnTransaction(none, currencyId.some, SwapAmount(1L), currencyId.value)
      burnAction = BurnAction(NonEmptyList.of(selfBurnTx))

      activeAllowSpends = SortedMap(currencyId.value.some -> SortedMap(address -> SortedSet.empty[Signed[AllowSpend]]))
      balances: Map[Option[Address], SortedMap[Address, Balance]] = Map(
        currencyId.value.some -> SortedMap(currencyId.value -> Balance(NonNegLong(1000L)))
      )

      result <- validator.validate(burnAction, activeAllowSpends, balances, currencyId.value)
    } yield expect(result.isValid)
  }

  test("should reject cumulative self-burns in one BurnAction when total exceeds currencyId balance") { res =>
    implicit val (_, hs, sp) = res

    val validator = BurnActionValidator.make

    for {
      keyPair1 <- KeyPairGenerator.makeKeyPair[IO]
      currencyId = CurrencyId(keyPair1.getPublic.toAddress)

      firstBurnTx = BurnTransaction(none, currencyId.some, SwapAmount(70L), currencyId.value)
      secondBurnTx = BurnTransaction(none, currencyId.some, SwapAmount(40L), currencyId.value)
      burnAction = BurnAction(NonEmptyList.of(firstBurnTx, secondBurnTx))

      activeAllowSpends = SortedMap.empty[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]]
      balances = Map(currencyId.value.some -> SortedMap(currencyId.value -> Balance(NonNegLong(100L))))

      result <- validator.validate(burnAction, activeAllowSpends, balances, currencyId.value)
    } yield
      expect(result.isInvalid).and(expect(result.toEither.left.map(_.head).left.exists {
        case BurnActionValidator.NotEnoughCurrencyIdBalance(_) => true
        case _                                                 => false
      }))
  }

  test("should reject later self-burn action when earlier accepted action depleted the balance") { res =>
    implicit val (_, hs, sp) = res

    val validator = BurnActionValidator.make

    for {
      keyPair1 <- KeyPairGenerator.makeKeyPair[IO]
      currencyId = CurrencyId(keyPair1.getPublic.toAddress)

      firstBurnTx = BurnTransaction(none, currencyId.some, SwapAmount(70L), currencyId.value)
      secondBurnTx = BurnTransaction(none, currencyId.some, SwapAmount(40L), currencyId.value)
      firstBurnAction = BurnAction(NonEmptyList.of(firstBurnTx))
      secondBurnAction = BurnAction(NonEmptyList.of(secondBurnTx))

      burnActions = Map(currencyId.value -> List(firstBurnAction, secondBurnAction))
      activeAllowSpends = SortedMap.empty[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]]
      balances = Map(currencyId.value.some -> SortedMap(currencyId.value -> Balance(NonNegLong(100L))))

      (acceptedBurnActions, rejectedBurnActions) <- validator.validateReturningAcceptedAndRejected(
        burnActions,
        activeAllowSpends,
        balances
      )
    } yield
      expect.all(
        acceptedBurnActions.get(currencyId.value).contains(List(firstBurnAction)),
        rejectedBurnActions.contains(currencyId.value),
        rejectedBurnActions(currencyId.value)._1 === secondBurnAction,
        rejectedBurnActions(currencyId.value)._2.exists {
          case BurnActionValidator.NotEnoughCurrencyIdBalance(_) => true
          case _                                                 => false
        }
      )
  }

  test("should fail validation when currency not found in active allow spends") { res =>
    implicit val (_, hs, sp) = res

    val validator = BurnActionValidator.make

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

      userBurnTx = BurnTransaction(hashedAllowSpend.hash.some, invalidCurrencyId.some, SwapAmount(1L), currencyId.value)
      burnAction = BurnAction(NonEmptyList.of(userBurnTx))
      balances = Map(none[Address] -> SortedMap(ammAddress -> Balance(NonNegLong(1000L))))

      result <- validator.validate(burnAction, activeAllowSpends, balances, ammAddress)
    } yield
      expect(result.isInvalid).and(expect(result.toEither.left.map(_.head).left.exists {
        case BurnActionValidator.NoActiveAllowSpends(_) => true
        case _                                          => false
      }))
  }

  test("should fail validation when approver does not include currencyId") { res =>
    implicit val (_, hs, sp) = res

    val validator = BurnActionValidator.make

    for {
      keyPair1 <- KeyPairGenerator.makeKeyPair[IO]
      keyPair2 <- KeyPairGenerator.makeKeyPair[IO]
      keyPair3 <- KeyPairGenerator.makeKeyPair[IO]

      address = keyPair1.getPublic.toAddress
      currencyId = CurrencyId(keyPair2.getPublic.toAddress)
      wrongApprover = keyPair3.getPublic.toAddress
      ammAddress = keyPair2.getPublic.toAddress

      allowSpend = AllowSpend(
        address,
        ammAddress,
        currencyId.some,
        SwapAmount(1L),
        AllowSpendFee(1L),
        AllowSpendReference.empty,
        EpochProgress(20L),
        List(wrongApprover)
      )
      signedAllowSpend <- Signed.forAsyncHasher(allowSpend, keyPair1)
      hashedAllowSpend <- signedAllowSpend.toHashed

      activeAllowSpends = SortedMap(currencyId.value.some -> SortedMap(address -> SortedSet(signedAllowSpend)))

      userBurnTx = BurnTransaction(hashedAllowSpend.hash.some, currencyId.some, SwapAmount(1L), address)
      burnAction = BurnAction(NonEmptyList.of(userBurnTx))
      balances = Map(currencyId.value.some -> SortedMap(ammAddress -> Balance(NonNegLong(1000L))))

      result <- validator.validate(burnAction, activeAllowSpends, balances, ammAddress)
    } yield
      expect(result.isInvalid).and(expect(result.toEither.left.map(_.head).left.exists {
        case BurnActionValidator.InvalidCurrencyId(_) => true
        case _                                        => false
      }))
  }

  test("should fail validation when burn source does not match allow spend source") { res =>
    implicit val (_, hs, sp) = res

    val validator = BurnActionValidator.make

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

      // burn references the allow spend but claims a different source (address2)
      userBurnTx = BurnTransaction(hashedAllowSpend.hash.some, currencyId.some, SwapAmount(1L), address2)
      burnAction = BurnAction(NonEmptyList.of(userBurnTx))
      balances = Map(currencyId.value.some -> SortedMap(ammAddress -> Balance(NonNegLong(1000L))))

      result <- validator.validate(burnAction, activeAllowSpends, balances, ammAddress)
    } yield
      expect(result.isInvalid).and(expect(result.toEither.left.map(_.head).left.exists {
        case BurnActionValidator.InvalidSourceAddress(_) => true
        case _                                           => false
      }))
  }

  test("should fail validation when burn amount greater than allowed") { res =>
    implicit val (_, hs, sp) = res

    val validator = BurnActionValidator.make

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

      userBurnTx = BurnTransaction(hashedAllowSpend.hash.some, currencyId.some, SwapAmount(5L), address)
      burnAction = BurnAction(NonEmptyList.of(userBurnTx))
      balances = Map(currencyId.value.some -> SortedMap(ammAddress -> Balance(NonNegLong(1000L))))

      result <- validator.validate(burnAction, activeAllowSpends, balances, ammAddress)
    } yield
      expect(result.isInvalid).and(expect(result.toEither.left.map(_.head).left.exists {
        case BurnActionValidator.BurnAmountGreaterThanAllowed(_) => true
        case _                                                   => false
      }))
  }

  test("should fail validation when allow spend hash not found") { res =>
    implicit val (_, hs, sp) = res

    val validator = BurnActionValidator.make

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
      _ <- signedAllowSpend.toHashed
      invalidHash = Hash.empty

      activeAllowSpends = SortedMap(currencyId.value.some -> SortedMap(address -> SortedSet(signedAllowSpend)))

      userBurnTx = BurnTransaction(invalidHash.some, currencyId.some, SwapAmount(1L), ammAddress)
      burnAction = BurnAction(NonEmptyList.of(userBurnTx))
      balances = Map(none[Address] -> SortedMap(ammAddress -> Balance(NonNegLong(1000L))))

      result <- validator.validate(burnAction, activeAllowSpends, balances, ammAddress)
    } yield
      expect(result.isInvalid).and(expect(result.toEither.left.map(_.head).left.exists {
        case BurnActionValidator.AllowSpendNotFound(_) => true
        case _                                         => false
      }))
  }

  test("should fail self-burn when source != currencyId") { res =>
    implicit val (_, hs, sp) = res

    val validator = BurnActionValidator.make

    for {
      keyPair1 <- KeyPairGenerator.makeKeyPair[IO]
      keyPair2 <- KeyPairGenerator.makeKeyPair[IO]

      address = keyPair1.getPublic.toAddress
      currencyId = CurrencyId(keyPair2.getPublic.toAddress)

      // self-burn but source is NOT the metagraph (currencyId) address
      selfBurnTx = BurnTransaction(none, currencyId.some, SwapAmount(1L), address)
      burnAction = BurnAction(NonEmptyList.of(selfBurnTx))

      activeAllowSpends = SortedMap(currencyId.value.some -> SortedMap(address -> SortedSet.empty[Signed[AllowSpend]]))
      balances: Map[Option[Address], SortedMap[Address, Balance]] = Map(
        currencyId.value.some -> SortedMap(currencyId.value -> Balance(NonNegLong(1000L)))
      )

      result <- validator.validate(burnAction, activeAllowSpends, balances, currencyId.value)
    } yield
      expect(result.isInvalid).and(expect(result.toEither.left.map(_.head).left.exists {
        case BurnActionValidator.InvalidSourceAddress(_) => true
        case _                                           => false
      }))
  }

  test("should fail self-burn when amount greater than currencyId balance") { res =>
    implicit val (_, hs, sp) = res

    val validator = BurnActionValidator.make

    for {
      keyPair1 <- KeyPairGenerator.makeKeyPair[IO]
      keyPair2 <- KeyPairGenerator.makeKeyPair[IO]

      address = keyPair1.getPublic.toAddress
      currencyId = CurrencyId(keyPair2.getPublic.toAddress)

      selfBurnTx = BurnTransaction(none, currencyId.some, SwapAmount(5L), currencyId.value)
      burnAction = BurnAction(NonEmptyList.of(selfBurnTx))

      activeAllowSpends = SortedMap(currencyId.value.some -> SortedMap(address -> SortedSet.empty[Signed[AllowSpend]]))
      balances = Map.empty[Option[Address], SortedMap[Address, Balance]]

      result <- validator.validate(burnAction, activeAllowSpends, balances, currencyId.value)
    } yield
      expect(result.isInvalid).and(expect(result.toEither.left.map(_.head).left.exists {
        case BurnActionValidator.NotEnoughCurrencyIdBalance(_) => true
        case _                                                 => false
      }))
  }

  test("should reject entire BurnAction when duplicated allow spend reference") { res =>
    implicit val (_, hs, sp) = res

    val validator = BurnActionValidator.make

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

      userBurnTx = BurnTransaction(hashedAllowSpend.hash.some, None, SwapAmount(1L), address)
      burnAction = BurnAction(NonEmptyList.of(userBurnTx, userBurnTx))
      burnActions = Map(ammAddress -> List(burnAction))
      balances = Map(none[Address] -> SortedMap(ammAddress -> Balance(NonNegLong(1000L))))

      (acceptedBurnActions, rejectedBurnActions) <- validator.validateReturningAcceptedAndRejected(
        burnActions,
        activeAllowSpends,
        balances
      )
    } yield
      expect.all(
        acceptedBurnActions.isEmpty,
        rejectedBurnActions.nonEmpty,
        rejectedBurnActions.size === 1,
        rejectedBurnActions.contains(ammAddress),
        rejectedBurnActions(ammAddress)._1 === burnAction,
        rejectedBurnActions(ammAddress)._2 === List(
          DuplicatedAllowSpendReference("Duplicated allow spend reference in the same BurnAction")
        )
      )
  }

  test("Should accept burnTransactions without allowSpendRef (self-burn) skipping allowSpendRefValidation") { res =>
    implicit val (_, hs, sp) = res

    val validator = BurnActionValidator.make

    for {
      keyPair1 <- KeyPairGenerator.makeKeyPair[IO]
      keyPair2 <- KeyPairGenerator.makeKeyPair[IO]

      _ = keyPair1.getPublic.toAddress
      ammAddress = keyPair2.getPublic.toAddress

      activeAllowSpends = SortedMap.empty[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]]

      metagraphBurnTx = BurnTransaction(none, None, SwapAmount(2L), ammAddress)
      userBurnTx = BurnTransaction(none, None, SwapAmount(1L), ammAddress)
      burnAction = BurnAction(NonEmptyList.of(metagraphBurnTx, userBurnTx))
      burnActions = Map(ammAddress -> List(burnAction))
      balances = Map(none[Address] -> SortedMap(ammAddress -> Balance(NonNegLong(1000L))))

      (acceptedBurnActions, rejectedBurnActions) <- validator.validateReturningAcceptedAndRejected(
        burnActions,
        activeAllowSpends,
        balances
      )
    } yield
      expect.all(
        rejectedBurnActions.isEmpty,
        acceptedBurnActions.nonEmpty,
        acceptedBurnActions.contains(ammAddress),
        acceptedBurnActions(ammAddress) === List(burnAction)
      )
  }
}
