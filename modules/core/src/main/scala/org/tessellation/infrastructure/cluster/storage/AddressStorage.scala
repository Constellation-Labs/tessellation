package org.tessellation.infrastructure.cluster.storage

import cats.effect.MonadCancelThrow
import cats.syntax.applicative._
import cats.syntax.functor._

import org.tessellation.domain.cluster.storage.AddressStorage
import org.tessellation.infrastructure.db.Database
import org.tessellation.infrastructure.db.context.AddressDBContext
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Balance

import doobie.implicits._
import doobie.quill.DoobieContext
import eu.timepit.refined.auto._
import io.getquill.{H2Dialect, Literal}

object AddressStorage {

  type Context = DoobieContext.H2[Literal] with AddressDBContext[H2Dialect, Literal]

  def make[F[_]: MonadCancelThrow: Database]: F[AddressStorage[F]] =
    make[F](new DoobieContext.H2[Literal](Literal) with AddressDBContext[H2Dialect, Literal]).pure[F]

  def make[F[_]: MonadCancelThrow](ctx: Context)(implicit db: Database[F]): AddressStorage[F] =
    new AddressStorage[F] {
      val xa = db.xa

      import ctx._

      override def getBalance(address: Address): F[Balance] =
        run(getAddressBalance(lift(address))).map(_.headOption.getOrElse(Balance(BigInt(0)))).transact(xa)

      override def updateBalance(address: Address, balance: Balance): F[(Address, Balance)] =
        run(getAddressBalance(lift(address)))
          .map(_.headOption)
          .flatMap {
            case Some(_) => run(updateAddressBalance(lift(address), lift(balance)))
            case None    => run(insertAddressBalance(lift(address), lift(balance)))
          }
          .transact(xa)
          .as((address, balance))

      override def clearBalance(address: Address): F[Unit] =
        run(deleteAddressBalance(lift(address))).map(_ => ()).transact(xa)
    }
}
