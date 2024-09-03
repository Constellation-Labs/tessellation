package io.constellationnetwork.security

import cats.effect.kernel.Sync
import cats.syntax.all._

import io.constellationnetwork.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencyIncrementalSnapshotV1}
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.schema.{GlobalSnapshotInfo, GlobalSnapshotInfoV2, SnapshotOrdinal}
import io.constellationnetwork.security.hash.Hash

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
}

trait HasherSelector[F[_]] {
  def forOrdinal[A](ordinal: SnapshotOrdinal)(fn: Hasher[F] => F[A]): F[A] =
    fn(getForOrdinal(ordinal))

  def getForOrdinal(ordinal: SnapshotOrdinal): Hasher[F]

  def withCurrent[A](fn: Hasher[F] => A): A = fn(getCurrent)
  def getCurrent: Hasher[F]
}

object HasherSelector {
  def apply[F[_]: HasherSelector]: HasherSelector[F] = implicitly

  def alwaysCurrent[F[_]: HasherSelector]: HasherSelector[F] = forSyncAlwaysCurrent(HasherSelector[F].getCurrent)

  def forSync[F[_]](hasherJson: Hasher[F], hasherKryo: Hasher[F], hashSelect: HashSelect): HasherSelector[F] = new HasherSelector[F] {
    def getForOrdinal(ordinal: SnapshotOrdinal): Hasher[F] =
      hashSelect.select(ordinal) match {
        case JsonHash => hasherJson
        case KryoHash => hasherKryo
      }

    def getCurrent = hasherJson
  }

  def forSyncAlwaysCurrent[F[_]](hasherJson: Hasher[F]) = new HasherSelector[F] {
    def getCurrent = hasherJson
    def getForOrdinal(ordinal: SnapshotOrdinal): Hasher[F] = getCurrent
  }
}

object Hasher {

  def apply[F[_]: Hasher]: Hasher[F] = implicitly

  def forKryo[F[_]: Sync: KryoSerializer]: Hasher[F] = new Hasher[F] {
    def getLogic(ordinal: SnapshotOrdinal): HashLogic = KryoHash

    def compare[A: Encoder](data: A, expectedHash: Hash): F[Boolean] =
      hashKryo(data).map(_ === expectedHash)

    def hashKryo[A](data: A): F[Hash] = {
      def map[B](d: B) =
        d match {
          case g: GlobalSnapshotInfo          => GlobalSnapshotInfoV2.fromGlobalSnapshotInfo(g)
          case s: CurrencyIncrementalSnapshot => CurrencyIncrementalSnapshotV1.fromCurrencyIncrementalSnapshot(s)
          case a                              => a
        }

      KryoSerializer[F]
        .serialize(data match {
          case d: Encodable[_] => map(d.toEncode)
          case _               => map(data)
        })
        .map(Hash.fromBytes)
        .liftTo[F]
    }

    def hash[A: Encoder](data: A): F[Hash] =
      hashKryo(data)
  }

  def forJson[F[_]: Sync: JsonSerializer]: Hasher[F] = new Hasher[F] {
    def getLogic(ordinal: SnapshotOrdinal): HashLogic = JsonHash

    def compare[A: Encoder](data: A, expectedHash: Hash): F[Boolean] =
      hashJson(data).map(_ === expectedHash)

    def hashJson[A: Encoder](data: A): F[Hash] =
      (data match {
        case d: Encodable[_] =>
          JsonSerializer[F].serialize(d.toEncode)(d.jsonEncoder)
        case _ =>
          JsonSerializer[F].serialize[A](data)
      }).map(Hash.fromBytes)

    def hash[A: Encoder](data: A): F[Hash] =
      hashJson(data)
  }
}
