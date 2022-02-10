package org.tessellation.ext.decline

import cats.Show
import cats.data.ValidatedNel
import cats.syntax.validated._

import _root_.ciris.Secret
import com.comcast.ip4s.{Host, Port}
import com.monovore.decline.Argument
import enumeratum.{Enum, EnumEntry}
import fs2.io.file.Path
import io.estatico.newtype.Coercible

package object decline {
  implicit def coercibleArgument[R, N](implicit ev: Coercible[Argument[R], Argument[N]], R: Argument[R]): Argument[N] =
    ev(R)

  implicit def secretArgument[A: Show](implicit A: Argument[A]): Argument[Secret[A]] = new Argument[Secret[A]] {
    override def read(string: String): ValidatedNel[String, Secret[A]] = A.read(string).map(Secret(_))
    override def defaultMetavar: String = A.defaultMetavar
  }

  implicit val pathArgument: Argument[Path] = new Argument[Path] {
    override def read(string: String): ValidatedNel[String, Path] = Path(string).validNel
    override def defaultMetavar: String = "path"
  }

  implicit val hostArgument: Argument[Host] = new Argument[Host] {
    override def read(string: String): ValidatedNel[String, Host] = Host.fromString(string) match {
      case None       => "Provided host is invalid".invalidNel[Host]
      case Some(host) => host.validNel
    }

    override def defaultMetavar: String = "host"
  }

  implicit val portArgument: Argument[Port] = new Argument[Port] {
    override def read(string: String): ValidatedNel[String, Port] = Port.fromString(string) match {
      case None       => "Provided port is invalid".invalidNel[Port]
      case Some(port) => port.validNel
    }
    override def defaultMetavar: String = "port"
  }

  private def invalidChoice(missing: String, choices: Seq[String]): String =
    choices match {
      case Seq()      => s"Invalid value $missing"
      case Seq(value) => s"Invalid value $missing, expected: $value"
      case many       => s"Invalid value $missing, expected ${many.init.mkString(", ")} or ${many.last}"
    }

  implicit def enumEntryArgument[A <: EnumEntry](implicit `enum`: Enum[A]): Argument[A] = new Argument[A] {
    override def read(string: String): ValidatedNel[String, A] =
      `enum`.withNameOption(string) match {
        case Some(v) => v.validNel
        case None    => invalidChoice(string, `enum`.values.map(_.entryName)).invalidNel
      }
    override def defaultMetavar: String = "value"
  }

}
