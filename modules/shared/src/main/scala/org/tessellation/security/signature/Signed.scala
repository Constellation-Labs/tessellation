package org.tessellation.security.signature

import java.security.KeyPair

import cats.data.NonEmptyList
import cats.effect.Async
import cats.syntax.applicative._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.list._
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
import io.estatico.newtype.ops._

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

  //@deprecated("use signAlsoWith", "0.6.0")
  implicit def semigroup[A]: Semigroup[Signed[A]] = Semigroup.instance { (a, b) =>
    Signed(a.value, a.proofs ::: b.proofs)
  }

  implicit class SignedOps[A <: AnyRef](signed: Signed[A]) {

    def signAlsoWith[F[_]: Async: SecurityProvider: KryoSerializer](keyPair: KeyPair): F[Signed[A]] =
      SignatureProof.fromData(keyPair)(signed.value).map { sp =>
        Signed(signed.value, sp :: signed.proofs)
      }

    def isSignedBy(signers: Set[Id]): Boolean =
      signed.proofs.map(_.id).toList.toSet == signers

    def hasValidSignature[F[_]: Async: SecurityProvider: KryoSerializer]: F[Boolean] =
      validProofs.map(_.isRight)

    def validProofs[F[_]: Async: SecurityProvider: KryoSerializer]
      : F[Either[NonEmptyList[SignatureProof], NonEmptyList[SignatureProof]]] =
      for {
        hash <- signed.value.hashF
        invalidOrValidProofs <- signed.proofs.traverse { proof =>
          signature
            .verifySignatureProof(hash, proof)
            .map(result => proof -> result)
        }.map { proofsAndResults =>
          proofsAndResults
            .filterNot(_._2)
            .map(_._1)
            .toNel
            .toLeft(signed.proofs)
        }
      } yield invalidOrValidProofs

    def toHashedWithSignatureCheck[F[_]: Async: KryoSerializer: SecurityProvider]
      : F[Either[InvalidSignatureForHash[A], Hashed[A]]] =
      hasValidSignature.ifM(
        toHashed.map(_.asRight[InvalidSignatureForHash[A]]),
        InvalidSignatureForHash(signed).asLeft[Hashed[A]].pure[F]
      )

    def toHashed[F[_]: Async: KryoSerializer: SecurityProvider]: F[Hashed[A]] =
      signed.value.hashF.flatMap { hash =>
        proofsHash.map { proofsHash =>
          Hashed(signed, hash, proofsHash)
        }
      }

    def proofsHash[F[_]: Async: KryoSerializer]: F[ProofsHash] =
      signed.proofs
        .sortBy(_.signature)
        .hashF
        .map(hash => ProofsHash(hash.coerce))
  }
}
