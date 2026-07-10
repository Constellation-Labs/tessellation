package io.constellationnetwork.currency.l0

import cats.MonadThrow
import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.currency.l0.modules.Storages
import io.constellationnetwork.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshotInfo}
import io.constellationnetwork.node.shared.cli.CliMethod
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.signature.Signed

import org.typelevel.log4cats.Logger

object StoragesInitializer {

  def initializeCurrencySnapshotStorages[
    F[_]: Async: Logger: Hasher,
    R <: CliMethod
  ](
    storages: Storages[F],
    maybeCurrencySnapshot: Option[Signed[CurrencyIncrementalSnapshot]] = None,
    maybeCurrencySnapshotInfo: Option[CurrencySnapshotInfo] = None
  ): F[Unit] =
    for {
      _ <- Logger[F].info(s"Initializing currency snapshot storages")
      _ <- (maybeCurrencySnapshot, maybeCurrencySnapshotInfo) match {
        case (Some(currencySnapshot), Some(currencySnapshotInfo)) =>
          val ordinal = currencySnapshot.ordinal
          Logger[F].info(s"Prepending currency snapshot with ordinal=$ordinal") >>
            storages.snapshot.prepend(currencySnapshot, currencySnapshotInfo).flatMap { prepended =>
              if (prepended) Logger[F].info(s"Successfully prepended currency snapshot with ordinal=$ordinal")
              else Logger[F].info(s"Currency snapshot with ordinal=$ordinal already in storage, skipping prepend")
            }
        case (None, None) =>
          Logger[F].info(s"No currency snapshot provided, skipping prepend")
        case _ =>
          MonadThrow[F].raiseError[Unit](new IllegalArgumentException("Currency snapshot and info must both be provided or both be absent"))
      }
      _ <- Logger[F].info(s"Successfully initialized currency snapshot storages")
    } yield ()
}
