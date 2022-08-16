package org.tessellation.rosetta.server

import org.bouncycastle.jce.{ECNamedCurveTable, ECPointUtil}
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.jce.spec.ECNamedCurveSpec

import java.security.KeyFactory
import java.security.interfaces.ECPublicKey
import java.security.spec.ECPublicKeySpec

object Util {

  // This works from:
  //  https://stackoverflow.com/questions/26159149/how-can-i-get-a-publickey-object-from-ec-public-key-bytes
  def getPublicKeyFromBytes(pubKey: Array[Byte]) = {
    val spec = ECNamedCurveTable.getParameterSpec("secp256k1")
    val kf = KeyFactory.getInstance("ECDSA", new BouncyCastleProvider)
    val params = new ECNamedCurveSpec("secp256k1", spec.getCurve, spec.getG, spec.getN)
    val point = ECPointUtil.decodePoint(params.getCurve, pubKey)
    val pubKeySpec = new ECPublicKeySpec(point, params)
    val pk = kf.generatePublic(pubKeySpec).asInstanceOf[ECPublicKey]
    pk
  }

  def reduceListEither[L, R](eitherList: List[Either[L, List[R]]]): Either[L, List[R]] =
    eitherList.reduce { (l: Either[L, List[R]], r: Either[L, List[R]]) =>
      l match {
        case x @ Left(_) => x
        case Right(y) =>
          r match {
            case xx @ Left(_) => xx
            case Right(yy)    => Right(y ++ yy)
          }
      }
    }
}
