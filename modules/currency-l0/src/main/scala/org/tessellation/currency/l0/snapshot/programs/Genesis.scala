package org.tessellation.currency.l0.snapshot.programs

import java.security.KeyPair

import cats.effect.Async
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.show._

import scala.util.control.NoStackTrace

import org.tessellation.currency.l0.node.IdentifierStorage
import org.tessellation.currency.l0.snapshot.services.StateChannelSnapshotService
import org.tessellation.currency.l0.snapshot.storages.LastBinaryHashStorage
import org.tessellation.currency.l0.snapshot.{CurrencySnapshotArtifact, CurrencySnapshotContext}
import org.tessellation.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshot, CurrencySnapshotInfo}
import org.tessellation.ext.crypto._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.SnapshotOrdinal
import org.tessellation.schema.address.Address
import org.tessellation.schema.peer.{L0Peer, PeerId}
import org.tessellation.sdk.domain.collateral.{Collateral, OwnCollateralNotSatisfied}
import org.tessellation.sdk.domain.genesis.{Loader => GenesisLoader}
import org.tessellation.sdk.domain.snapshot.storage.SnapshotStorage
import org.tessellation.sdk.http.p2p.clients.StateChannelSnapshotClient
import org.tessellation.sdk.infrastructure.consensus.ConsensusManager
import org.tessellation.security.SecurityProvider

import fs2.io.file.Path
import org.typelevel.log4cats.slf4j.Slf4jLogger

trait Genesis[F[_]] {
  def accept(path: Path, dataApplication: Option[Array[Byte]] = None): F[Unit]
  def accept(genesis: CurrencySnapshot): F[Unit]
}

object Genesis {
  def make[F[_]: Async: KryoSerializer: SecurityProvider](
    keyPair: KeyPair,
    collateral: Collateral[F],
    lastBinaryHashStorage: LastBinaryHashStorage[F],
    stateChannelSnapshotService: StateChannelSnapshotService[F],
    snapshotStorage: SnapshotStorage[F, CurrencyIncrementalSnapshot, CurrencySnapshotInfo],
    stateChannelSnapshotClient: StateChannelSnapshotClient[F],
    globalL0Peer: L0Peer,
    nodeId: PeerId,
    consensusManager: ConsensusManager[F, SnapshotOrdinal, CurrencySnapshotArtifact, CurrencySnapshotContext],
    genesisLoader: GenesisLoader[F],
    identifierStorage: IdentifierStorage[F]
  ): Genesis[F] = new Genesis[F] {
    private val logger = Slf4jLogger.getLogger

    override def accept(genesis: CurrencySnapshot): F[Unit] = for {
      hashedGenesis <- genesis.sign(keyPair).flatMap(_.toHashed[F])
      firstIncrementalSnapshot <- CurrencySnapshot.mkFirstIncrementalSnapshot[F](hashedGenesis)
      signedFirstIncrementalSnapshot <- firstIncrementalSnapshot.sign(keyPair)
      _ <- snapshotStorage.prepend(signedFirstIncrementalSnapshot, hashedGenesis.info)

      _ <- collateral
        .hasCollateral(nodeId)
        .flatMap(OwnCollateralNotSatisfied.raiseError[F, Unit].unlessA)

      signedBinary <- stateChannelSnapshotService.createGenesisBinary(hashedGenesis.signed)
      identifier = signedBinary.value.toAddress
      _ <- identifierStorage.setInitial(identifier)
      _ <- logger.info(s"Address from genesis data is ${identifier.show}")
      binaryHash <- signedBinary.toHashed.map(_.hash)
      _ <- stateChannelSnapshotClient.send(identifier, signedBinary)(globalL0Peer)

      _ <- lastBinaryHashStorage.set(binaryHash)

      signedIncrementalBinary <- stateChannelSnapshotService.createBinary(signedFirstIncrementalSnapshot)
      incrementalBinaryHash <- signedIncrementalBinary.toHashed.map(_.hash)
      _ <- stateChannelSnapshotClient.send(identifier, signedIncrementalBinary)(globalL0Peer)

      _ <- lastBinaryHashStorage.set(incrementalBinaryHash)

      _ <- consensusManager.startFacilitatingAfterRollback(
        signedFirstIncrementalSnapshot.ordinal,
        signedFirstIncrementalSnapshot,
        hashedGenesis.info
      )
      _ <- logger.info(s"Genesis binary ${binaryHash.show} and ${incrementalBinaryHash.show} accepted and sent to Global L0")
    } yield ()

    def accept(path: Path, dataApplication: Option[Array[Byte]] = None): F[Unit] =
      genesisLoader
        .load(path)
        .map(_.map(a => (a.address, a.balance)).toMap)
        .map(CurrencySnapshot.mkGenesis(_, dataApplication))
        .flatMap(accept)
  }

  case class IdentifierNotFromGenesisData(identifier: Address, genesisAddress: Address) extends NoStackTrace
}
