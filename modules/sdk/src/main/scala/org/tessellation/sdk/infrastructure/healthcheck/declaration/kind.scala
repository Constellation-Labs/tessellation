package org.tessellation.sdk.infrastructure.healthcheck.declaration

object kind {

  sealed trait PeerDeclarationKind

  case object Facility extends PeerDeclarationKind
  case object Proposal extends PeerDeclarationKind
  case object Signature extends PeerDeclarationKind

}
