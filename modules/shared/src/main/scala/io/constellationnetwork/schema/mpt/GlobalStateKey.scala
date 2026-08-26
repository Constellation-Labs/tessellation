package io.constellationnetwork.schema.mpt

import cats.Show
import cats.effect.Sync
import cats.syntax.all._

import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.mpt.PartitionNamespace._
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex

import derevo.cats.{eqv, order, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import io.circe._

sealed trait PartitionKeyType {
  def toByte: Byte
}

object PartitionKeyType {
  case object PKTHypergraph extends PartitionKeyType { val toByte: Byte = 0x00 }
  case object PKTAddress extends PartitionKeyType { val toByte: Byte = 0x01 }
  case object PKTHash extends PartitionKeyType { val toByte: Byte = 0x02 }

  implicit val ordering: Ordering[PartitionKeyType] = Ordering.by(_.toByte)
  implicit val show: Show[PartitionKeyType] = Show.show(_.toByte.toString)

  implicit val encoder: Encoder[PartitionKeyType] = Encoder[Byte].contramap(_.toByte)
  implicit val decoder: Decoder[PartitionKeyType] = Decoder[Byte].emap { b =>
    fromByte(b).toRight(s"Invalid PartitionKeyType byte: $b")
  }

  def fromByte(b: Byte): Option[PartitionKeyType] = b match {
    case 0x00 => Some(PKTHypergraph)
    case 0x01 => Some(PKTAddress)
    case 0x02 => Some(PKTHash)
    case _    => None
  }
}

sealed trait PartitionNamespace {
  def keyType: PartitionKeyType
}

object PartitionNamespace {
  import PartitionKeyType._

  case object HypergraphNamespace extends PartitionNamespace {
    val keyType: PartitionKeyType = PKTHypergraph
  }

  case class MetagraphNamespace(address: Address) extends PartitionNamespace {
    val keyType: PartitionKeyType = PKTAddress
  }

  case class AddressNamespace(address: Address) extends PartitionNamespace {
    val keyType: PartitionKeyType = PKTAddress
  }

  case class HashNamespace(hash: Hash) extends PartitionNamespace {
    val keyType: PartitionKeyType = PKTHash
  }

  case object EmptyNamespace extends PartitionNamespace {
    val keyType: PartitionKeyType = PKTHypergraph
  }

  implicit val ordering: Ordering[PartitionNamespace] = Ordering.by {
    case EmptyNamespace           => (0, "", "")
    case HypergraphNamespace      => (0, "", "")
    case MetagraphNamespace(addr) => (1, addr.value.value, "")
    case AddressNamespace(addr)   => (1, addr.value.value, "")
    case HashNamespace(hash)      => (2, hash.value, "")
  }

  implicit val show: Show[PartitionNamespace] = Show.show {
    case EmptyNamespace           => "Empty"
    case HypergraphNamespace      => "Hypergraph"
    case MetagraphNamespace(addr) => s"Metagraph(${addr.value.value})"
    case AddressNamespace(addr)   => s"Address(${addr.value.value})"
    case HashNamespace(hash)      => s"Hash(${hash.value})"
  }

  implicit val encoder: Encoder[PartitionNamespace] = Encoder.instance {
    case HypergraphNamespace => Json.obj("type" -> Json.fromString("hypergraph"))
    case EmptyNamespace      => Json.obj("type" -> Json.fromString("empty"))
    case MetagraphNamespace(addr) =>
      Json.obj("type" -> Json.fromString("metagraph"), "address" -> Json.fromString(addr.value.value))
    case AddressNamespace(addr) =>
      Json.obj("type" -> Json.fromString("address"), "address" -> Json.fromString(addr.value.value))
    case HashNamespace(hash) =>
      Json.obj("type" -> Json.fromString("hash"), "hash" -> Json.fromString(hash.value))
  }

  implicit val decoder: Decoder[PartitionNamespace] = Decoder.instance { cursor =>
    cursor.downField("type").as[String].flatMap {
      case "empty"      => Right(EmptyNamespace)
      case "hypergraph" => Right(HypergraphNamespace)
      case "metagraph"  => cursor.downField("address").as[String].map(s => MetagraphNamespace(Address.fromBytes(s.getBytes)))
      case "address"    => cursor.downField("address").as[String].map(s => AddressNamespace(Address.fromBytes(s.getBytes)))
      case "hash"       => cursor.downField("hash").as[String].map(s => HashNamespace(Hash(s)))
      case other        => Left(DecodingFailure(s"Unknown PartitionNamespace type: $other", cursor.history))
    }
  }
}

sealed trait GlobalStateFieldId {
  def toInt: Int
}

object GlobalStateFieldId {
  case object LastStateChannelSnapshotHashes extends GlobalStateFieldId { def toInt: Int = 0 }
  case object LastTxRefs extends GlobalStateFieldId { def toInt: Int = 1 }
  case object Balances extends GlobalStateFieldId { def toInt: Int = 2 }
  case object LastCurrencySnapshots extends GlobalStateFieldId { def toInt: Int = 3 }
  case object LastCurrencySnapshotsProofs extends GlobalStateFieldId { def toInt: Int = 4 }
  case object LastIncrementalCurrencySnapshots extends GlobalStateFieldId { def toInt: Int = 5 }
  case object LastCurrencySnapshotInfo extends GlobalStateFieldId { def toInt: Int = 6 }
  case object ActiveAllowSpends extends GlobalStateFieldId { def toInt: Int = 7 }
  case object ActiveTokenLocks extends GlobalStateFieldId { def toInt: Int = 8 }
  case object TokenLockBalances extends GlobalStateFieldId { def toInt: Int = 9 }
  case object LastAllowSpendRefs extends GlobalStateFieldId { def toInt: Int = 10 }
  case object LastTokenLockRefs extends GlobalStateFieldId { def toInt: Int = 11 }
  case object UpdateNodeParameters extends GlobalStateFieldId { def toInt: Int = 12 }
  case object ActiveDelegatedStakes extends GlobalStateFieldId { def toInt: Int = 13 }
  case object DelegatedStakesWithdrawals extends GlobalStateFieldId { def toInt: Int = 14 }
  case object ActiveNodeCollaterals extends GlobalStateFieldId { def toInt: Int = 15 }
  case object NodeCollateralWithdrawals extends GlobalStateFieldId { def toInt: Int = 16 }
  case object PriceState extends GlobalStateFieldId { def toInt: Int = 17 }
  case object MetagraphSyncData extends GlobalStateFieldId { def toInt: Int = 18 }
  case object RetiredAllowSpendRefs extends GlobalStateFieldId { def toInt: Int = 19 }

  implicit val ordering: Ordering[GlobalStateFieldId] = Ordering.by(_.toInt)
  implicit val show: Show[GlobalStateFieldId] = Show.show(_.toInt.toString)

  implicit val encoder: Encoder[GlobalStateFieldId] = Encoder[Int].contramap(_.toInt)
  implicit val decoder: Decoder[GlobalStateFieldId] = Decoder[Int].emap { i =>
    fromInt(i).toRight(s"Invalid GlobalStateFieldId int: $i")
  }

  def fromInt(i: Int): Option[GlobalStateFieldId] = i match {
    case 0  => Some(LastStateChannelSnapshotHashes)
    case 1  => Some(LastTxRefs)
    case 2  => Some(Balances)
    case 3  => Some(LastCurrencySnapshots)
    case 4  => Some(LastCurrencySnapshotsProofs)
    case 5  => Some(LastIncrementalCurrencySnapshots)
    case 6  => Some(LastCurrencySnapshotInfo)
    case 7  => Some(ActiveAllowSpends)
    case 8  => Some(ActiveTokenLocks)
    case 9  => Some(TokenLockBalances)
    case 10 => Some(LastAllowSpendRefs)
    case 11 => Some(LastTokenLockRefs)
    case 12 => Some(UpdateNodeParameters)
    case 13 => Some(ActiveDelegatedStakes)
    case 14 => Some(DelegatedStakesWithdrawals)
    case 15 => Some(ActiveNodeCollaterals)
    case 16 => Some(NodeCollateralWithdrawals)
    case 17 => Some(PriceState)
    case 18 => Some(MetagraphSyncData)
    case 19 => Some(RetiredAllowSpendRefs)
    case _  => None
  }
}

@derive(encoder, decoder, eqv, show, order)
case class GlobalStateKey(
  networkNamespace: PartitionNamespace,
  fieldId: GlobalStateFieldId,
  contractNamespace: PartitionNamespace,
  userNamespace: PartitionNamespace
)

object GlobalStateKey {

  def metagraph(addr: Address, fieldId: GlobalStateFieldId): GlobalStateKey =
    GlobalStateKey(MetagraphNamespace(addr), fieldId, EmptyNamespace, EmptyNamespace)

  def hypergraph(fieldId: GlobalStateFieldId, user: Address): GlobalStateKey =
    GlobalStateKey(HypergraphNamespace, fieldId, EmptyNamespace, AddressNamespace(user))

  def hypergraph(fieldId: GlobalStateFieldId, contract: Address, user: Address): GlobalStateKey =
    GlobalStateKey(HypergraphNamespace, fieldId, AddressNamespace(contract), AddressNamespace(user))

  def hypergraph(fieldId: GlobalStateFieldId, contract: Option[Address], user: Address): GlobalStateKey =
    GlobalStateKey(
      HypergraphNamespace,
      fieldId,
      contract.map(MetagraphNamespace(_)).getOrElse(EmptyNamespace),
      AddressNamespace(user)
    )

  def toHex[F[_]: Sync: Hasher](key: GlobalStateKey): F[Hex] =
    for {
      networkPart <- serializeNamespace[F](key.networkNamespace)
      fieldPart <- f"${key.fieldId.toInt}%08x".pure[F]
      contractPart <- serializeNamespace[F](key.contractNamespace)
      userPart <- serializeNamespace[F](key.userNamespace)
      serialized = networkPart + fieldPart + contractPart + userPart
    } yield Hex(serialized)

  private def serializeNamespace[F[_]: Sync: Hasher](ns: PartitionNamespace): F[String] =
    ns match {
      case HypergraphNamespace =>
        f"${ns.keyType.toByte}%02x".pure[F]
      case EmptyNamespace =>
        f"${ns.keyType.toByte}%02x".pure[F]
      case MetagraphNamespace(addr) =>
        Hasher[F].hash(addr.value.value).map(h => f"${ns.keyType.toByte}%02x" + h.value)
      case AddressNamespace(addr) =>
        Hasher[F].hash(addr.value.value).map(h => f"${ns.keyType.toByte}%02x" + h.value)
      case HashNamespace(hash) =>
        (f"${ns.keyType.toByte}%02x" + hash.value).pure[F]
    }
}
