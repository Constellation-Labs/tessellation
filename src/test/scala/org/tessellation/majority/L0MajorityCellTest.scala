package org.tessellation.majority

import cats.data.NonEmptyList
import cats.syntax.all._
import fs2.Stream
import org.mockito.IdiomaticMockito
import org.mockito.cats.IdiomaticMockitoCats
import org.scalatest.GivenWhenThen
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.tessellation.consensus.transaction.RandomTransactionGenerator
import org.tessellation.http.HttpClient
import org.tessellation.majority.SnapshotStorage.{MajorityHeight, SnapshotProposal}
import org.tessellation.metrics.Metrics
import org.tessellation.node.{Node, Peer}
import org.tessellation.snapshot.L0Pipeline

import scala.collection.SortedMap

class L0MajorityCellTest extends AnyFreeSpec with GivenWhenThen with Matchers with IdiomaticMockito with IdiomaticMockitoCats {

  val peer1 = "peer1"
  val peer2 = "peer2"
  val peers = Set(
    Peer("", 9000, peer1, NonEmptyList.one(MajorityHeight(0L.some, 10L.some))),
    Peer("", 9000, peer2, NonEmptyList.one(MajorityHeight(0L.some, 10L.some)))
  )

  val peer1ProposalH2 = PeerProposal(peer1, SnapshotProposal("hash123", 2L, SortedMap.empty))
  val peer1ProposalH4 = PeerProposal(peer1, SnapshotProposal("hash456", 4L, SortedMap.empty))
  val ownProposalH2 = OwnProposal(SnapshotProposal("hash123", 2L, SortedMap.empty))
  val ownProposalH4 = OwnProposal(SnapshotProposal("hash456", 4L, SortedMap.empty))
  val peer2ProposalH2 = PeerProposal(peer2, SnapshotProposal("hash456", 2L, SortedMap.empty))
  val peer2ProposalH4 = PeerProposal(peer2, SnapshotProposal("hash123", 4L, SortedMap.empty))
  val metrics = mock[Metrics]
  val randomTransactionGenerator = mock[RandomTransactionGenerator]

  "majority selection" in {
    val node = Node("me", metrics, randomTransactionGenerator)
    val snapshotStorage = SnapshotStorage()
    val l0MajorityContext = L0MajorityContext(node, snapshotStorage)
    peers.toList.traverse(node.updatePeers).unsafeRunSync()
    snapshotStorage.activeBetweenHeights.modify(_ => (MajorityHeight(0L.some, 10L.some).some, ())).unsafeRunSync()
    snapshotStorage.lastMajorityState.modify(_ => (Map.empty, ())).unsafeRunSync()

    Given("first peer proposal")
    When("executing L0MajorityCell and there aren't all proposals at height 2 present")
    val step1 = L0MajorityCell(peer1ProposalH2, l0MajorityContext).run()

    Then("proposal should be processed but majority shouldn't be picked")
    step1.unsafeRunSync() shouldBe Right(ProposalProcessed(peer1ProposalH2))

    Given("own proposal")
    When("executing L0MajorityCell and there still aren't all proposals at height 2 present")
    val step2 = L0MajorityCell(ownProposalH2, l0MajorityContext).run()

    Then("own proposal should be processed but majority shouldn't be picked")
    step2.unsafeRunSync() shouldBe Right(ProposalProcessed(ownProposalH2))

    Given("second peer proposal")
    When("executing L0MajorityCell and it's the last missing proposal at a given height 2")
    val finalStep = L0MajorityCell(peer2ProposalH2, l0MajorityContext).run()

    Then("proposal should be processed and majority picked")
    finalStep.unsafeRunSync() shouldBe Right(Majority(Map(2L -> "hash123")))
  }

  "majority selection - should pick majority if all nodes present at height sent their proposals" in {
    val node = Node("me", metrics, randomTransactionGenerator)
    val snapshotStorage = SnapshotStorage()
    val l0MajorityContext = L0MajorityContext(node, snapshotStorage)
    peers.toList.traverse(node.updatePeers).unsafeRunSync()
    snapshotStorage.lastMajorityState.modify(_ => (Map.empty, ())).unsafeRunSync()

    Given("first peer proposal")
    When("executing L0MajorityCell and there aren't all proposals at height 2 present")
    val step1 = L0MajorityCell(peer1ProposalH2, l0MajorityContext).run()

    Then("proposal should be processed but majority shouldn't be picked")
    step1.unsafeRunSync() shouldBe Right(ProposalProcessed(peer1ProposalH2))

    Given("second peer proposal")
    When("executing L0MajorityCell and it's the last missing proposal at a given height 2")
    val finalStep = L0MajorityCell(peer2ProposalH2, l0MajorityContext).run()

    Then("proposal should be processed and majority picked")
    finalStep.unsafeRunSync() shouldBe Right(Majority(Map(2L -> "hash123")))
  }

