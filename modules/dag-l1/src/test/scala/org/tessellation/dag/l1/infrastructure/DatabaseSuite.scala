package org.tessellation.dag.l1.infrastructure.db

import cats.effect.IO

import org.tessellation.dag.l1.config.types.DBConfig
import org.tessellation.dag.l1.infrastructure.address.storage.AddressStorage
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Balance

import ciris.Secret
import eu.timepit.refined.auto._
import eu.timepit.refined.types.all.NonNegLong
import weaver.SimpleIOSuite

object DatabaseSuite extends SimpleIOSuite {
  test("Test writing to the database.") {
    val dbConfig = new DBConfig("org.sqlite.JDBC", "jdbc:sqlite:test.db", "sa", Secret(""))
    Database.forAsync[IO](dbConfig).use { implicit db =>
      val addressStorage = AddressStorage.make[IO]
      val walletAddress = Address("DAG3k3VihUWMjse9LE93jRqZLEuwGd6a5Ypk4zYS")
      val testBalance = Balance(NonNegLong(1000000000000000000L))

      for {
        _ <- addressStorage.updateBalances(Map(walletAddress -> testBalance)) // Map(A -> B) creates a tuple.
        balance <- addressStorage.getBalance(walletAddress)
      } yield expect.same(balance, testBalance)
    }
  }
}
