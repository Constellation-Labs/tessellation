package org.tessellation.sdk.app

import java.security.KeyPair

import cats.effect.std.Random

import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.resources.SdkResources
import org.tessellation.security.SecurityProvider

trait SDK[F[_]] {
  implicit val random: Random[F]
  implicit val securityProvider: SecurityProvider[F]
  implicit val kryoPool: KryoSerializer[F]

  val keyPair: KeyPair 
  val nodeId = PeerId.fromPublic(keyPair.getPublic)

  val sdkResources: SdkResources[F]
}
