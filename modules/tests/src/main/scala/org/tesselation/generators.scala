package org.tesselation

import org.tesselation.keytool.Base58
import org.tesselation.schema.peer.{Peer, PeerId}

import com.comcast.ip4s.{Host, Port}
import org.scalacheck.Gen

object generators {

  val nonEmptyStringGen: Gen[String] =
    Gen.chooseNum(21, 40).flatMap { n =>
      Gen.buildableOfN[String, Char](n, Gen.alphaChar)
    }

  def nesGen[A](f: String => A): Gen[A] =
    nonEmptyStringGen.map(f)

  val peerIdGen: Gen[PeerId] =
    nesGen(PeerId.apply)

  val hostGen: Gen[Host] =
    for {
      a <- Gen.chooseNum(1, 255)
      b <- Gen.chooseNum(1, 255)
      c <- Gen.chooseNum(1, 255)
      d <- Gen.chooseNum(1, 255)
    } yield Host.fromString(s"$a.$b.$c.$d").get

  val portGen: Gen[Port] =
    Gen.chooseNum(1, 65535).map(Port.fromInt(_).get)

  val peerGen: Gen[Peer] =
    for {
      i <- peerIdGen
      h <- hostGen
      p <- portGen
      p2 <- portGen
    } yield Peer(i, h, p, p2)

  def peersGen(n: Option[Int] = None): Gen[Set[Peer]] =
    n.map(Gen.const).getOrElse(Gen.chooseNum(1, 20)).flatMap { n =>
      Gen.sequence[Set[Peer], Peer](Array.tabulate(n)(_ => peerGen))
    }

  val base58CharGen: Gen[Char] = Gen.oneOf(Base58.alphabet)

  val base58StringGen: Gen[String] =
    Gen.chooseNum(21, 40).flatMap { n =>
      Gen.buildableOfN[String, Char](n, base58CharGen)
    }

}
