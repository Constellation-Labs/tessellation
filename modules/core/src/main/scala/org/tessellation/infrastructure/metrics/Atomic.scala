package org.tessellation.infrastructure.metrics

import java.util.concurrent.atomic.{AtomicInteger => JAtomicInteger, AtomicLong => JAtomicLong}

import cats.effect.Async

import com.google.common.util.concurrent.{AtomicDouble => JAtomicDouble}

sealed trait Atomic[F[_], A] extends Number {
  def setAsync(value: A)(implicit N: Numeric[A]): F[Unit]
}
private final case class AtomicInt[F[_]: Async](value: Int) extends JAtomicInteger(value) with Atomic[F, Int] {
  def setAsync(value: Int)(implicit N: Numeric[Int]): F[Unit] = Async[F].delay(super.set(value))
}

private final case class AtomicDouble[F[_]: Async](value: Double) extends JAtomicDouble(value) with Atomic[F, Double] {
  def setAsync(value: Double)(implicit N: Numeric[Double]): F[Unit] = Async[F].delay(super.set(value))
}

private final case class AtomicLong[F[_]: Async](value: Long) extends JAtomicLong(value) with Atomic[F, Long] {
  def setAsync(value: Long)(implicit N: Numeric[Long]): F[Unit] = Async[F].delay(super.set(value))
}

trait AtomicConversion[F[_], A] {
  def convert(value: A): Atomic[F, A]
}

object AtomicConversion {
  implicit def intToAtomic[F[_]: Async]: AtomicConversion[F, Int] = AtomicInt.apply
  implicit def doubleToAtomic[F[_]: Async]: AtomicConversion[F, Double] = AtomicDouble.apply
  implicit def longToAtomic[F[_]: Async]: AtomicConversion[F, Long] = AtomicLong.apply
}
