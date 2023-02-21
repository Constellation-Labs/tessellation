package org.tessellation.currency

import org.tessellation.currency.cli.method
import org.tessellation.currency.cli.method.Run
import org.tessellation.ext.kryo._
import org.tessellation.schema.cluster.ClusterId
import org.tessellation.sdk.app.TessellationIOApp
import org.tessellation.sdk.{SdkOrSharedOrKernelRegistrationIdRange, sdkKryoRegistrar}

import com.monovore.decline.Opts
import eu.timepit.refined.boolean.Or

abstract class CurrencyApp(
  header: String,
  clusterId: ClusterId,
  helpFlag: Boolean,
  version: String,
  tokenSymbol: String
) extends TessellationIOApp[Run](clusterId.toString, header, clusterId, helpFlag, version) {
  val opts: Opts[Run] = method.opts

  type KryoRegistrationIdRange = CurrencyKryoRegistrationIdRange Or SdkOrSharedOrKernelRegistrationIdRange

  val kryoRegistrar: Map[Class[_], KryoRegistrationId[KryoRegistrationIdRange]] =
    currencyKryoRegistrar.union(sdkKryoRegistrar)
}
