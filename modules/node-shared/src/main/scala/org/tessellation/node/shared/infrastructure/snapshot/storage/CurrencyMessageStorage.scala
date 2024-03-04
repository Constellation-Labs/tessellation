package org.tessellation.node.shared.infrastructure.snapshot.storage

import cats.effect.std.MapRef
import cats.effect.{Concurrent, Ref}
import cats.syntax.functor._
import cats.syntax.option._
import cats.syntax.partialOrder._

import org.tessellation.schema.currencyMessage._
import org.tessellation.security.signature.Signed

trait CurrencyMessageStorage[F[_]] {
  def get(messageType: MessageType): F[Option[Signed[CurrencyMessage]]]
  def set(message: Signed[CurrencyMessage]): F[Boolean]
}

object CurrencyMessageStorage {
  def make[F[_]: Concurrent: Ref.Make]: F[CurrencyMessageStorage[F]] =
    MapRef.ofSingleImmutableMap[F, MessageType, Signed[CurrencyMessage]](Map.empty).map { mapRef =>
      new CurrencyMessageStorage[F] {
        def get(messageType: MessageType): F[Option[Signed[CurrencyMessage]]] =
          mapRef(messageType).get

        def set(message: Signed[CurrencyMessage]): F[Boolean] =
          mapRef(message.messageType).modify {
            case None                                           => (message.some, true)
            case Some(m) if m.ordinal === message.parentOrdinal => (message.some, true)
            case other                                          => (other, false)
          }
      }
    }

}
