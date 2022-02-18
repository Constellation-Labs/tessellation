package org.tessellation.sdk.cli

import cats.syntax.contravariantSemigroupal._

import org.tessellation.ext.decline.decline.{coercibleArgument, hostArgument, portArgument}
import org.tessellation.schema.peer.{L0Peer, PeerId}

import com.comcast.ip4s.{Host, IpLiteralSyntax, Port}
import com.monovore.decline.Opts

object L0PeerOpts {

  val l0PeerIdOpts: Opts[PeerId] = Opts
    .option[PeerId]("l0-peer-id", help = "L0 peer Id")
    .orElse(Opts.env[PeerId]("CL_L0_PEER_ID", help = "L0 peer Id"))

  val l0PeerHostOpts: Opts[Host] = Opts
    .option[Host]("l0-peer-host", help = "L0 peer HTTP host")
    .orElse(Opts.env[Host]("CL_L0_PEER_HTTP_HOST", help = "L0 peer HTTP host"))

  val l0PeerPortOpts: Opts[Port] = Opts
    .option[Port]("l0-peer-port", help = "L0 peer HTTP port")
    .orElse(Opts.env[Port]("CL_L0_PEER_HTTP_PORT", help = "L0 peer HTTP port"))
    .withDefault(port"9000")

  val opts: Opts[L0Peer] =
    (l0PeerIdOpts, l0PeerHostOpts, l0PeerPortOpts)
      .mapN(L0Peer(_, _, _))
}
