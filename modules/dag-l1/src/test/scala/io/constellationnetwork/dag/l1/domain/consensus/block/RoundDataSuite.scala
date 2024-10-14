package io.constellationnetwork.dag.l1.domain.consensus.block

import java.security.KeyPair
import java.util.UUID
import java.util.concurrent.TimeUnit

import cats.data.NonEmptyList
import cats.effect.{IO, Resource}
import cats.syntax.option._

import scala.concurrent.duration.FiniteDuration

import io.constellationnetwork.dag.l1.Main
import io.constellationnetwork.dag.l1.domain.consensus.block.BlockConsensusInput.Proposal
import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.node.shared.config.types.AddressesConfig
import io.constellationnetwork.node.shared.domain.transaction.TransactionValidator
import io.constellationnetwork.node.shared.nodeSharedKryoRegistrar
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.block.Tips
import io.constellationnetwork.schema.height.Height
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.round.RoundId
import io.constellationnetwork.schema.transaction.{TransactionFee, TransactionReference}
import io.constellationnetwork.schema.{Block, BlockReference}
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.ProofsHash
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.key.ops.PublicKeyOps
import io.constellationnetwork.security.signature.SignedValidator
import io.constellationnetwork.security.signature.signature.SignatureProof
import io.constellationnetwork.transaction.TransactionGenerator

import eu.timepit.refined.auto._
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import suite.ResourceSuite
import weaver.scalacheck.Checkers

object RoundDataSuite extends ResourceSuite with Checkers with TransactionGenerator {

  type Res =
    (KryoSerializer[IO], Hasher[IO], SecurityProvider[IO], KeyPair, KeyPair, Address, Address, TransactionValidator[IO])

  def sharedResource: Resource[IO, Res] =
    for {
      implicit0(ks: KryoSerializer[IO]) <- KryoSerializer.forAsync[IO](Main.kryoRegistrar ++ nodeSharedKryoRegistrar)
      implicit0(sp: SecurityProvider[IO]) <- SecurityProvider.forAsync[IO]
      implicit0(j: JsonSerializer[IO]) <- JsonSerializer.forSync[IO].asResource
      implicit0(h: Hasher[IO]) = Hasher.forKryo[IO]
      srcKey <- KeyPairGenerator.makeKeyPair[IO].asResource
      dstKey <- KeyPairGenerator.makeKeyPair[IO].asResource
      srcAddress = srcKey.getPublic.toAddress
      dstAddress = dstKey.getPublic.toAddress
      signedValidator = SignedValidator.make
      txHasher = Hasher.forKryo[IO]
      txValidator = TransactionValidator.make[F](AddressesConfig(Set()), signedValidator, txHasher)
    } yield (ks, h, sp, srcKey, dstKey, srcAddress, dstAddress, txValidator)

  implicit val logger: SelfAwareStructuredLogger[IO] = Slf4jLogger.getLogger[IO]

  val roundId = RoundId(UUID.randomUUID())
  val peerIdA = PeerId(Hex("peerA"))
  val peerIdB = PeerId(Hex("peerB"))
  val peerIdC = PeerId(Hex("peerC"))
  val tips = Tips(NonEmptyList.of(BlockReference(Height(1L), ProofsHash("0000"))))
  val baseProposal = Proposal(roundId, peerIdA, peerIdA, Set.empty, Set.empty, tips)

  val baseRoundData =
    RoundData(
      roundId,
      FiniteDuration(1000L, TimeUnit.MINUTES),
      Set.empty,
      peerIdA,
      baseProposal,
      None,
      None,
      Map.empty,
      Map.empty,
      Map.empty,
      tips
    )

  test("formBlock should return None when there were no transactions in RoundData") {
    case (ks, h, _, _, _, _, _, txValidator) =>
      implicit val kryo = ks

      baseRoundData.formBlock[IO](txValidator, Hasher.forKryo[IO]).map(maybeBlock => expect.same(None, maybeBlock))
  }

