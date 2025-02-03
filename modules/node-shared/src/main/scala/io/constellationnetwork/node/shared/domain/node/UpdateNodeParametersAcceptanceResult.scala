package io.constellationnetwork.node.shared.domain.node

import cats.data.NonEmptyChain

import io.constellationnetwork.node.shared.domain.node.UpdateNodeParametersValidator.UpdateNodeParametersValidationError
import io.constellationnetwork.schema.node.UpdateNodeParameters
import io.constellationnetwork.security.signature.Signed

case class UpdateNodeParametersAcceptanceResult(
  accepted: List[Signed[UpdateNodeParameters]],
  notAccepted: List[(Signed[UpdateNodeParameters], NonEmptyChain[UpdateNodeParametersValidationError])]
)
