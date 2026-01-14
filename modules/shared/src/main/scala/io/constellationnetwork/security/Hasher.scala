package io.constellationnetwork.security

import cats.effect.kernel.{Ref, Sync}
import cats.syntax.all._

import scala.concurrent.duration._

import io.constellationnetwork.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencyIncrementalSnapshotV1}
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.{CustomKryoSerializer, KryoSerializer}
import io.constellationnetwork.schema.transaction.Transaction
import io.constellationnetwork.schema.{GlobalSnapshotInfo, GlobalSnapshotInfoV2, SnapshotOrdinal}
import io.constellationnetwork.security.hash.Hash

import com.github.blemale.scaffeine.{Cache, Scaffeine}
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
  def prefixedHash[A: Encoder](data: A, prefix: Array[Byte]): F[Hash]
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

  /** Cache key wrapper using identity semantics (reference equality).
    *
    * Using the data object directly as a cache key requires computing hashCode() on every lookup, which for large objects like
    * GlobalSnapshotInfo means iterating millions of map entries. Identity-based keys use System.identityHashCode() which is O(1).
    */
  private class IdentityKey(val value: AnyRef) {
    override def hashCode(): Int = System.identityHashCode(value)
    override def equals(obj: Any): Boolean = obj match {
      case other: IdentityKey => value eq other.value
      case _                  => false
    }
  }

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

      data match {
        case transaction: Transaction =>
          CustomKryoSerializer.hash(transaction.toEncode)
        case _ =>
          KryoSerializer[F]
            .serialize(data match {
              case d: Encodable[_] => map(d.toEncode)
              case _               => map(data)
            })
            .map(Hash.fromBytes)
            .liftTo[F]
      }
    }

    def hash[A: Encoder](data: A): F[Hash] =
      hashKryo(data)

    def prefixedHash[A: Encoder](data: A, prefix: Array[Byte]): F[Hash] =
      KryoSerializer[F]
        .serialize(data)
        .map(bytes => prefix ++ bytes)
        .liftTo[F]
        .flatMap(Hash.fromBytesForSync[F])
  }

  def forJson[F[_]: Sync: JsonSerializer]: Hasher[F] =
    if (sys.env.get("CL_DISABLE_HASH_CACHE").exists(_.toLowerCase == "true")) forJsonUncached[F]
    else forJsonCached[F]

  def forJsonCached[F[_]: Sync: JsonSerializer]: Hasher[F] = {
    val cache: Cache[IdentityKey, Hash] = Scaffeine()
      .recordStats()
      .expireAfterAccess(5.minutes)
      .maximumSize(10000)
      .softValues()
      .build[IdentityKey, Hash]()

    new Hasher[F] {
      def getLogic(ordinal: SnapshotOrdinal): HashLogic = JsonHash

      def compare[A: Encoder](data: A, expectedHash: Hash): F[Boolean] =
        hashJson(data).map(_ === expectedHash)

      def hashJson[A: Encoder](data: A): F[Hash] = {
        val key = new IdentityKey(data.asInstanceOf[AnyRef])
        Sync[F].delay(cache.getIfPresent(key)).flatMap {
          case Some(hash) => hash.pure[F]
          case None =>
            computeHash(data).flatTap { hash =>
              Sync[F].delay(cache.put(key, hash))
            }
        }
      }

      private def computeHash[A: Encoder](data: A): F[Hash] =
        (data match {
          case d: Encodable[_] =>
            JsonSerializer[F].serialize(d.toEncode)(d.jsonEncoder)
          case _ =>
            JsonSerializer[F].serialize[A](data)
        }).flatMap(Hash.fromBytesForSync[F])

      def hash[A: Encoder](data: A): F[Hash] =
        hashJson(data)

      def prefixedHash[A: Encoder](data: A, prefix: Array[Byte]): F[Hash] =
        JsonSerializer[F]
          .serialize(data)
          .map(bytes => prefix ++ bytes)
          .flatMap(Hash.fromBytesForSync[F])
    }
  }

  def forJsonUncached[F[_]: Sync: JsonSerializer]: Hasher[F] = new Hasher[F] {
    def getLogic(ordinal: SnapshotOrdinal): HashLogic = JsonHash

    def compare[A: Encoder](data: A, expectedHash: Hash): F[Boolean] =
      hashJson(data).map(_ === expectedHash)

    def hashJson[A: Encoder](data: A): F[Hash] =
      (data match {
        case d: Encodable[_] =>
          JsonSerializer[F].serialize(d.toEncode)(d.jsonEncoder)
        case _ =>
          JsonSerializer[F].serialize[A](data)
      }).flatMap(Hash.fromBytesForSync[F])

    def hash[A: Encoder](data: A): F[Hash] =
      hashJson(data)

    def prefixedHash[A: Encoder](data: A, prefix: Array[Byte]): F[Hash] =
      JsonSerializer[F]
        .serialize(data)
        .map(bytes => prefix ++ bytes)
        .flatMap(Hash.fromBytesForSync[F])
  }
}
