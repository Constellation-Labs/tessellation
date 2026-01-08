package io.constellationnetwork.node.shared.app

sealed trait Layer
case object DagL0 extends Layer
case object DagL1 extends Layer
case object CurrencyL0 extends Layer
case object CurrencyL1 extends Layer
