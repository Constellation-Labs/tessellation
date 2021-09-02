package org.tessellation.aci

import cats.data.OptionT
import cats.effect.{Async, ContextShift}
import doobie._
import doobie.implicits._
import cats.syntax.all._

class ACIRepository[F[_]: Async: ContextShift](private val dbPath: String) {

  private val xa = Transactor
    .fromDriverManager[F]("org.sqlite.JDBC", s"jdbc:sqlite:$dbPath", "", "")

  private val createStateChannelJar: doobie.ConnectionIO[Int] =
    sql"""create table if not exists state_channel_jar (
         |    id text not null,
         |    content blob not null,
         |    primary key(id)
         |)""".stripMargin.update.run

  def initDb: F[Int] =
    createStateChannelJar
      .transact(xa)

  def saveStateChannelJar(jar: StateChannelJar): F[Int] =
    sql"""insert into state_channel_jar (id, content)
          values (${jar.id}, ${jar.content})
          """.update.run
      .transact(xa)

  def findStateChannelJar(id: String): OptionT[F, StateChannelJar] =
    OptionT(
      sql"""select s.id, s.content
          from state_channel_jar s
          where s.id = $id"""
        .query[StateChannelJar]
        .option
        .transact(xa)
    )
}

object ACIRepository {

  def apply[F[_]: Async: ContextShift](dbPath: String): ACIRepository[F] =
    new ACIRepository(dbPath)
}
