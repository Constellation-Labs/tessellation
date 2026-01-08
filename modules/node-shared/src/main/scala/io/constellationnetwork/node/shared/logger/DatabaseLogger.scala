package io.constellationnetwork.node.shared.logger

import io.circe.Encoder

trait DatabaseLogger[F[_]] {
  def createLogsTable(): F[Unit]

  def log[T: Encoder](logType: String, data: T): F[Unit]

  def info[T: Encoder](data: T): F[Unit]
  def error[T: Encoder](data: T): F[Unit]
  def warn[T: Encoder](data: T): F[Unit]
  def debug[T: Encoder](data: T): F[Unit]

  def info(message: String): F[Unit]
  def error(message: String): F[Unit]
  def warn(message: String): F[Unit]
  def debug(message: String): F[Unit]
}
