package org.tessellation.eth

import cats.effect.IO
import org.tessellation.eth.hylo.ETHStackHylomorphism
import org.tessellation.eth.web3j.ETHBlockchainClient
import org.tessellation.schema.{Cell, CellError, DAG, ETH, LiquidityPool, StackF, Ω}

case class ETHContext(lp: LiquidityPool[ETH, DAG], ethBlockchainClient: ETHBlockchainClient)

case class ETHCellInput(cmd: Ω, ctx: ETHContext) extends Ω

case class ETHCell(input: Ω, ctx: ETHContext)
    extends Cell[IO, StackF, Ω, Either[CellError, Ω], Ω](ETHCellInput(input, ctx), ETHStackHylomorphism.hyloM, identity) {}
