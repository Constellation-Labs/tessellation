package org.tesselation.schema

import derevo.cats.{eqv, order, show}
import derevo.derive
import io.estatico.newtype.macros.newtype

object ID {

  @derive(eqv, show, order)
  @newtype
  case class Id(hex: String)
}

object foo {

//  val peerId: PeerID = PeerID("foo")
//  val id: Id = Id("bar")

//  val id2: PeerID = PeerID.fromId(Id("aa"))
//  val id2: PeerID = Id("baz")

}
