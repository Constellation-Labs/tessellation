package io.constellationnetwork.node.shared.infrastructure.block.processing

import cats.data.NonEmptyList
import cats.effect.{IO, Resource}
import cats.syntax.option._

import io.constellationnetwork.node.shared.domain.block.generators.signedBlockGen
import io.constellationnetwork.node.shared.domain.block.processing.{BlockAcceptanceContext, SigningPeerBelowCollateral}
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.{Amount, Balance}
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.transaction.TransactionReference
import io.constellationnetwork.schema.{Block, BlockReference}
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature.{Signature, SignatureProof}

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong
import org.scalacheck.Gen
import weaver.MutableIOSuite
import weaver.scalacheck.Checkers

object BlockAcceptanceLogicSuite extends MutableIOSuite with Checkers {

  private val (address1, peer1) = (
    Address("DAG0y4eLqhhXUafeE3mgBstezPTnr8L3tZjAtMWB"),
    PeerId(
      Hex(
        "6128e64d623ce4320c9523dc6d64d7d93647e40fb44c77d70bcb34dc4042e63cde16320f336c9c0011315aa9f006ad2941b9a92102a055e1bcc5a66ef8b612ef"
      )
    )
  )

  private val (addressWithoutBalance, peerWithoutBalance) = (
    Address("DAG07tqNLYW8jHU9emXcRTT3CfgCUoumwcLghopd"),
    PeerId(
      Hex(
        "79c4a78387a8782dbc88de95098d134a7dbf3b8a3316eaa1e41e112dc5b21a5b0cefdd0871495435591089264aa5c8a2429a75b384519662184bedfa6e7b886f"
      )
    )
  )

  type Res = SecurityProvider[F]

  def sharedResource: Resource[IO, BlockAcceptanceLogicSuite.Res] = SecurityProvider.forAsync[IO]

  def mkContext(returnedBalance: Balance, collateral: Amount = Amount(250_000L)) =
    new BlockAcceptanceContext[IO] {

      def getBalance(address: Address): IO[Option[Balance]] =
        IO.pure(address match {
          case `address1` => returnedBalance.some
          case _          => none
        })

      def getLastTxRef(address: Address): IO[Option[TransactionReference]] = ???

      def getInitialTxRef: TransactionReference = ???

      def getParentUsage(blockReference: BlockReference): IO[Option[NonNegLong]] = ???

      def getCollateral: Amount = collateral
    }

  test("accept block with signers with collateral") { implicit sc =>
    forall(dagBlockWithSigningPeer(Seq(peer1))) { block =>
      BlockAcceptanceLogic
        .processSignatures[IO](block, mkContext(Balance(250_000L)))
        .value
        .map(expect.same(_, Right(())))
    }
  }

  test("reject block with signers without collateral") { implicit sc =>
    forall(dagBlockWithSigningPeer(Seq(peer1))) { block =>
      BlockAcceptanceLogic
        .processSignatures[IO](block, mkContext(Balance(249_999L)))
        .value
        .map(expect.same(_, Left(SigningPeerBelowCollateral(NonEmptyList.of(address1)))))
    }
  }

  test("reject block with signers without balance") { implicit sc =>
    forall(dagBlockWithSigningPeer(Seq(peerWithoutBalance))) { block =>
      BlockAcceptanceLogic
        .processSignatures[IO](block, mkContext(Balance(250_000L)))
        .value
        .map(expect.same(_, Left(SigningPeerBelowCollateral(NonEmptyList.of(addressWithoutBalance)))))
    }
  }

  test("accept block with signers without balance when collateral is 0") { implicit sc =>
    forall(dagBlockWithSigningPeer(Seq(peer1))) { block =>
      BlockAcceptanceLogic
        .processSignatures[IO](block, mkContext(Balance(250_000L), Amount(0L)))
        .value
        .map(expect.same(_, Right(())))
    }
  }

  def dagBlockWithSigningPeer(peers: Seq[PeerId]): Gen[Signed[Block]] =
    signedBlockGen.map(_.copy(proofs = buildProofs(peers)))

  def buildProofs(peerIds: Seq[PeerId]) =
    NonEmptyList.fromListUnsafe(peerIds.toList.map(peerId => SignatureProof(peerId.toId, Signature(Hex(""))))).toNes

}
