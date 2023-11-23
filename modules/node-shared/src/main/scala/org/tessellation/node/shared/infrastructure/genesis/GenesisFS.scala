package org.tessellation.node.shared.infrastructure.genesis

import cats.effect.Async
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.ext.kryo._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.node.shared.domain.genesis.types.GenesisCSVAccount
import org.tessellation.node.shared.domain.genesis.{GenesisFS, types}
import org.tessellation.schema.address.Address
import org.tessellation.schema.snapshot.FullSnapshot
import org.tessellation.security.signature.Signed

import fs2.data.csv._
import fs2.io.file.{Files, Path}
import fs2.{Stream, text}

object GenesisFS {

  def make[F[_]: Async: KryoSerializer, S <: FullSnapshot[_, _]]: GenesisFS[F, S] = new GenesisFS[F, S] {
    def write(genesis: Signed[S], identifier: Address, path: Path): F[Unit] = {
      val writeGenesis = Stream
        .evalSeq(genesis.toBinaryF.map(_.toSeq))
        .through(Files[F].writeAll(path / "genesis.snapshot"))

      val writeIdentifier = Stream
        .emit(identifier.value.value)
        .through(text.utf8.encode)
        .through(Files[F].writeAll(path / "genesis.address"))

      writeGenesis.merge(writeIdentifier).compile.drain
    }

    def loadBalances(path: Path): F[Set[types.GenesisAccount]] =
      Files[F]
        .readAll(path)
        .through(text.utf8.decode)
        .through(
          decodeWithoutHeaders[GenesisCSVAccount]()
        )
        .map(_.toGenesisAccount)
        .map(_.leftMap(new RuntimeException(_)))
        .rethrow
        .compile
        .toList
        .map(_.toSet)

    def loadSignedGenesis(path: Path): F[Signed[S]] =
      Files[F]
        .readAll(path)
        .compile
        .toList
        .map(_.toArray)
        .flatMap(_.fromBinaryF[Signed[S]])
  }
}
