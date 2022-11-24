package org.tessellation.currency

import org.tessellation.currency.l0.cli.method
import org.tessellation.currency.l0.cli.method.Run
import org.tessellation.ext.kryo.KryoRegistrationId
import org.tessellation.schema.cluster.ClusterId
import org.tessellation.sdk.app.TessellationIOApp

import com.monovore.decline.Opts

abstract class CurrencyApp(
  header: String,
  clusterId: ClusterId,
  helpFlag: Boolean,
  version: String,
  tokenSymbol: String
) extends TessellationIOApp[Run](clusterId.toString, header, clusterId, helpFlag, version) {
  val opts: Opts[Run] = method.opts

  type KryoRegistrationIdRange = CurrencyKryoRegistrationIdRange

  val kryoRegistrar: Map[Class[_], KryoRegistrationId[KryoRegistrationIdRange]] =
    currencyKryoRegistrar
}
