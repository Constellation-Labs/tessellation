package org.tesselation.schema

import org.tesselation.generators._
import org.tesselation.schema.cluster.SessionToken
import org.tesselation.schema.node.NodeState
import org.tesselation.schema.peer.{Peer, PeerId}
import org.tesselation.security.hex.Hex

import com.comcast.ip4s.{Host, Port}
import org.scalacheck.Gen

object generators {

  val peerIdGen: Gen[PeerId] =
    nesGen(str => PeerId(Hex(str)))

  val hostGen: Gen[Host] =
    for {
      a <- Gen.chooseNum(1, 255)
      b <- Gen.chooseNum(1, 255)
      c <- Gen.chooseNum(1, 255)
      d <- Gen.chooseNum(1, 255)
    } yield Host.fromString(s"$a.$b.$c.$d").get

  val portGen: Gen[Port] =
    Gen.chooseNum(1, 65535).map(Port.fromInt(_).get)

  val nodeStateGen: Gen[NodeState] =
    Gen.oneOf(NodeState.all)

  val peerGen: Gen[Peer] =
    for {
      i <- peerIdGen
      h <- hostGen
      p <- portGen
      p2 <- portGen
      s <- Gen.uuid.map(SessionToken.apply)
      st <- nodeStateGen
    } yield Peer(i, h, p, p2, s, st)

  def peersGen(n: Option[Int] = None): Gen[Set[Peer]] =
    n.map(Gen.const).getOrElse(Gen.chooseNum(1, 20)).flatMap { n =>
      Gen.sequence[Set[Peer], Peer](Array.tabulate(n)(_ => peerGen))
    }

}
