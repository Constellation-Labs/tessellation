package org.example

import java.io.FileOutputStream
import java.nio.file.Path

import cats.effect.{ExitCode, IO, Resource}
import cats.syntax.contravariantSemigroupal._
import cats.syntax.either._
import cats.syntax.foldable._
import cats.syntax.traverse._
import cats.syntax.validated._
import com.monovore.decline.Opts
import com.monovore.decline.effect.CommandIOApp
import org.example.SimpleSnapshotPublisherDef.kryoRegistrar
import org.tessellation.ext.crypto._
import org.tessellation.ext.kryo._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.security.hash.Hash

object GenerateInputApp
    extends CommandIOApp(
      "generateInput",
      s"Generates a series of serialized ${classOf[EmitSimpleSnapshot].getName}"
    ) {

  final case class GenerateInputArgs(targetDir: Path, count: Int, initHash: Hash)

  val pathOpts: Opts[Path] =
    Opts.argument[Path](metavar = "targetDir").mapValidated { path =>
      if (path.toFile.isDirectory)
        path.validNel[String]
      else
        s"Path does not exist or is not a directory: ${path.toString}".invalidNel[Path]
    }

  val countOpts: Opts[Int] =
    Opts.argument[Int](metavar = "count")

  val initHashOpt: Opts[Hash] =
    Opts
      .option[String](
        "init-hash",
        s"Value of `lastSnapshotHash` property of the initial ${classOf[SimpleSnapshot].getName}, defaults to empty string."
      )
      .withDefault("")
      .map(Hash(_))

  override def main: Opts[IO[ExitCode]] =
    (pathOpts, countOpts, initHashOpt).mapN(GenerateInputArgs).map { args =>
      KryoSerializer
        .forAsync[IO](kryoRegistrar.view.mapValues(_.value).toMap)
        .use { implicit kryo =>
          val initAcc = (SimpleSnapshot(args.initHash), List.empty[(Int, EmitSimpleSnapshot)])
          List
            .range(0, args.count)
            .foldM(initAcc) { (acc, i) =>
              acc match {
                case (s, l) =>
                  s.hash
                    .liftTo[IO]
                    .flatMap { h =>
                      val input = EmitSimpleSnapshot(h)
                      IO.println(input) >>
                        IO.pure((SimpleSnapshot(h), (i, input) :: l))
                    }
              }
            }
            .map(_._2)
            .flatMap {
              _.traverse {
                case (i, s) =>
                  Resource.fromAutoCloseable(IO(new FileOutputStream(args.targetDir.resolve(s"$i.bin").toFile))).use {
                    os =>
                      s.toBinary.liftTo[IO].flatMap { bytes =>
                        IO.blocking(os.write(bytes))
                      }
                  }
              }.void
            }
        }
        .as(ExitCode.Success)
    }
}
