package org.tesselation.infrastructure.db

import cats.effect.Async

import org.tesselation.infrastructure.db.doobie.DoobieDataSource

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.output.MigrateResult

trait Migrations[F[_]] {
  def migrate: F[MigrateResult]
}

object Migrations {

  def make[F[_]: Async: DoobieDataSource]: Migrations[F] = new Migrations[F] {
    private val flyway = Flyway
      .configure()
      .dataSource(DoobieDataSource[F].ds)
      .load()

    override def migrate: F[MigrateResult] = Async[F].delay(flyway.migrate())
  }

}
