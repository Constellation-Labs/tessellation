package io.constellationnetwork.schema.mpt

import cats.Show
import cats.effect.Sync
import cats.syntax.applicative._
import cats.syntax.flatMap._
import cats.syntax.functor._

import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.hex.Hex

import derevo.cats.{eqv, order, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import io.circe.{Decoder, Encoder}

sealed trait GlobalStateFieldId {
  def toByte: Byte
}

object GlobalStateFieldId {
  case object LastStateChannelSnapshotHashes extends GlobalStateFieldId { def toByte: Byte = 0 }
  case object LastTxRefs extends GlobalStateFieldId { def toByte: Byte = 1 }
  case object Balances extends GlobalStateFieldId { def toByte: Byte = 2 }
  case object LastCurrencySnapshots extends GlobalStateFieldId { def toByte: Byte = 3 }
  case object LastCurrencySnapshotsProofs extends GlobalStateFieldId { def toByte: Byte = 4 }
  case object ActiveAllowSpends extends GlobalStateFieldId { def toByte: Byte = 5 }
  case object ActiveTokenLocks extends GlobalStateFieldId { def toByte: Byte = 6 }
  case object TokenLockBalances extends GlobalStateFieldId { def toByte: Byte = 7 }
  case object LastAllowSpendRefs extends GlobalStateFieldId { def toByte: Byte = 8 }
  case object LastTokenLockRefs extends GlobalStateFieldId { def toByte: Byte = 9 }
  case object UpdateNodeParameters extends GlobalStateFieldId { def toByte: Byte = 10 }
  case object ActiveDelegatedStakes extends GlobalStateFieldId { def toByte: Byte = 11 }
  case object DelegatedStakesWithdrawals extends GlobalStateFieldId { def toByte: Byte = 12 }
  case object ActiveNodeCollaterals extends GlobalStateFieldId { def toByte: Byte = 13 }
  case object NodeCollateralWithdrawals extends GlobalStateFieldId { def toByte: Byte = 14 }
  case object PriceState extends GlobalStateFieldId { def toByte: Byte = 15 }
  case object MetagraphSyncData extends GlobalStateFieldId { def toByte: Byte = 16 }

  implicit val ordering: Ordering[GlobalStateFieldId] = Ordering.by(_.toByte)
  implicit val show: Show[GlobalStateFieldId] = Show.show(_.toByte.toString)

  implicit val encoder: Encoder[GlobalStateFieldId] = Encoder[Byte].contramap(_.toByte)
  implicit val decoder: Decoder[GlobalStateFieldId] = Decoder[Byte].emap { b =>
    fromByte(b).toRight(s"Invalid GlobalStateFieldId byte: $b")
  }

  def fromByte(b: Byte): Option[GlobalStateFieldId] = b match {
    case 0  => Some(LastStateChannelSnapshotHashes)
    case 1  => Some(LastTxRefs)
    case 2  => Some(Balances)
    case 3  => Some(LastCurrencySnapshots)
    case 4  => Some(LastCurrencySnapshotsProofs)
    case 5  => Some(ActiveAllowSpends)
    case 6  => Some(ActiveTokenLocks)
    case 7  => Some(TokenLockBalances)
    case 8  => Some(LastAllowSpendRefs)
    case 9  => Some(LastTokenLockRefs)
    case 10 => Some(UpdateNodeParameters)
    case 11 => Some(ActiveDelegatedStakes)
    case 12 => Some(DelegatedStakesWithdrawals)
    case 13 => Some(ActiveNodeCollaterals)
    case 14 => Some(NodeCollateralWithdrawals)
    case 15 => Some(PriceState)
    case 16 => Some(MetagraphSyncData)
    case _  => None
  }
}

@derive(encoder, decoder, eqv, show, order)
case class GlobalStateKey(
  fieldId: GlobalStateFieldId,
  metagraphId: Option[Address],
  primaryAddress: Option[Address],
  secondaryAddress: Option[Address]
)

object GlobalStateKey {

  def toHex[F[_]: Sync: Hasher](key: GlobalStateKey): F[Hex] =
    for {
      fieldPart <- f"${key.fieldId.toByte}%02x".pure[F]

      metagraphPart <- key.metagraphId match {
        case Some(addr) => Hasher[F].hash(addr.value.value).map(_.value)
        case None       => Sync[F].pure("")
      }

      primaryPart <- key.primaryAddress match {
        case Some(addr) => Hasher[F].hash(addr.value.value).map(_.value)
        case None       => Sync[F].pure("")
      }

      secondaryPart <- key.secondaryAddress match {
        case Some(addr) => Hasher[F].hash(addr.value.value).map(_.value)
        case None       => Sync[F].pure("")
      }

      serialized = fieldPart + metagraphPart + primaryPart + secondaryPart
    } yield Hex(serialized)
}
