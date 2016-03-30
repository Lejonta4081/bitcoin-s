package org.scalacoin.marshallers.transaction

import org.scalacoin.currency.{CurrencyUnits, Satoshis}
import org.scalacoin.marshallers.RawBitcoinSerializer
import org.scalacoin.marshallers.script.{RawScriptPubKeyParser, ScriptParser}
import org.scalacoin.protocol.CompactSizeUInt
import org.scalacoin.protocol.transaction.{TransactionOutputFactory, TransactionOutputImpl, TransactionOutput}
import org.scalacoin.util.{BitcoinSUtil}
import org.slf4j.LoggerFactory

import scala.annotation.tailrec

/**
 * Created by chris on 1/11/16.
 * https://bitcoin.org/en/developer-reference#txout
 */
trait RawTransactionOutputParser extends RawBitcoinSerializer[Seq[TransactionOutput]] with ScriptParser {

  private lazy val logger = LoggerFactory.getLogger(this.getClass().toString())
  override def read(bytes : List[Byte]) : Seq[TransactionOutput] = {
    val numOutputs = bytes.head.toInt
    @tailrec
    def loop(bytes : List[Byte], accum : List[TransactionOutput], outputsLeftToParse : Int) : List[TransactionOutput] = {
      if (outputsLeftToParse > 0) {
        //TODO: this needs to be refactored to, need to create a function that returns a single TransactionOutput
        //then call that function multiple times to get a Seq[TransactionOutput]
        val satoshisHex = BitcoinSUtil.encodeHex(bytes.take(8).reverse)
        logger.debug("Satoshi hex: " + satoshisHex)
        val satoshis = Satoshis(java.lang.Long.parseLong(satoshisHex, 16))
        //it doesn't include itself towards the size, thats why it is incremented by one
        val firstScriptPubKeyByte = 8
        val scriptCompactSizeUIntSize : Int = BitcoinSUtil.parseCompactSizeUIntSize(bytes(firstScriptPubKeyByte)).toInt
        logger.debug("VarInt hex: " + BitcoinSUtil.encodeHex(bytes.slice(firstScriptPubKeyByte,firstScriptPubKeyByte + scriptCompactSizeUIntSize)))
        val scriptSigCompactSizeUInt : CompactSizeUInt =
          BitcoinSUtil.parseCompactSizeUInt(bytes.slice(firstScriptPubKeyByte,firstScriptPubKeyByte + scriptCompactSizeUIntSize))

        val scriptPubKeyBytes = bytes.slice(firstScriptPubKeyByte + scriptCompactSizeUIntSize,
          firstScriptPubKeyByte + scriptCompactSizeUIntSize + scriptSigCompactSizeUInt.num.toInt)
        val scriptPubKey = RawScriptPubKeyParser.read(scriptPubKeyBytes)
        val parsedOutput = TransactionOutputFactory.factory(satoshis,scriptPubKey)
        val newAccum =  parsedOutput:: accum
        val bytesToBeParsed = bytes.slice(parsedOutput.size, bytes.size)
        val outputsLeft = outputsLeftToParse-1
        logger.debug("Parsed output: " + parsedOutput)
        logger.debug("Outputs left to parse: " + outputsLeft)
        loop(bytesToBeParsed, newAccum, outputsLeft)
      } else accum
    }
    loop(bytes.tail,List(),numOutputs).reverse
  }

  override def write(outputs : Seq[TransactionOutput]) : String = {
    val numOutputs = BitcoinSUtil.encodeHex(outputs.size.toByte)
    val serializedOutputs : Seq[String] = for {
      output <- outputs
    } yield write(output)
    numOutputs + serializedOutputs.mkString
  }


  /**
   * Writes a single transaction output
   * @param output
   * @return
   */
  def write(output : TransactionOutput) : String = {
    val satoshis = CurrencyUnits.toSatoshis(output.value)
    val compactSizeUIntHex = output.scriptPubKeyCompactSizeUInt.hex
    //TODO: Clean this up, this is very confusing. If you remove this .reverse method calls you can see the unit test failing
    val satoshisHexWithoutPadding : String = BitcoinSUtil.encodeHex(satoshis)
    val satoshisHex = addPadding(16,satoshisHexWithoutPadding)
    satoshisHex + compactSizeUIntHex + output.scriptPubKey.hex
  }
}


object RawTransactionOutputParser extends RawTransactionOutputParser