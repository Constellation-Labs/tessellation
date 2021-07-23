package org.tessellation.aci

import cats.data.OptionT
import cats.effect.{Async, ContextShift}
import doobie._
import doobie.implicits._
import cats.syntax.all._

class ACIRepository[F[_]: Async: ContextShift](private val dbPath: String) {

  private val xa = Transactor
    .fromDriverManager[F]("org.sqlite.JDBC", s"jdbc:sqlite:$dbPath", "", "")

  private val createAciTypeTable: doobie.ConnectionIO[Int] =
    sql"""create table if not exists aci_type (
         |    address text not null,
         |    definition text not null,
         |    kryoRegistrationId integer not null unique,
         |    primary key(address)
         |)""".stripMargin.update.run

  private val createAciMappingTable: doobie.ConnectionIO[Int] =
    sql"""create table if not exists aci_mapping (
         |    src_type_address text not null,
         |    dst_type_address text not null,
         |    fn text not null,
         |    primary key(src_type_address, dst_type_address)
         |)""".stripMargin.update.run

  def initDb: F[Int] = {
    (createAciTypeTable, createAciMappingTable)
      .mapN(_ + _)
      .transact(xa)
  }

  def findAciType(address: String): OptionT[F, ACIType] =
    OptionT(
      sql"""select address, definition, kryoRegistrationId from aci_type where address = $address"""
        .query[ACIType]
        .option
        .transact(xa)
    )

  def findAciMappings(
    srcTypeAddress: String
  ): F[List[(ACITypeMapping, ACIType)]] =
    sql"""select m.src_type_address, m.dst_type_address, m.fn,
                 t.id, t.address, t.definition, t.kryoRegistrationId
          from aci_mapping m 
          join aci_type t
          on m.dst_type_address = t.address
         where m.src_type_address = $srcTypeAddress"""
      .query[(ACITypeMapping, ACIType)]
      .to[List]
      .transact(xa)

  def saveAciType(aciType: ACIType): F[Int] =
    sql"""insert into aci_type (address, definition, kryoRegistrationId) 
          values (${aciType.address}, ${aciType.definition}, ${aciType.kryoRegistrationId})
          on conflict (address) do update set 
            definition=excluded.definition, 
            kryoRegistrationId=excluded.kryoRegistrationId
          """.update.run
      .transact(xa)

  def maxKryoRegistrationId: OptionT[F, Int] =
    OptionT(
      sql"""select max(kryoRegistrationId) from aci_type"""
        .query[Option[Int]]
        .unique
        .transact(xa)
    )
}

object ACIRepository {
  def apply[F[_]: Async: ContextShift](dbPath: String): ACIRepository[F] =
    new ACIRepository(dbPath)
}
