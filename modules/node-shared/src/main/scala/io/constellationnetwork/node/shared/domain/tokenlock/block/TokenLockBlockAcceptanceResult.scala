package io.constellationnetwork.node.shared.domain.tokenlock.block

import io.constellationnetwork.schema.tokenLock.TokenLockBlock
import io.constellationnetwork.security.signature.Signed

import derevo.cats.show
import derevo.derive
import eu.timepit.refined.cats._

@derive(show)
case class TokenLockBlockAcceptanceResult(
  contextUpdate: TokenLockBlockAcceptanceContextUpdate,
  accepted: List[Signed[TokenLockBlock]],
  notAccepted: List[(Signed[TokenLockBlock], TokenLockBlockNotAcceptedReason)]
)
