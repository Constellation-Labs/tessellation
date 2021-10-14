package org.tesselation.infrastructure.cluster.storage

import cats.effect.MonadCancelThrow
import cats.syntax.applicative._
import cats.syntax.functor._

import org.tesselation.domain.cluster.storage.AddressStorage
import org.tesselation.infrastructure.db.context.AddressDBContext
import org.tesselation.infrastructure.db.doobie.DoobieTransactor
import org.tesselation.schema.address.{Address, Balance}

import doobie.implicits._
import doobie.quill.DoobieContext
import io.getquill.{H2Dialect, Literal}

object AddressStorage {

  type Context = DoobieContext.H2[Literal] with AddressDBContext[H2Dialect, Literal]

  def make[F[_]: MonadCancelThrow: DoobieTransactor]: F[AddressStorage[F]] =
    make[F](new DoobieContext.H2[Literal](Literal) with AddressDBContext[H2Dialect, Literal]).pure[F]

  def make[F[_]: MonadCancelThrow: DoobieTransactor](
    ctx: Context
  ): AddressStorage[F] = new AddressStorage[F] {

    val xa = DoobieTransactor[F].xa

    import ctx._

    override def getBalance(address: Address): F[Balance] =
      run(getAddressBalance(lift(address))).map(_.headOption.getOrElse(Balance(0))).transact(xa)

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
