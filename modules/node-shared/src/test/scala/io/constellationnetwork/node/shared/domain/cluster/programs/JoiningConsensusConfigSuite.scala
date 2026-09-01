package io.constellationnetwork.node.shared.domain.cluster.programs

import cats.syntax.option._

import io.constellationnetwork.security.hash.Hash

import weaver.SimpleIOSuite

object JoiningConsensusConfigSuite extends SimpleIOSuite {

  private val hashA = Hash.fromBytes("config-a".getBytes("UTF-8"))
  private val hashB = Hash.fromBytes("config-b".getBytes("UTF-8"))

  pureTest("equal advertised consensus config hashes are compatible") {
    expect(Joining.consensusConfigHashesMatch(hashA.some, hashA.some))
  }

  pureTest("different advertised consensus config hashes are rejected") {
    expect(!Joining.consensusConfigHashesMatch(hashA.some, hashB.some))
  }

  pureTest("an advertised hash cannot join a peer that omits it") {
    expect(!Joining.consensusConfigHashesMatch(hashA.some, none))
  }

  pureTest("a peer omitting the hash cannot join one that advertises it") {
    expect(!Joining.consensusConfigHashesMatch(none, hashA.some))
  }

  pureTest("non-L0 peers that both omit a consensus config hash remain compatible") {
    expect(Joining.consensusConfigHashesMatch(none, none))
  }
}
