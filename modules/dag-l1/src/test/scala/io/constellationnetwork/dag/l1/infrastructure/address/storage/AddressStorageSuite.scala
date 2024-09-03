package io.constellationnetwork.dag.l1.infrastructure.address.storage

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.syntax.eq._

import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Balance
import io.constellationnetwork.schema.generators._

import eu.timepit.refined.auto._
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

object AddressStorageSuite extends SimpleIOSuite with Checkers {
  val gen = for {
    address <- addressGen
    balance <- balanceGen
  } yield (address, balance)

  test("getBalance returns empty balance by default") {
    forall(addressGen) { address =>
      for {
        storage <- AddressStorage.make[IO]
        get <- storage.getBalance(address)
      } yield expect.same(get, Balance.empty)
    }
  }

  test("getBalance returns balance for given address") {
    forall(gen) {
      case (address, balance) =>
        for {
          ref <- Ref.of[IO, Map[Address, Balance]](Map(address -> balance))
          storage = AddressStorage.make[IO](ref)
          get <- storage.getBalance(address)
        } yield expect.same(get, balance)
    }
  }

  test("updateBalances overwrites existing balances for given addresses") {
    val gen = for {
      initialBalance <- balanceGen
      address1 <- addressGen
      address2 <- addressGen
      balance1 <- balanceGen
      balance2 <- balanceGen
    } yield (initialBalance, address1, address2, balance1, balance2)

    forall(gen) {
      case (initialBalance, address1, address2, balance1, balance2) =>
        for {
          ref <- Ref.of[IO, Map[Address, Balance]](Map(address1 -> initialBalance, address2 -> initialBalance))
          storage = AddressStorage.make[IO](ref)

          _ <- storage.updateBalances(Map(address1 -> balance1, address2 -> balance2))

          get1 <- storage.getBalance(address1)
          get2 <- storage.getBalance(address2)
        } yield expect.all(get1 === balance1, get2 === balance2)

    }
  }

  test("clean removes all balances") {
    forall(gen) {
      case (address, balance) =>
        for {
          ref <- Ref.of[IO, Map[Address, Balance]](Map(address -> balance))
          storage = AddressStorage.make[IO](ref)
          _ <- storage.clean
          get <- storage.getBalance(address)
        } yield expect.same(get, Balance.empty)
    }
  }
}
