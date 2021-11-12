package org.tessellation.ext.ciris

import org.tessellation.ext.derevo.Derive

import _root_.ciris.ConfigDecoder

class configDecoder extends Derive[Decoder.Id]

object Decoder {
  type Id[A] = ConfigDecoder[String, A]
}
