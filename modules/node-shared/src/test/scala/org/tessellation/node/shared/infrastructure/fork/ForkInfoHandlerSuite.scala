package org.tessellation.node.shared.infrastructure.fork

import org.tessellation.node.shared.config.types.ForkInfoStorageConfig
import org.tessellation.node.shared.domain.fork.{ForkInfo, ForkInfoEntries, ForkInfoMap}
import org.tessellation.node.shared.infrastructure.fork.generators.genStoredForkInfoEntry
import org.tessellation.schema.generation.Generation
import org.tessellation.schema.generators.peerIdGen
import org.tessellation.schema.gossip._

import eu.timepit.refined.auto._
import io.circe.syntax.EncoderOps
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

object ForkInfoHandlerSuite extends SimpleIOSuite with Checkers {

  test("received gossip gets added to the storage") {
    val gen = for {
      forkInfo <- genStoredForkInfoEntry.map(_._2)
      peerId1 <- peerIdGen
      peerId2 <- peerIdGen
    } yield (forkInfo, peerId1, peerId2)

    forall(gen) {
      case (forkInfo, peerId1, peerId2) =>
        for {
          storage <- ForkInfoStorage.make(ForkInfoStorageConfig(10))
          handler = ForkInfoHandler.make(storage)
          rawRumor = PeerRumorRaw(
            peerId1,
            Ordinal(Generation.MinValue, Counter.MinValue),
            forkInfo.asJson,
            ContentType.of[ForkInfo]
          )

          _ <- handler
            .run((rawRumor, peerId2))
            .value
          expected = ForkInfoMap(
            Map(
              peerId1 -> ForkInfoEntries(10)
                .add(forkInfo)
            )
          )
          actual <- storage.getForkInfo
        } yield expect.eql(expected, actual)
    }
  }

}
