package org.tessellation.security.signature

import java.security.KeyPair

import cats.data.NonEmptyList
import cats.effect.Async
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.show._
import cats.{Order, Semigroup, Show}

import scala.util.control.NoStackTrace

import org.tessellation.ext.crypto._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.ID.Id
import org.tessellation.security.hash.ProofsHash
import org.tessellation.security.signature.signature.SignatureProof
import org.tessellation.security.{Hashed, SecurityProvider}

import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import io.circe.{Decoder, Encoder}

case class Signed[+A](value: A, proofs: NonEmptyList[SignatureProof])

object Signed {
  case class InvalidSignatureForHash[A <: AnyRef](signed: Signed[A]) extends NoStackTrace

  implicit def show[A: Show]: Show[Signed[A]] =
    s => s"Signed(value=${s.value.show}, proofs=${s.proofs.show})"

  implicit def encoder[A: Encoder]: Encoder[Signed[A]] = deriveEncoder

  implicit def decoder[A: Decoder]: Decoder[Signed[A]] = deriveDecoder

  implicit def autoUnwrap[T](t: Signed[T]): T = t.value

  implicit def order[A: Order]: Order[Signed[A]] = (x: Signed[A], y: Signed[A]) => Order[A].compare(x, y)

  implicit def ordering[A: Order]: Ordering[Signed[A]] = order.toOrdering

  def forAsyncKryo[F[_]: Async: SecurityProvider: KryoSerializer, A <: AnyRef](
    data: A,
    keyPair: KeyPair
  ): F[Signed[A]] =
    SignatureProof.fromData(keyPair)(data).map { sp =>
      Signed[A](data, NonEmptyList.one(sp))
    }

  implicit def semigroup[A]: Semigroup[Signed[A]] = Semigroup.instance { (a, b) =>
    Signed(a.value, a.proofs ::: b.proofs)
  }

  implicit class SignedOps[A <: AnyRef](signed: Signed[A]) {

    def isSignedBy(signers: Set[Id]): Boolean =
      signed.proofs.map(_.id).toList.toSet == signers

    def hasValidSignature[F[_]: Async: SecurityProvider: KryoSerializer]: F[Boolean] =
      for {
        hash <- signed.value.hashF
        isValid <- signed.proofs
          .traverse(signature.verifySignatureProof(hash, _))
          .map(_.forall(identity))
      } yield isValid

    def hashWithSignatureCheck[F[_]: Async: SecurityProvider: KryoSerializer]
      : F[Either[InvalidSignatureForHash[A], Hashed[A]]] =
      for {
        hash <- signed.value.hashF
        proofsHash <- signed.proofs
          .sortBy(_.signature.value.value)
          .hashF
          .map(hash => ProofsHash(hash.value)) // I guess that's the SOE
        isValid <- signed.proofs
          .traverse(signature.verifySignatureProof(hash, _))
          .map(_.forall(identity))
        result = if (isValid) Hashed(signed, hash, proofsHash).asRight[InvalidSignatureForHash[A]]
        else
          InvalidSignatureForHash(signed).asLeft[Hashed[A]]
      } yield result
  }
}
