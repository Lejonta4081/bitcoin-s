package org.bitcoins.wallet

import org.bitcoins.core.api.wallet.SyncHeightDescriptor
import org.bitcoins.core.currency._
import org.bitcoins.core.gcs.FilterType
import org.bitcoins.core.hd.LegacyHDPath
import org.bitcoins.core.number.{Int32, UInt32}
import org.bitcoins.core.protocol.script.EmptyScriptSignature
import org.bitcoins.core.protocol.transaction._
import org.bitcoins.core.psbt.PSBT
import org.bitcoins.core.util.FutureUtil
import org.bitcoins.core.wallet.utxo.TxoState
import org.bitcoins.testkit.wallet.{
  BitcoinSWalletTestCachedBitcoinV19,
  WalletWithBitcoindV19
}
import org.bitcoins.wallet.models.AccountDAO
import org.scalatest.{FutureOutcome, Outcome}

import scala.concurrent.Future

class ProcessBlockTest extends BitcoinSWalletTestCachedBitcoinV19 {

  override type FixtureParam = WalletWithBitcoindV19

  override def withFixture(test: OneArgAsyncTest): FutureOutcome = {
    val f: Future[Outcome] = for {
      bitcoind <- cachedBitcoindWithFundsF
      futOutcome = withNewWalletAndBitcoindCachedV19(test, bitcoind)(
        getFreshWalletAppConfig)
      fut <- futOutcome.toFuture
    } yield fut
    new FutureOutcome(f)
  }

  it must "process a block" in { param =>
    val wallet = param.wallet
    val bitcoind = param.bitcoind

    for {
      startingUtxos <- wallet.listUtxos()
      _ = assert(startingUtxos.isEmpty)

      addr <- wallet.getNewAddress()
      txId <- bitcoind.sendToAddress(addr, 1.bitcoin)
      hash <-
        bitcoind.getNewAddress
          .flatMap(bitcoind.generateToAddress(1, _))
          .map(_.head)
      block <- bitcoind.getBlockRaw(hash)

      _ <- wallet.processBlock(block)
      utxos <- wallet.listUtxos()
      height <- bitcoind.getBlockCount
      bestHash <- bitcoind.getBestBlockHash
      syncHeightOpt <- wallet.getSyncDescriptorOpt()
      txDbOpt <- wallet.findByTxId(txId)
    } yield {
      assert(txDbOpt.isDefined)
      assert(txDbOpt.get.blockHashOpt.contains(hash))
      assert(utxos.size == 1)
      assert(utxos.head.output.scriptPubKey == addr.scriptPubKey)
      assert(utxos.head.output.value == 1.bitcoin)
      assert(utxos.head.txid == txId)

      assert(syncHeightOpt.contains(SyncHeightDescriptor(bestHash, height)))
    }
  }

  it must "process coinbase txs" in { param =>
    val wallet = param.wallet
    val bitcoind = param.bitcoind
    for {
      startingUtxos <- wallet.listUtxos(TxoState.ImmatureCoinbase)
      startingBalance <- wallet.getBalance()
      _ = assert(startingUtxos.isEmpty)
      _ = assert(startingBalance == Satoshis.zero)
      addr <- wallet.getNewAddress()
      hashes <- bitcoind.generateToAddress(101, addr)
      blocks <- FutureUtil.sequentially(hashes)(bitcoind.getBlockRaw)
      _ <- FutureUtil.sequentially(blocks)(wallet.processBlock)
      utxos <- wallet.listUtxos(TxoState.ImmatureCoinbase)
      balance <- wallet.getBalance()

      height <- bitcoind.getBlockCount
      bestHash <- bitcoind.getBestBlockHash
      syncHeightOpt <- wallet.getSyncDescriptorOpt()
    } yield {
      assert(utxos.size == 100)
      assert(balance == Bitcoins(50))

      assert(syncHeightOpt.contains(SyncHeightDescriptor(bestHash, height)))
    }
  }

