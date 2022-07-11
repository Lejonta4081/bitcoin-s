package org.bitcoins.server.util

import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.stream.scaladsl.SourceQueueWithComplete
import grizzled.slf4j.Logging
import org.bitcoins.chain.{
  ChainCallbacks,
  OnBlockHeaderConnected,
  OnSyncFlagChanged
}
import org.bitcoins.commons.jsonmodels.ws.TorNotification.TorStartedNotification
import org.bitcoins.commons.jsonmodels.ws.{
  ChainNotification,
  WalletNotification,
  WalletWsType
}
import org.bitcoins.commons.serializers.WsPicklers
import org.bitcoins.core.api.chain.ChainApi
import org.bitcoins.core.api.dlc.wallet.db.IncomingDLCOfferDb
import org.bitcoins.core.protocol.blockchain.BlockHeader
import org.bitcoins.core.protocol.dlc.models.DLCStatus
import org.bitcoins.core.protocol.transaction.Transaction
import org.bitcoins.core.util.FutureUtil
import org.bitcoins.crypto.{DoubleSha256DigestBE, Sha256Digest}
import org.bitcoins.dlc.wallet.{
  DLCWalletCallbacks,
  OnDLCOfferAdd,
  OnDLCOfferRemove,
  OnDLCStateChange
}
import org.bitcoins.tor.{OnTorStarted, TorCallbacks}
import org.bitcoins.wallet._

import scala.concurrent.{ExecutionContext, Future}

object WebsocketUtil extends Logging {

  def buildChainCallbacks(
      queue: SourceQueueWithComplete[Message],
      chainApi: ChainApi)(implicit ec: ExecutionContext): ChainCallbacks = {
    val onBlockProcessed: OnBlockHeaderConnected = {
      case headersWithHeight: Vector[(Int, BlockHeader)] =>
        val hashes: Vector[DoubleSha256DigestBE] =
          headersWithHeight.map(_._2.hashBE)
        val resultsF =
          ChainUtil.getBlockHeaderResult(hashes, chainApi)
        val f = for {
          results <- resultsF
          notifications =
            results.map(result =>
              ChainNotification.BlockProcessedNotification(result))
          notificationsJson = notifications.map { notification =>
            upickle.default.writeJs(notification)(
              WsPicklers.blockProcessedPickler)
          }

          msgs = notificationsJson.map(n => TextMessage.Strict(n.toString()))
          _ <- FutureUtil.sequentially(msgs) { case msg =>
            val x: Future[Unit] = queue
              .offer(msg)
              .map(_ => ())
            x
          }
        } yield {
          ()
        }
        f
    }

    val onSyncFlagChanged: OnSyncFlagChanged = { syncing =>
      val notification = ChainNotification.SyncFlagChangedNotification(syncing)
      val notificationJson =
        upickle.default.writeJs(notification)(WsPicklers.syncFlagChangedPickler)
      val msg = TextMessage.Strict(notificationJson.toString())
      for {
        _ <- queue.offer(msg)
      } yield ()
    }

    ChainCallbacks.onBlockHeaderConnected(onBlockProcessed) +
      ChainCallbacks.onOnSyncFlagChanged(onSyncFlagChanged)
  }

  /** Builds websocket callbacks for the wallet */
  def buildWalletCallbacks(
      walletQueue: SourceQueueWithComplete[Message],
      walletNameOpt: Option[String])(implicit
      ec: ExecutionContext): WalletCallbacks = {
    val onAddressCreated: OnNewAddressGenerated = { addr =>
      val notification = WalletNotification.NewAddressNotification(addr)
      val json =
        upickle.default.writeJs(notification)(WsPicklers.newAddressPickler)
      val msg = TextMessage.Strict(json.toString())
      val offerF = walletQueue.offer(msg)
      offerF.map(_ => ())
    }

    val onTxProcessed: OnTransactionProcessed = { tx =>
      buildTxNotification(wsType = WalletWsType.TxProcessed,
                          tx = tx,
                          walletQueue = walletQueue)
    }

    val onTxBroadcast: OnTransactionBroadcast = { tx =>
      buildTxNotification(wsType = WalletWsType.TxBroadcast,
                          tx = tx,
                          walletQueue = walletQueue)
    }

    val onReservedUtxo: OnReservedUtxos = { utxos =>
      val notification =
        WalletNotification.ReservedUtxosNotification(utxos)
      val notificationJson =
        upickle.default.writeJs(notification)(WsPicklers.reservedUtxosPickler)
      val msg = TextMessage.Strict(notificationJson.toString())
      val offerF = walletQueue.offer(msg)
      offerF.map(_ => ())
    }

    val onRescanComplete: OnRescanComplete = { _ =>
      val name =
        walletNameOpt.getOrElse("") // default name empty string on the wallet
      val notification = WalletNotification.RescanComplete(name)
      val notificationJson =
        upickle.default.writeJs(notification)(WsPicklers.rescanPickler)
      val msg = TextMessage.Strict(notificationJson.toString())
      val offerF = walletQueue.offer(msg)
      offerF.map(_ => ())
    }

    WalletCallbacks(
      onTransactionProcessed = Vector(onTxProcessed),
      onTransactionBroadcast = Vector(onTxBroadcast),
      onReservedUtxos = Vector(onReservedUtxo),
      onNewAddressGenerated = Vector(onAddressCreated),
      onBlockProcessed = Vector.empty,
      onRescanComplete = Vector(onRescanComplete)
    )
  }

