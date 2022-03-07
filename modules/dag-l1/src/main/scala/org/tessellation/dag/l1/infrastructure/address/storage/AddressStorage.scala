package org.tessellation.dag.l1.infrastructure.address.storage

import cats.effect.MonadCancelThrow
import cats.syntax.functor._
import cats.syntax.traverse._

import org.tessellation.dag.l1.domain.address.storage.AddressStorage
import org.tessellation.dag.l1.infrastructure.db.Database
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Balance

import doobie.implicits._
import doobie.quill.DoobieContext
import eu.timepit.refined.auto._
import io.getquill.context.Context
import io.getquill.{Literal, SqliteDialect}

object AddressStorage {
  case class StoredAddress(address: Address, balance: Balance)

  type Ctx = DoobieContext.SQLite[Literal] with Context[SqliteDialect, Literal]

  def make[F[_]: MonadCancelThrow: Database]: AddressStorage[F] =
    make[F](new DoobieContext.SQLite[Literal](Literal))

  def make[F[_]: MonadCancelThrow](ctx: Ctx)(implicit db: Database[F]): AddressStorage[F] =
    new AddressStorage[F] {
      val xa = db.xa

      import ctx._

      val getAddresses = quote {
        querySchema[StoredAddress]("address")
      }

      val getAddressBalance = quote { (address: Address) =>
        getAddresses.filter(_.address == address).map(_.balance).take(1)
      }

      val insertAddressBalance = quote { (address: Address, balance: Balance) =>
        getAddresses
          .insert(_.address -> address, _.balance -> balance)
      }

      val updateAddressBalance = quote { (address: Address, balance: Balance) =>
        getAddresses
          .filter(_.address == address)
          .update(_.balance -> balance)
      }

      def getBalance(address: Address): F[Balance] =
        run(getAddressBalance(lift(address))).map(_.headOption.getOrElse(Balance.empty)).transact(xa)

      def updateBalances(addressBalances: Map[Address, Balance]): F[Unit] =
        addressBalances.toList.traverse {
          case (address, balance) =>
            run(getAddressBalance(lift(address)))
              .map(_.headOption)
              .flatMap {
                case Some(_) => run(updateAddressBalance(lift(address), lift(balance)))
                case None    => run(insertAddressBalance(lift(address), lift(balance)))
              }
        }.transact(xa).as(())
    }
}
