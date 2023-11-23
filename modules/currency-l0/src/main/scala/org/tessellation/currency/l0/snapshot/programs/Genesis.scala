package org.tessellation.currency.l0.snapshot.programs

import java.security.KeyPair

import cats.effect.Async
import cats.syntax.all._

import org.tessellation.currency.dataApplication.{BaseDataApplicationL0Service, L0NodeContext}
import org.tessellation.currency.l0.node.IdentifierStorage
import org.tessellation.currency.l0.snapshot.CurrencySnapshotArtifact
import org.tessellation.currency.l0.snapshot.services.StateChannelSnapshotService
import org.tessellation.currency.l0.snapshot.storages.LastBinaryHashStorage
import org.tessellation.currency.schema.currency._
import org.tessellation.ext.crypto._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.node.shared.domain.collateral.{Collateral, OwnCollateralNotSatisfied}
import org.tessellation.node.shared.domain.genesis.{GenesisFS => GenesisLoader}
import org.tessellation.node.shared.domain.snapshot.storage.SnapshotStorage
import org.tessellation.node.shared.http.p2p.clients.StateChannelSnapshotClient
import org.tessellation.node.shared.infrastructure.consensus.ConsensusManager
import org.tessellation.schema.SnapshotOrdinal
import org.tessellation.schema.peer.{L0Peer, PeerId}
import org.tessellation.security.SecurityProvider
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed

import fs2.io.file.Path
import org.typelevel.log4cats.slf4j.Slf4jLogger

trait Genesis[F[_]] {
  def acceptSignedGenesis(dataApplication: Option[BaseDataApplicationL0Service[F]])(genesis: Signed[CurrencySnapshot])(
    implicit context: L0NodeContext[F]
  ): F[Unit]

  def accept(dataApplication: Option[BaseDataApplicationL0Service[F]])(genesisPath: Path)(
    implicit context: L0NodeContext[F]
  ): F[Unit]

  def create(dataApplication: Option[BaseDataApplicationL0Service[F]])(
    balancesPath: Path,
    keyPair: KeyPair
  ): F[Unit]
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
    genesisLoader: GenesisLoader[F, CurrencySnapshot],
    identifierStorage: IdentifierStorage[F]
  ): Genesis[F] = new Genesis[F] {
    private val logger = Slf4jLogger.getLogger

    override def acceptSignedGenesis(dataApplication: Option[BaseDataApplicationL0Service[F]])(genesis: Signed[CurrencySnapshot])(
      implicit context: L0NodeContext[F]
    ): F[Unit] = for {
      hashedGenesis <- genesis.toHashed[F]
      firstIncrementalSnapshot <- CurrencySnapshot.mkFirstIncrementalSnapshot[F](hashedGenesis)
      signedFirstIncrementalSnapshot <- firstIncrementalSnapshot.sign(keyPair)
      _ <- snapshotStorage.prepend(signedFirstIncrementalSnapshot, hashedGenesis.info)

      _ <- collateral
        .hasCollateral(nodeId)
        .flatMap(OwnCollateralNotSatisfied.raiseError[F, Unit].unlessA)

      _ <- dataApplication
        .traverse(app => app.setCalculatedState(firstIncrementalSnapshot.ordinal, app.genesis.calculated))

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
        CurrencySnapshotContext(identifier, hashedGenesis.info)
      )
      _ <- logger.info(s"Genesis binary ${binaryHash.show} and ${incrementalBinaryHash.show} accepted and sent to Global L0")
    } yield ()

    override def accept(dataApplication: Option[BaseDataApplicationL0Service[F]])(genesisPath: Path)(
      implicit context: L0NodeContext[F]
    ): F[Unit] = genesisLoader
      .loadSignedGenesis(genesisPath)
      .flatTap { genesis =>
        dataApplication
          .traverse(app => app.setCalculatedState(genesis.ordinal, app.genesis.calculated))
      }
      .flatMap(acceptSignedGenesis(dataApplication))

    def create(dataApplication: Option[BaseDataApplicationL0Service[F]] = None)(
      balancesPath: Path,
      keyPair: KeyPair
    ): F[Unit] = {
      def mkBalances =
        genesisLoader
          .loadBalances(balancesPath)
          .map(_.map(a => (a.address, a.balance)).toMap)

      def mkDataApplicationPart =
        dataApplication.traverse(da => da.serializedOnChainGenesis.map(DataApplicationPart(_, List.empty, Hash.empty)))

      for {
        balances <- mkBalances
        dataApplicationPart <- mkDataApplicationPart
        genesis = CurrencySnapshot.mkGenesis(balances, dataApplicationPart)
        signedGenesis <- genesis.sign(keyPair)
        signedBinary <- stateChannelSnapshotService.createGenesisBinary(signedGenesis)
        identifier = signedBinary.value.toAddress
        _ <- genesisLoader.write(signedGenesis, identifier, balancesPath.resolveSibling(""))
        _ <- logger.info(
          s"genesis.snapshot and genesis.address have been created for the metagraph ${identifier.show} in ${balancesPath.resolveSibling("")}"
        )
      } yield ()
    }
  }

}
