package org

import java.security.PublicKey

import cats.effect.Async

import org.tessellation.domain.aci.StateChannelOutput
import org.tessellation.domain.snapshot.{TimeSnapshotTrigger, TipSnapshotTrigger}
import org.tessellation.ext.kryo._
import org.tessellation.schema.peer.PeerId
import org.tessellation.security.SecurityProvider

import eu.timepit.refined.auto._
import eu.timepit.refined.numeric.Interval
import io.estatico.newtype.ops._

package object tessellation {

  type CoreKryoRegistrationIdRange = Interval.Closed[700, 799]
  type CoreKryoRegistrationId = KryoRegistrationId[CoreKryoRegistrationIdRange]

  val coreKryoRegistrar: Map[Class[_], CoreKryoRegistrationId] = Map(
    classOf[StateChannelOutput] -> 700,
    classOf[TipSnapshotTrigger] -> 701,
    classOf[TimeSnapshotTrigger] -> 702
  )

  implicit class PeerIdToPublicKey(id: PeerId) {

    def toPublic[F[_]: Async: SecurityProvider]: F[PublicKey] =
      id.coerce.toPublicKey
  }

}
