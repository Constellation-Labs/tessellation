package org.tesselation.keytool.config

import ciris.Secret
import org.tesselation.keytool.cert.DistinguishedName

object types {

  case class AppConfig(
    storepass: Secret[String],
    keypass: Secret[String],
    distinguishedName: DistinguishedName = DistinguishedName(
      commonName = "constellationnetwork.io",
      organization = "Constellation Labs"
    ),
    certificateValidity: Int = 365 * 1000 // 1000 years of validity should be enough I guess
  )

}
