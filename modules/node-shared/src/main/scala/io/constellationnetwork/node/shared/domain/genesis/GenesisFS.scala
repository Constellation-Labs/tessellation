package io.constellationnetwork.node.shared.domain.genesis

import io.constellationnetwork.node.shared.domain.genesis.types.GenesisAccount
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.snapshot.FullSnapshot
import io.constellationnetwork.security.signature.Signed

import fs2.io.file.Path

trait GenesisFS[F[_], S <: FullSnapshot[_, _]] {
  def write(genesis: Signed[S], identifier: Address, path: Path): F[Unit]

  def loadBalances(path: Path): F[Set[GenesisAccount]]

  def loadSignedGenesis(path: Path): F[Signed[S]]
}
