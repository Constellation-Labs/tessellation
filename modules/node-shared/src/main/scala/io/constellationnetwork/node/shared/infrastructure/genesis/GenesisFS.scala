package io.constellationnetwork.node.shared.infrastructure.genesis

import cats.effect.Async
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._

import io.constellationnetwork.ext.json._
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.node.shared.domain.genesis.types.GenesisCSVAccount
import io.constellationnetwork.node.shared.domain.genesis.{GenesisFS, types}
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.snapshot.FullSnapshot
import io.constellationnetwork.security.signature.Signed

import fs2.data.csv._
import fs2.io.file.{Files, Path}
import fs2.{Stream, text}
import io.circe.{Decoder, Encoder}

object GenesisFS {

  def make[F[_]: Async: JsonSerializer, S <: FullSnapshot[_, _]: Encoder: Decoder]: GenesisFS[F, S] = new GenesisFS[F, S] {
    def write(genesis: Signed[S], identifier: Address, path: Path): F[Unit] = {
      val writeGenesis = Stream
        .evalSeq(genesis.toBinaryF.map(_.toSeq))
        .through(Files.forAsync[F].writeAll(path / "genesis.snapshot"))

      val writeIdentifier = Stream
        .emit(identifier.value.value)
        .through(text.utf8.encode)
        .through(Files.forAsync[F].writeAll(path / "genesis.address"))

      writeGenesis.merge(writeIdentifier).compile.drain
    }

    def loadBalances(path: Path): F[Set[types.GenesisAccount]] =
      Files
        .forAsync[F]
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
      Files
        .forAsync[F]
        .readAll(path)
        .compile
        .toList
        .map(_.toArray)
        .flatMap(_.fromBinaryF[Signed[S]])
  }
}
