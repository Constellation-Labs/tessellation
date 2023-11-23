package org.tessellation.dag.l0.infrastructure.db

import cats.effect.{Async, Resource}

import org.tessellation.dag.l0.config.types.DBConfig

import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import doobie.Transactor
import doobie.hikari.HikariTransactor
import doobie.util.ExecutionContexts
import eu.timepit.refined.auto._

trait Database[F[_]] {
  val xa: Transactor[F]
}

object Database {

  def forAsync[F[_]: Async](dbConfig: DBConfig): Resource[F, Database[F]] =
    for {
      dataSource <- {
        val config = new HikariConfig()
        config.setDriverClassName(dbConfig.driver)
        config.setJdbcUrl(dbConfig.url)
        config.setUsername(dbConfig.user)
        config.setPassword(dbConfig.password.value)

        Resource.fromAutoCloseable(Async[F].delay(new HikariDataSource(config)))
      }
      transactor <- ExecutionContexts.fixedThreadPool[F](32).map(HikariTransactor(dataSource, _))
      _ <- Resource.eval {
        Migrations.make[F](dataSource).migrate
      }
    } yield make[F](transactor)

  def make[F[_]](transactor: Transactor[F]): Database[F] = new Database[F] {
    val xa: Transactor[F] = transactor
  }
}
