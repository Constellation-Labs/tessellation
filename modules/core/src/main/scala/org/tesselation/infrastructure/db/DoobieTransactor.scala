package org.tesselation.infrastructure.db

import cats.effect.{Async, Resource}

import org.tesselation.config.types.DBConfig

import doobie.Transactor
import doobie.hikari.HikariTransactor
import doobie.util.ExecutionContexts
import eu.timepit.refined.auto._

trait DoobieTransactor[F[_]] {
  val xa: Transactor[F]
}

object DoobieTransactor {
  def apply[F[_]: DoobieTransactor]: DoobieTransactor[F] = implicitly

  def forAsync[F[_]: Async](dbConfig: DBConfig): Resource[F, DoobieTransactor[F]] = make[F](dbConfig).map {
    transactor =>
      new DoobieTransactor[F] {
        override val xa: Transactor[F] = transactor
      }
  }

  def make[F[_]: Async](dbConfig: DBConfig): Resource[F, HikariTransactor[F]] =
    for {
      executionContext <- ExecutionContexts.fixedThreadPool[F](32)
      xa <- HikariTransactor.newHikariTransactor[F](
        dbConfig.driver,
        dbConfig.url,
        dbConfig.user,
        dbConfig.password.value,
        executionContext
      )
    } yield xa
}
