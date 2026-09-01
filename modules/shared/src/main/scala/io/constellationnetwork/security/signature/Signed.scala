package io.constellationnetwork.security.signature

import java.security.KeyPair

import cats._
import cats.data.NonEmptySet
import cats.effect.Async
import cats.syntax.applicative._
import cats.syntax.contravariant._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.list._
import cats.syntax.order._
import cats.syntax.show._

import scala.collection.immutable.SortedSet
import scala.util.control.NoStackTrace

import io.constellationnetwork.ext.codecs.NonEmptySetCodec
import io.constellationnetwork.ext.crypto._
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.security.hash.{Hash, ProofsHash}
import io.constellationnetwork.security.signature.signature.SignatureProof
import io.constellationnetwork.security.{Hashed, Hasher, SecurityProvider}

import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import io.circe.{Decoder, Encoder}
import io.estatico.newtype.ops._
import org.scalacheck.Arbitrary
import org.scalacheck.Arbitrary.arbitrary

case class Signed[+A](value: A, proofs: NonEmptySet[SignatureProof])

object Signed {
  implicit val functor: Functor[Signed] = new Functor[Signed] {
    def map[A, B](fa: Signed[A])(f: A => B): Signed[B] = Signed(f(fa.value), fa.proofs)
  }

  case class SignedHasher[F[_]](hasher: Hasher[F])

  case class ProofsHasher[F[_]](hasher: Hasher[F])

  case class InvalidSignatureForHash[A](signed: Signed[A]) extends NoStackTrace

  object InvalidSignatureForHash {
    implicit val functor: Functor[InvalidSignatureForHash] = new Functor[InvalidSignatureForHash] {
      def map[A, B](fa: InvalidSignatureForHash[A])(f: A => B): InvalidSignatureForHash[B] =
        InvalidSignatureForHash(fa.signed.map(f))
    }
  }

  implicit def show[A: Show]: Show[Signed[A]] =
    s => s"Signed(value=${s.value.show}, proofs=${s.proofs.show})"

  implicit def encoder[A: Encoder]: Encoder[Signed[A]] = deriveEncoder

  implicit val proofsEncoder: Encoder[NonEmptySet[SignatureProof]] = NonEmptySetCodec.encoder[SignatureProof]

  implicit val proofsDecoder: Decoder[NonEmptySet[SignatureProof]] = NonEmptySetCodec.decoder[SignatureProof]

  implicit def decoder[A: Decoder]: Decoder[Signed[A]] = deriveDecoder

  implicit def autoUnwrap[T](t: Signed[T]): T = t.value

  /** Semantic equality intentionally ignores the proof envelope.
    *
    * Different honest observers may collect different valid quorum proof subsets for the same value. Callers at an authority boundary where
    * the proof set itself carries meaning (for example, selecting a genesis committee from its signers) must use [[sameValueAndProofs]]
    * explicitly.
    */
  implicit def eq[A: Eq]: Eq[Signed[A]] = (a, b) => Eq[A].eqv(a, b)

  /** Proof-aware equality for the narrow trust boundaries where both the signed value and its exact proof set are authoritative. */
  def sameValueAndProofs[A: Eq](left: Signed[A], right: Signed[A]): Boolean =
    Eq[A].eqv(left.value, right.value) &&
      Eq[NonEmptySet[SignatureProof]].eqv(left.proofs, right.proofs)

  implicit def order[A: Order]: Order[Signed[A]] = Order.fromOrdering(ordering(Order[A].toOrdering))

  implicit def ordering[A: Ordering]: Ordering[Signed[A]] = new SignedOrdering[A]()

  implicit def _arbitrary[A: Arbitrary]: Arbitrary[Signed[A]] =
    Arbitrary(for {
      value <- arbitrary[A]
      head <- arbitrary[SignatureProof]
      tail <- arbitrary[SortedSet[SignatureProof]]
    } yield Signed(value, NonEmptySet(head, tail)))

  def forAsyncHasher[F[_]: Async: SecurityProvider: Hasher, A: Encoder](
    data: A,
    keyPair: KeyPair
  ): F[Signed[A]] =
    SignatureProof.fromData(keyPair)(data).map { sp =>
      Signed[A](data, NonEmptySet.fromSetUnsafe(SortedSet(sp)))
    }

  def forAsync[F[_]: Async: SecurityProvider, A](
    data: A,
    keyPair: KeyPair
  )(implicit toBytes: A => F[Array[Byte]]): F[Signed[A]] =
    toBytes(data).flatMap(Hash.fromBytesForSync[F]).flatMap { hash =>
      SignatureProof.fromHash(keyPair, hash).map { sp =>
        Signed[A](data, NonEmptySet.fromSetUnsafe(SortedSet(sp)))
      }
    }

