package org.bitcoins.crypto

import scodec.bits.ByteVector

/** Created by chris on 2/26/16.
  */
case class ECDigitalSignature(bytes: ByteVector) extends DigitalSignature {
  require(r.signum == 1 || r.signum == 0, s"r must not be negative, got $r")
  require(s.signum == 1 || s.signum == 0, s"s must not be negative, got $s")

  override def equals(other: Any): Boolean =
    other match {
      case sig: ECDigitalSignature => bytes == sig.bytes
      case _                       => other.equals(this)
    }

  def isEmpty: Boolean = bytes.isEmpty

  override def toString: String = "ECDigitalSignature(" + hex + ")"

  /** Checks if this signature is encoded to DER correctly
    * https://crypto.stackexchange.com/questions/1795/how-can-i-convert-a-der-ecdsa-signature-to-asn-1
    * @return
    *   boolean representing if the signature is a valid
    */
  def isDEREncoded: Boolean = CryptoUtil.isDEREncoded(this)

  /** Checks if the signature is strictly der encoded as per BIP66
    * [[https://github.com/bitcoin/bips/blob/master/bip-0066.mediawiki]]
    */
  def isStrictEncoded: Boolean = CryptoUtil.isValidSignatureEncoding(this)

  /** Decodes the digital signature into it's r and s points throws an exception
    * if the given sequence of bytes is not a DER encoded signature
    * @return
    *   the (r,s) values for the elliptic curve digital signature
    */
  lazy val decodeSignature: (BigInt, BigInt) =
    CryptoUtil.decodeSignature(this)

  /** Represents the r value found in a elliptic curve digital signature */
  def r: BigInt = {
    decodeSignature._1
  }

  /** If we need to do serialization with the r value, you should use this. It
    * will pad the byte vector so we have exactly 32 bytes
    * @return
    */
  def rBytes: ByteVector = {
    val bytes = r.bigInteger.toByteArray.takeRight(32)
    val padded = ByteVector(bytes).padLeft(32)
    padded
  }

  /** If we need to do serialization with the s value, you should use this. It
    * will pad the byte vector so we have exactly 32 bytes
    * @return
    */
  def s: BigInt = {
    decodeSignature._2
  }

  def sBytes: ByteVector = {
    val bytes = s.bigInteger.toByteArray.takeRight(32)
    val padded = ByteVector(bytes).padLeft(32)
    padded
  }

  /** Creates a ByteVector with only the 32byte r value and 32 byte s value in
    * the vector
    */
  def toRawRS: ByteVector = {
    rBytes ++ sBytes
  }

  def hashTypeOpt: Option[HashType] = {
    val trailingBytes = bytes.drop(ECDigitalSignature.fromRS(r, s).bytes.length)

    if (trailingBytes.nonEmpty && trailingBytes.length <= 4) {
      Some(HashType.fromBytes(trailingBytes))
    } else {
      None
    }
  }

  def appendHashType(hashType: HashType): ECDigitalSignature = {
    require(this.hashTypeOpt.isEmpty,
            "Cannot append HashType to signature which already has HashType")

    val bytesWithHashType = bytes.:+(hashType.byte)
    ECDigitalSignature.fromBytes(bytesWithHashType)
  }
}

object ECDigitalSignature extends Factory[ECDigitalSignature] {

  val empty: ECDigitalSignature = {
    val bytes: ByteVector = ByteVector.empty
    new ECDigitalSignature(bytes) {
      override def r: BigInt = java.math.BigInteger.valueOf(0)
      override def s: BigInt = r
    }
  }

  /** The point of this case object is to help with fee estimation an average
    * [[ECDigitalSignature]] is 72 bytes in size Technically this number can
    * vary, 72 bytes is the most likely though according to
    * https://en.bitcoin.it/wiki/Elliptic_Curve_Digital_Signature_Algorithm
    */
  val dummy: ECDigitalSignature = {
    val bytes: ByteVector = ByteVector(Array.fill(72)(0.toByte))
    new ECDigitalSignature(bytes) {
      override def r: BigInt = BigInt(0)
      override def s: BigInt = r
    }
  }

  /** The point of this case object is to help with fee estimation when using
    * low r signing. Technically this number can vary, 71 bytes is the most
    * likely when using low r signing
    */
  val dummyLowR: ECDigitalSignature = {
    val bytes: ByteVector = ByteVector(Array.fill(71)(0.toByte))
    new ECDigitalSignature(bytes) {
      override def r: BigInt = empty.r
      override def s: BigInt = r
    }
  }

