package io.constellationnetwork.schema

import java.util.UUID

import io.constellationnetwork.env.AppEnvironment
import io.constellationnetwork.schema.cluster.{ClusterId, ClusterSessionToken, SessionToken}
import io.constellationnetwork.schema.generation.Generation
import io.constellationnetwork.schema.node.NodeState
import io.constellationnetwork.schema.peer.{PeerId, RegistrationRequest}
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex

import com.comcast.ip4s.IpLiteralSyntax
import eu.timepit.refined.auto._
import io.circe.parser.decode
import io.circe.syntax._
import weaver.SimpleIOSuite

object RegistrationRequestCompatibilitySuite extends SimpleIOSuite {

  private val request = RegistrationRequest(
    id = PeerId(Hex("aa" * 64)),
    ip = host"127.0.0.1",
    publicPort = port"9000",
    p2pPort = port"9001",
    session = SessionToken(Generation(1L)),
    clusterSession = ClusterSessionToken(Generation(2L)),
    clusterId = ClusterId(UUID.fromString("6d7f1d6a-213a-4148-9d45-d7200f555ecf")),
    state = NodeState.Ready,
    seedlist = Hash("11" * 32),
    version = Hash("22" * 32),
    metagraphVersion = Hash("33" * 32),
    jar = Hash("44" * 32),
    environment = AppEnvironment.Integrationnet,
    allowanceList = Hash("55" * 32),
    metagraphId = None,
    consensusConfigHash = None
  )

  pureTest("a legacy registration request missing consensusConfigHash still decodes as None") {
    val legacyJson = request.asJson.mapObject(_.remove("consensusConfigHash")).noSpaces

    decode[RegistrationRequest](legacyJson).fold(
      error => failure(error.getMessage),
      decoded => expect.same(request.id, decoded.id) && expect(decoded.consensusConfigHash.isEmpty)
    )
  }
}
