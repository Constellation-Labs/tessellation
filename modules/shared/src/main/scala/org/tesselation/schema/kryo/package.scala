package org.tesselation.schema

import org.tesselation.crypto.Signed
import org.tesselation.schema.address.AddressCache
import org.tesselation.schema.gossip._
import org.tesselation.schema.peer.SignRequest

package object kryo {

  val schemaKryoRegistrar: Map[Class[_], Int] = Map(
    classOf[AddressCache] -> 101,
    classOf[SignRequest] -> 102,
    classOf[Signed[_]] -> 201,
    classOf[Rumor] -> 202,
    classOf[StartGossipRoundRequest] -> 203,
    classOf[StartGossipRoundResponse] -> 204,
    classOf[EndGossipRoundRequest] -> 205,
    classOf[EndGossipRoundResponse] -> 206
  )
}
