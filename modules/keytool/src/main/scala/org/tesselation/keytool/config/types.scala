package org.tesselation.keytool.config

import org.tesselation.keytool.cert.DistinguishedName

import ciris.Secret

object types {

  case class AppConfig(
    keystore: String,
    storepass: Secret[String],
    keypass: Secret[String],
    keyalias: Secret[String],
    distinguishedName: DistinguishedName = DistinguishedName(
      commonName = "constellationnetwork.io",
      organization = "Constellation Labs"
    ),
    certificateValidity: Int = 365 * 1000 // 1000 years of validity should be enough I guess
  )

}