  override def fromBytes(bytes: ByteVector): ECDigitalSignature = {
    // this represents the empty signature
    if (bytes.size == 1 && bytes.head == 0x0) empty
    else if (bytes.size == 0) {
      empty
    } else if (bytes == dummy.bytes)
      dummy
    else if (bytes == dummyLowR.bytes)
      dummyLowR
    else {
      // make sure the signature follows BIP62's low-s value
      // https://github.com/bitcoin/bips/blob/master/bip-0062.mediawiki#Low_S_values_in_signatures
      // bitcoinj implementation
      // https://github.com/bitcoinj/bitcoinj/blob/1e66b9a8e38d9ad425507bf5f34d64c5d3d23bb8/core/src/main/java/org/bitcoinj/core/ECKey.java#L551
      new ECDigitalSignature(bytes)
    }
  }

  /** Reads a (DER encoded) ECDigitalSignature from the front of a ByteVector
    * This method is also useful if you want to parse a ecdsa digital signature
    * but remove the [[HashType]] that the bitcoin protocol appends to the end
    * of a signature
    */
  def fromFrontOfBytes(bytes: ByteVector): ECDigitalSignature = {
    val sigWithExtra = fromBytes(bytes)
    val sig = fromRS(sigWithExtra.r, sigWithExtra.s)

    require(bytes.startsWith(sig.bytes),
            s"Received weirdly encoded signature at beginning of $bytes")

    sig
  }

  /** Reads a (DER encoded with sighash) ECDigitalSignature from the front of a
    * ByteVector
    */
  def fromFrontOfBytesWithSigHash(bytes: ByteVector): ECDigitalSignature = {
    val sigWithoutSigHash = fromFrontOfBytes(bytes)
    ECDigitalSignature(
      sigWithoutSigHash.bytes :+ bytes.drop(sigWithoutSigHash.byteSize).head)
  }

  def apply(r: BigInt, s: BigInt): ECDigitalSignature = fromRS(r, s)

  def apply(r: BigInt, s: BigInt, hashType: HashType): ECDigitalSignature =
    fromRS(r, s, hashType)

  /** Takes in the r and s component of a digital signature and gives back a
    * ECDigitalSignature object The ECDigitalSignature object complies with
    * strict der encoding as per BIP62 note: That the hash type for the
    * signature CANNOT be added to the digital signature
    *
    * @param r
    *   the r component of the digital signature
    * @param s
    *   the s component of the digital signature
    * @return
    */
  def fromRS(r: BigInt, s: BigInt): ECDigitalSignature = {
    val rsSize = r.toByteArray.length + s.toByteArray.length
    val totalSize = 4 + rsSize
    val bytes: ByteVector = {
      ByteVector(
        Array(0x30.toByte,
              totalSize.toByte,
              0x2.toByte,
              r.toByteArray.length.toByte))
        .++(ByteVector(r.toByteArray))
        .++(ByteVector(Array(0x2.toByte, s.toByteArray.length.toByte)))
        .++(ByteVector(s.toByteArray))
    }

    fromBytes(bytes)
  }

  /** Takes in the r and s component of a digital signature along with a
    * HashType and gives back a ECDigitalSignature object The ECDigitalSignature
    * object complies with strict der encoding as per BIP62 note: That the hash
    * type for the signature CANNOT be added to the digital signature
    *
    * @param r
    *   the r component of the digital signature
    * @param s
    *   the s component of the digital signature
    * @param hashType
    *   The HashType byte to append
    * @return
    */
  def fromRS(r: BigInt, s: BigInt, hashType: HashType): ECDigitalSignature = {
    fromRS(r, s).appendHashType(hashType)
  }

  /** Reads a 64 byte bytevector and assumes the first 32 bytes in the R value,
    * the second 32 is the value
    */
  def fromRS(byteVector: ByteVector): ECDigitalSignature = {
    require(
      byteVector.length == 64,
      s"Incorrect size for reading a ECDigital signature from a bytevec, got ${byteVector.length}")
    val r = BigInt(1, byteVector.take(32).toArray)
    val s = BigInt(1, byteVector.takeRight(32).toArray)
    fromRS(r, s)
  }

  /** Reads a 64 byte bytevector and assumes the first 32 bytes in the R value,
    * the second 32 is the value
    */
  def fromRS(byteVector: ByteVector, hashType: HashType): ECDigitalSignature = {
    fromRS(byteVector).appendHashType(hashType)
  }

  /** Reads a 64 byte bytevector and assumes the first 32 bytes in the R value,
    * the second 32 is the value
    */
  def fromRS(hex: String): ECDigitalSignature = {
    val bytes = CryptoBytesUtil.decodeHex(hex)
    fromRS(bytes)
  }

  /** Reads a 64 byte bytevector and assumes the first 32 bytes in the R value,
    * the second 32 is the value
    */
  def fromRS(hex: String, hashType: HashType): ECDigitalSignature = {
    fromRS(hex).appendHashType(hashType)
  }

  /** Minimally encoded zero signature This will NOT be 64 bytes in length, it
    * will be much less due to the DER encoding
    */
  val minimalEncodedZeroSig: ECDigitalSignature = fromRS(0, 0)
}
