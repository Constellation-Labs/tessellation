package io.constellationnetwork.schema

import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.json.JsonBinarySerializer
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.swap.AllowSpend
import io.constellationnetwork.schema.tokenLock.TokenLock
import io.constellationnetwork.security.signature.Signed

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import io.circe._

@derive(eqv, show, encoder, decoder)
case class GlobalSnapshotWithState(
  snapshot: Signed[GlobalIncrementalSnapshot],
  state: GlobalSnapshotInfo
)

object GlobalSnapshotWithState {
  implicit def binaryEncoder[F[_]: Async]: GlobalSnapshotWithState => F[Array[Byte]] = { snapshot =>
    Async[F].blocking(JsonBinarySerializer.serialize(snapshot))
  }

  implicit def binaryDecoder[F[_]: Async]: Array[Byte] => F[GlobalSnapshotWithState] = { bytes =>
    Async[F].blocking(JsonBinarySerializer.deserialize[GlobalSnapshotWithState](bytes)).flatMap {
      case Right(snapshot) => Async[F].pure(snapshot)
      case Left(error)     => Async[F].raiseError(new RuntimeException(s"Failed to deserialize: $error"))
    }
  }
}

@derive(eqv, show, encoder, decoder)
case class GlobalSnapshotWithStateDeltas(
  snapshot: Signed[GlobalIncrementalSnapshot],
  activeAllowSpends: Option[SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]]],
  activeTokenLocks: Option[SortedMap[Address, SortedSet[Signed[TokenLock]]]]
)

object GlobalSnapshotWithStateDeltas {
  import GlobalSnapshotInfo.{optionAddressKeyDecoder, optionAddressKeyEncoder}

  implicit def binaryEncoder[F[_]: Async]: GlobalSnapshotWithStateDeltas => F[Array[Byte]] = { snapshot =>
    Async[F].blocking(JsonBinarySerializer.serialize(snapshot))
  }

  implicit def binaryDecoder[F[_]: Async]: Array[Byte] => F[GlobalSnapshotWithStateDeltas] = { bytes =>
    Async[F].blocking(JsonBinarySerializer.deserialize[GlobalSnapshotWithStateDeltas](bytes)).flatMap {
      case Right(snapshot) => Async[F].pure(snapshot)
      case Left(error)     => Async[F].raiseError(new RuntimeException(s"Failed to deserialize: $error"))
    }
  }
}
