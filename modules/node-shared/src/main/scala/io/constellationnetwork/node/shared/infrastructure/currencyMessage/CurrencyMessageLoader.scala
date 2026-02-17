package io.constellationnetwork.node.shared.infrastructure.currencyMessage

import java.nio.file.NoSuchFileException

import cats.effect.Async
import cats.syntax.all._

import scala.util.control.NoStackTrace

import io.constellationnetwork.currency.cli.MetagraphOwnerMessageOpts.MetagraphOwnerMessagePath
import io.constellationnetwork.schema.currencyMessage.CurrencyMessage
import io.constellationnetwork.security.signature.Signed

import fs2.io.file.Files
import fs2.text
import io.circe.parser.decode
import io.estatico.newtype.ops._

trait CurrencyMessageLoader[F[_]] {
  def load(path: MetagraphOwnerMessagePath): F[Signed[CurrencyMessage]]
}

object CurrencyMessageLoader {

  case class CurrencyMessageFileNotFound(path: String) extends NoStackTrace {
    override def getMessage: String = s"Currency message file not found at $path"
  }

  def make[F[_]: Async]: CurrencyMessageLoader[F] =
    (path: MetagraphOwnerMessagePath) =>
      Files
        .forAsync[F]
        .readAll(path.coerce)
        .through(text.utf8.decode)
        .compile
        .string
        .flatMap { body =>
          decode[Signed[CurrencyMessage]](body)
            .leftMap(err => new RuntimeException(s"Failed to parse owner message JSON: ${err.getMessage}"))
            .liftTo[F]
        }
        .adaptError {
          case _: NoSuchFileException => CurrencyMessageFileNotFound(path.coerce.toString)
        }
}
