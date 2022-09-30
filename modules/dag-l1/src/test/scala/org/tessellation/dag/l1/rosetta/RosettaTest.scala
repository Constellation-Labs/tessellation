package org.tessellation.dag.l1.rosetta

import cats.effect.IO

import weaver.SimpleIOSuite

object RosettaTest extends SimpleIOSuite {

  test("asdf") {

    // start the api server
    // run a client here that hits the api server with all the postman queries.
    IO.pure(expect.all(true))
  }
}
