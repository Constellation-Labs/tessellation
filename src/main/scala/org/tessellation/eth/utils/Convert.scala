package org.tessellation.eth.utils

import org.web3j.utils.Numeric.hexStringToByteArray
import java.nio.charset.StandardCharsets

object Convert {

  def asciiToHex(ascii: String): String = {
    val chars = ascii.toCharArray
    val hex = new StringBuffer()
    for (ch <- chars) {
      hex.append(Integer.toHexString(ch.toInt))
    }
    hex.toString
  }

  def hexToAscii(hex: String): String = {
    val bytes = hexStringToByteArray(hex)
    new String(bytes, StandardCharsets.US_ASCII)
  }
}
