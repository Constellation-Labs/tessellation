package org.tessellation.eth

import cats.effect.IO
import org.tessellation.schema.{DAG, ETH, LiquidityPool}

class ETHDAGLiquidityPool(ethAmount: Long, dagAmount: Long, val ethAddress: String, val dagAddress: String)
    extends LiquidityPool[ETH, DAG](ethAmount, dagAmount, ethAddress, dagAddress) {

  def getETHAmount: IO[Long] = getBalanceA

  def exchangeETHForDAG(amount: Long): IO[Long] = exchangeAForB(amount)

  def getDAGAmount: IO[Long] = getBalanceB

  def exchangeDAGForETH(amount: Long): IO[Long] = exchangeBForA(amount)
}

object ETHDAGLiquidityPool {

  def apply(ethAmount: Long, dagAmount: Long, ethAddress: String, dagAddress: String): ETHDAGLiquidityPool =
    new ETHDAGLiquidityPool(ethAmount, dagAmount, ethAddress, dagAddress)
}
