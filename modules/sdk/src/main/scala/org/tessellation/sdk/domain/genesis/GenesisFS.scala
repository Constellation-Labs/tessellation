package org.tessellation.sdk.domain.genesis

import org.tessellation.schema.address.Address
import org.tessellation.schema.snapshot.FullSnapshot
import org.tessellation.sdk.domain.genesis.types.GenesisAccount
import org.tessellation.security.signature.Signed

import fs2.io.file.Path

trait GenesisFS[F[_], S <: FullSnapshot[_, _]] {
  def write(genesis: Signed[S], identifier: Address, path: Path): F[Unit]

  def loadBalances(path: Path): F[Set[GenesisAccount]]

  def loadSignedGenesis(path: Path): F[Signed[S]]
}
