package org.tessellation.security.signature

import java.security.KeyPair

import cats.data.NonEmptyList
import cats.effect.Async
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.order._
import cats.{Order, Semigroup}

import scala.util.control.NoStackTrace

import org.tessellation.ext.crypto._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.security.signature.signature.{SignatureProof, signatureProofFromData}
import org.tessellation.security.{Hashed, SecurityProvider}

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

@derive(encoder, decoder, eqv, show)
case class Signed[A](value: A, proofs: NonEmptyList[SignatureProof])

object Signed {
  case class InvalidSignatureForHash[A <: AnyRef](signed: Signed[A]) extends NoStackTrace

  implicit def order[A: Order]: Order[Signed[A]] = (x: Signed[A], y: Signed[A]) => x.value.compare(y.value)

  def forAsyncKryo[F[_]: Async: SecurityProvider: KryoSerializer, A <: AnyRef](
    data: A,
    keyPair: KeyPair
  ): F[Signed[A]] =
    signatureProofFromData(data, keyPair).map { sp =>
      Signed[A](data, NonEmptyList.one(sp))
    }

  implicit def semigroup[A]: Semigroup[Signed[A]] = Semigroup.instance { (a, b) =>
    Signed(a.value, a.proofs ::: b.proofs)
  }

  implicit class SignedOps[A <: AnyRef](signed: Signed[A]) {

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
        isValid <- signed.proofs
          .traverse(signature.verifySignatureProof(hash, _))
          .map(_.forall(identity))
        result = if (isValid) Hashed(signed, hash).asRight[InvalidSignatureForHash[A]]
        else
          InvalidSignatureForHash(signed).asLeft[Hashed[A]]
      } yield result
  }
}