  def buildTorCallbacks(queue: SourceQueueWithComplete[Message])(implicit
      ec: ExecutionContext): TorCallbacks = {
    val onTorStarted: OnTorStarted = { _ =>
      val notification = TorStartedNotification
      val json =
        upickle.default.writeJs(notification)(WsPicklers.torStartedPickler)

      val msg = TextMessage.Strict(json.toString())
      val offerF = queue.offer(msg)
      offerF.map(_ => ())
    }

    TorCallbacks(onTorStarted)
  }

  private def buildTxNotification(
      wsType: WalletWsType,
      tx: Transaction,
      walletQueue: SourceQueueWithComplete[Message])(implicit
      ec: ExecutionContext): Future[Unit] = {
    val json = wsType match {
      case WalletWsType.TxProcessed =>
        val notification = WalletNotification.TxProcessedNotification(tx)
        upickle.default.writeJs(notification)(WsPicklers.txProcessedPickler)
      case WalletWsType.TxBroadcast =>
        val notification = WalletNotification.TxBroadcastNotification(tx)
        upickle.default.writeJs(notification)(WsPicklers.txBroadcastPickler)
      case x @ (WalletWsType.NewAddress | WalletWsType.ReservedUtxos |
          WalletWsType.DLCStateChange | WalletWsType.DLCOfferAdd |
          WalletWsType.DLCOfferRemove | WalletWsType.RescanComplete) =>
        sys.error(s"Cannot build tx notification for $x")
    }

    val msg = TextMessage.Strict(json.toString())
    val offerF = walletQueue.offer(msg)
    offerF.map(_ => ())
  }

  def buildDLCWalletCallbacks(walletQueue: SourceQueueWithComplete[Message])(
      implicit ec: ExecutionContext): DLCWalletCallbacks = {
    val onStateChange: OnDLCStateChange = { status: DLCStatus =>
      val notification = WalletNotification.DLCStateChangeNotification(status)
      val json =
        upickle.default.writeJs(notification)(WsPicklers.dlcStateChangePickler)
      val msg = TextMessage.Strict(json.toString())
      val offerF = walletQueue.offer(msg)
      offerF.map(_ => ())
    }

    val onOfferAdd: OnDLCOfferAdd = { offerDb: IncomingDLCOfferDb =>
      val notification = WalletNotification.DLCOfferAddNotification(offerDb)
      val json =
        upickle.default.writeJs(notification)(WsPicklers.dlcOfferAddPickler)
      val msg = TextMessage.Strict(json.toString())
      val offerF = walletQueue.offer(msg)
      offerF.map(_ => ())
    }

    val onOfferRemove: OnDLCOfferRemove = { offerHash: Sha256Digest =>
      val notification =
        WalletNotification.DLCOfferRemoveNotification(offerHash)
      val json =
        upickle.default.writeJs(notification)(WsPicklers.dlcOfferRemovePickler)
      val msg = TextMessage.Strict(json.toString())
      val offerF = walletQueue.offer(msg)
      offerF.map(_ => ())
    }

    import DLCWalletCallbacks._

    onDLCStateChange(onStateChange) + onDLCOfferAdd(
      onOfferAdd) + onDLCOfferRemove(onOfferRemove)
  }
}
