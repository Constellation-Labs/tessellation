package io.constellationnetwork.node.shared.infrastructure

sealed trait L1Layer

case object DagL1 extends L1Layer
case object CurrencyL1 extends L1Layer
case object DataL1 extends L1Layer
