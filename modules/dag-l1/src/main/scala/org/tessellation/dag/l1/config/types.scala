package org.tessellation.dag.l1.config

import org.tessellation.dag.block.config.BlockValidatorConfig
import org.tessellation.dag.l1.domain.consensus.block.config.ConsensusConfig
import org.tessellation.sdk.config.AppEnvironment
import org.tessellation.sdk.config.types.{GossipConfig, HttpConfig}

import ciris.Secret
import eu.timepit.refined.types.string.NonEmptyString

object types {

  case class AppConfig(
    environment: AppEnvironment,
    http: HttpConfig,
    db: DBConfig,
    gossip: GossipConfig,
    blockValidator: BlockValidatorConfig,
    consensus: ConsensusConfig,
    tips: TipsConfig
  )

  case class DBConfig(
    driver: NonEmptyString,
    url: NonEmptyString,
    user: NonEmptyString,
    password: Secret[String]
  )
}
