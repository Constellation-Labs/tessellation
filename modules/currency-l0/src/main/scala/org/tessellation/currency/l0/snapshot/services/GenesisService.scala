package org.tessellation.currency.l0.snapshot.services

import java.security.KeyPair

import cats.effect.Async
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.show._

import org.tessellation.currency.l0.snapshot.CurrencySnapshotArtifact
import org.tessellation.currency.l0.snapshot.storages.LastSignedBinaryHashStorage
import org.tessellation.currency.schema.currency.CurrencySnapshot
import org.tessellation.ext.crypto._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.SnapshotOrdinal
import org.tessellation.schema.peer.{L0Peer, PeerId}
import org.tessellation.sdk.domain.collateral.{Collateral, OwnCollateralNotSatisfied}
import org.tessellation.sdk.domain.snapshot.storage.SnapshotStorage
import org.tessellation.sdk.http.p2p.clients.StateChannelSnapshotClient
import org.tessellation.sdk.infrastructure.consensus.ConsensusManager
import org.tessellation.security.SecurityProvider

import org.typelevel.log4cats.slf4j.Slf4jLogger

trait GenesisService[F[_]] {
  def accept(genesis: CurrencySnapshot): F[Unit]
}

object GenesisService {
  def make[F[_]: Async: KryoSerializer: SecurityProvider](
    keyPair: KeyPair,
    collateral: Collateral[F],
    lastSignedBinaryHashStorage: LastSignedBinaryHashStorage[F],
    stateChannelSnapshotService: StateChannelSnapshotService[F],
    snapshotStorage: SnapshotStorage[F, CurrencySnapshot],
    stateChannelSnapshotClient: StateChannelSnapshotClient[F],
    globalL0Peer: L0Peer,
    nodeId: PeerId,
    consensusManager: ConsensusManager[F, SnapshotOrdinal, CurrencySnapshotArtifact]
  ): GenesisService[F] = new GenesisService[F] {
    private val logger = Slf4jLogger.getLogger

    override def accept(genesis: CurrencySnapshot): F[Unit] = for {
      signedGenesis <- genesis.sign(keyPair)
      _ <- snapshotStorage.prepend(signedGenesis)
      _ <- collateral
        .hasCollateral(nodeId)
        .flatMap(OwnCollateralNotSatisfied.raiseError[F, Unit].unlessA)
      signedBinary <- stateChannelSnapshotService.createBinary(signedGenesis)
      signedBinaryHash <- signedBinary.hashF
      _ <- stateChannelSnapshotClient.send(signedBinary)(globalL0Peer)
      _ <- lastSignedBinaryHashStorage.set(signedBinaryHash)
      _ <- consensusManager.startFacilitatingAfter(genesis.ordinal, signedGenesis)
      _ <- logger.info(s"Genesis binary ${signedBinaryHash.show} accepted and sent to Global L0")
    } yield ()
  }
}
