package io.constellationnetwork.currency.l0.snapshot.programs

import java.security.KeyPair

import cats.Parallel
import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.{BaseDataApplicationL0Service, L0NodeContext}
import io.constellationnetwork.currency.l0.snapshot.services.StateChannelSnapshotService
import io.constellationnetwork.currency.schema.currency._
import io.constellationnetwork.ext.crypto._
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.node.shared.domain.collateral.{Collateral, OwnCollateralNotSatisfied}
import io.constellationnetwork.node.shared.domain.genesis.{GenesisFS => GenesisLoader}
import io.constellationnetwork.node.shared.domain.snapshot.services.GlobalL0Service
import io.constellationnetwork.node.shared.http.p2p.clients.StateChannelSnapshotClient
import io.constellationnetwork.node.shared.infrastructure.snapshot.storage.IdentifierStorage
import io.constellationnetwork.schema.CurrencyStateProofSelector
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.peer.{L0Peer, PeerId}
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.{Hashed, Hasher, SecurityProvider}
import io.constellationnetwork.statechannel.StateChannelSnapshotBinary

import fs2.io.file.Path
import org.typelevel.log4cats.slf4j.Slf4jLogger

trait Genesis[F[_]] {
  def acceptSignedGenesis(dataApplication: Option[BaseDataApplicationL0Service[F]])(genesis: Signed[CurrencySnapshot])(
    implicit context: L0NodeContext[F],
    hasher: Hasher[F],
    currencyStateProofSelector: CurrencyStateProofSelector
  ): F[(Signed[CurrencyIncrementalSnapshot], CurrencySnapshotInfo, Hashed[StateChannelSnapshotBinary], Address)]

  def accept(dataApplication: Option[BaseDataApplicationL0Service[F]])(genesisPath: Path)(
    implicit context: L0NodeContext[F],
    hasher: Hasher[F],
    currencyStateProofSelector: CurrencyStateProofSelector
  ): F[(Signed[CurrencyIncrementalSnapshot], CurrencySnapshotInfo, Hashed[StateChannelSnapshotBinary], Address)]

  def create(dataApplication: Option[BaseDataApplicationL0Service[F]])(
    balancesPath: Path,
    keyPair: KeyPair
  )(implicit context: L0NodeContext[F], hasher: Hasher[F]): F[Unit]
}

object Genesis {
  def make[F[_]: Async: Parallel: SecurityProvider: JsonSerializer](
    keyPair: KeyPair,
    collateral: Collateral[F],
    stateChannelSnapshotService: StateChannelSnapshotService[F],
    stateChannelSnapshotClient: StateChannelSnapshotClient[F],
    globalL0Peer: L0Peer,
    nodeId: PeerId,
    genesisLoader: GenesisLoader[F, CurrencySnapshot],
    identifierStorage: IdentifierStorage[F],
    l0Service: GlobalL0Service[F]
  ): Genesis[F] = new Genesis[F] {
    private val logger = Slf4jLogger.getLogger

    override def acceptSignedGenesis(
      dataApplication: Option[BaseDataApplicationL0Service[F]]
    )(
      genesis: Signed[CurrencySnapshot]
    )(
      implicit context: L0NodeContext[F],
      hasher: Hasher[F],
      currencyStateProofSelector: CurrencyStateProofSelector
    ): F[(Signed[CurrencyIncrementalSnapshot], CurrencySnapshotInfo, Hashed[StateChannelSnapshotBinary], Address)] = for {
      hashedGenesis <- genesis.toHashed[F]
      firstIncrementalSnapshot <- CurrencySnapshot.mkFirstIncrementalSnapshot[F](hashedGenesis)
      signedFirstIncrementalSnapshot <- firstIncrementalSnapshot.sign(keyPair)

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

      signedIncrementalBinary <- stateChannelSnapshotService.createBinary(
        signedFirstIncrementalSnapshot,
        binaryHash,
        None,
        None
      )
      hashedIncrementalBinary <- signedIncrementalBinary.toHashed
      _ <- stateChannelSnapshotClient.send(identifier, signedIncrementalBinary)(globalL0Peer)

      _ <- logger.info(s"Genesis binary ${binaryHash.show} and ${hashedIncrementalBinary.hash.show} accepted and sent to Global L0")
    } yield (signedFirstIncrementalSnapshot, hashedGenesis.info.toCurrencySnapshotInfo, hashedIncrementalBinary, identifier)

    override def accept(dataApplication: Option[BaseDataApplicationL0Service[F]])(genesisPath: Path)(
      implicit context: L0NodeContext[F],
      hasher: Hasher[F],
      currencyStateProofSelector: CurrencyStateProofSelector
    ): F[(Signed[CurrencyIncrementalSnapshot], CurrencySnapshotInfo, Hashed[StateChannelSnapshotBinary], Address)] = genesisLoader
      .loadSignedGenesis(genesisPath)
      .flatTap { genesis =>
        dataApplication
          .traverse(app => app.setCalculatedState(genesis.ordinal, app.genesis.calculated))
      }
      .flatMap(acceptSignedGenesis(dataApplication))

    def create(dataApplication: Option[BaseDataApplicationL0Service[F]])(
      balancesPath: Path,
      keyPair: KeyPair
    )(implicit context: L0NodeContext[F], hasher: Hasher[F]): F[Unit] = {
      def mkBalances =
        genesisLoader
          .loadBalances(balancesPath)
          .map(_.map(a => (a.address, a.balance)).toMap)

      def mkDataApplicationPart =
        dataApplication.traverse { da =>
          (
            da.serializedOnChainGenesis,
            da.hashCalculatedState(da.genesis.calculated)
          ).mapN(DataApplicationPartV1(_, List.empty, _))
        }

      for {
        balances <- mkBalances
        dataApplicationPart <- mkDataApplicationPart
        (latestSnapshot, _) <- l0Service.pullLatestSnapshot

        genesis = CurrencySnapshot.mkGenesis(balances, dataApplicationPart, latestSnapshot.some)
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
