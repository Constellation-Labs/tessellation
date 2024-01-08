package org.tessellation.dag.l1.domain.consensus.block

import java.security.KeyPair
import java.util.UUID
import java.util.concurrent.TimeUnit

import cats.data.NonEmptyList
import cats.effect.{IO, Resource}
import cats.syntax.option._

import scala.concurrent.duration.FiniteDuration

import org.tessellation.dag.l1.Main
import org.tessellation.dag.l1.domain.consensus.block.BlockConsensusInput.Proposal
import org.tessellation.ext.cats.effect.ResourceIO
import org.tessellation.json.JsonHashSerializer
import org.tessellation.kryo.KryoSerializer
import org.tessellation.node.shared.domain.transaction.TransactionValidator
import org.tessellation.node.shared.nodeSharedKryoRegistrar
import org.tessellation.schema.address.Address
import org.tessellation.schema.block.Tips
import org.tessellation.schema.height.Height
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.round.RoundId
import org.tessellation.schema.transaction.{TransactionFee, TransactionReference}
import org.tessellation.schema.{Block, BlockReference}
import org.tessellation.security.hash.ProofsHash
import org.tessellation.security.hex.Hex
import org.tessellation.security.key.ops.PublicKeyOps
import org.tessellation.security.signature.SignedValidator
import org.tessellation.security.signature.signature.SignatureProof
import org.tessellation.security.{Hasher, KeyPairGenerator, SecurityProvider}
import org.tessellation.transaction.TransactionGenerator

import eu.timepit.refined.auto._
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
      implicit0(j: JsonHashSerializer[IO]) <- JsonHashSerializer.forSync[IO].asResource
      implicit0(h: Hasher[IO]) = Hasher.forSync[IO]
      srcKey <- KeyPairGenerator.makeKeyPair[IO].asResource
      dstKey <- KeyPairGenerator.makeKeyPair[IO].asResource
      srcAddress = srcKey.getPublic.toAddress
      dstAddress = dstKey.getPublic.toAddress
      signedValidator = SignedValidator.make
      txValidator = TransactionValidator.make[F](signedValidator)
    } yield (ks, h, sp, srcKey, dstKey, srcAddress, dstAddress, txValidator)

  implicit val logger = Slf4jLogger.getLogger[IO]

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
    case (kp, h, _, _, _, _, _, txValidator) =>
      implicit val kryoPool = kp
      implicit val hasher = h

      baseRoundData.formBlock[IO](txValidator).map(maybeBlock => expect.same(None, maybeBlock))
  }

  test(
    "formBlock should return the block with properly selected transactions - preferring the ones with higher fee if there are concurrent chains of transactions"
  ) {
    case (kp, h, sp, srcKey, _, srcAddress, dstAddress, txValidator) =>
      implicit val kryoPool = kp
      implicit val securityProvider = sp
      implicit val hasher = h

      for {
        txsA <- generateTransactions(srcAddress, srcKey, dstAddress, 3)
        txsA2 <- generateTransactions(srcAddress, srcKey, dstAddress, 3, TransactionFee(1L))
        roundData = baseRoundData.copy(
          ownProposal = baseRoundData.ownProposal.copy(transactions = txsA.toList.map(_.signed).toSet),
          peerProposals = Map(
            peerIdB -> baseProposal.copy(senderId = peerIdB, transactions = txsA2.toList.map(_.signed).toSet),
            peerIdC -> baseProposal.copy(senderId = peerIdC, transactions = Set.empty)
          )
        )
        result <- roundData.formBlock(txValidator)
      } yield
        expect.same(
          Block(baseProposal.tips.value, txsA2.map(_.signed).toNes).some,
          result
        )
  }

  test("formBlock should pick transactions correctly from the pool of transactions from all facilitators") {
    case (kp, h, sp, srcKey, _, srcAddress, dstAddress, txValidator) =>
      implicit val kryoPool = kp
      implicit val securityProvider = sp
      implicit val hasher = h

      for {
        txsA <- generateTransactions(srcAddress, srcKey, dstAddress, 1)
        txRef = TransactionReference.of(txsA.head).some
        txsA2 <- generateTransactions(srcAddress, srcKey, dstAddress, 1, TransactionFee(1L), txRef)
        roundData = baseRoundData.copy(
          ownProposal = baseRoundData.ownProposal.copy(transactions = txsA.toList.map(_.signed).toSet),
          peerProposals = Map(
            peerIdB -> baseProposal.copy(senderId = peerIdB, transactions = txsA2.toList.map(_.signed).toSet),
            peerIdC -> baseProposal.copy(senderId = peerIdC, transactions = Set.empty)
          )
        )
        result <- roundData.formBlock(txValidator)
      } yield
        expect.same(
          Block(baseProposal.tips.value, (txsA.map(_.signed) ++ txsA2.map(_.signed).toList).toNes).some,
          result
        )
  }

  test("formBlock should pick transactions correctly when concurrent transactions are proposed by different facilitators") {
    case (kp, h, sp, srcKey, _, srcAddress, dstAddress, txValidator) =>
      implicit val kryoPool = kp
      implicit val securityProvider = sp
      implicit val hasher = h

      for {
        txsA <- generateTransactions(srcAddress, srcKey, dstAddress, 2)
        txRef = TransactionReference.of(txsA.head).some
        txsA2 <- generateTransactions(srcAddress, srcKey, dstAddress, 2, TransactionFee(1L), txRef)
        roundData = baseRoundData.copy(
          ownProposal = baseRoundData.ownProposal.copy(transactions = txsA.toList.map(_.signed).toSet),
          peerProposals = Map(
            peerIdB -> baseProposal.copy(senderId = peerIdB, transactions = txsA2.toList.map(_.signed).toSet),
            peerIdC -> baseProposal.copy(senderId = peerIdC, transactions = Set.empty)
          )
        )
        result <- roundData.formBlock(txValidator)
      } yield
        expect.same(
          Block(baseProposal.tips.value, (NonEmptyList.one(txsA.head.signed) ++ txsA2.map(_.signed).toList).toNes).some,
          result
        )
  }

  test("formBlock should discard transactions that are invalid") {
    case (kp, h, sp, srcKey, dstKey, srcAddress, dstAddress, txValidator) =>
      implicit val kryoPool = kp
      implicit val securityProvider = sp
      implicit val hasher = h

      for {
        txsA <- generateTransactions(srcAddress, srcKey, dstAddress, 3)
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
        result <- roundData.formBlock(txValidator)
      } yield
        expect.same(
          Block(baseProposal.tips.value, NonEmptyList.one(txsA.head.signed).toNes).some,
          result
        )
  }
}
