package org.tessellation.schema.security.signature

import java.security.KeyPair

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
import cats.{Order, Show}

import scala.collection.immutable.SortedSet
import scala.util.control.NoStackTrace

import org.tessellation.ext.codecs.NonEmptySetCodec
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.ID.Id
import org.tessellation.schema.ext.crypto._
import org.tessellation.schema.security.hash.ProofsHash
import org.tessellation.schema.security.signature.signature.SignatureProof
import org.tessellation.schema.security.{Hashed, SecurityProvider}

import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import io.circe.{Decoder, Encoder}
import io.estatico.newtype.ops._

case class Signed[+A](value: A, proofs: NonEmptySet[SignatureProof])

object Signed {
  case class InvalidSignatureForHash[A <: AnyRef](signed: Signed[A]) extends NoStackTrace

  implicit def show[A: Show]: Show[Signed[A]] =
    s => s"Signed(value=${s.value.show}, proofs=${s.proofs.show})"

  implicit def encoder[A: Encoder]: Encoder[Signed[A]] = deriveEncoder

  implicit val proofsDecoder: Decoder[NonEmptySet[SignatureProof]] = NonEmptySetCodec.decoder[SignatureProof]

  implicit def decoder[A: Decoder]: Decoder[Signed[A]] = deriveDecoder

  implicit def autoUnwrap[T](t: Signed[T]): T = t.value

  implicit def order[A: Order]: Order[Signed[A]] = Order.fromOrdering(ordering(Order[A].toOrdering))

  implicit def ordering[A: Ordering]: Ordering[Signed[A]] = new SignedOrdering[A]()

  def forAsyncKryo[F[_]: Async: SecurityProvider: KryoSerializer, A <: AnyRef](
    data: A,
    keyPair: KeyPair
  ): F[Signed[A]] =
    SignatureProof.fromData(keyPair)(data).map { sp =>
      Signed[A](data, NonEmptySet.fromSetUnsafe(SortedSet(sp)))
    }

  implicit class SignedOps[A <: AnyRef](signed: Signed[A]) {

    def addProof(proof: SignatureProof): Signed[A] =
      signed.copy(proofs = NonEmptySet.fromSetUnsafe(signed.proofs.toSortedSet + proof))

    def signAlsoWith[F[_]: Async: SecurityProvider: KryoSerializer](keyPair: KeyPair): F[Signed[A]] =
      SignatureProof.fromData(keyPair)(signed.value).map { sp =>
        Signed(signed.value, signed.proofs.add(sp))
      }

    def isSignedBy(signer: Id): Boolean = isSignedBy(Set(signer))

    def isSignedBy(signers: Set[Id]): Boolean =
      signers.forall(signed.proofs.map(_.id).contains(_))

    def isSignedExclusivelyBy(signer: Id): Boolean = isSignedExclusivelyBy(Set(signer))

    def isSignedExclusivelyBy(signers: Set[Id]): Boolean =
      signed.proofs.map(_.id).toSortedSet.unsorted === signers

    def hasValidSignature[F[_]: Async: SecurityProvider: KryoSerializer]: F[Boolean] =
      validProofs.map(_.isRight)

    def validProofs[F[_]: Async: SecurityProvider: KryoSerializer]: F[Either[NonEmptySet[SignatureProof], NonEmptySet[SignatureProof]]] =
      for {
        hash <- signed.value.hashF
        invalidOrValidProofs <- signed.proofs.toNonEmptyList.traverse { proof =>
          signature
            .verifySignatureProof(hash, proof)
            .map(result => proof -> result)
        }.map { proofsAndResults =>
          proofsAndResults
            .filterNot(_._2)
            .map(_._1)
            .toNel
            .map(_.toNes)
            .toLeft(signed.proofs)
        }
      } yield invalidOrValidProofs

    def toHashedWithSignatureCheck[F[_]: Async: KryoSerializer: SecurityProvider]: F[Either[InvalidSignatureForHash[A], Hashed[A]]] =
      hasValidSignature.ifM(
        toHashed.map(_.asRight[InvalidSignatureForHash[A]]),
        InvalidSignatureForHash(signed).asLeft[Hashed[A]].pure[F]
      )

    def toHashed[F[_]: Async: KryoSerializer]: F[Hashed[A]] =
      signed.value.hashF.flatMap { hash =>
        proofsHash.map { proofsHash =>
          Hashed(signed, hash, proofsHash)
        }
      }

    def proofsHash[F[_]: Async: KryoSerializer]: F[ProofsHash] =
      signed.proofs.toSortedSet.hashF
        .map(hash => ProofsHash(hash.coerce))
  }

  final class SignedOrdering[A: Ordering] extends Ordering[Signed[A]] {

    def compare(x: Signed[A], y: Signed[A]): Int =
      Order
        .whenEqual(
          Order.fromOrdering(Ordering[A]).contramap[Signed[A]](s => s.value),
          Order[NonEmptySet[SignatureProof]].contramap[Signed[A]](s => s.proofs)
        )
        .compare(x, y)
  }
}
