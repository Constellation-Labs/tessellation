package io.constellationnetwork.env

import java.io.File
import java.nio.file.Files

import io.constellationnetwork.security.hash.Hash

object JarSignature {

  lazy val hash: Hash = jarHash

  private def jarHash = {
    val jarFile = new File(JarSignature.getClass.getProtectionDomain.getCodeSource.getLocation.toURI)
    if (jarFile.isFile) {
      val buffer = Files.readAllBytes(jarFile.toPath)
      Hash.fromBytes(buffer)
    } else Hash.empty
  }

}
