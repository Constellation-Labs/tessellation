package org.tessellation.currency.l0.node

import java.security.KeyPair

import cats.data.OptionT
import cats.effect.Async
import cats.syntax.functor._

import org.tessellation.currency.dataApplication.L0NodeContext
import org.tessellation.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshotInfo}
import org.tessellation.node.shared.domain.snapshot.storage.SnapshotStorage
import org.tessellation.schema.SnapshotOrdinal
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.signature.SignatureProof
import org.tessellation.security.{Hashed, Hasher, SecurityProvider}

object L0NodeContext {
  def make[F[_]: SecurityProvider: Hasher: Async](
    snapshotStorage: SnapshotStorage[F, CurrencyIncrementalSnapshot, CurrencySnapshotInfo]
  )(keypair: KeyPair): L0NodeContext[F] = new L0NodeContext[F] {
    def securityProvider: SecurityProvider[F] = SecurityProvider[F]

    def getLastCurrencySnapshot: F[Option[Hashed[CurrencyIncrementalSnapshot]]] =
      OptionT(snapshotStorage.headSnapshot)
        .semiflatMap(_.toHashed)
        .value

    def getCurrencySnapshot(ordinal: SnapshotOrdinal): F[Option[Hashed[CurrencyIncrementalSnapshot]]] =
      OptionT(snapshotStorage.get(ordinal))
        .semiflatMap(_.toHashed)
        .value

    def getLastCurrencySnapshotCombined: F[Option[(Hashed[CurrencyIncrementalSnapshot], CurrencySnapshotInfo)]] =
      OptionT(snapshotStorage.head).semiflatMap {
        case (snapshot, info) => snapshot.toHashed.map((_, info))
      }.value

    def signWithNodeKey(input: Hash): F[SignatureProof] = SignatureProof.fromHash(keypair, input)

  }
}
