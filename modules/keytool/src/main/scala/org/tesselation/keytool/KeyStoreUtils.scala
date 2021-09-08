package org.tesselation.keytool

import cats.data.EitherT
import cats.effect.{Async, Resource}
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import cats.syntax.functor._
import org.bouncycastle.util.io.pem.{PemObject, PemWriter}
import org.tesselation.keytool.cert.{DistinguishedName, SelfSignedCertificate}

import java.io.{
  BufferedReader,
  BufferedWriter,
  File,
  FileInputStream,
  FileOutputStream,
  InputStreamReader,
  OutputStream,
  OutputStreamWriter
}
import java.nio.charset.Charset
import java.nio.file.FileAlreadyExistsException
import java.security.cert.Certificate
import java.security.{Key, KeyPair, KeyStore, PrivateKey}

object KeyStoreUtils {

  private val storeType: String = "PKCS12"
  private val storeExtension: String = "p12"
  private val singlePasswordKeyStoreSuffix: String = "_v2"
  private val privateKeyHexName: String = "id_ecdsa.hex"

  def readFromFileStream[F[_]: Async, T](
    dataPath: String,
    streamParser: FileInputStream => F[T]
  ): EitherT[F, Throwable, T] =
    reader(dataPath)
      .use(streamParser)
      .attemptT

  def storeWithFileStream[F[_]: Async](
    path: String,
    bufferedWriter: OutputStream => F[Unit]
  ): EitherT[F, Throwable, Unit] =
    writer(path)
      .use(bufferedWriter)
      .attemptT

  def parseFileOfTypeOp[F[_]: Async, T](parser: String => Option[T])(stream: FileInputStream): F[Option[T]] =
    Resource
      .fromAutoCloseable(Async[F].delay {
        new BufferedReader(new InputStreamReader(stream))
      })
      .use(inStream => Option(inStream.readLine()).flatMap(parser).pure[F])

  def writeTypeToFileStream[F[_]: Async, T](serializer: T => String)(obj: T)(stream: OutputStream): F[Unit] =
    Resource
      .fromAutoCloseable(Async[F].delay {
        new BufferedWriter(new OutputStreamWriter(stream))
      })
      .use(
        outStream =>
          Async[F].delay {
            outStream.write(serializer(obj))
          }
      )

  def storeKeyPemDecrypted[F[_]: Async](key: Key)(stream: FileOutputStream): F[Unit] =
    Resource
      .fromAutoCloseable(Async[F].delay {
        new PemWriter(new OutputStreamWriter(stream))
      })
      .use { pemWriter =>
        val pemObj = new PemObject("EC KEY", key.getEncoded)
        Async[F].delay {
          pemWriter.writeObject(pemObj)
        }
      }

  def exportPrivateKeyAsHex[F[_]: Async](
    path: String,
    alias: String,
    storePassword: Array[Char],
    keyPassword: Array[Char]
  ): EitherT[F, Throwable, String] =
    for {
      keyPair <- keyPairFromStorePath(path, alias, storePassword, keyPassword)
      hex = KeyUtils.privateKeyToHex(keyPair.getPrivate)
      _ <- writer(pathDir(path) + privateKeyHexName)
        .use(
          os =>
            Async[F].delay {
              os.write(hex.getBytes(Charset.forName("UTF-8")))
            }
        )
        .attemptT
    } yield hex

  def migrateKeyStoreToSinglePassword[F[_]: Async](
    path: String,
    alias: String,
    storePassword: Array[Char],
    keyPassword: Array[Char]
  ): EitherT[F, Throwable, KeyStore] =
    for {
      keyPair <- keyPairFromStorePath(path, alias, storePassword, keyPassword)
      newKeyStore <- putKeyPairToStorePath(keyPair, withSinglePasswordSuffix(path), alias, storePassword, storePassword)
    } yield newKeyStore

  /**
    * Generates new keypair and puts it to new keyStore at path
    */
  def generateKeyPairToStorePath[F[_]: Async](
    path: String,
    alias: String,
    storePassword: Array[Char],
    keyPassword: Array[Char]
  ): EitherT[F, Throwable, KeyStore] =
    writer(withExtension(path))
      .use(
        stream =>
          for {
            keyStore <- createEmptyKeyStore(storePassword)
            keyPair <- KeyUtils.makeKeyPair().pure[F]
            chain <- generateCertificateChain(keyPair)
            _ <- setKeyEntry(alias, keyPair, keyPassword, chain)(keyStore)
            _ <- store(stream, storePassword)(keyStore)
          } yield keyStore
      )
      .attemptT

  /**
    * Puts existing to new keyStore at path
    */
  def putKeyPairToStorePath[F[_]: Async](
    keyPair: KeyPair,
    path: String,
    alias: String,
    storePassword: Array[Char],
    keyPassword: Array[Char]
  ): EitherT[F, Throwable, KeyStore] =
    writer(withExtension(path))
      .use(
        stream =>
          for {
            keyStore <- createEmptyKeyStore(storePassword)
            chain <- generateCertificateChain(keyPair)
            _ <- setKeyEntry(alias, keyPair, keyPassword, chain)(keyStore)
            _ <- store(stream, storePassword)(keyStore)
          } yield keyStore
      )
      .attemptT

