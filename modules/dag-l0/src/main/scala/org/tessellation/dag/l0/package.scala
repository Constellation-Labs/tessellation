package org.tessellation.dag

import java.security.PublicKey

import cats.effect.Async

import org.tessellation.ext.kryo._
import org.tessellation.schema.peer.PeerId
import org.tessellation.security.SecurityProvider
import org.tessellation.statechannel.StateChannelOutput

import eu.timepit.refined.auto._
import eu.timepit.refined.numeric.Interval
import io.estatico.newtype.ops._

package object l0 {

  type DagL0KryoRegistrationIdRange = Interval.Closed[700, 799]
  type DagL0KryoRegistrationId = KryoRegistrationId[DagL0KryoRegistrationIdRange]

  val dagL0KryoRegistrar: Map[Class[_], DagL0KryoRegistrationId] = Map(
    classOf[StateChannelOutput] -> 700
  )

  implicit class PeerIdToPublicKey(id: PeerId) {

    def toPublic[F[_]: Async: SecurityProvider]: F[PublicKey] =
      id.coerce.toPublicKey
  }

}
