package org.example

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class CreateInputTest extends AnyFlatSpec with Matchers {
  "The Hello object" should "say hello" in {
    val input = EmptyInput(123)

  }
}