  "majority selection - should pick majority for consecutive heights when needed proposals arrive" in {
    val node = Node("me", metrics, randomTransactionGenerator)
    val snapshotStorage = SnapshotStorage()
    val l0MajorityContext = L0MajorityContext(node, snapshotStorage)
    peers.toList.traverse(node.updatePeers).unsafeRunSync()
    snapshotStorage.activeBetweenHeights.modify(_ => (MajorityHeight(0L.some, 10L.some).some, ())).unsafeRunSync()
    snapshotStorage.lastMajorityState.modify(_ => (Map.empty, ())).unsafeRunSync()

    Given("first peer proposal for height 2")
    When("executing L0MajorityCell and there aren't all proposals at height 2 present")
    val step1 = L0MajorityCell(peer1ProposalH2, l0MajorityContext).run()

    Then("proposal should be processed but majority shouldn't be picked")
    step1.unsafeRunSync() shouldBe Right(ProposalProcessed(peer1ProposalH2))

    Given("own proposal for height 2")
    When("executing L0MajorityCell and there still aren't all proposals at height 2 present")
    val step2 = L0MajorityCell(ownProposalH2, l0MajorityContext).run()

    Then("own proposal should be processed but majority shouldn't be picked")
    step2.unsafeRunSync() shouldBe Right(ProposalProcessed(ownProposalH2))

    Given("second peer proposal for height 2")
    When("executing L0MajorityCell and it's the last missing proposal at a given height 2")
    val majorityHeight2 = L0MajorityCell(peer2ProposalH2, l0MajorityContext).run()

    Then("proposal should be processed and majority picked for height 2")
    majorityHeight2.unsafeRunSync() shouldBe Right(Majority(Map(2L -> "hash123")))

    Given("first peer proposal for height 4")
    When("executing L0MajorityCell and there aren't all proposals at height 4 present")
    val step3 = L0MajorityCell(peer1ProposalH4, l0MajorityContext).run()

    Then("proposal should be processed but majority shouldn't be picked")
    step3.unsafeRunSync() shouldBe Right(ProposalProcessed(peer1ProposalH4))

    Given("own proposal for height 4")
    When("executing L0MajorityCell and there still aren't all proposals at height 4 present")
    val step4 = L0MajorityCell(ownProposalH4, l0MajorityContext).run()

    Then("own proposal should be processed but majority shouldn't be picked")
    step4.unsafeRunSync() shouldBe Right(ProposalProcessed(ownProposalH4))

    Given("second peer proposal for height 4")
    When("executing L0MajorityCell and it's the last missing proposal at a given height 4")
    val majorityHeight4 = L0MajorityCell(peer2ProposalH4, l0MajorityContext).run()

    Then("proposal should be processed and majority picked for height 4")
    majorityHeight4.unsafeRunSync() shouldBe Right(Majority(Map(2L -> "hash123", 4L -> "hash456")))
  }

  "majority selection with state inside of a stream - all proposals arrive so majority should be picked" in {
    val node = Node("me", metrics, randomTransactionGenerator)
    val snapshotStorage = SnapshotStorage()
    peers.toList.traverse(node.updatePeers).unsafeRunSync()
    snapshotStorage.activeBetweenHeights.modify(_ => (MajorityHeight(0L.some, 10L.some).some, ())).unsafeRunSync()
    val l0 = L0Pipeline.init(metrics, node, snapshotStorage, mock[HttpClient]).compile.toList.unsafeRunSync().head

    val scenario = Stream(peer1ProposalH2, ownProposalH2, peer2ProposalH2).through(l0.majorityPipelineInStream)

    scenario.compile.toList.unsafeRunSync() shouldBe List(Majority(Map(2L -> "hash123")))
  }

  "majority selection with state inside of a stream - all proposals for the nodes present at given height arrive so majority should be picked" in {
    val node = Node("me", metrics, randomTransactionGenerator)
    val snapshotStorage = SnapshotStorage()
    peers.toList.traverse(node.updatePeers).unsafeRunSync()
    val l0 = L0Pipeline.init(metrics, node, snapshotStorage, mock[HttpClient]).compile.toList.unsafeRunSync().head

    val scenario = Stream(peer1ProposalH2, peer2ProposalH2).through(l0.majorityPipelineInStream)

    scenario.compile.toList.unsafeRunSync() shouldBe List(Majority(Map(2L -> "hash123")))
  }

  "majority selection with state inside of a stream - when missing proposal, no majority should be picked" in {
    val node = Node("me", metrics, randomTransactionGenerator)
    val snapshotStorage = SnapshotStorage()
    peers.toList.traverse(node.updatePeers).unsafeRunSync()
    snapshotStorage.activeBetweenHeights.modify(_ => (MajorityHeight(0L.some, 10L.some).some, ())).unsafeRunSync()
    val l0 = L0Pipeline.init(metrics, node, snapshotStorage, mock[HttpClient]).compile.toList.unsafeRunSync().head

    val scenario = Stream(peer1ProposalH2, ownProposalH2).through(l0.majorityPipelineInStream)

    scenario.compile.toList.unsafeRunSync() shouldBe List()
  }

  "majority selection with state inside of a stream - should pick majority for consecutive heights when needed proposals arrive" in {
    val node = Node("me", metrics, randomTransactionGenerator)
    val snapshotStorage = SnapshotStorage()
    peers.toList.traverse(node.updatePeers).unsafeRunSync()
    snapshotStorage.activeBetweenHeights.modify(_ => (MajorityHeight(0L.some, 10L.some).some, ())).unsafeRunSync()
    val l0 = L0Pipeline.init(metrics, node, snapshotStorage, mock[HttpClient]).compile.toList.unsafeRunSync().head

    val scenario = Stream(peer1ProposalH2, ownProposalH2, peer2ProposalH2, peer1ProposalH4, ownProposalH4, peer2ProposalH4).through(l0.majorityPipelineInStream)

    scenario.compile.toList.unsafeRunSync() shouldBe List(Majority(Map(2L -> "hash123")), Majority(Map(4L -> "hash456")))
  }

}
