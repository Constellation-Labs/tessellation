package org.tessellation.currency.infrastructure.snapshot

import cats.data.OptionT
import cats.effect.kernel.Async
import cats.syntax.either._
import cats.syntax.functor._

import org.tessellation.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshot}
import org.tessellation.kryo.KryoSerializer
import org.tessellation.sdk.infrastructure.snapshot.storage.SnapshotLocalFileSystemStorage
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed

import fs2.io.file.Path

trait CurrencySnapshotLoader[F[_]] {

  def readCurrencySnapshot(hash: Hash): F[Either[Signed[CurrencySnapshot], Signed[CurrencyIncrementalSnapshot]]]
}

object CurrencySnapshotLoader {

  def make[F[_]: Async: KryoSerializer](
    incrementalGlobalSnapshotLocalFileSystemStorage: SnapshotLocalFileSystemStorage[F, CurrencyIncrementalSnapshot],
    path: Path
  ): F[CurrencySnapshotLoader[F]] =
    SnapshotLocalFileSystemStorage.make[F, CurrencySnapshot](path).map(make(incrementalGlobalSnapshotLocalFileSystemStorage, _))

  def make[F[_]: Async](
    incrementalGlobalSnapshotLocalFileSystemStorage: SnapshotLocalFileSystemStorage[F, CurrencyIncrementalSnapshot],
    globalSnapshotLocalFileSystemStorage: SnapshotLocalFileSystemStorage[F, CurrencySnapshot]
  ): CurrencySnapshotLoader[F] = new CurrencySnapshotLoader[F] {

    def readCurrencySnapshot(hash: Hash): F[Either[Signed[CurrencySnapshot], Signed[CurrencyIncrementalSnapshot]]] = {

      def getIncremental = OptionT(incrementalGlobalSnapshotLocalFileSystemStorage.read(hash))
      def getFull = OptionT(globalSnapshotLocalFileSystemStorage.read(hash))

      def getSnapshot = getIncremental
        .map(_.asRight[Signed[CurrencySnapshot]])
        .orElse(getFull.map(_.asLeft[Signed[CurrencyIncrementalSnapshot]]))

      getSnapshot.getOrRaise(new Throwable("Cannot find neither GlobalSnapshot nor IncrementalGlobalSnapshot."))

    }

  }

}
