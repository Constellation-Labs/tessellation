package org.tesselation.ext.decline

import cats.Show
import cats.data.ValidatedNel
import cats.syntax.validated._

import _root_.ciris.Secret
import com.monovore.decline.Argument
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

}
