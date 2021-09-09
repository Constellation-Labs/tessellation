package org.tessellation.eth.schema

import org.tessellation.schema.Ω
import org.web3j.protocol.core.methods.response.EthBlock.TransactionObject
import org.web3j.protocol.core.methods.response.Transaction

case class ETHBlock(number: BigInt, transactions: Set[Transaction] = Set.empty) extends Ω {}
