package org.tesselation.ext.ciris

import org.tesselation.ext.derevo.Derive

import _root_.ciris.ConfigDecoder

class configDecoder extends Derive[Decoder.Id]

object Decoder {
  type Id[A] = ConfigDecoder[String, A]
}
