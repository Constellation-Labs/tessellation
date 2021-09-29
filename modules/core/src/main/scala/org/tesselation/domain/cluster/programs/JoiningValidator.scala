package org.tesselation.domain.cluster.programs

import cats.data.ValidatedNel
import cats.syntax.validated._

import org.tesselation.schema.cluster.{
  HostDifferentThanRemoteAddress,
  LocalHostNotPermitted,
  RegistrationRequestVerification
}
import org.tesselation.schema.peer.RegistrationRequest

import com.comcast.ip4s.{Host, IpLiteralSyntax}

sealed trait JoiningValidator {

  private def isLocalHost(h: Host) =
    if (h.compare(host"127.0.0.1") == 0 || h.compare(host"localhost") == 0)
      h.validNel
    else LocalHostNotPermitted.invalidNel

  private def isHostEqualRemoteAddress(h: Host, remoteAddress: Host) =
    if (h.compare(remoteAddress) == 0) h.validNel
    else HostDifferentThanRemoteAddress.invalidNel

//  private def isNotSelfId(id: PeerId)

  def verifyRegistrationRequest(
    registrationRequest: RegistrationRequest,
    remoteAddress: Host
  ): ValidatedNel[RegistrationRequestVerification, RegistrationRequest] =
    isLocalHost(registrationRequest.ip)
      .product(isHostEqualRemoteAddress(registrationRequest.ip, remoteAddress))
      .map(_ => registrationRequest)

}

object JoiningValidator extends JoiningValidator
