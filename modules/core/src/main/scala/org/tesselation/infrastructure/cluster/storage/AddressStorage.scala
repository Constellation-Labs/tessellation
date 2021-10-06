package org.tesselation.infrastructure.cluster.storage

import cats.effect.Async

import org.tesselation.domain.cluster.storage.AddressStorage
import org.tesselation.infrastructure.db.context.AddressDBContext
import org.tesselation.infrastructure.db.{DoobieTransactor, schema}
import org.tesselation.schema.address.{Address, Balance}

import doobie.implicits._
import doobie.quill.DoobieContextBase
import io.getquill.{Literal, PostgresDialect}

object AddressStorage {

  def make[F[_]: Async: DoobieTransactor](
    ctx: DoobieContextBase[PostgresDialect, Literal] with AddressDBContext[PostgresDialect, Literal]
  ): AddressStorage[F] = new AddressStorage[F] {

    val xa = DoobieTransactor[F].xa

    import ctx._

    override def getBalance(address: Address): F[Balance] =
      run(getAddressBalance(lift(address))).map(_.headOption.getOrElse(Balance(0))).transact(xa)

    override def updateBalance(address: Address, balance: Balance): F[schema.StoredAddress] =
      run(insertOrUpdateAddressBalance(lift(address), lift(balance)))
        .map(_ => schema.StoredAddress(address, balance))
        .transact(xa)

    override def clearBalance(address: Address): F[Unit] =
      run(deleteAddressBalance(lift(address))).map(_ => ()).transact(xa)
  }
}
