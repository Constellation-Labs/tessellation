package io.constellationnetwork.node.shared.domain.tokenlock.block

import io.constellationnetwork.node.shared.domain.tokenlock.TokenLockChainValidator.TokenLockNel
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.tokenLock.TokenLockBlock
import io.constellationnetwork.security.signature.Signed

import derevo.cats.{eqv, show}
import derevo.derive
import eu.timepit.refined.cats._

@derive(eqv, show)
case class TokenLockBlockAcceptanceState(
  contextUpdate: TokenLockBlockAcceptanceContextUpdate,
  accepted: List[Signed[TokenLockBlock]],
  rejected: List[(Signed[TokenLockBlock], TokenLockBlockRejectionReason)],
  awaiting: List[((Signed[TokenLockBlock], Map[Address, TokenLockNel]), TokenLockBlockAwaitReason)]
) {

  def toBlockAcceptanceResult: TokenLockBlockAcceptanceResult =
    TokenLockBlockAcceptanceResult(
      contextUpdate,
      accepted,
      awaiting.map { case ((block, _), reason) => (block, reason) } ++ rejected
    )
}

object TokenLockBlockAcceptanceState {

  def withRejectedBlocks(rejected: List[(Signed[TokenLockBlock], TokenLockBlockRejectionReason)]): TokenLockBlockAcceptanceState =
    TokenLockBlockAcceptanceState(
      contextUpdate = TokenLockBlockAcceptanceContextUpdate.empty,
      accepted = List.empty,
      rejected = rejected,
      awaiting = List.empty
    )
}
