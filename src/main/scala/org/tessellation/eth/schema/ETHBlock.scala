package org.tessellation.eth.schema

import org.tessellation.schema.Ω
import org.web3j.protocol.core.methods.response.EthBlock.TransactionObject

case class ETHBlock(transactions: Set[TransactionObject] = Set.empty) extends Ω {}
