package org.bitcoins.rpc.client.v22

import org.bitcoins.commons.jsonmodels.bitcoind.{
  GetNodeAddressesResultPostV22,
  ListDescriptorsResult
}
import org.bitcoins.commons.serializers.JsonSerializers._
import org.bitcoins.rpc.client.common.{Client, WalletRpc}
import org.bitcoins.rpc.client.v18.V18AssortedRpc
import org.bitcoins.rpc.client.v20.V20AssortedRpc
import play.api.libs.json.Json

import scala.concurrent.Future

trait V22AssortedRpc extends V18AssortedRpc with V20AssortedRpc with WalletRpc {
  self: Client =>

  def listDescriptors(): Future[ListDescriptorsResult] = {
    bitcoindCall[ListDescriptorsResult](
      "listdescriptors"
    )
  }

  def listDescriptors(
      Private: Option[Boolean],
      walletName: String): Future[ListDescriptorsResult] = {
    bitcoindCall[ListDescriptorsResult](
      "listdescriptors",
      List(Json.toJson(Private)),
      uriExtensionOpt = Some(walletExtension(walletName))
    )
  }

  def listDescriptors(
      Private: Option[Boolean]): Future[ListDescriptorsResult] = {
    bitcoindCall[ListDescriptorsResult](
      "listdescriptors",
      List(Json.toJson(Private))
    )
  }

  def listDescriptors(walletName: String): Future[ListDescriptorsResult] = {
    bitcoindCall[ListDescriptorsResult](
      "listdescriptors",
      uriExtensionOpt = Some(walletExtension(walletName))
    )
  }

  private def getNodeAddresses(
      count: Option[Int]): Future[Vector[GetNodeAddressesResultPostV22]] = {
    bitcoindCall[Vector[GetNodeAddressesResultPostV22]](
      "getnodeaddresses",
      List(Json.toJson(count)))
  }

  def getNodeAddresses(
      network: String,
      count: Int): Future[Vector[GetNodeAddressesResultPostV22]] = {
    bitcoindCall[Vector[GetNodeAddressesResultPostV22]](
      "getnodeaddresses",
      List(Json.toJson(count), Json.toJson(network))
    )
  }

  override def getNodeAddresses(
      count: Int): Future[Vector[GetNodeAddressesResultPostV22]] =
    getNodeAddresses(Some(count))

  override def getNodeAddresses(): Future[
    Vector[GetNodeAddressesResultPostV22]] =
    getNodeAddresses(None)

}
