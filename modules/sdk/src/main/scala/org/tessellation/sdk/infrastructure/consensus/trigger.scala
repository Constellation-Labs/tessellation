package org.tessellation.sdk.infrastructure.consensus

import derevo.cats.{order, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

object trigger {

  @derive(order, show, encoder, decoder)
  sealed trait ConsensusTrigger

  case object EventTrigger extends ConsensusTrigger
  case object TimeTrigger extends ConsensusTrigger

}
