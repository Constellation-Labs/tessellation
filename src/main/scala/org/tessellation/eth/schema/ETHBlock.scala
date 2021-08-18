package org.tessellation.eth.schema

import org.tessellation.schema.Ω

case class ETHBlock(transactions: Set[NativeETHTransaction] = Set.empty) extends Ω {}
