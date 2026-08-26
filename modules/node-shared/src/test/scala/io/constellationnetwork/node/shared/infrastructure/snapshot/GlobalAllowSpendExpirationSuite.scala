package io.constellationnetwork.node.shared.infrastructure.snapshot

import cats.data.NonEmptyList
import cats.effect.{IO, Resource}
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.node.shared.domain.swap.SpendActionValidator
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.artifact.{SpendAction, SpendTransaction}
import io.constellationnetwork.schema.balance.Balance
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.swap._
import io.constellationnetwork.security._
import io.constellationnetwork.security.key.ops.PublicKeyOps
import io.constellationnetwork.security.signature.Signed

import eu.timepit.refined.auto._
import weaver.MutableIOSuite

object GlobalAllowSpendExpirationSuite extends MutableIOSuite {

  type Res = (Hasher[IO], SecurityProvider[IO])

  override def sharedResource: Resource[IO, Res] =
    for {
      sp <- SecurityProvider.forAsync[IO]
      implicit0(j: JsonSerializer[IO]) <- JsonSerializer.forSync[IO].asResource
      implicit0(h: Hasher[IO]) = Hasher.forJson[IO]
    } yield (h, sp)

  private val expiryEpoch = EpochProgress(500L)
  private val boundaryEpoch = EpochProgress(501L)

  private case class Fixture(
    source: Address,
    metagraph: Address,
    signedAllowSpend: Signed[AllowSpend],
    spendTransaction: SpendTransaction,
    activeAllowSpends: SortedMap[Address, SortedSet[Signed[AllowSpend]]]
  )

  private def fixture(implicit hasher: Hasher[IO], securityProvider: SecurityProvider[IO]): IO[Fixture] =
    for {
      sourceKeyPair <- KeyPairGenerator.makeKeyPair[IO]
      source <- sourceKeyPair.getPublic.toId.toAddress
      metagraphKeyPair <- KeyPairGenerator.makeKeyPair[IO]
      metagraph <- metagraphKeyPair.getPublic.toId.toAddress
      signedAllowSpend <- Signed.forAsyncHasher(
        AllowSpend(
          source = source,
          destination = metagraph,
          currencyId = none,
          amount = SwapAmount(1000L),
          fee = AllowSpendFee(10L),
          parent = AllowSpendReference.empty,
          lastValidEpochProgress = expiryEpoch,
          approvers = List(metagraph)
        ),
        sourceKeyPair
      )
      hashedAllowSpend <- signedAllowSpend.toHashed
      spendTransaction = SpendTransaction(
        allowSpendRef = hashedAllowSpend.hash.some,
        currencyId = none,
        amount = SwapAmount(1000L),
        source = source,
        destination = metagraph
      )
      activeAllowSpends = SortedMap(source -> SortedSet(signedAllowSpend))
    } yield Fixture(source, metagraph, signedAllowSpend, spendTransaction, activeAllowSpends)

  private def balanceAfterExpiryRefund(
    f: Fixture,
    refundableExpiredAllowSpends: SortedMap[Address, SortedSet[Signed[AllowSpend]]]
  ): SortedMap[Address, Balance] =
    GlobalSnapshotAcceptanceManager
      .updateGlobalBalancesByAllowSpends(
        boundaryEpoch,
        SortedMap(f.source -> Balance(5000L)),
        SortedMap.empty,
        refundableExpiredAllowSpends
      )
      .toOption
      .get

  test("a consumed expired global AllowSpend is paid by the spend path without also being refunded") { res =>
    implicit val (hasher, securityProvider) = res
    val validator = SpendActionValidator.make[IO]

    for {
      f <- fixture
      validation <- validator.validate(
        SpendAction(NonEmptyList.one(f.spendTransaction)),
        SortedMap(none[Address] -> f.activeAllowSpends),
        Map.empty,
        f.metagraph
      )
      refundable <- GlobalSnapshotAcceptanceManager.filterExpiredGlobalAllowSpends(
        f.activeAllowSpends,
        boundaryEpoch,
        List(f.spendTransaction),
        suppressSpent = true
      )
      afterRefund = balanceAfterExpiryRefund(f, refundable)
    } yield
      expect(validation.isValid) &&
        expect(refundable.values.forall(_.isEmpty)) &&
        expect(afterRefund == SortedMap(f.source -> Balance(5000L)))
  }

  test("below activation the historical double-credit path remains reproducible") { res =>
    implicit val (hasher, securityProvider) = res

    for {
      f <- fixture
      refundable <- GlobalSnapshotAcceptanceManager.filterExpiredGlobalAllowSpends(
        f.activeAllowSpends,
        boundaryEpoch,
        List(f.spendTransaction),
        suppressSpent = false
      )
      afterRefund = balanceAfterExpiryRefund(f, refundable)
    } yield
      expect(f.activeAllowSpends == refundable) &&
        expect(afterRefund == SortedMap(f.source -> Balance(6000L)))
  }

  test("an unspent expired global AllowSpend is still refunded") { res =>
    implicit val (hasher, securityProvider) = res

    for {
      f <- fixture
      refundable <- GlobalSnapshotAcceptanceManager.filterExpiredGlobalAllowSpends(
        f.activeAllowSpends,
        boundaryEpoch,
        List.empty,
        suppressSpent = true
      )
    } yield
      expect(f.activeAllowSpends == refundable) &&
        expect(balanceAfterExpiryRefund(f, refundable) == SortedMap(f.source -> Balance(6000L)))
  }

  test("a global AllowSpend is not refundable at its last valid epoch") { res =>
    implicit val (hasher, securityProvider) = res

    for {
      f <- fixture
      refundable <- GlobalSnapshotAcceptanceManager.filterExpiredGlobalAllowSpends(
        f.activeAllowSpends,
        expiryEpoch,
        List(f.spendTransaction),
        suppressSpent = true
      )
    } yield expect(refundable.values.forall(_.isEmpty))
  }

  test("only the referenced expired global AllowSpend has its refund and expiry event suppressed") { res =>
    implicit val (hasher, securityProvider) = res

    for {
      spent <- fixture
      unspent <- fixture
      active = SortedMap(
        spent.source -> SortedSet(spent.signedAllowSpend),
        unspent.source -> SortedSet(unspent.signedAllowSpend)
      )
      refundable <- GlobalSnapshotAcceptanceManager.filterExpiredGlobalAllowSpends(
        active,
        boundaryEpoch,
        List(spent.spendTransaction),
        suppressSpent = true
      )
    } yield
      expect(refundable.get(spent.source).forall(_.isEmpty)) &&
        expect(refundable.get(unspent.source).contains(SortedSet(unspent.signedAllowSpend)))
  }
}
