package io.constellationnetwork.currency.cli

import cats.syntax.contravariantSemigroupal._

import io.constellationnetwork.ext.decline.decline.{coercibleArgument, hostArgument, portArgument}
import io.constellationnetwork.schema.peer.{L0Peer, PeerId}

import com.comcast.ip4s.{Host, IpLiteralSyntax, Port}
import com.monovore.decline.Opts

object GlobalL0PeerOpts {

  val globalL0PeerIdOpts: Opts[PeerId] = Opts
    .option[PeerId]("global-l0-peer-id", help = "Global L0 peer Id")
    .orElse(Opts.env[PeerId]("CL_GLOBAL_L0_PEER_ID", help = "Global L0 peer Id"))

  val globalL0PeerHostOpts: Opts[Host] = Opts
    .option[Host]("global-l0-peer-host", help = "Global L0 peer HTTP host")
    .orElse(Opts.env[Host]("CL_GLOBAL_L0_PEER_HTTP_HOST", help = "Global L0 peer HTTP host"))

  val globalL0PeerPortOpts: Opts[Port] = Opts
    .option[Port]("global-l0-peer-port", help = "Global L0 peer HTTP port")
    .orElse(Opts.env[Port]("CL_GLOBAL_L0_PEER_HTTP_PORT", help = "Global L0 peer HTTP port"))
    .withDefault(port"9000")

  val opts: Opts[L0Peer] =
    (globalL0PeerIdOpts, globalL0PeerHostOpts, globalL0PeerPortOpts)
      .mapN(L0Peer(_, _, _))
}
