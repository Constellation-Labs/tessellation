package org.tessellation.rosetta.api.dag.crypto

import derevo.cats.{eqv, show}
import derevo.derive
import enumeratum.{Enum, EnumEntry}
import io.circe.Encoder

@derive(eqv, show)
sealed trait SignatureType extends EnumEntry

object SignatureType extends Enum[SignatureType] with SignatureTypeEncoder {
  val values = findValues

  case object Ecdsa extends SignatureType
  case object EcdsaRecovery extends SignatureType
  case object Ed25519 extends SignatureType
  case object Schnorr1 extends SignatureType
  case object SchnorrPoseidon extends SignatureType
}

trait SignatureTypeEncoder {
  implicit val encode: Encoder[SignatureType] =
    Encoder.encodeString.contramap[SignatureType](_ match {
      case SignatureType.EcdsaRecovery   => "ecdsa_recovery"
      case SignatureType.Schnorr1        => "schnorr_1"
      case SignatureType.SchnorrPoseidon => "schnorr_poseidon"
      case signatureTypeValue            => signatureTypeValue.toString.toLowerCase
    })
}
