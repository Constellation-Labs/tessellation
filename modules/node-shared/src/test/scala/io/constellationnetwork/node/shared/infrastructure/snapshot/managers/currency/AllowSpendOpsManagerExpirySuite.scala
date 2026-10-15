package io.constellationnetwork.node.shared.infrastructure.snapshot.managers.currency

import cats.effect.{IO, Resource}
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.artifact.SpendTransaction
import io.constellationnetwork.schema.balance.Balance
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.swap._
import io.constellationnetwork.security._
import io.constellationnetwork.security.key.ops.PublicKeyOps
import io.constellationnetwork.security.signature.Signed

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.{NonNegLong, PosLong}
import weaver.MutableIOSuite

/** Allow-spend expiry selection must reproduce release/mainnet CurrencySnapshotAcceptanceManager.filterExpiredAllowSpends on both sides of
  * fixing-allow-spend-expiration (mainnet 5033174, live).
  */
object AllowSpendOpsManagerExpirySuite extends MutableIOSuite {

  type Res = (Hasher[IO], SecurityProvider[IO])

  def sharedResource: Resource[IO, Res] = for {
    sp <- SecurityProvider.forAsync[IO]
    implicit0(j: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO].asResource
  } yield (Hasher.forJson[IO], sp)

  private val manager = AllowSpendOpsManager.make[IO]
  private val gate = SnapshotOrdinal.unsafeApply(100L)
  private val aboveGate = SnapshotOrdinal.unsafeApply(101L)
  private val belowGate = SnapshotOrdinal.unsafeApply(100L)
  private val epoch = EpochProgress(NonNegLong.unsafeFrom(10L))

  private case class Fixture(
    expiredOwner: Address,
    activeOwner: Address,
    destination: Address,
    expired: Signed[AllowSpend],
    active: Signed[AllowSpend]
  ) {
    val allowSpends: SortedMap[Address, SortedSet[Signed[AllowSpend]]] =
      SortedMap(expiredOwner -> SortedSet(expired), activeOwner -> SortedSet(active))
    val directSpend: SpendTransaction = SpendTransaction(None, None, SwapAmount(5L), activeOwner, destination)
  }

  private def fixture(implicit hasher: Hasher[IO], sp: SecurityProvider[IO]): IO[Fixture] =
    for {
      expiredKey <- KeyPairGenerator.makeKeyPair[IO]
      activeKey <- KeyPairGenerator.makeKeyPair[IO]
      destinationKey <- KeyPairGenerator.makeKeyPair[IO]
      destination = destinationKey.getPublic.toAddress
      expired <- Signed.forAsyncHasher(allowSpend(expiredKey.getPublic.toAddress, destination, 100L, 5L), expiredKey)
      active <- Signed.forAsyncHasher(allowSpend(activeKey.getPublic.toAddress, destination, 200L, 50L), activeKey)
    } yield Fixture(expiredKey.getPublic.toAddress, activeKey.getPublic.toAddress, destination, expired, active)

  private def allowSpend(source: Address, destination: Address, amount: Long, lastValidEpoch: Long): AllowSpend =
    AllowSpend(
      source,
      destination,
      None,
      SwapAmount(PosLong.unsafeFrom(amount)),
      AllowSpendFee(1L),
      AllowSpendReference.empty,
      EpochProgress(NonNegLong.unsafeFrom(lastValidEpoch)),
      List(destination)
    )

  test("a direct spend (no allow-spend reference) does not suppress expiry of an unused allow spend above the gate") { res =>
    implicit val (hasher, sp) = res

    for {
      f <- fixture
      expired <- manager.filterExpiredAllowSpends(f.allowSpends, epoch, List(f.directSpend), aboveGate, gate)
      balances <- manager.updateCurrencyBalancesByAllowSpends(
        epoch,
        SortedMap(f.expiredOwner -> Balance(NonNegLong.unsafeFrom(7L))),
        SortedMap.empty,
        f.allowSpends,
        List(f.directSpend),
        aboveGate,
        gate
      )
    } yield
      expect(
        expired.get(f.expiredOwner).contains(SortedSet(f.expired)),
        s"the unused expired allow spend is still selected: $expired"
      ).and(
        expect(
          balances.toOption.flatMap(_.get(f.expiredOwner)).contains(Balance(NonNegLong.unsafeFrom(107L))),
          s"its amount is refunded to the source: $balances"
        )
      )
  }

  test("a spent allow spend is not expired above the gate") { res =>
    implicit val (hasher, sp) = res

    for {
      f <- fixture
      hashed <- f.expired.toHashed
      spend = SpendTransaction(hashed.hash.some, None, SwapAmount(100L), f.expiredOwner, f.destination)
      expired <- manager.filterExpiredAllowSpends(f.allowSpends, epoch, List(spend, f.directSpend), aboveGate, gate)
    } yield expect(expired.get(f.expiredOwner).contains(SortedSet.empty[Signed[AllowSpend]]), s"spent reference is not refunded: $expired")
  }

  test("every address keeps an entry, empty when nothing expired, on both sides of the gate") { res =>
    implicit val (hasher, sp) = res

    List(belowGate, aboveGate).traverse { ordinal =>
      for {
        f <- fixture
        expired <- manager.filterExpiredAllowSpends(f.allowSpends, epoch, List.empty, ordinal, gate)
        balances <- manager.updateCurrencyBalancesByAllowSpends(
          epoch,
          SortedMap.empty,
          SortedMap.empty,
          f.allowSpends,
          List.empty,
          ordinal,
          gate
        )
      } yield
        expect(
          expired == SortedMap(f.expiredOwner -> SortedSet(f.expired), f.activeOwner -> SortedSet.empty[Signed[AllowSpend]]),
          s"ordinal=$ordinal: $expired"
        ).and(
          expect(
            balances.toOption.flatMap(_.get(f.activeOwner)).contains(Balance.empty),
            s"ordinal=$ordinal: mainnet records a balance entry for the active owner: $balances"
          )
        )
    }.map(_.combineAll)
  }
}
