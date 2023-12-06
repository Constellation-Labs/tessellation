package org.tessellation.dag.l0.infrastructure.statechannel

import org.tessellation.env.AppEnvironment

import eu.timepit.refined.auto._
import weaver._

object StateChannelAllowanceListsSuite extends SimpleIOSuite {

  pureTest("allowance list config for dev should not be defined") {

    val result = StateChannelAllowanceLists.get(AppEnvironment.Dev)

    expect.same(None, result)
  }

  pureTest("allowance list config for testnet should not be defined") {

    val result = StateChannelAllowanceLists.get(AppEnvironment.Testnet)

    expect.same(None, result)
  }

  pureTest("allowance list config for integrationnet should be defined") {

    val result = StateChannelAllowanceLists.get(AppEnvironment.Integrationnet).exists(_.nonEmpty)

    expect.same(true, result)
  }

  pureTest("allowance list config for mainnet should be defined") {

    val result = StateChannelAllowanceLists.get(AppEnvironment.Mainnet).exists(_.nonEmpty)

    expect.same(true, result)
  }

}
