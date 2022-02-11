package org.tessellation.sdk.infrastructure.gossip

import derevo.cats.eqv
import derevo.derive

@derive(eqv)
sealed trait OriginPolicy

/**
  * Handles all rumors
  */
case object IncludeSelfOrigin extends OriginPolicy

/**
  * Handle rumors only from other peers
  */
case object ExcludeSelfOrigin extends OriginPolicy

/**
  * Handle all rumors, but rumors from self will be handled with empty function
  */
case object IgnoreSelfOrigin extends OriginPolicy
