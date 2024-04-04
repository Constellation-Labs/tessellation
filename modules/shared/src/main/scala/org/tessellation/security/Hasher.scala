package org.tessellation.security

import cats.effect.kernel.Sync
import cats.syntax.all._

import org.tessellation.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencyIncrementalSnapshotV1}
import org.tessellation.json.JsonSerializer
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.snapshot.Snapshot
import org.tessellation.schema.transaction.Transaction
import org.tessellation.schema.{GlobalSnapshotInfo, GlobalSnapshotInfoV2, SnapshotOrdinal}
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed

import eu.timepit.refined.auto._
import io.circe.Encoder

sealed trait HashLogic
case object JsonHash extends HashLogic
case object KryoHash extends HashLogic

trait HashSelect {
  def select(ordinal: SnapshotOrdinal): HashLogic
}

trait Hasher[F[_]] {
  def hash[A: Encoder](data: A): F[Hash]
  def compare[A: Encoder](data: A, expectedHash: Hash): F[Boolean]
  def getLogic(ordinal: SnapshotOrdinal): HashLogic

  def hashJson[A: Encoder](data: A): F[Hash]
  def hashKryo[A](data: A): F[Hash]
}

object Hasher {

  def apply[F[_]: Hasher]: Hasher[F] = implicitly

  def forSync[F[_]: Sync: KryoSerializer: JsonSerializer](hashSelect: HashSelect): Hasher[F] = new Hasher[F] {

    def getLogic(ordinal: SnapshotOrdinal): HashLogic = hashSelect.select(ordinal)

    def hashJson[A: Encoder](data: A): F[Hash] =
      (data match {
        case d: Encodable[_] =>
          JsonSerializer[F].serialize(d.toEncode)(d.jsonEncoder)
        case _ =>
          JsonSerializer[F].serialize[A](data)
      }).map(Hash.fromBytes)

    def hashKryo[A](data: A): F[Hash] =
      KryoSerializer[F]
        .serialize(data match {
          case d: Encodable[_] => d.toEncode
          case _               => data
        })
        .map(Hash.fromBytes)
        .liftTo[F]

    def compare[A: Encoder](data: A, expectedHash: Hash): F[Boolean] =
      hashJson(data)
        .map(_ === expectedHash)
        .ifM(true.pure[F], hashKryo(data).map(_ === expectedHash))

    def hash[A: Encoder](data: A): F[Hash] = {
      def select[B](d: B): HashLogic =
        d match {
          case _: Transaction => KryoHash
          case s: Snapshot    => hashSelect.select(s.ordinal)
          case _              => JsonHash
        }

      def map[B](d: B) =
        d match {
          case g: GlobalSnapshotInfo          => GlobalSnapshotInfoV2.fromGlobalSnapshotInfo(g)
          case s: CurrencyIncrementalSnapshot => CurrencyIncrementalSnapshotV1.fromCurrencyIncrementalSnapshot(s)
          case a                              => a
        }

      val hashLogic = data match {
        case st: Signed[_] => select(st.value)
        case _             => select(data)
      }

      hashLogic match {
        case JsonHash => hashJson(data)
        case KryoHash => hashKryo(map(data))
      }
    }

  }
}