  it must "process coinbase txs using filters" in { param =>
    val wallet = param.wallet
    val bitcoind = param.bitcoind

    for {
      startingUtxos <- wallet.listUtxos(TxoState.ImmatureCoinbase)
      startingBalance <- wallet.getBalance()
      _ = assert(startingUtxos.isEmpty)
      _ = assert(startingBalance == Satoshis.zero)
      addr <- wallet.getNewAddress()
      hashes <- bitcoind.generateToAddress(102, addr)
      filters <- FutureUtil.sequentially(hashes)(
        bitcoind.getBlockFilter(_, FilterType.Basic))
      filtersWithBlockHash = hashes.map(_.flip).zip(filters.map(_.filter))
      _ <- wallet.processCompactFilters(filtersWithBlockHash)
      utxos <- wallet.listUtxos(TxoState.ImmatureCoinbase)
      balance <- wallet.getBalance()

      height <- bitcoind.getBlockCount
      bestHash <- bitcoind.getBestBlockHash
      syncHeightOpt <- wallet.getSyncDescriptorOpt()
    } yield {
      assert(utxos.size == 100)
      assert(balance == Bitcoins(50))

      assert(syncHeightOpt.contains(SyncHeightDescriptor(bestHash, height)))
    }
  }

  it must "receive and spend funds in the same block" in { param =>
    val wallet = param.wallet
    val bitcoind = param.bitcoind
    val recvAmount = Bitcoins.one
    val sendAmount = Bitcoins(0.5)

    val accountDAO: AccountDAO =
      AccountDAO()(system.dispatcher, param.walletConfig)
    for {
      startBal <- wallet.getBalance()
      recvAddr <- wallet.getNewAddress()
      changeAddr <- wallet.getNewChangeAddress()

      bitcoindAddr <- bitcoind.getNewAddress
      recvTxId <- bitcoind.sendToAddress(recvAddr, recvAmount)
      recvTx <- bitcoind.getRawTransactionRaw(recvTxId)

      // Make sure we didn't process the tx
      afterBal <- wallet.getBalance()
      _ = assert(startBal == afterBal)

      index = recvTx.outputs.zipWithIndex
        .find(_._1.scriptPubKey == recvAddr.scriptPubKey)
        .get
        ._2

      input =
        TransactionInput(TransactionOutPoint(recvTx.txId, UInt32(index)),
                         EmptyScriptSignature,
                         UInt32.max)
      output0 =
        TransactionOutput(recvAmount - sendAmount - Satoshis(500),
                          changeAddr.scriptPubKey)
      output1 =
        TransactionOutput(sendAmount, bitcoindAddr.scriptPubKey)

      unsignedTx = BaseTransaction(Int32.two,
                                   Vector(input),
                                   Vector(output0, output1),
                                   UInt32.zero)

      addrDb <- wallet.getAddressInfo(recvAddr).map(_.get)
      path = addrDb.path
      coin = path.coin
      accountDb <- accountDAO
        .read((coin, path.accountIdx))
        .map(_.get)

      bip32Path = LegacyHDPath(path.coinType,
                               path.accountIdx,
                               path.chainType,
                               path.address.index)

      psbt = PSBT
        .fromUnsignedTx(unsignedTx)
        .addUTXOToInput(recvTx, 0)
        .addKeyPathToInput(accountDb.xpub, bip32Path, addrDb.pubkey, 0)

      signed <- wallet.signPSBT(psbt)
      tx <- Future.fromTry(
        signed.finalizePSBT.flatMap(_.extractTransactionAndValidate))

      _ <- bitcoind.sendRawTransaction(tx)
      hash <- bitcoind.generateToAddress(1, bitcoindAddr).map(_.head)
      block <- bitcoind.getBlockRaw(hash)

      _ <- wallet.processBlock(block)

      balance <- wallet.getBalance()
    } yield assert(balance == output0.value)
  }
}