  implicit class SignedOps[A](signed: Signed[A]) {

    def addProof(proof: SignatureProof): Signed[A] =
      signed.copy(proofs = NonEmptySet.fromSetUnsafe(signed.proofs.toSortedSet + proof))

    def signAlsoWith[F[_]: Async: SecurityProvider: Hasher](keyPair: KeyPair)(implicit encoder: Encoder[A]): F[Signed[A]] =
      SignatureProof.fromData(keyPair)(signed.value).map { sp =>
        Signed(signed.value, signed.proofs.add(sp))
      }

    def isSignedBy(signer: Id): Boolean = isSignedBy(Set(signer))

    def isSignedBy(signers: Set[Id]): Boolean =
      signers.forall(signed.proofs.map(_.id).contains(_))

    def isSignedExclusivelyBy(signer: Id): Boolean = isSignedExclusivelyBy(Set(signer))

    def isSignedExclusivelyBy(signers: Set[Id]): Boolean =
      signed.proofs.map(_.id).toSortedSet.unsorted === signers

    def hasValidSignature[F[_]: Async: SecurityProvider: Hasher](implicit encoder: Encoder[A]): F[Boolean] =
      validProofs(encoder.asRight).map(_.isRight)

    def hasValidSignature[F[_]: Async: SecurityProvider: Hasher](toBytes: A => F[Array[Byte]]): F[Boolean] =
      validProofs(toBytes.asLeft).map(_.isRight)

    def validProofs[F[_]: Async: SecurityProvider: Hasher](
      toBytesOrEncoder: Either[A => F[Array[Byte]], Encoder[A]]
    ): F[Either[NonEmptySet[SignatureProof], NonEmptySet[SignatureProof]]] =
      signed.proofs.toNonEmptyList.traverse { proof =>
        toBytesOrEncoder.fold(
          toBytes =>
            toHashed(toBytes).map(_.hash).flatMap { hash =>
              signature.verifySignatureProof(hash, proof).map(proof -> _)
            },
          implicit encoder =>
            Hasher[F]
              .hash(signed.value)
              .flatMap(signature.verifySignatureProof(_, proof))
              .map(result => proof -> result)
        )
      }.map {
        _.collect {
          case (proof, false) => proof
        }.toNel
          .map(_.toNes)
          .toLeft(signed.proofs)
      }

    def toHashedWithSignatureCheck[F[_]: Async: Hasher: SecurityProvider](
      implicit encoder: Encoder[A]
    ): F[Either[InvalidSignatureForHash[A], Hashed[A]]] =
      hasValidSignature.ifM(
        toHashed.map(_.asRight[InvalidSignatureForHash[A]]),
        InvalidSignatureForHash(signed).asLeft[Hashed[A]].pure[F]
      )

    def toHashedWithSignatureCheck[F[_]: Async: Hasher: SecurityProvider](
      toBytes: A => F[Array[Byte]]
    ): F[Either[InvalidSignatureForHash[A], Hashed[A]]] =
      hasValidSignature(toBytes).ifM(
        toHashed(toBytes).map(_.asRight[InvalidSignatureForHash[A]]),
        InvalidSignatureForHash(signed).asLeft[Hashed[A]].pure[F]
      )

    def toHashedHybrid[F[_]: Async](
      implicit encoder: Encoder[A],
      signedHasher: SignedHasher[F],
      proofsHasher: ProofsHasher[F]
    ): F[Hashed[A]] =
      for {
        hash <- {
          implicit val h: Hasher[F] = signedHasher.hasher
          signed.value.hash
        }
        proofsHash <- {
          implicit val h: Hasher[F] = proofsHasher.hasher
          proofsHash[F]
        }
      } yield Hashed(signed, hash, proofsHash)

    def toHashed[F[_]: Async: Hasher](implicit encoder: Encoder[A]): F[Hashed[A]] =
      signed.value.hash.flatMap { hash =>
        proofsHash.map { proofsHash =>
          Hashed(signed, hash, proofsHash)
        }
      }

    def toHashed[F[_]: Async: Hasher](toBytes: A => F[Array[Byte]]): F[Hashed[A]] =
      toBytes(signed.value).flatMap(Hash.fromBytesForSync[F]).flatMap { hash =>
        proofsHash.map(Hashed(signed, hash, _))
      }

    def proofsHash[F[_]: Async: Hasher]: F[ProofsHash] =
      signed.proofs.toSortedSet.hash
        .map(hash => ProofsHash(hash.coerce))
  }

  final class SignedOrdering[A](implicit evidence$25: Ordering[A]) extends Ordering[Signed[A]] {

    def compare(x: Signed[A], y: Signed[A]): Int =
      Order
        .whenEqual(
          Order.fromOrdering(Ordering[A]).contramap[Signed[A]](s => s.value),
          Order[NonEmptySet[SignatureProof]].contramap[Signed[A]](s => s.proofs)
        )
        .compare(x, y)
  }
}
