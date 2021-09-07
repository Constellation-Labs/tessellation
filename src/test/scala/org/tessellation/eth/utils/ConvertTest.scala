package org.tessellation.eth.utils

import org.scalatest.GivenWhenThen
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.tessellation.eth.utils.Convert._

class ConvertTest extends AnyFreeSpec with GivenWhenThen with Matchers {
  "String to Hex" in {
    Given("DAG address")
    val address = "DAGXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX"

    When("calling asciiToHex")
    val hex = asciiToHex(address)

    And("calling hexToAscii")
    val ascii = hexToAscii(hex)

    Then("produces DAG address")
    ascii shouldBe address
  }
}