  test(
    "formBlock should return the block with properly selected transactions - preferring the ones with higher fee if there are concurrent chains of transactions"
  ) {
    case (ks, h, sp, srcKey, _, srcAddress, dstAddress, txValidator) =>
      implicit val securityProvider = sp
      implicit val kryo = ks

      for {
        txsA <- generateTransactions(srcAddress, srcKey, dstAddress, 3, txHasher = Hasher.forKryo[IO])
        txsA2 <- generateTransactions(srcAddress, srcKey, dstAddress, 3, TransactionFee(1L), txHasher = Hasher.forKryo[IO])
        roundData = baseRoundData.copy(
          ownProposal = baseRoundData.ownProposal.copy(transactions = txsA.toList.map(_.signed).toSet),
          peerProposals = Map(
            peerIdB -> baseProposal.copy(senderId = peerIdB, transactions = txsA2.toList.map(_.signed).toSet),
            peerIdC -> baseProposal.copy(senderId = peerIdC, transactions = Set.empty)
          )
        )
        result <- roundData.formBlock(txValidator, Hasher.forKryo[IO])
      } yield
        expect.same(
          Block(baseProposal.tips.value, txsA2.map(_.signed).toNes).some,
          result
        )
  }

  test("formBlock should pick transactions correctly from the pool of transactions from all facilitators") {
    case (ks, h, sp, srcKey, _, srcAddress, dstAddress, txValidator) =>
      implicit val securityProvider = sp
      implicit val kryo = ks

      for {
        txsA <- generateTransactions(srcAddress, srcKey, dstAddress, 1, txHasher = Hasher.forKryo[IO])
        txRef = TransactionReference.of(txsA.head).some
        txsA2 <- generateTransactions(srcAddress, srcKey, dstAddress, 1, TransactionFee(1L), txRef, txHasher = Hasher.forKryo[IO])
        roundData = baseRoundData.copy(
          ownProposal = baseRoundData.ownProposal.copy(transactions = txsA.toList.map(_.signed).toSet),
          peerProposals = Map(
            peerIdB -> baseProposal.copy(senderId = peerIdB, transactions = txsA2.toList.map(_.signed).toSet),
            peerIdC -> baseProposal.copy(senderId = peerIdC, transactions = Set.empty)
          )
        )
        result <- roundData.formBlock(txValidator, Hasher.forKryo[IO])
      } yield
        expect.same(
          Block(baseProposal.tips.value, (txsA.map(_.signed) ++ txsA2.map(_.signed).toList).toNes).some,
          result
        )
  }

  test("formBlock should pick transactions correctly when concurrent transactions are proposed by different facilitators") {
    case (ks, h, sp, srcKey, _, srcAddress, dstAddress, txValidator) =>
      implicit val securityProvider = sp
      implicit val kryo = ks

      for {
        txsA <- generateTransactions(srcAddress, srcKey, dstAddress, 2, txHasher = Hasher.forKryo[IO])
        txRef = TransactionReference.of(txsA.head).some
        txsA2 <- generateTransactions(srcAddress, srcKey, dstAddress, 2, TransactionFee(1L), txRef, txHasher = Hasher.forKryo[IO])
        roundData = baseRoundData.copy(
          ownProposal = baseRoundData.ownProposal.copy(transactions = txsA.toList.map(_.signed).toSet),
          peerProposals = Map(
            peerIdB -> baseProposal.copy(senderId = peerIdB, transactions = txsA2.toList.map(_.signed).toSet),
            peerIdC -> baseProposal.copy(senderId = peerIdC, transactions = Set.empty)
          )
        )
        result <- roundData.formBlock(txValidator, Hasher.forKryo[IO])
      } yield
        expect.same(
          Block(baseProposal.tips.value, (NonEmptyList.one(txsA.head.signed) ++ txsA2.map(_.signed).toList).toNes).some,
          result
        )
  }

  test("formBlock should discard transactions that are invalid") {
    case (ks, h, sp, srcKey, dstKey, srcAddress, dstAddress, txValidator) =>
      implicit val securityProvider = sp
      implicit val kryo = ks

      for {
        txsA <- generateTransactions(srcAddress, srcKey, dstAddress, 3, txHasher = Hasher.forKryo[IO])
        txToBreak = txsA.toList(1).signed
        brokenTx = txToBreak.copy(proofs = txToBreak.proofs.map(sp => SignatureProof(dstKey.getPublic.toId, sp.signature)))
        txs = Set(txsA.head.signed, brokenTx, txsA.last.signed)
        roundData = baseRoundData.copy(
          ownProposal = baseRoundData.ownProposal.copy(transactions = txs),
          peerProposals = Map(
            peerIdB -> baseProposal.copy(senderId = peerIdB, transactions = Set.empty),
            peerIdC -> baseProposal.copy(senderId = peerIdC, transactions = Set.empty)
          )
        )
        result <- roundData.formBlock(txValidator, Hasher.forKryo[IO])
      } yield
        expect.same(
          Block(baseProposal.tips.value, NonEmptyList.one(txsA.head.signed).toNes).some,
          result
        )
  }
}
