package org.tessellation.eth.hylo

import cats.syntax.all._
import cats.{Applicative, Traverse}
import higherkindness.droste.util.DefaultTraverse
import org.tessellation.consensus.L1Block
import org.tessellation.eth.schema.{ETHBlock, ETHEmission}
import org.tessellation.schema.{Hom, Ω}
import org.web3j.protocol.core.methods.response.Transaction

sealed trait ETHConsensusF[A] extends Hom[Ω, A]

case class ReceivedETHEmission[A](emission: ETHEmission) extends ETHConsensusF[A]

case class ReceivedETHBlock[A](block: ETHBlock) extends ETHConsensusF[A]

case class WaitForCorrespondingBlockTimeout[A]() extends ETHConsensusF[A]

case class ETHEmissionError[A]() extends ETHConsensusF[A]

case class ETHSwapEnd[A](block: L1Block) extends ETHConsensusF[A]

case class ETHEmissionEnd[A](tx: Transaction) extends ETHConsensusF[A]

object ETHConsensusF {
  implicit val traverse: Traverse[ETHConsensusF] = new DefaultTraverse[ETHConsensusF] {
    override def traverse[G[_]: Applicative, A, B](fa: ETHConsensusF[A])(f: A => G[B]): G[ETHConsensusF[B]] =
      fa.asInstanceOf[ETHConsensusF[B]].pure[G]
  }
}
