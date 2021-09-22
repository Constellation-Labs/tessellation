package org.tesselation.crypto

import org.tesselation.crypto.hash.Hash
import org.tesselation.ext.crypto._
import org.tesselation.kryo.KryoSerializer

trait Signed[A] {
  val value: A
  val hash: Hash
}

object Signed {
  def apply[A: Signed](): Signed[A] = implicitly

  def forKryo[F[_]: KryoSerializer, A <: AnyRef](data: A): Either[Throwable, Signed[A]] =
    data.hash.map { h =>
      new Signed[A] {
        val value: A = data
        val hash: Hash = h
      }
    }

}
