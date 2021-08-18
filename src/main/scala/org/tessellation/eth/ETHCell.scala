package org.tessellation.eth

import cats.effect.IO
import org.tessellation.eth.hylo.ETHStackHylomorphism
import org.tessellation.schema.{Cell, CellError, StackF, Ω}

case class ETHCell(input: Ω)
    extends Cell[IO, StackF, Ω, Either[CellError, Ω], Ω](input, ETHStackHylomorphism.hyloM, identity) {}
