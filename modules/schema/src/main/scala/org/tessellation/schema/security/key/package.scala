package org.tessellation.schema.security

package object key {
  val ECDSA = "ECDsA"

  val secp256k = "secp256k1"

  val PublicKeyHexPrefix: String = "3056301006072a8648ce3d020106052b8104000a03420004"

  val PrivateKeyHexPrefix: String = "30818d020100301006072a8648ce3d020106052b8104000a047630740201010420"

  val secp256kHexIdentifier: String = "a00706052b8104000aa144034200"
}
