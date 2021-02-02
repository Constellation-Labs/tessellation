package org.tessellation.schema

import cats.Functor
import cats.syntax.all._
import cats.effect.IO
import higherkindness.droste.{Algebra, Coalgebra}

import scala.util.Random

/*
object L1Consensus {
  type Peer = String
  type Facilitators = Set[Peer]
  case class Response[O](output: O)

  def apiCall: Peer => IO[Int] = peer => IO {
    val proposal = Random.nextInt(2)
    println(s"Peer=${peer} proposal is ${proposal}")
    proposal
  }

  def consensus(peers: Set[Peer]): IO[Option[Int]] =
    for {
      facilitators <- peers.take(2).pure[IO] // step 1 - select facilitators
      responses <- facilitators.toList.traverse(apiCall) // step 2 - collect responses
      output = (if (responses.toSet.size == 1) Some(responses.head) else None) // step 3 - validate responses
    } yield output

  val coalgebra: Coalgebra[AciF, Int] = Coalgebra {
    i => Suspend(consensus(Set("aa", "bb", "cc", "dd"))).asInstanceOf[AciF[Int]]
  }
  val algebra: Algebra[AciF, Option[Int]] = Algebra {
    case Suspend(io) => io.asInstanceOf[IO[Option[Int]]].unsafeRunSync()
  }
}

object L0Consensus {
  def createSnapshot(block: Int): IO[String] = IO { s"${block}-${block}" }

  val coalgebra: Coalgebra[AciF, Int] = Coalgebra {
    i => Suspend[Int, String](createSnapshot(i)).asInstanceOf[AciF[Int]]
  }
  val algebra: Algebra[AciF, String] = Algebra {
    case Suspend(io) => io.unsafeRunSync().asInstanceOf[String]
  }
}

sealed trait AciF[A]
case class Suspend[A, B](io: IO[B]) extends AciF[A]
case class Return[A](output: Int) extends AciF[A]

object AciF {
  implicit val functor: Functor[AciF] = new Functor[AciF] {
    override def map[A, B](fa: AciF[A])(f: A => B): AciF[B] = fa match {
      case Return(output) => Return(output)
      case Suspend(io) => Suspend(io)
    }
  }
}
 */