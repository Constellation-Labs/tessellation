package org.tesselation

import com.comcast.ip4s.{Host, Port}
import io.circe.{Decoder, Encoder}

package object schema extends OrphanInstances

// instances for types we don't control
trait OrphanInstances {
  implicit val hostDecoder: Decoder[Host] =
    Decoder[String].emap(s => Host.fromString(s).toRight("Invalid host"))

  implicit val hostEncoder: Encoder[Host] =
    Encoder[String].contramap(_.toString)

  implicit val portDecoder: Decoder[Port] = {
    Decoder[Int].emap(p => Port.fromInt(p).toRight("Invalid port"))
  }

  implicit val portEncoder: Encoder[Port] =
    Encoder[Int].contramap(_.value)
}
