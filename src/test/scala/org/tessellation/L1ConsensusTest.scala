package org.tessellation

import higherkindness.droste.scheme
import org.scalatest.GivenWhenThen
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.tessellation.schema.L1Consensus.{L1ConsensusMetadata, algebra, coalgebra}
import org.tessellation.schema.{CreateBlock, GatherProposals, L1Block, L1Edge, L1Transaction, ProposalResponses, SelectFacilitators}

class L1ConsensusTest extends AnyFreeSpec with GivenWhenThen with Matchers {

  "hylomorphism" - {

    val hylo = scheme.hyloM(algebra, coalgebra)

    "Initial step" in {
      Given("an empty initial state")
      val initialState = L1ConsensusMetadata.empty

      And("a random transaction")
      val input = L1Edge(Set(L1Transaction(2)))

      When("executing L1Consensus hylomorphism")
      val result = hylo(input).run(initialState).unsafeRunSync

      Then("it stores own transaction in the state")
      result._1 shouldEqual L1ConsensusMetadata(txs = Set(L1Transaction(2)), facilitators = None)

      And("returns SelectFacilitators step")
      result._2 shouldBe SelectFacilitators()
    }


    "SelectFacilitators" in {
      Given("a state with own transaction")
      val initialState = L1ConsensusMetadata(txs = Set(L1Transaction(1)), facilitators = None)

      And("the SelectFacilitators step")
      val input = SelectFacilitators()

      When("executing L1Consensus hylomorphism")
      val result = hylo(input).run(initialState).unsafeRunSync

      Then("it selects facilitators from the peers")
      // TODO: mock getPeers
      result._1.facilitators shouldBe defined

      And("returns GatherProposals step")
      result._2 shouldBe GatherProposals()
    }

    "GatherProposals" in {
      Given("a state with own transaction and facilitators")
      val initialState = L1ConsensusMetadata(txs = Set(L1Transaction(1)), facilitators = Some(Set("node1", "node3")))

      And("the GatherProposals step")
      val input = GatherProposals()

      When("executing L1Consensus hylomorphism")
      val result = hylo(input).run(initialState).unsafeRunSync

      Then("it asks facilitators for proposals")
      // TODO: mockito - mock dependencies

      And("returns ProposalResponses step") // TODO: when mocked above, mock random responses
      result._2.isInstanceOf[ProposalResponses[Any]] shouldBe true
    }

    "ProposalResponses" in {
      Given("a state with own transaction and facilitators")
      val initialState = L1ConsensusMetadata(txs = Set(L1Transaction(1)), facilitators = Some(Set("node1", "node3")))

      And("the CreateBlock step with data")
      val input = ProposalResponses(Set(("node1", 4), ("node3", 5)))

      When("executing L1Consensus hylomorphism")
      val result = hylo(input).run(initialState).unsafeRunSync

      And("returns a block") // TODO: when mocked above, mock random responses
      result._2 shouldEqual L1Block(9)
    }
  }

}
