package org.tesselation.infrastructure.db.doobie

import cats.effect.{Async, Resource}

import org.tesselation.config.types.DBConfig

import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import eu.timepit.refined.auto._

trait DoobieDataSource[F[_]] {
  val ds: HikariDataSource
}

object DoobieDataSource {
  def apply[F[_]: DoobieDataSource]: DoobieDataSource[F] = implicitly

  def forAsync[F[_]: Async](dbConfig: DBConfig): Resource[F, DoobieDataSource[F]] = make[F](dbConfig).map {
    dataSource =>
      new DoobieDataSource[F] {
        override val ds: HikariDataSource = dataSource
      }
  }

  def make[F[_]: Async](dbConfig: DBConfig): Resource[F, HikariDataSource] = {
    val config = new HikariConfig()
    config.setDriverClassName(dbConfig.driver)
    config.setJdbcUrl(dbConfig.url)
    config.setUsername(dbConfig.user)
    config.setPassword(dbConfig.password.value)

    Resource.fromAutoCloseable(Async[F].delay(new HikariDataSource(config)))
  }

}
