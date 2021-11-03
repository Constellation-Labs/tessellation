package org.tesselation.security.signature

import java.security.KeyPair

import cats.Semigroup
import cats.data.NonEmptyList
import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tesselation.ext.crypto._
import org.tesselation.kryo.KryoSerializer
import org.tesselation.security.SecurityProvider
import org.tesselation.security.signature.signature.{SignatureProof, signatureProofFromData}

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

@derive(encoder, decoder, eqv, show)
case class Signed[A](value: A, proofs: NonEmptyList[SignatureProof])

object Signed {

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
  }
}
