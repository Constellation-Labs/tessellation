package org.tessellation.infrastructure.statechannel

import org.tessellation.sdk.config.AppEnvironment

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

  pureTest("allowance list config for integrationnet should not be defined") {

    val result = StateChannelAllowanceLists.get(AppEnvironment.Integrationnet)

    expect.same(None, result)
  }

  pureTest("allowance list config for mainnet should be defined") {

    val result = StateChannelAllowanceLists.get(AppEnvironment.Mainnet)

    expect.same(true, result.isDefined)
  }

}
