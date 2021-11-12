package org.tessellation.schema

import org.tessellation.schema.address.DAGAddressRefined

import eu.timepit.refined.refineV
import weaver.FunSuite
import weaver.scalacheck.Checkers

object AddressSuite extends FunSuite with Checkers {
  val validAddress = "DAG2EUdecqFwEGcgAcH1ac2wrsg8acrgGwrQgech"

  test("correct DAG Address should pass validation") {
    val result = refineV[DAGAddressRefined].apply[String](validAddress)

    expect(result.isRight)
  }

  test("stardust collective DAG Address should pass validation") {
    val result = refineV[DAGAddressRefined].apply[String](StardustCollective.address)

    expect(result.isRight)
  }

  test("too long DAG Address should fail validation") {
    val result = refineV[DAGAddressRefined].apply[String](validAddress + "a")

    expect(result.isLeft)
  }

  test("DAG Address with wrong parity should fail validation") {
    val result = refineV[DAGAddressRefined].apply[String]("DAG1" + validAddress.substring(4))

    expect(result.isLeft)
  }

  test("DAG Address with non-base58 character should fail validation") {
    val result = refineV[DAGAddressRefined].apply[String](validAddress.replace("h", "0"))

    expect(result.isLeft)
  }
}
