package org.tesselation.infrastructure.db.doobie

import cats.effect.{Async, Resource}

import doobie.Transactor
import doobie.hikari.HikariTransactor
import doobie.util.ExecutionContexts

trait DoobieTransactor[F[_]] {
  val xa: Transactor[F]
}

object DoobieTransactor {
  def apply[F[_]: DoobieTransactor]: DoobieTransactor[F] = implicitly

  def forAsync[F[_]: Async: DoobieDataSource]: Resource[F, DoobieTransactor[F]] =
    make[F].map { transactor =>
      new DoobieTransactor[F] {
        override val xa: Transactor[F] = transactor
      }
    }

  def make[F[_]: Async: DoobieDataSource]: Resource[F, HikariTransactor[F]] =
    ExecutionContexts.fixedThreadPool[F](32).map(HikariTransactor(DoobieDataSource[F].ds, _))
}