  def keyPairFromStorePath[F[_]: Async](
    path: String,
    alias: String
  ): EitherT[F, Throwable, KeyPair] =
    for {
      env <- loadEnvPasswords
      keyPair <- reader(path)
        .evalMap(unlockKeyStore[F](env.storepass))
        .evalMap(unlockKeyPair[F](alias, env.keypass))
        .use(_.pure[F])
        .attemptT
    } yield keyPair

  def keyPairFromStorePath[F[_]: Async](
    path: String,
    alias: String,
    storepass: Array[Char],
    keypass: Array[Char]
  ): EitherT[F, Throwable, KeyPair] =
    reader(path)
      .evalMap(unlockKeyStore[F](storepass))
      .evalMap(unlockKeyPair[F](alias, keypass))
      .use(_.pure[F])
      .attemptT

  private def reader[F[_]: Async](path: String): Resource[F, FileInputStream] =
    Resource.fromAutoCloseable(Async[F].delay {
      new FileInputStream(path)
    })

  private def writer[F[_]: Async](path: String): Resource[F, FileOutputStream] =
    Resource.fromAutoCloseable(Async[F].delay {
      val file = new File(path)
      if (file.exists()) {
        throw new FileAlreadyExistsException(file.getPath)
      }
      new FileOutputStream(file, false)
    })

  private def generateCertificateChain[F[_]: Async](keyPair: KeyPair): F[Array[Certificate]] =
    Async[F].delay {
      // TODO: Maybe move to config
      val dn = DistinguishedName(
        commonName = "constellationnetwork.io",
        organization = "Constellation Labs"
      )

      val validity = 365 * 1000 // // 1000 years of validity should be enough I guess

      val certificate = SelfSignedCertificate.generate(dn.toString, keyPair, validity, KeyUtils.DefaultSignFunc)
      Array(certificate)
    }

  private def unlockKeyStore[F[_]: Async](
    password: Array[Char]
  )(stream: FileInputStream): F[KeyStore] =
    Async[F].delay { KeyStore.getInstance(storeType) }.flatTap { keyStore =>
      Async[F].delay { keyStore.load(stream, password) }
    }

  private def createEmptyKeyStore[F[_]: Async](password: Array[Char]): F[KeyStore] =
    Async[F].delay { KeyStore.getInstance(storeType) }.flatTap { keyStore =>
      Async[F].delay { keyStore.load(null, password) }
    }

  private def unlockKeyPair[F[_]: Async](alias: String, keyPassword: Array[Char])(keyStore: KeyStore): F[KeyPair] =
    for {
      privateKey <- Async[F].delay { keyStore.getKey(alias, keyPassword).asInstanceOf[PrivateKey] }
      publicKey <- Async[F].delay { keyStore.getCertificate(alias).getPublicKey }
    } yield new KeyPair(publicKey, privateKey)

  private def setKeyEntry[F[_]: Async](
    alias: String,
    keyPair: KeyPair,
    keyPassword: Array[Char],
    chain: Array[Certificate]
  )(keyStore: KeyStore): F[KeyStore] =
    Async[F].delay {
      keyStore.setKeyEntry(alias, keyPair.getPrivate, keyPassword, chain)
      keyStore
    }

  private def store[F[_]: Async](stream: FileOutputStream, storePassword: Array[Char])(
    keyStore: KeyStore
  ): F[KeyStore] = Async[F].delay {
    keyStore.store(stream, storePassword)
    keyStore
  }

  def loadEnvPasswords[F[_]: Async]: EitherT[F, Throwable, Passwords] =
    EitherT.fromEither[F] {
      for {
        storepass <- sys.env.get("CL_STOREPASS").toRight(new RuntimeException("CL_STOREPASS is missing in environment"))
        keypass <- sys.env.get("CL_KEYPASS").toRight(new RuntimeException("CL_KEYPASS is missing in environment"))
      } yield Passwords(storepass = storepass.toCharArray, keypass = keypass.toCharArray)
    }

  private def pathDir(path: String): String = {
    val slashIndex = path.lastIndexOf("/")
    if (slashIndex > 0) {
      path.substring(0, slashIndex + 1)
    } else {
      ""
    }
  }

  private def withSinglePasswordSuffix(path: String): String =
    withExtension(withoutExtension(path) + singlePasswordKeyStoreSuffix)

  private def withExtension(path: String): String =
    if (path.endsWith(storeExtension)) path else s"$path.$storeExtension"

  private def withoutExtension(path: String): String = {
    val dotIndex = path.lastIndexOf(".")
    if (dotIndex > 0) {
      path.substring(0, dotIndex)
    } else {
      path
    }
  }

}
