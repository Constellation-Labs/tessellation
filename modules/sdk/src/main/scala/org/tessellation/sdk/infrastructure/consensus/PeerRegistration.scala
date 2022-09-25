package org.tessellation.sdk.infrastructure.consensus

sealed trait PeerRegistration[Key]

case class Registered[Key](at: Key) extends PeerRegistration[Key]
case class Deregistered[Key](at: Key) extends PeerRegistration[Key]
