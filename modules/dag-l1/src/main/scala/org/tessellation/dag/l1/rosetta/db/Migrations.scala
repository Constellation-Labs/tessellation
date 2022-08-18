package org.tessellation.dag.l1.rosetta.db

import javax.sql.DataSource

import cats.effect.Async

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.output.MigrateResult

trait Migrations[F[_]] {
  def migrate: F[MigrateResult]
}

object Migrations {

  def make[F[_]: Async](dataSource: DataSource): Migrations[F] = new Migrations[F] {
    private val flyway = Flyway
      .configure()
      .dataSource(dataSource)
      .load()

    override def migrate: F[MigrateResult] = Async[F].delay(flyway.migrate())
  }

}
