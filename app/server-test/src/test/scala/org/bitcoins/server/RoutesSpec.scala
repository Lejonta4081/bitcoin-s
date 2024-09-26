package org.bitcoins.server

import org.apache.pekko.http.scaladsl.model.ContentTypes.*
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.testkit.{
  RouteTestTimeout,
  ScalatestRouteTest
}
import org.bitcoins.core.api.chain.ChainApi
import org.bitcoins.core.api.chain.db.*
import org.bitcoins.core.api.dlc.wallet.DLCNeutrinoHDWalletApi
import org.bitcoins.core.api.wallet.db.*
import org.bitcoins.core.api.wallet.{
  AccountHandlingApi,
  AddressHandlingApi,
  AddressInfo,
  CoinSelectionAlgo,
  FundTransactionHandlingApi,
  RescanHandlingApi,
  SendFundsHandlingApi,
  TransactionProcessingApi,
  UtxoHandlingApi
}
import org.bitcoins.core.config.RegTest
import org.bitcoins.core.crypto.ExtPublicKey
import org.bitcoins.core.currency.{Bitcoins, CurrencyUnit, Satoshis}
import org.bitcoins.core.dlc.accounting.DLCWalletAccounting
import org.bitcoins.core.hd.*
import org.bitcoins.core.number.{UInt32, UInt64}
import org.bitcoins.core.protocol.BlockStamp.{BlockHash, BlockHeight, BlockTime}
import org.bitcoins.core.protocol.blockchain.BlockHeader
import org.bitcoins.core.protocol.dlc.models.DLCMessage.*
import org.bitcoins.core.protocol.dlc.models.*
import org.bitcoins.core.protocol.script.P2WPKHWitnessV0
import org.bitcoins.core.protocol.tlv.*
import org.bitcoins.core.protocol.transaction.*
import org.bitcoins.core.protocol.{
  Bech32Address,
  BitcoinAddress,
  BlockStamp,
  P2PKHAddress
}
import org.bitcoins.core.psbt.InputPSBTRecord.PartialSignature
import org.bitcoins.core.psbt.PSBT
import org.bitcoins.core.util.FutureUtil
import org.bitcoins.core.util.sorted.OrderedSchnorrSignatures
import org.bitcoins.core.wallet.fee.{FeeUnit, SatoshisPerVirtualByte}
import org.bitcoins.core.wallet.rescan.RescanState
import org.bitcoins.core.wallet.utxo.*
import org.bitcoins.crypto.*
import org.bitcoins.feeprovider.ConstantFeeRateProvider
import org.bitcoins.node.Node
import org.bitcoins.server.routes.{CommonRoutes, ServerCommand}
import org.bitcoins.testkit.BitcoinSTestAppConfig
import org.bitcoins.testkit.wallet.DLCWalletUtil
import org.bitcoins.testkitcore.util.TransactionTestUtil
import org.bitcoins.wallet.WalletHolder
import org.scalamock.scalatest.MockFactory
import org.scalatest.wordspec.AnyWordSpec
import scodec.bits.ByteVector
import ujson.*

import java.net.InetSocketAddress
import java.time.{ZoneId, ZonedDateTime}
import scala.collection.mutable
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future, Promise}

class RoutesSpec extends AnyWordSpec with ScalatestRouteTest with MockFactory {

  implicit val conf: BitcoinSAppConfig =
    BitcoinSTestAppConfig.getNeutrinoTestConfig()

  implicit val timeout: RouteTestTimeout = RouteTestTimeout(5.seconds)

  // the genesis address
  val testAddressStr = "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa"
  val testAddress: BitcoinAddress = BitcoinAddress.fromString(testAddressStr)
  val testLabel: AddressLabelTag = AddressLabelTag("test")

  val xpub = ExtPublicKey
    .fromString(
      "xpub661MyMwAqRbcFtXgS5sYJABqqG9YLmC4Q1Rdap9gSE8NqtwybGhePY2gZ29ESFjqJoCu1Rupje8YtGqsefD265TMg7usUDFdp6W1EGMcet8"
    )

  val accountDb =
    AccountDb(
      xpub = xpub,
      hdAccount = HDAccount(HDCoin(HDPurpose.Legacy, HDCoinType.Testnet), 0)
    )

  val mockChainApi: ChainApi = mock[ChainApi]

  val mockNode: Node = mock[Node]

  val chainRoutes: ChainRoutes = ChainRoutes(mockChainApi, RegTest, Future.unit)

  val nodeRoutes: NodeRoutes = NodeRoutes(mockNode)

  val mockWalletApi: DLCNeutrinoHDWalletApi = mock[DLCNeutrinoHDWalletApi]

  val mockRescanHandlingApi: RescanHandlingApi = mock[RescanHandlingApi]

  val mockAccountHandlingApi: AccountHandlingApi = mock[AccountHandlingApi]

  val mockAddressHandlingApi: AddressHandlingApi = mock[AddressHandlingApi]

  val mockTransactionProcessingApi: TransactionProcessingApi =
    mock[TransactionProcessingApi]

  val mockFundTxHandlingApi: FundTransactionHandlingApi =
    mock[FundTransactionHandlingApi]

  val mockSendFundsHandlingApi: SendFundsHandlingApi =
    mock[SendFundsHandlingApi]

  val mockUtxoHandlingApi: UtxoHandlingApi = mock[UtxoHandlingApi]

  val walletHolder = new WalletHolder(Some(mockWalletApi))

  val feeRateApi = ConstantFeeRateProvider(SatoshisPerVirtualByte.one)

  val fee = SatoshisPerVirtualByte(Satoshis(4))
  val walletLoader: DLCWalletNeutrinoBackendLoader = {
    DLCWalletNeutrinoBackendLoader(
      walletHolder,
      mockChainApi,
      mockNode,
      feeRateApi
    )
  }

  val walletRoutes: WalletRoutes =
    WalletRoutes(walletLoader)(system, conf.walletConf)

  val coreRoutes: CoreRoutes = CoreRoutes()

  val commonRoutes: CommonRoutes = CommonRoutes(conf.baseDatadir)
  "The server" should {

    "combine PSBTs" in {
      val psbt1 = PSBT(
        "70736274ff01003f0200000001ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff0000000000ffffffff010000000000000000036a0100000000000a0f0102030405060708090f0102030405060708090a0b0c0d0e0f000a0f0102030405060708090f0102030405060708090a0b0c0d0e0f000a0f0102030405060708090f0102030405060708090a0b0c0d0e0f00"
      )
      val psbt2 = PSBT(
        "70736274ff01003f0200000001ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff0000000000ffffffff010000000000000000036a0100000000000a0f0102030405060708100f0102030405060708090a0b0c0d0e0f000a0f0102030405060708100f0102030405060708090a0b0c0d0e0f000a0f0102030405060708100f0102030405060708090a0b0c0d0e0f00"
      )
      val expected = PSBT(
        "70736274ff01003f0200000001ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff0000000000ffffffff010000000000000000036a0100000000000a0f0102030405060708090f0102030405060708090a0b0c0d0e0f0a0f0102030405060708100f0102030405060708090a0b0c0d0e0f000a0f0102030405060708090f0102030405060708090a0b0c0d0e0f0a0f0102030405060708100f0102030405060708090a0b0c0d0e0f000a0f0102030405060708090f0102030405060708090a0b0c0d0e0f0a0f0102030405060708100f0102030405060708090a0b0c0d0e0f00"
      )

      val route =
        coreRoutes.handleCommand(
          ServerCommand(
            "combinepsbts",
            Arr(Arr(Str(psbt1.base64), Str(psbt2.base64)))
          )
        )

      Get() ~> route ~> check {
        assert(contentType == `application/json`)
        assert(
          responseAs[
            String
          ] == s"""{"result":"${expected.base64}","error":null}"""
        )
      }

      val joinRoute =
        coreRoutes.handleCommand(
          ServerCommand(
            "joinpsbts",
            Arr(Arr(Str(psbt1.base64), Str(psbt2.base64)))
          )
        )

      Get() ~> joinRoute ~> check {
        assert(contentType == `application/json`)
        assert(
          responseAs[
            String
          ] == s"""{"result":"${expected.base64}","error":null}"""
        )
      }
    }

    "finalize a PSBT" in {
      val psbt = PSBT(
        "70736274ff01009a020000000258e87a21b56daf0c23be8e7070456c336f7cbaa5c8757924f545887bb2abdd750000000000ffffffff838d0427d0ec650a68aa46bb0b098aea4422c071b2ca78352a077959d07cea1d0100000000ffffffff0270aaf00800000000160014d85c2b71d0060b09c9886aeb815e50991dda124d00e1f5050000000016001400aea9a2e5f0f876a588df5546e8742d1d87008f00000000000100bb0200000001aad73931018bd25f84ae400b68848be09db706eac2ac18298babee71ab656f8b0000000048473044022058f6fc7c6a33e1b31548d481c826c015bd30135aad42cd67790dab66d2ad243b02204a1ced2604c6735b6393e5b41691dd78b00f0c5942fb9f751856faa938157dba01feffffff0280f0fa020000000017a9140fb9463421696b82c833af241c78c17ddbde493487d0f20a270100000017a91429ca74f8a08f81999428185c97b5d852e4063f6187650000002202029583bf39ae0a609747ad199addd634fa6108559d6c5cd39b4c2183f1ab96e07f473044022074018ad4180097b873323c0015720b3684cc8123891048e7dbcd9b55ad679c99022073d369b740e3eb53dcefa33823c8070514ca55a7dd9544f157c167913261118c01220202dab61ff49a14db6a7d02b0cd1fbb78fc4b18312b5b4e54dae4dba2fbfef536d7483045022100f61038b308dc1da865a34852746f015772934208c6d24454393cd99bdf2217770220056e675a675a6d0a02b85b14e5e29074d8a25a9b5760bea2816f661910a006ea01010304010000000104475221029583bf39ae0a609747ad199addd634fa6108559d6c5cd39b4c2183f1ab96e07f2102dab61ff49a14db6a7d02b0cd1fbb78fc4b18312b5b4e54dae4dba2fbfef536d752ae2206029583bf39ae0a609747ad199addd634fa6108559d6c5cd39b4c2183f1ab96e07f10d90c6a4f000000800000008000000080220602dab61ff49a14db6a7d02b0cd1fbb78fc4b18312b5b4e54dae4dba2fbfef536d710d90c6a4f000000800000008001000080000100f80200000000010158e87a21b56daf0c23be8e7070456c336f7cbaa5c8757924f545887bb2abdd7501000000171600145f275f436b09a8cc9a2eb2a2f528485c68a56323feffffff02d8231f1b0100000017a914aed962d6654f9a2b36608eb9d64d2b260db4f1118700c2eb0b0000000017a914b7f5faf40e3d40a5a459b1db3535f2b72fa921e88702483045022100a22edcc6e5bc511af4cc4ae0de0fcd75c7e04d8c1c3a8aa9d820ed4b967384ec02200642963597b9b1bc22c75e9f3e117284a962188bf5e8a74c895089046a20ad770121035509a48eb623e10aace8bfd0212fdb8a8e5af3c94b0b133b95e114cab89e4f79650000002202023add904f3d6dcf59ddb906b0dee23529b7ffb9ed50e5e86151926860221f0e73473044022065f45ba5998b59a27ffe1a7bed016af1f1f90d54b3aa8f7450aa5f56a25103bd02207f724703ad1edb96680b284b56d4ffcb88f7fb759eabbe08aa30f29b851383d201220203089dc10c7ac6db54f91329af617333db388cead0c231f723379d1b99030b02dc473044022062eb7a556107a7c73f45ac4ab5a1dddf6f7075fb1275969a7f383efff784bcb202200c05dbb7470dbf2f08557dd356c7325c1ed30913e996cd3840945db12228da5f010103040100000001042200208c2353173743b595dfb4a07b72ba8e42e3797da74e87fe7d9d7497e3b2028903010547522103089dc10c7ac6db54f91329af617333db388cead0c231f723379d1b99030b02dc21023add904f3d6dcf59ddb906b0dee23529b7ffb9ed50e5e86151926860221f0e7352ae2206023add904f3d6dcf59ddb906b0dee23529b7ffb9ed50e5e86151926860221f0e7310d90c6a4f000000800000008003000080220603089dc10c7ac6db54f91329af617333db388cead0c231f723379d1b99030b02dc10d90c6a4f00000080000000800200008000220203a9a4c37f5996d3aa25dbac6b570af0650394492942460b354753ed9eeca5877110d90c6a4f000000800000008004000080002202027f6399757d2eff55a136ad02c684b1838b6556e5f1b6b34282a94b6b5005109610d90c6a4f00000080000000800500008000"
      )
      val expected = PSBT(
        "70736274ff01009a020000000258e87a21b56daf0c23be8e7070456c336f7cbaa5c8757924f545887bb2abdd750000000000ffffffff838d0427d0ec650a68aa46bb0b098aea4422c071b2ca78352a077959d07cea1d0100000000ffffffff0270aaf00800000000160014d85c2b71d0060b09c9886aeb815e50991dda124d00e1f5050000000016001400aea9a2e5f0f876a588df5546e8742d1d87008f00000000000100bb0200000001aad73931018bd25f84ae400b68848be09db706eac2ac18298babee71ab656f8b0000000048473044022058f6fc7c6a33e1b31548d481c826c015bd30135aad42cd67790dab66d2ad243b02204a1ced2604c6735b6393e5b41691dd78b00f0c5942fb9f751856faa938157dba01feffffff0280f0fa020000000017a9140fb9463421696b82c833af241c78c17ddbde493487d0f20a270100000017a91429ca74f8a08f81999428185c97b5d852e4063f6187650000000107da00473044022074018ad4180097b873323c0015720b3684cc8123891048e7dbcd9b55ad679c99022073d369b740e3eb53dcefa33823c8070514ca55a7dd9544f157c167913261118c01483045022100f61038b308dc1da865a34852746f015772934208c6d24454393cd99bdf2217770220056e675a675a6d0a02b85b14e5e29074d8a25a9b5760bea2816f661910a006ea01475221029583bf39ae0a609747ad199addd634fa6108559d6c5cd39b4c2183f1ab96e07f2102dab61ff49a14db6a7d02b0cd1fbb78fc4b18312b5b4e54dae4dba2fbfef536d752ae000100f80200000000010158e87a21b56daf0c23be8e7070456c336f7cbaa5c8757924f545887bb2abdd7501000000171600145f275f436b09a8cc9a2eb2a2f528485c68a56323feffffff02d8231f1b0100000017a914aed962d6654f9a2b36608eb9d64d2b260db4f1118700c2eb0b0000000017a914b7f5faf40e3d40a5a459b1db3535f2b72fa921e88702483045022100a22edcc6e5bc511af4cc4ae0de0fcd75c7e04d8c1c3a8aa9d820ed4b967384ec02200642963597b9b1bc22c75e9f3e117284a962188bf5e8a74c895089046a20ad770121035509a48eb623e10aace8bfd0212fdb8a8e5af3c94b0b133b95e114cab89e4f79650000000107232200208c2353173743b595dfb4a07b72ba8e42e3797da74e87fe7d9d7497e3b20289030108da0400473044022062eb7a556107a7c73f45ac4ab5a1dddf6f7075fb1275969a7f383efff784bcb202200c05dbb7470dbf2f08557dd356c7325c1ed30913e996cd3840945db12228da5f01473044022065f45ba5998b59a27ffe1a7bed016af1f1f90d54b3aa8f7450aa5f56a25103bd02207f724703ad1edb96680b284b56d4ffcb88f7fb759eabbe08aa30f29b851383d20147522103089dc10c7ac6db54f91329af617333db388cead0c231f723379d1b99030b02dc21023add904f3d6dcf59ddb906b0dee23529b7ffb9ed50e5e86151926860221f0e7352ae00220203a9a4c37f5996d3aa25dbac6b570af0650394492942460b354753ed9eeca5877110d90c6a4f000000800000008004000080002202027f6399757d2eff55a136ad02c684b1838b6556e5f1b6b34282a94b6b5005109610d90c6a4f00000080000000800500008000"
      )

      val route =
        coreRoutes.handleCommand(
          ServerCommand("finalizepsbt", Arr(Str(psbt.hex)))
        )

      Get() ~> route ~> check {
        assert(contentType == `application/json`)
        assert(
          responseAs[
            String
          ] == s"""{"result":"${expected.base64}","error":null}"""
        )

      }
    }

    "extract a transaction from a PSBT" in {
      val psbt = PSBT(
        "70736274ff01009a020000000258e87a21b56daf0c23be8e7070456c336f7cbaa5c8757924f545887bb2abdd750000000000ffffffff838d0427d0ec650a68aa46bb0b098aea4422c071b2ca78352a077959d07cea1d0100000000ffffffff0270aaf00800000000160014d85c2b71d0060b09c9886aeb815e50991dda124d00e1f5050000000016001400aea9a2e5f0f876a588df5546e8742d1d87008f00000000000100bb0200000001aad73931018bd25f84ae400b68848be09db706eac2ac18298babee71ab656f8b0000000048473044022058f6fc7c6a33e1b31548d481c826c015bd30135aad42cd67790dab66d2ad243b02204a1ced2604c6735b6393e5b41691dd78b00f0c5942fb9f751856faa938157dba01feffffff0280f0fa020000000017a9140fb9463421696b82c833af241c78c17ddbde493487d0f20a270100000017a91429ca74f8a08f81999428185c97b5d852e4063f6187650000000107da00473044022074018ad4180097b873323c0015720b3684cc8123891048e7dbcd9b55ad679c99022073d369b740e3eb53dcefa33823c8070514ca55a7dd9544f157c167913261118c01483045022100f61038b308dc1da865a34852746f015772934208c6d24454393cd99bdf2217770220056e675a675a6d0a02b85b14e5e29074d8a25a9b5760bea2816f661910a006ea01475221029583bf39ae0a609747ad199addd634fa6108559d6c5cd39b4c2183f1ab96e07f2102dab61ff49a14db6a7d02b0cd1fbb78fc4b18312b5b4e54dae4dba2fbfef536d752ae0001012000c2eb0b0000000017a914b7f5faf40e3d40a5a459b1db3535f2b72fa921e8870107232200208c2353173743b595dfb4a07b72ba8e42e3797da74e87fe7d9d7497e3b20289030108da0400473044022062eb7a556107a7c73f45ac4ab5a1dddf6f7075fb1275969a7f383efff784bcb202200c05dbb7470dbf2f08557dd356c7325c1ed30913e996cd3840945db12228da5f01473044022065f45ba5998b59a27ffe1a7bed016af1f1f90d54b3aa8f7450aa5f56a25103bd02207f724703ad1edb96680b284b56d4ffcb88f7fb759eabbe08aa30f29b851383d20147522103089dc10c7ac6db54f91329af617333db388cead0c231f723379d1b99030b02dc21023add904f3d6dcf59ddb906b0dee23529b7ffb9ed50e5e86151926860221f0e7352ae00220203a9a4c37f5996d3aa25dbac6b570af0650394492942460b354753ed9eeca5877110d90c6a4f000000800000008004000080002202027f6399757d2eff55a136ad02c684b1838b6556e5f1b6b34282a94b6b5005109610d90c6a4f00000080000000800500008000"
      )
      val expected = Transaction(
        "0200000000010258e87a21b56daf0c23be8e7070456c336f7cbaa5c8757924f545887bb2abdd7500000000da00473044022074018ad4180097b873323c0015720b3684cc8123891048e7dbcd9b55ad679c99022073d369b740e3eb53dcefa33823c8070514ca55a7dd9544f157c167913261118c01483045022100f61038b308dc1da865a34852746f015772934208c6d24454393cd99bdf2217770220056e675a675a6d0a02b85b14e5e29074d8a25a9b5760bea2816f661910a006ea01475221029583bf39ae0a609747ad199addd634fa6108559d6c5cd39b4c2183f1ab96e07f2102dab61ff49a14db6a7d02b0cd1fbb78fc4b18312b5b4e54dae4dba2fbfef536d752aeffffffff838d0427d0ec650a68aa46bb0b098aea4422c071b2ca78352a077959d07cea1d01000000232200208c2353173743b595dfb4a07b72ba8e42e3797da74e87fe7d9d7497e3b2028903ffffffff0270aaf00800000000160014d85c2b71d0060b09c9886aeb815e50991dda124d00e1f5050000000016001400aea9a2e5f0f876a588df5546e8742d1d87008f000400473044022062eb7a556107a7c73f45ac4ab5a1dddf6f7075fb1275969a7f383efff784bcb202200c05dbb7470dbf2f08557dd356c7325c1ed30913e996cd3840945db12228da5f01473044022065f45ba5998b59a27ffe1a7bed016af1f1f90d54b3aa8f7450aa5f56a25103bd02207f724703ad1edb96680b284b56d4ffcb88f7fb759eabbe08aa30f29b851383d20147522103089dc10c7ac6db54f91329af617333db388cead0c231f723379d1b99030b02dc21023add904f3d6dcf59ddb906b0dee23529b7ffb9ed50e5e86151926860221f0e7352ae00000000"
      )

      val route =
        coreRoutes.handleCommand(
          ServerCommand("extractfrompsbt", Arr(Str(psbt.hex)))
        )

      Get() ~> route ~> check {
        assert(contentType == `application/json`)
        assert(
          responseAs[String] == s"""{"result":"${expected.hex}","error":null}"""
        )
      }
    }

    "convert a transaction to a PSBT" in {
      val tx = Transaction(
        "020000000258e87a21b56daf0c23be8e7070456c336f7cbaa5c8757924f545887bb2abdd750000000000ffffffff838d0427d0ec650a68aa46bb0b098aea4422c071b2ca78352a077959d07cea1d0100000000ffffffff0270aaf00800000000160014d85c2b71d0060b09c9886aeb815e50991dda124d00e1f5050000000016001400aea9a2e5f0f876a588df5546e8742d1d87008f00000000"
      )
      val expected = PSBT(
        "70736274ff01009a020000000258e87a21b56daf0c23be8e7070456c336f7cbaa5c8757924f545887bb2abdd750000000000ffffffff838d0427d0ec650a68aa46bb0b098aea4422c071b2ca78352a077959d07cea1d0100000000ffffffff0270aaf00800000000160014d85c2b71d0060b09c9886aeb815e50991dda124d00e1f5050000000016001400aea9a2e5f0f876a588df5546e8742d1d87008f000000000000000000"
      )

      val route =
        coreRoutes.handleCommand(
          ServerCommand("converttopsbt", Arr(Str(tx.hex)))
        )

      Get() ~> route ~> check {
        assert(contentType == `application/json`)
        assert(
          responseAs[
            String
          ] == s"""{"result":"${expected.base64}","error":null}"""
        )

      }
    }

    "return the block count" in {
      (() => mockChainApi.getBlockCount())
        .expects()
        .returning(Future.successful(1234567890))

      val route =
        chainRoutes.handleCommand(ServerCommand("getblockcount", Arr()))

      Get() ~> route ~> check {
        assert(contentType == `application/json`)
        assert(responseAs[String] == """{"result":1234567890,"error":null}""")
      }
    }

    "return the filter count" in {
      (() => mockChainApi.getFilterCount())
        .expects()
        .returning(Future.successful(1234567890))

      val route =
        chainRoutes.handleCommand(ServerCommand("getfiltercount", Arr()))

      Get() ~> route ~> check {
        assert(contentType == `application/json`)
        assert(responseAs[String] == """{"result":1234567890,"error":null}""")
      }
    }

    "return the filter header count" in {
      (() => mockChainApi.getFilterHeaderCount())
        .expects()
        .returning(Future.successful(1234567890))

      val route =
        chainRoutes.handleCommand(ServerCommand("getfilterheadercount", Arr()))

      Get() ~> route ~> check {
        assert(contentType == `application/json`)
        assert(responseAs[String] == """{"result":1234567890,"error":null}""")
      }
    }

    "return the best block hash" in {
      (() => mockChainApi.getBestBlockHash())
        .expects()
        .returning(Future.successful(DoubleSha256DigestBE.empty))

      val route =
        chainRoutes.handleCommand(ServerCommand("getbestblockhash", Arr()))

      Get() ~> route ~> check {
        assert(contentType == `application/json`)
        assert(
          responseAs[String] == """{"result":"0000000000000000000000000000000000000000000000000000000000000000","error":null}"""
        )
      }
    }

    val blockHeader = BlockHeader(
      "00e0002094c6692e100ed20d14f8c325c897259749e781d55ed1b7eb1000000000000000309a90b49f5f5a14ffdb2857557f6f27a136943603fb29e65e283dcb27fd886124fee25f57e53019886c0e8b"
    )
    val blockHeaderDb =
      BlockHeaderDbHelper.fromBlockHeader(
        height = 1899697,
        chainWork = BigInt(12345),
        bh = blockHeader
      )

    "get a block header" in {
      val chainworkStr = {
        val bytes = ByteVector(blockHeaderDb.chainWork.toByteArray)
        val padded = if (bytes.length <= 32) {
          bytes.padLeft(32)
        } else bytes

        padded.toHex
      }

      (mockChainApi
        .getHeader(_: DoubleSha256DigestBE))
        .expects(blockHeader.hashBE)
        .returning(Future.successful(Some(blockHeaderDb)))

      (() => mockChainApi.getBestBlockHeader())
        .expects()
        .returning(Future.successful(blockHeaderDb))

      (mockChainApi
        .getHeaders(_: Vector[DoubleSha256DigestBE]))
        .expects(Vector(blockHeader.hashBE))
        .returning(Future.successful(Vector(Some(blockHeaderDb))))

      val route =
        chainRoutes.handleCommand(
          ServerCommand("getblockheader", Arr(Str(blockHeader.hashBE.hex)))
        )

      Get() ~> route ~> check {
        assert(contentType == `application/json`)
        assert(
          responseAs[
            String
          ] == s"""{"result":{"raw":"${blockHeader.hex}","hash":"${blockHeader.hashBE.hex}","confirmations":0,"height":1899697,"version":${blockHeader.version.toLong},"versionHex":"${blockHeader.version.hex}","merkleroot":"${blockHeader.merkleRootHashBE.hex}","time":${blockHeader.time.toLong},"mediantime":${blockHeaderDb.time.toLong},"nonce":${blockHeader.nonce.toLong},"bits":"${blockHeader.nBits.hex}","difficulty":${blockHeader.difficulty.toDouble},"chainwork":"$chainworkStr","previousblockhash":"${blockHeader.previousBlockHashBE.hex}","nextblockhash":null},"error":null}"""
        )
      }
    }

    "return the median time past" in {
      (() => mockChainApi.getMedianTimePast())
        .expects()
        .returning(Future.successful(1234567890L))

      val route =
        chainRoutes.handleCommand(ServerCommand("getmediantimepast", Arr()))

      Get() ~> route ~> check {
        assert(contentType == `application/json`)
        assert(responseAs[String] == """{"result":1234567890,"error":null}""")
      }
    }

    "return the wallet's balance" in {
      (mockWalletApi
        .getBalance()(_: ExecutionContext))
        .expects(executor)
        .returning(Future.successful(Bitcoins(50)))

      val route =
        walletRoutes.handleCommand(
          ServerCommand("getbalance", Arr(Bool(false)))
        )

      Get() ~> route ~> check {
        assert(contentType == `application/json`)
        assert(responseAs[String] == """{"result":50,"error":null}""")
      }
    }

    "return the wallet's balance in sats" in {
      (mockWalletApi
        .getBalance()(_: ExecutionContext))
        .expects(executor)
        .returning(Future.successful(Bitcoins(50)))

      val route =
        walletRoutes.handleCommand(ServerCommand("getbalance", Arr(Bool(true))))

      Get() ~> route ~> check {
        assert(contentType == `application/json`)
        assert(responseAs[String] == """{"result":5000000000,"error":null}""")
      }
    }

    "return the wallet's confirmed balance" in {
      (() => mockWalletApi.getConfirmedBalance())
        .expects()
        .returning(Future.successful(Bitcoins(50)))

      val route =
        walletRoutes.handleCommand(
          ServerCommand("getconfirmedbalance", Arr(Bool(false)))
        )

      Get() ~> route ~> check {
        assert(contentType == `application/json`)
        assert(responseAs[String] == """{"result":50,"error":null}""")
      }
    }

    "return the wallet's confirmed balance in sats" in {
      (() => mockWalletApi.getConfirmedBalance())
        .expects()
        .returning(Future.successful(Bitcoins(50)))

      val route =
        walletRoutes.handleCommand(
          ServerCommand("getconfirmedbalance", Arr(Bool(true)))
        )

      Get() ~> route ~> check {
        assert(contentType == `application/json`)
        assert(responseAs[String] == """{"result":5000000000,"error":null}""")
      }
    }

    "return the wallet's unconfirmed balance" in {
      (() => mockWalletApi.getUnconfirmedBalance())
        .expects()
        .returning(Future.successful(Bitcoins(50)))

      val route =
        walletRoutes.handleCommand(
          ServerCommand("getunconfirmedbalance", Arr(Bool(false)))
        )

      Get() ~> route ~> check {
        assert(contentType == `application/json`)
        assert(responseAs[String] == """{"result":50,"error":null}""")
      }
    }

    val spendingInfoDb = TransactionTestUtil.spendingInfoDb

    "return the wallet's balances in bitcoin" in {

      (() => mockWalletApi.utxoHandling)
        .expects()
        .returning(mockUtxoHandlingApi)
        .anyNumberOfTimes()

      (() => mockWalletApi.getConfirmedBalance())
        .expects()
        .returning(Future.successful(Bitcoins(50)))

      (() => mockWalletApi.getUnconfirmedBalance())
        .expects()
        .returning(Future.successful(Bitcoins(50)))

      (mockWalletApi.utxoHandling
        .listUtxos(_: TxoState))
        .expects(TxoState.Reserved)
        .returning(Future.successful(Vector(spendingInfoDb)))

      val route =
        walletRoutes.handleCommand(
          ServerCommand("getbalances", Arr(Bool(false)))
        )

      Get() ~> route ~> check {
        assert(contentType == `application/json`)
        assert(
          responseAs[String] == """{"result":{"confirmed":50,"unconfirmed":50,"reserved":1,"total":101},"error":null}"""
        )
      }
    }

    "return the wallet's balances in sats" in {
      (() => mockWalletApi.getConfirmedBalance())
        .expects()
        .returning(Future.successful(Bitcoins(50)))

      (() => mockWalletApi.getUnconfirmedBalance())
        .expects()
        .returning(Future.successful(Bitcoins(50)))

      (() => mockWalletApi.utxoHandling)
        .expects()
        .returning(mockUtxoHandlingApi)
        .anyNumberOfTimes()

      (mockWalletApi.utxoHandling
        .listUtxos(_: TxoState))
        .expects(TxoState.Reserved)
        .returning(Future.successful(Vector(spendingInfoDb)))

      val route =
        walletRoutes.handleCommand(
          ServerCommand("getbalances", Arr(Bool(true)))
        )

      Get() ~> route ~> check {
        assert(contentType == `application/json`)
        assert(
          responseAs[String] == """{"result":{"confirmed":5000000000,"unconfirmed":5000000000,"reserved":100000000,"total":10100000000},"error":null}"""
        )
      }
    }

    "return the wallet's unconfirmed balance in sats" in {
      (() => mockWalletApi.getUnconfirmedBalance())
        .expects()
        .returning(Future.successful(Bitcoins(50)))

      val route =
        walletRoutes.handleCommand(
          ServerCommand("getunconfirmedbalance", Arr(Bool(true)))
        )

      Get() ~> route ~> check {
        assert(contentType == `application/json`)
        assert(responseAs[String] == """{"result":5000000000,"error":null}""")
      }
    }

    "check if the wallet is empty" in {
      (() => mockWalletApi.isEmpty())
        .expects()
        .returning(Future.successful(true))

      val route =
        walletRoutes.handleCommand(ServerCommand("isempty", Arr()))

      Get() ~> route ~> check {
        assert(contentType == `application/json`)
        assert(responseAs[String] == """{"result":true,"error":null}""")
      }
    }

    "return the wallet utxos" in {
      (() => mockWalletApi.utxoHandling)
        .expects()
        .returning(mockUtxoHandlingApi)
        .anyNumberOfTimes()

      (() => mockWalletApi.utxoHandling.listUtxos())
        .expects()
        .returning(Future.successful(Vector(spendingInfoDb)))

      val route =
        walletRoutes.handleCommand(ServerCommand("getutxos", Arr()))

      Get() ~> route ~> check {
        assert(contentType == `application/json`)
        assert(
          responseAs[String] == """{"result":[{"outpoint":{"txid":"0000000000000000000000000000000000000000000000000000000000000000","vout":0},"value":100000000}],"error":null}"""
        )
      }
    }

    "return the reserved wallet utxos" in {
      (() => mockWalletApi.utxoHandling)
        .expects()
        .returning(mockUtxoHandlingApi)
        .anyNumberOfTimes()
      (mockWalletApi.utxoHandling
        .listUtxos(_: TxoState))
        .expects(TxoState.Reserved)
        .returning(Future.successful(Vector(spendingInfoDb)))

      val route =
        walletRoutes.handleCommand(ServerCommand("listreservedutxos", Arr()))

      Get() ~> route ~> check {
        assert(contentType == `application/json`)
        assert(
          responseAs[String] == """{"result":[{"outpoint":{"txid":"0000000000000000000000000000000000000000000000000000000000000000","vout":0},"value":100000000}],"error":null}"""
        )
      }
    }

    "return the wallet addresses" in {
      val addressDb = LegacyAddressDb(
        LegacyHDPath(HDCoinType.Testnet, 0, HDChainType.External, 0),
        ECPublicKey.freshPublicKey,
        Sha256Hash160Digest.fromBytes(ByteVector.low(20)),
        testAddress.asInstanceOf[P2PKHAddress],
        testAddress.scriptPubKey
      )

      (() => mockWalletApi.addressHandling)
        .expects()
        .returning(mockAddressHandlingApi)
        .anyNumberOfTimes()

      (() => mockWalletApi.addressHandling.listAddresses())
        .expects()
        .returning(Future.successful(Vector(addressDb)))

      val route =
        walletRoutes.handleCommand(ServerCommand("getaddresses", Arr()))

      Get() ~> route ~> check {
        assert(contentType == `application/json`)
        assert(
          responseAs[
            String
          ] == """{"result":["""" + testAddressStr + """"],"error":null}"""
        )
      }
    }

    "return the wallet's spent addresses" in {
      val addressDb = LegacyAddressDb(
        LegacyHDPath(HDCoinType.Testnet, 0, HDChainType.External, 0),
        ECPublicKey.freshPublicKey,
        Sha256Hash160Digest.fromBytes(ByteVector.low(20)),
        testAddress.asInstanceOf[P2PKHAddress],
        testAddress.scriptPubKey
      )

      (() => mockWalletApi.addressHandling)
        .expects()
        .returning(mockAddressHandlingApi)
        .anyNumberOfTimes()

      (() => mockWalletApi.addressHandling.listSpentAddresses())
        .expects()
        .returning(Future.successful(Vector(addressDb)))

      val route =
        walletRoutes.handleCommand(ServerCommand("getspentaddresses", Arr()))

      Get() ~> route ~> check {
        assert(contentType == `application/json`)
        assert(
          responseAs[
            String
          ] == """{"result":["""" + testAddressStr + """"],"error":null}"""
        )
      }
    }

    "return the wallet's funded addresses" in {
      val addressDb = LegacyAddressDb(
        LegacyHDPath(HDCoinType.Testnet, 0, HDChainType.External, 0),
        ECPublicKey.freshPublicKey,
        Sha256Hash160Digest.fromBytes(ByteVector.low(20)),
        testAddress.asInstanceOf[P2PKHAddress],
        testAddress.scriptPubKey
      )

      (() => mockWalletApi.addressHandling)
        .expects()
        .returning(mockAddressHandlingApi)
        .anyNumberOfTimes()

      (() => mockWalletApi.addressHandling.listFundedAddresses())
        .expects()
        .returning(Future.successful(Vector((addressDb, Satoshis.zero))))

      val route =
        walletRoutes.handleCommand(ServerCommand("getfundedaddresses", Arr()))

      Get() ~> route ~> check {
        assert(contentType == `application/json`)
        assert(
          responseAs[String] ==
            s"""{"result":[{"address":"$testAddressStr","value":0}],"error":null}""".stripMargin
        )
      }
    }

    "return the wallet's unused addresses" in {
      val addressDb = LegacyAddressDb(
        LegacyHDPath(HDCoinType.Testnet, 0, HDChainType.External, 0),
        ECPublicKey.freshPublicKey,
        Sha256Hash160Digest.fromBytes(ByteVector.low(20)),
        testAddress.asInstanceOf[P2PKHAddress],
        testAddress.scriptPubKey
      )

      (() => mockWalletApi.addressHandling)
        .expects()
        .returning(mockAddressHandlingApi)
        .anyNumberOfTimes()

      (() => mockWalletApi.addressHandling.listUnusedAddresses())
        .expects()
        .returning(Future.successful(Vector(addressDb)))

      val route =
        walletRoutes.handleCommand(ServerCommand("getunusedaddresses", Arr()))

      Get() ~> route ~> check {
        assert(contentType == `application/json`)
        assert(
          responseAs[
            String
          ] == """{"result":["""" + testAddressStr + """"],"error":null}"""
        )
      }
    }

    "return the wallet accounts" in {

      (() => mockWalletApi.accountHandling)
        .expects()
        .returning(mockAccountHandlingApi)
        .anyNumberOfTimes()

      (() => mockWalletApi.accountHandling.listAccounts())
        .expects()
        .returning(Future.successful(Vector(accountDb)))

      val route =
        walletRoutes.handleCommand(ServerCommand("getaccounts", Arr()))

      Get() ~> route ~> check {
        assert(contentType == `application/json`)
        assert(
          responseAs[
            String
          ] == """{"result":["""" + xpub.toString + """"],"error":null}"""
        )
      }
    }

    "return a new address" in {
      (() => mockWalletApi.addressHandling)
        .expects()
        .returning(mockAddressHandlingApi)
        .anyNumberOfTimes()

      (() => mockWalletApi.addressHandling.getNewAddress())
        .expects()
        .returning(Future.successful(testAddress))

      val route =
        walletRoutes.handleCommand(ServerCommand("getnewaddress", Arr()))

      Get() ~> route ~> check {
        assert(contentType == `application/json`)
        assert(
          responseAs[
            String
          ] == """{"result":"""" + testAddressStr + """","error":null}"""
        )
      }
    }

    "get address info" in {

      val key = ECPublicKey.freshPublicKey
      val hdPath = HDPath.fromString("m/84'/1'/0'/0/0")

      (() => mockWalletApi.addressHandling)
        .expects()
        .returning(mockAddressHandlingApi)
        .anyNumberOfTimes()

      (mockWalletApi.addressHandling
        .getAddressInfo(_: BitcoinAddress))
        .expects(testAddress)
        .returning(Future.successful(Some(AddressInfo(key, RegTest, hdPath))))

      val route =
        walletRoutes.handleCommand(
          ServerCommand("getaddressinfo", Arr(Str(testAddressStr)))
        )

      Get() ~> route ~> check {
        assert(contentType == `application/json`)
        assert(
          responseAs[
            String
          ] == s"""{"result":{"pubkey":"${key.hex}","path":"${hdPath.toString}"},"error":null}"""
        )
      }
    }

    "return a new address with a label" in {

      (() => mockWalletApi.addressHandling)
        .expects()
        .returning(mockAddressHandlingApi)
        .anyNumberOfTimes()

      (mockWalletApi.addressHandling
        .getNewAddress(_: Vector[AddressTag]))
        .expects(Vector(testLabel))
        .returning(Future.successful(testAddress))

      val route =
        walletRoutes.handleCommand(
          ServerCommand("getnewaddress", Arr(Str(testLabel.name)))
        )

      Get() ~> route ~> check {
        assert(contentType == `application/json`)
        assert(
          responseAs[
            String
          ] == """{"result":"""" + testAddressStr + """","error":null}"""
        )
      }
    }

    "lock unspent" in {
      (() => mockWalletApi.utxoHandling)
        .expects()
        .returning(mockUtxoHandlingApi)
        .anyNumberOfTimes()

      (() => mockWalletApi.utxoHandling.listUtxos())
        .expects()
        .returning(Future.successful(Vector(spendingInfoDb)))
        .anyNumberOfTimes()

      (mockWalletApi.utxoHandling
        .listUtxos(_: TxoState))
        .expects(TxoState.Reserved)
        .returning(Future.successful(Vector(spendingInfoDb)))
        .anyNumberOfTimes()

      (() => mockWalletApi.utxoHandling)
        .expects()
        .returning(mockUtxoHandlingApi)
        .anyNumberOfTimes()

      (mockWalletApi.utxoHandling
        .markUTXOsAsReserved(_: Vector[SpendingInfoDb]))
        .expects(Vector(spendingInfoDb))
        .returning(Future.successful(Vector(spendingInfoDb)))
        .anyNumberOfTimes()

      (mockWalletApi.utxoHandling
        .unmarkUTXOsAsReserved(_: Vector[SpendingInfoDb]))
        .expects(Vector(spendingInfoDb))
        .returning(Future.successful(Vector(spendingInfoDb)))
        .anyNumberOfTimes()

      val route1 =
        walletRoutes.handleCommand(
          ServerCommand("lockunspent", Arr(Bool(false), Arr()))
        )

      Get() ~> route1 ~> check {
        assert(contentType == `application/json`)
        assert(responseAs[String] == """{"result":true,"error":null}""")
      }

      val route2 =
        walletRoutes.handleCommand(
          ServerCommand("lockunspent", Arr(Bool(true), Arr()))
        )

      Get() ~> route2 ~> check {
        assert(contentType == `application/json`)
        assert(responseAs[String] == """{"result":true,"error":null}""")
      }

      val obj = mutable.LinkedHashMap(
        "txid" -> Str(spendingInfoDb.outPoint.txIdBE.hex),
        "vout" -> Num(spendingInfoDb.outPoint.vout.toInt)
      )

      val route3 =
        walletRoutes.handleCommand(
          ServerCommand("lockunspent", Arr(Bool(true), Arr(obj)))
        )

      Get() ~> route3 ~> check {
        assert(contentType == `application/json`)
        assert(responseAs[String] == """{"result":true,"error":null}""")
      }

      val route4 =
        walletRoutes.handleCommand(
          ServerCommand("lockunspent", Arr(Bool(true), Arr(obj)))
        )

      Get() ~> route4 ~> check {
        assert(contentType == `application/json`)
        assert(responseAs[String] == """{"result":true,"error":null}""")
      }
    }

    "label an address" in {
      (() => mockWalletApi.addressHandling)
        .expects()
        .returning(mockAddressHandlingApi)
        .anyNumberOfTimes()

      (mockWalletApi.addressHandling
        .tagAddress(_: BitcoinAddress, _: AddressTag))
        .expects(testAddress, testLabel)
        .returning(Future.successful(AddressTagDb(testAddress, testLabel)))

      val route =
        walletRoutes.handleCommand(
          ServerCommand(
            "labeladdress",
            Arr(Str(testAddressStr), Str(testLabel.name))
          )
        )

      Get() ~> route ~> check {
        assert(contentType == `application/json`)
        assert(
          responseAs[
            String
          ] == """{"result":"""" + s"Added label \'${testLabel.name}\' to $testAddressStr" + """","error":null}"""
        )
      }
    }

    "get address tags" in {
      (() => mockWalletApi.addressHandling)
        .expects()
        .returning(mockAddressHandlingApi)
        .anyNumberOfTimes()

      (mockWalletApi.addressHandling
        .getAddressTags(_: BitcoinAddress))
        .expects(testAddress)
        .returning(
          Future.successful(Vector(AddressTagDb(testAddress, testLabel)))
        )

      val route =
        walletRoutes.handleCommand(
          ServerCommand("getaddresstags", Arr(Str(testAddressStr)))
        )

      Get() ~> route ~> check {
        assert(contentType == `application/json`)
        assert(
          responseAs[
            String
          ] == """{"result":["""" + testLabel.name + """"],"error":null}"""
        )
      }
    }

    "get address label" in {
      (() => mockWalletApi.addressHandling)
        .expects()
        .returning(mockAddressHandlingApi)
        .anyNumberOfTimes()

      (mockWalletApi.addressHandling
        .getAddressTags(_: BitcoinAddress, _: AddressTagType))
        .expects(testAddress, AddressLabelTagType)
        .returning(
          Future.successful(Vector(AddressTagDb(testAddress, testLabel)))
        )

      val route =
        walletRoutes.handleCommand(
          ServerCommand("getaddresslabel", Arr(Str(testAddressStr)))
        )

      Get() ~> route ~> check {
        assert(contentType == `application/json`)
        assert(
          responseAs[
            String
          ] == """{"result":["""" + testLabel.name + """"],"error":null}"""
        )
      }
    }

    "get address labels" in {
      (() => mockWalletApi.addressHandling)
        .expects()
        .returning(mockAddressHandlingApi)
        .anyNumberOfTimes()

      (() => mockWalletApi.addressHandling.getAddressTags())
        .expects()
        .returning(
          Future.successful(Vector(AddressTagDb(testAddress, testLabel)))
        )

      val route =
        walletRoutes.handleCommand(ServerCommand("getaddresslabels", Arr()))

      Get() ~> route ~> check {
        assert(contentType == `application/json`)
        assert(
          responseAs[String] == """{"result":[{"address":"1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa","labels":["test"]}],"error":null}"""
        )
      }
    }

    "drop address label" in {
      val labelName = "label"

      (() => mockWalletApi.addressHandling)
        .expects()
        .returning(mockAddressHandlingApi)
        .anyNumberOfTimes()

      (mockWalletApi.addressHandling
        .dropAddressTagName(_: BitcoinAddress, _: AddressTagName))
        .expects(testAddress, AddressLabelTagName(labelName))
        .returning(Future.successful(1))

      val route =
        walletRoutes.handleCommand(
          ServerCommand(
            "dropaddresslabel",
            Arr(Str(testAddressStr), Str(labelName))
          )
        )

      Get() ~> route ~> check {
        assert(contentType == `application/json`)
        assert(
          responseAs[
            String
          ] == """{"result":"""" + "1 label dropped" + """","error":null}"""
        )
      }
    }

    "drop address labels with no labels" in {
      (() => mockWalletApi.addressHandling)
        .expects()
        .returning(mockAddressHandlingApi)
        .anyNumberOfTimes()

      (mockWalletApi.addressHandling
        .dropAddressTagType(_: BitcoinAddress, _: AddressTagType))
        .expects(testAddress, AddressLabelTagType)
        .returning(Future.successful(0))

      val route =
        walletRoutes.handleCommand(
          ServerCommand("dropaddresslabels", Arr(Str(testAddressStr)))
        )

      Get() ~> route ~> check {
        assert(contentType == `application/json`)
        assert(
          responseAs[String] == """{"result":"""" + "Address had no labels" + """","error":null}"""
        )
      }
    }

    "drop address labels with 1 label" in {
      (() => mockWalletApi.addressHandling)
        .expects()
        .returning(mockAddressHandlingApi)
        .anyNumberOfTimes()

      (mockWalletApi.addressHandling
        .dropAddressTagType(_: BitcoinAddress, _: AddressTagType))
        .expects(testAddress, AddressLabelTagType)
        .returning(Future.successful(1))

      val route =
        walletRoutes.handleCommand(
          ServerCommand("dropaddresslabels", Arr(Str(testAddressStr)))
        )

      Get() ~> route ~> check {
        assert(contentType == `application/json`)
        assert(
          responseAs[
            String
          ] == """{"result":"""" + "1 label dropped" + """","error":null}"""
        )
      }
    }

    "drop address labels with 2 labels" in {
      (() => mockWalletApi.addressHandling)
        .expects()
        .returning(mockAddressHandlingApi)
        .anyNumberOfTimes()

      (mockWalletApi.addressHandling
        .dropAddressTagType(_: BitcoinAddress, _: AddressTagType))
        .expects(testAddress, AddressLabelTagType)
        .returning(Future.successful(2))

      val route =
        walletRoutes.handleCommand(
          ServerCommand("dropaddresslabels", Arr(Str(testAddressStr)))
        )

      Get() ~> route ~> check {
        assert(contentType == `application/json`)
        assert(
          responseAs[
            String
          ] == """{"result":"""" + "2 labels dropped" + """","error":null}"""
        )
      }
    }

    "send a raw transaction" in {
      val tx = Transaction(
        "020000000258e87a21b56daf0c23be8e7070456c336f7cbaa5c8757924f545887bb2abdd750000000000ffffffff838d0427d0ec650a68aa46bb0b098aea4422c071b2ca78352a077959d07cea1d0100000000ffffffff0270aaf00800000000160014d85c2b71d0060b09c9886aeb815e50991dda124d00e1f5050000000016001400aea9a2e5f0f876a588df5546e8742d1d87008f00000000"
      )

      (mockWalletApi
        .broadcastTransaction(_: Transaction))
        .expects(tx)
        .returning(Future.unit)
        .anyNumberOfTimes()

      val route =
        walletRoutes.handleCommand(
          ServerCommand("sendrawtransaction", Arr(Str(tx.hex)))
        )

      Get() ~> route ~> check {
        assert(contentType == `application/json`)
        assert(
          responseAs[
            String
          ] == s"""{"result":"${tx.txIdBE.hex}","error":null}"""
        )
      }
    }

    "get a transaction" in {
      val tx = Transaction(
        "020000000258e87a21b56daf0c23be8e7070456c336f7cbaa5c8757924f545887bb2abdd750000000000ffffffff838d0427d0ec650a68aa46bb0b098aea4422c071b2ca78352a077959d07cea1d0100000000ffffffff0270aaf00800000000160014d85c2b71d0060b09c9886aeb815e50991dda124d00e1f5050000000016001400aea9a2e5f0f876a588df5546e8742d1d87008f00000000"
      )

      val txDb = TransactionDbHelper.fromTransaction(tx, None)

      (() => mockWalletApi.transactionProcessing)
        .expects()
        .returning(mockTransactionProcessingApi)
        .anyNumberOfTimes()

      (mockWalletApi.transactionProcessing
        .findByTxIds(_: Vector[DoubleSha256DigestBE]))
        .expects(Vector(tx.txIdBE))
        .returning(Future.successful(Vector(txDb)))

      val route =
        walletRoutes.handleCommand(
          ServerCommand("gettransaction", Arr(Str(tx.txIdBE.hex)))
        )

      Get() ~> route ~> check {
        assert(contentType == `application/json`)
        assert(responseAs[String] == s"""{"result":"${tx.hex}","error":null}""")
      }
    }

    val contractInfoDigests =
      Vector(
        "ffbbcde836cee437a2fa4ef7db1ea3d79ca71c0c821d2a197dda51bc6534f562",
        "e770f42c578084a4a096ce1085f7fe508f8d908d2c5e6e304b2c3eab9bc973ea"
      )

    val contractDesc = EnumContractDescriptor.fromStringVec(
      Vector(
        (contractInfoDigests.head, Satoshis(5)),
        (contractInfoDigests.last, Satoshis(4))
      )
    )

    val contractMaturity = 1580323752
    val contractTimeout = 1581323752

    val dummyKey = ECPrivateKey.freshPrivateKey
    val dummyPubKey = dummyKey.publicKey

    val dummySig = dummyKey.sign(Sha256DigestBE.empty)

    val dummyPartialSig = PartialSignature(dummyPubKey, dummySig)

    val dummyScriptWitness: P2WPKHWitnessV0 = {
      P2WPKHWitnessV0(dummyPartialSig.pubKey, dummyPartialSig.signature)
    }

    val dummyOracleSig = SchnorrDigitalSignature(
      "65ace55b5d073cc7a1c783fa8c254692c421270fa988247e3c87627ffe804ed06c20bf779da91f82da3311b1d9e0a3a513409a15c66f25201280751177dad24c"
    )

    val dummyOracleAttestment =
      OracleAttestmentV0TLV(
        "eventId",
        dummyPubKey.schnorrPublicKey,
        OrderedSchnorrSignatures(dummyOracleSig).toVector,
        Vector("outcome")
      )

    lazy val winStr: String = "WIN"

    lazy val loseStr: String = "LOSE"

    val dummyAddress = "bc1quq29mutxkgxmjfdr7ayj3zd9ad0ld5mrhh89l2"

    val dummyDLCKeys =
      DLCPublicKeys(dummyPubKey, BitcoinAddress(dummyAddress))

    val paramHash = Sha256DigestBE(
      "de462f212d95ca4cf5db54eee08f14be0ee934e9ecfc6e9b7014ecfa51ba7b66"
    )

    val contractId = ByteVector.fromValidHex(
      "4c6eb53573aae186dbb1a93274cc00c795473d7cfe2cb69e7d185ee28a39b919"
    )

    val wtx: WitnessTransaction = WitnessTransaction(
      "02000000000101a2619b5d58b209439c937e563018efcf174063ca011e4f177a5b14e5ba76211c0100000017160014614e9b96cbc7477eda98f0936385ded6b636f74efeffffff024e3f57c4000000001600147cf00288c2c1b3c5cdf275db532a1c15c514bb2fae1112000000000016001440efb02597b9e9d9bc968f12cec3347e2e264c570247304402205768c2ac8178539fd44721e2a7541bedd6b55654f095143514624203c133f7e8022060d51f33fc2b5c1f51f26c7f703de21be6246dbb5fb7e1c6919aae6d442610c6012102b99a63f166ef53ca67a5c55ae969e80c33456e07189f8457e3438f000be42c19307d1900"
    )

    val fundingInput: DLCFundingInputP2WPKHV0 =
      DLCFundingInputP2WPKHV0(UInt64.zero, wtx, UInt32.zero, UInt32.zero)

    val announcementTLV = OracleAnnouncementV0TLV(
      "fdd824fd0118c6a52b0901a23fe7d2febbb492e66d4cd4783483aeec1cae374c3e8e6bf779dc471104aa249f7904732d0a87e9c6aa51ff5705cf46b41bc2d55d83ad1fac998ae096a7f99df21b4a43eb92b07110dbab3aa53c81b9ba08755653512ac5246a09fdd822b40001aec3c6498dfedb0db322644e97be338417f5a552c4487b037130bf19f01a069b00000000fdd806840002406666626263646538333663656534333761326661346566376462316561336437396361373163306338323164326131393764646135316263363533346635363240653737306634326335373830383461346130393663653130383566376665353038663864393038643263356536653330346232633365616239626339373365610564756d6d79"
    )

    val oracleInfo = EnumSingleOracleInfo(announcementTLV)

    val dummyOutcomeSigs =
      Vector(
        EnumOracleOutcome(
          Vector(oracleInfo),
          EnumOutcome(winStr)
        ).sigPoint -> ECAdaptorSignature.dummy,
        EnumOracleOutcome(
          Vector(oracleInfo),
          EnumOutcome(loseStr)
        ).sigPoint -> ECAdaptorSignature.dummy
      )

    val contractInfo = SingleContractInfo(contractDesc, oracleInfo)
    val contractInfoTLV = contractInfo.toTLV

    val offer = DLCOffer(
      protocolVersionOpt = DLCOfferTLV.currentVersionOpt,
      contractInfo = contractInfo,
      pubKeys = dummyDLCKeys,
      collateral = Satoshis(3),
      fundingInputs =
        Vector(fundingInput, fundingInput.copy(inputSerialId = UInt64.max)),
      changeAddress = Bech32Address.fromString(dummyAddress),
      payoutSerialId = UInt64.zero,
      changeSerialId = UInt64.zero,
      fundOutputSerialId = UInt64.one,
      feeRate = SatoshisPerVirtualByte.one,
      timeouts =
        DLCTimeouts(BlockStamp(contractMaturity), BlockStamp(contractTimeout))
    )

    "create a dlc offer" in {
      (
        mockWalletApi
          .createDLCOffer(
            _: ContractInfoTLV,
            _: Satoshis,
            _: Option[SatoshisPerVirtualByte],
            _: UInt32,
            _: UInt32,
            _: Option[InetSocketAddress],
            _: Option[BitcoinAddress],
            _: Option[BitcoinAddress]
          )
        )
        .expects(
          contractInfoTLV,
          Satoshis(2500),
          Some(SatoshisPerVirtualByte(Satoshis.one)),
          UInt32(contractMaturity),
          UInt32(contractTimeout),
          None,
          None,
          None
        )
        .returning(Future.successful(offer))

      val route = walletRoutes.handleCommand(
        ServerCommand(
          "createdlcoffer",
          Arr(
            Str(contractInfoTLV.hex),
            Num(2500),
            Num(1),
            Num(contractMaturity),
            Num(contractTimeout)
          )
        )
      )

      Post() ~> route ~> check {
        assert(contentType == `application/json`)
        assert(responseAs[String] == s"""{"result":"${LnMessage(
            offer.toTLV
          ).hex}","error":null}""")
      }

      val badRoute = walletRoutes.handleCommand(
        ServerCommand(
          "createdlcoffer",
          Arr(
            Str(contractInfoTLV.hex),
            Num(2500),
            Num(1),
            Str("abcd"),
            Num(contractTimeout)
          )
        )
      )

      Post() ~> badRoute ~> check {
        assert(contentType == `application/json`)
        assert(status == StatusCodes.BadRequest)
        assert(
          responseAs[
            String
          ] == s"""{"result":null,"error":"For input string: \\"abcd\\""}"""
        )
      }
    }

    val accept = DLCAccept(
      collateral = Satoshis(1000),
      pubKeys = dummyDLCKeys,
      fundingInputs = Vector(fundingInput),
      changeAddress = Bech32Address
        .fromString(dummyAddress),
      payoutSerialId = UInt64.zero,
      changeSerialId = UInt64.zero,
      cetSigs = CETSignatures(dummyOutcomeSigs),
      refundSig = DLCWalletUtil.minimalPartialSig,
      negotiationFields = DLCAccept.NoNegotiationFields,
      tempContractId = Sha256Digest.empty
    )

    "accept a dlc offer" in {
      (mockWalletApi
        .acceptDLCOffer(
          _: DLCOfferTLV,
          _: Option[InetSocketAddress],
          _: Option[BitcoinAddress],
          _: Option[BitcoinAddress]
        ))
        .expects(offer.toTLV, None, None, None)
        .returning(Future.successful(accept))

      val route = walletRoutes.handleCommand(
        ServerCommand("acceptdlcoffer", Arr(Str(LnMessage(offer.toTLV).hex)))
      )

      Post() ~> route ~> check {
        assert(contentType == `application/json`)
        assert(responseAs[String] == s"""{"result":"${LnMessage(
            accept.toTLV
          ).hex}","error":null}""")
      }
    }

    val sign = DLCSign(
      CETSignatures(dummyOutcomeSigs),
      dummyPartialSig,
      FundingSignatures(Vector((EmptyTransactionOutPoint, dummyScriptWitness))),
      paramHash.bytes
    )

    "sign a dlc" in {

      (mockWalletApi
        .signDLC(_: DLCAcceptTLV))
        .expects(accept.toTLV)
        .returning(Future.successful(sign))

      val route = walletRoutes.handleCommand(
        ServerCommand("signdlc", Arr(Str(LnMessage(accept.toTLV).hex)))
      )

      Post() ~> route ~> check {
        assert(contentType == `application/json`)
        assert(responseAs[String] == s"""{"result":"${LnMessage(
            sign.toTLV
          ).hex}","error":null}""")
      }
    }

    "add dlc sigs" in {
      (mockWalletApi
        .addDLCSigs(_: DLCSignTLV))
        .expects(sign.toTLV)
        .returning(
          Future.successful(
            DLCWalletUtil.sampleDLCDb.copy(contractIdOpt = Some(contractId))
          )
        )

      val route = walletRoutes.handleCommand(
        ServerCommand("adddlcsigs", Arr(Str(LnMessage(sign.toTLV).hex)))
      )

      Post() ~> route ~> check {
        assert(contentType == `application/json`)
        assert(
          responseAs[
            String
          ] == s"""{"result":"Successfully added sigs to DLC ${contractId.toHex}","error":null}"""
        )
      }
    }

    "add dlc sigs and broadcast" in {
      (mockWalletApi
        .addDLCSigs(_: DLCSignTLV))
        .expects(sign.toTLV)
        .returning(
          Future.successful(
            DLCWalletUtil.sampleDLCDb.copy(contractIdOpt = Some(contractId))
          )
        )

      (mockWalletApi
        .broadcastDLCFundingTx(_: ByteVector))
        .expects(sign.contractId)
        .returning(Future.successful(EmptyTransaction))

      val route = walletRoutes.handleCommand(
        ServerCommand(
          "adddlcsigsandbroadcast",
          Arr(Str(LnMessage(sign.toTLV).hex))
        )
      )

      Post() ~> route ~> check {
        assert(contentType == `application/json`)
        assert(
          responseAs[
            String
          ] == s"""{"result":"${EmptyTransaction.txIdBE.hex}","error":null}"""
        )
      }
    }

    "get dlc funding tx" in {
      (mockWalletApi
        .getDLCFundingTx(_: ByteVector))
        .expects(contractId)
        .returning(Future.successful(EmptyTransaction))

      val route = walletRoutes.handleCommand(
        ServerCommand("getdlcfundingtx", Arr(Str(contractId.toHex)))
      )

      Post() ~> route ~> check {
        assert(contentType == `application/json`)
        assert(
          responseAs[
            String
          ] == s"""{"result":"${EmptyTransaction.hex}","error":null}"""
        )
      }
    }

    "broadcast dlc funding tx" in {
      (mockWalletApi
        .broadcastDLCFundingTx(_: ByteVector))
        .expects(contractId)
        .returning(Future.successful(EmptyTransaction))

      val route = walletRoutes.handleCommand(
        ServerCommand("broadcastdlcfundingtx", Arr(Str(contractId.toHex)))
      )

      Post() ~> route ~> check {
        assert(contentType == `application/json`)
        assert(
          responseAs[
            String
          ] == s"""{"result":"${EmptyTransaction.txIdBE.hex}","error":null}"""
        )
      }
    }

    "execute a dlc" in {
      (mockWalletApi
        .executeDLC(_: ByteVector, _: Seq[OracleAttestmentTLV]))
        .expects(contractId, Vector(dummyOracleAttestment))
        .returning(Future.successful(Some(EmptyTransaction)))

      val route = walletRoutes.handleCommand(
        ServerCommand(
          "executedlc",
          Arr(
            Str(contractId.toHex),
            Arr(Str(dummyOracleAttestment.hex)),
            Bool(true)
          )
        )
      )

      Post() ~> route ~> check {
        assert(contentType == `application/json`)
        assert(
          responseAs[
            String
          ] == s"""{"result":"${EmptyTransaction.hex}","error":null}"""
        )
      }
    }

    "execute a dlc that is not in the wallet and handle gracefully" in {
      (mockWalletApi
        .executeDLC(_: ByteVector, _: Seq[OracleAttestmentTLV]))
        .expects(contractId, Vector(dummyOracleAttestment))
        .returning(Future.successful(None))

      val route = walletRoutes.handleCommand(
        ServerCommand(
          "executedlc",
          Arr(
            Str(contractId.toHex),
            Arr(Str(dummyOracleAttestment.hex)),
            Bool(true)
          )
        )
      )

      Post() ~> route ~> check {
        assert(contentType == `application/json`)
        assert(
          responseAs[
            String
          ] == s"""{"result":null,"error":"Cannot execute DLC with contractId=${contractId.toHex}"}"""
        )
      }
    }

    "execute a dlc with multiple sigs" in {
      (mockWalletApi
        .executeDLC(_: ByteVector, _: Seq[OracleAttestmentTLV]))
        .expects(
          contractId,
          Vector(dummyOracleAttestment, dummyOracleAttestment)
        )
        .returning(Future.successful(Some(EmptyTransaction)))

      val route = walletRoutes.handleCommand(
        ServerCommand(
          "executedlc",
          Arr(
            Str(contractId.toHex),
            Arr(Str(dummyOracleAttestment.hex), Str(dummyOracleAttestment.hex)),
            Bool(true)
          )
        )
      )

      Post() ~> route ~> check {
        assert(contentType == `application/json`)
        assert(
          responseAs[
            String
          ] == s"""{"result":"${EmptyTransaction.hex}","error":null}"""
        )
      }
    }

    "execute a dlc with broadcast" in {
      (mockWalletApi
        .executeDLC(_: ByteVector, _: Seq[OracleAttestmentTLV]))
        .expects(contractId, Vector(dummyOracleAttestment))
        .returning(Future.successful(Some(EmptyTransaction)))

      (mockWalletApi.broadcastTransaction _)
        .expects(EmptyTransaction)
        .returning(FutureUtil.unit)
        .anyNumberOfTimes()

      val route = walletRoutes.handleCommand(
        ServerCommand(
          "executedlc",
          Arr(
            Str(contractId.toHex),
            Arr(Str(dummyOracleAttestment.hex)),
            Bool(false)
          )
        )
      )

      Post() ~> route ~> check {
        assert(contentType == `application/json`)
        assert(
          responseAs[
            String
          ] == s"""{"result":"${EmptyTransaction.txIdBE.hex}","error":null}"""
        )
      }
    }

    "execute a dlc refund" in {
      (mockWalletApi
        .executeDLCRefund(_: ByteVector))
        .expects(contractId)
        .returning(Future.successful(EmptyTransaction))

      val route = walletRoutes.handleCommand(
        ServerCommand(
          "executedlcrefund",
          Arr(Str(contractId.toHex), Bool(true))
        )
      )

      Post() ~> route ~> check {
        assert(contentType == `application/json`)
        assert(
          responseAs[
            String
          ] == s"""{"result":"${EmptyTransaction.hex}","error":null}"""
        )
      }
    }

    "execute a dlc refund with broadcast" in {
      (mockWalletApi
        .executeDLCRefund(_: ByteVector))
        .expects(contractId)
        .returning(Future.successful(EmptyTransaction))

      (mockWalletApi.broadcastTransaction _)
        .expects(EmptyTransaction)
        .returning(FutureUtil.unit)
        .anyNumberOfTimes()

      val route = walletRoutes.handleCommand(
        ServerCommand(
          "executedlcrefund",
          Arr(Str(contractId.toHex), Bool(false))
        )
      )

      Post() ~> route ~> check {
        assert(contentType == `application/json`)
        assert(
          responseAs[
            String
          ] == s"""{"result":"${EmptyTransaction.txIdBE.hex}","error":null}"""
        )
      }
    }

    "send to an address" in {
      // positive cases

      (() => mockWalletApi.sendFundsHandling)
        .expects()
        .returning(mockSendFundsHandlingApi)
        .anyNumberOfTimes()

      (() => mockWalletApi.sendFundsHandling.accountHandling)
        .expects()
        .returning(mockAccountHandlingApi)
        .anyNumberOfTimes()

      (() =>
        mockWalletApi.sendFundsHandling.accountHandling.getDefaultAccount())
        .expects()
        .returning(Future.successful(accountDb))
        .anyNumberOfTimes()

      (mockWalletApi.sendFundsHandling
        .sendWithAlgo(_: BitcoinAddress,
                      _: CurrencyUnit,
                      _: FeeUnit,
                      _: CoinSelectionAlgo,
                      _: AccountDb,
                      _: Vector[AddressTag]))
        .expects(testAddress,
                 Bitcoins(100),
                 fee,
                 CoinSelectionAlgo.LeastWaste,
                 accountDb,
                 Vector.empty)
        .returning(Future.successful(EmptyTransaction))

      (mockWalletApi.broadcastTransaction _)
        .expects(EmptyTransaction)
        .returning(Future.unit)
        .anyNumberOfTimes()

      val route = walletRoutes.handleCommand(
        ServerCommand(
          "sendtoaddress",
          Arr(Str(testAddressStr), Num(100), Num(4), Bool(false))
        )
      )

      Post() ~> route ~> check {
        assert(contentType == `application/json`)
        assert(
          responseAs[String] == """{"result":"0000000000000000000000000000000000000000000000000000000000000000","error":null}"""
        )
      }

      // negative cases

      val route1 = walletRoutes.handleCommand(
        ServerCommand("sendtoaddress", Arr(Null, Null, Null, Bool(false)))
      )

      Post() ~> route1 ~> check {
        assert(contentType == `application/json`)
        assert(status == StatusCodes.BadRequest)
        assert(
          responseAs[
            String
          ] == s"""{"result":null,"error":"Expected ujson.Str (data: null)"}"""
        )
      }

      val route2 = walletRoutes.handleCommand(
        ServerCommand("sendtoaddress", Arr("Null", Null, Null, Bool(false)))
      )

      Post() ~> route2 ~> check {
        assert(contentType == `application/json`)
        assert(status == StatusCodes.BadRequest)
        assert(
          responseAs[String] == s"""{"result":null,"error":"Expected a valid address (data: \\"Null\\")"}"""
        )
      }

      val route3 = walletRoutes.handleCommand(
        ServerCommand(
          "sendtoaddress",
          Arr(Str(testAddressStr), Null, Null, Bool(false))
        )
      )

      Post() ~> route3 ~> check {
        assert(contentType == `application/json`)
        assert(status == StatusCodes.BadRequest)
        assert(
          responseAs[
            String
          ] == s"""{"result":null,"error":"Expected ujson.Num (data: null)"}"""
        )
      }

      val route4 = walletRoutes.handleCommand(
        ServerCommand(
          "sendtoaddress",
          Arr(Str(testAddressStr), Str("abc"), Null, Bool(false))
        )
      )

      Post() ~> route4 ~> check {
        assert(contentType == `application/json`)
        assert(status == StatusCodes.BadRequest)
        assert(
          responseAs[String] == s"""{"result":null,"error":"Expected ujson.Num (data: \\"abc\\")"}"""
        )
      }

    }

    "send from outpoints" in {
      // positive cases

      (() => mockWalletApi.sendFundsHandling)
        .expects()
        .returning(mockSendFundsHandlingApi)
        .anyNumberOfTimes()

      (() => mockWalletApi.sendFundsHandling.accountHandling)
        .expects()
        .returning(mockAccountHandlingApi)
        .anyNumberOfTimes()

      (() =>
        mockWalletApi.sendFundsHandling.accountHandling.getDefaultAccount())
        .expects()
        .returning(Future.successful(accountDb))
        .anyNumberOfTimes()

      (mockWalletApi.sendFundsHandling
        .sendFromOutPoints(
          _: Vector[TransactionOutPoint],
          _: BitcoinAddress,
          _: CurrencyUnit,
          _: FeeUnit,
          _: AccountDb,
          _: Vector[AddressTag]
        ))
        .expects(
          Vector.empty[TransactionOutPoint],
          testAddress,
          Bitcoins(100),
          fee,
          accountDb,
          Vector.empty
        )
        .returning(Future.successful(EmptyTransaction))

      (mockWalletApi.broadcastTransaction _)
        .expects(EmptyTransaction)
        .returning(Future.unit)
        .anyNumberOfTimes()

      val route = walletRoutes.handleCommand(
        ServerCommand(
          "sendfromoutpoints",
          Arr(Arr(), Str(testAddressStr), Num(100), Num(4))
        )
      )

      Post() ~> route ~> check {
        assert(contentType == `application/json`)
        assert(
          responseAs[String] == """{"result":"0000000000000000000000000000000000000000000000000000000000000000","error":null}"""
        )
      }

      // negative cases

      val route1 = walletRoutes.handleCommand(
        ServerCommand("sendfromoutpoints", Arr(Arr(), Null, Null, Null))
      )

      Post() ~> route1 ~> check {
        assert(contentType == `application/json`)
        assert(status == StatusCodes.BadRequest)
        assert(
          responseAs[
            String
          ] == s"""{"result":null,"error":"Expected ujson.Str (data: null)"}"""
        )
      }

      val route2 = walletRoutes.handleCommand(
        ServerCommand("sendfromoutpoints", Arr(Arr(), "Null", Null, Null))
      )

      Post() ~> route2 ~> check {
        assert(contentType == `application/json`)
        assert(status == StatusCodes.BadRequest)
        assert(
          responseAs[String] == s"""{"result":null,"error":"Expected a valid address (data: \\"Null\\")"}"""
        )
      }

      val route3 = walletRoutes.handleCommand(
        ServerCommand(
          "sendfromoutpoints",
          Arr(Arr(), Str(testAddressStr), Null, Null)
        )
      )

      Post() ~> route3 ~> check {
        assert(contentType == `application/json`)
        assert(status == StatusCodes.BadRequest)
        assert(
          responseAs[
            String
          ] == s"""{"result":null,"error":"Expected ujson.Num (data: null)"}"""
        )
      }

      val route4 = walletRoutes.handleCommand(
        ServerCommand(
          "sendfromoutpoints",
          Arr(Arr(), Str(testAddressStr), Str("abc"), Null)
        )
      )

      Post() ~> route4 ~> check {
        assert(contentType == `application/json`)
        assert(status == StatusCodes.BadRequest)
        assert(
          responseAs[String] == s"""{"result":null,"error":"Expected ujson.Num (data: \\"abc\\")"}"""
        )
      }

      val route5 = walletRoutes.handleCommand(
        ServerCommand(
          "sendfromoutpoints",
          Arr(Null, Str(testAddressStr), Num(100), Num(4))
        )
      )

      Post() ~> route5 ~> check {
        assert(contentType == `application/json`)
        assert(status == StatusCodes.BadRequest)
        assert(
          responseAs[
            String
          ] == s"""{"result":null,"error":"Expected ujson.Arr (data: null)"}"""
        )
      }
    }

    "sweep wallet" in {
      (() => mockWalletApi.sendFundsHandling)
        .expects()
        .returning(mockSendFundsHandlingApi)
        .anyNumberOfTimes()

      (() => mockWalletApi.sendFundsHandling.utxoHandling)
        .expects()
        .returning(mockUtxoHandlingApi)
        .anyNumberOfTimes()

      (() => mockWalletApi.sendFundsHandling.utxoHandling.listUtxos())
        .expects()
        .returning(Future.successful(Vector.empty))

      (mockWalletApi.sendFundsHandling
        .sendFromOutPoints(_: Vector[TransactionOutPoint],
                           _: BitcoinAddress,
                           _: FeeUnit))
        .expects(Vector.empty, testAddress, fee)
        .returning(Future.successful(EmptyTransaction))

      (mockWalletApi.broadcastTransaction _)
        .expects(EmptyTransaction)
        .returning(Future.unit)
        .anyNumberOfTimes()

      val route = walletRoutes.handleCommand(
        ServerCommand("sweepwallet", Arr(Str(testAddressStr), Num(4)))
      )

      Post() ~> route ~> check {
        assert(contentType == `application/json`)
        assert(
          responseAs[String] == """{"result":"0000000000000000000000000000000000000000000000000000000000000000","error":null}"""
        )
      }
    }

    "send with algo" in {
      // positive cases

      (() => mockWalletApi.sendFundsHandling)
        .expects()
        .returning(mockSendFundsHandlingApi)
        .anyNumberOfTimes()

      (mockWalletApi.sendFundsHandling
        .sendWithAlgo(
          _: BitcoinAddress,
          _: CurrencyUnit,
          _: Option[FeeUnit],
          _: CoinSelectionAlgo
        )(_: ExecutionContext))
        .expects(
          testAddress,
          Bitcoins(100),
          Some(fee),
          CoinSelectionAlgo.AccumulateSmallestViable,
          executor
        )
        .returning(Future.successful(EmptyTransaction))

      (mockWalletApi.broadcastTransaction _)
        .expects(EmptyTransaction)
        .returning(Future.unit)
        .anyNumberOfTimes()

      val route = walletRoutes.handleCommand(
        ServerCommand(
          "sendwithalgo",
          Arr(
            Str(testAddressStr),
            Num(100),
            Num(4),
            Str("AccumulateSmallestViable")
          )
        )
      )

      Post() ~> route ~> check {
        assert(contentType == `application/json`)
        assert(
          responseAs[String] == """{"result":"0000000000000000000000000000000000000000000000000000000000000000","error":null}"""
        )
      }

      // negative cases

      val route1 = walletRoutes.handleCommand(
        ServerCommand("sendwithalgo", Arr(Null, Null, Null, Null))
      )

      Post() ~> route1 ~> check {
        assert(contentType == `application/json`)
        assert(status == StatusCodes.BadRequest)
        assert(
          responseAs[
            String
          ] == s"""{"result":null,"error":"Expected ujson.Str (data: null)"}"""
        )
      }

      val route2 = walletRoutes.handleCommand(
        ServerCommand("sendwithalgo", Arr("Null", Null, Null, Null))
      )

      Post() ~> route2 ~> check {
        assert(contentType == `application/json`)
        assert(status == StatusCodes.BadRequest)
        assert(
          responseAs[String] == s"""{"result":null,"error":"Expected a valid address (data: \\"Null\\")"}"""
        )
      }

      val route3 = walletRoutes.handleCommand(
        ServerCommand(
          "sendwithalgo",
          Arr(Str(testAddressStr), Null, Null, Null)
        )
      )

      Post() ~> route3 ~> check {
        assert(contentType == `application/json`)
        assert(status == StatusCodes.BadRequest)
        assert(
          responseAs[
            String
          ] == s"""{"result":null,"error":"Expected ujson.Num (data: null)"}"""
        )
      }

      val route4 = walletRoutes.handleCommand(
        ServerCommand(
          "sendwithalgo",
          Arr(Str(testAddressStr), Str("abc"), Null, Null)
        )
      )

      Post() ~> route4 ~> check {
        assert(contentType == `application/json`)
        assert(status == StatusCodes.BadRequest)
        assert(
          responseAs[String] == s"""{"result":null,"error":"Expected ujson.Num (data: \\"abc\\")"}"""
        )
      }

      val route5 = walletRoutes.handleCommand(
        ServerCommand(
          "sendwithalgo",
          Arr(Str(testAddressStr), Num(100), Num(4), Null)
        )
      )

      Post() ~> route5 ~> check {
        assert(contentType == `application/json`)
        assert(status == StatusCodes.BadRequest)
        assert(
          responseAs[
            String
          ] == s"""{"result":null,"error":"Expected ujson.Str (data: null)"}"""
        )
      }
    }

    "sign a psbt" in {
      val tx = Transaction(
        "020000000258e87a21b56daf0c23be8e7070456c336f7cbaa5c8757924f545887bb2abdd750000000000ffffffff838d0427d0ec650a68aa46bb0b098aea4422c071b2ca78352a077959d07cea1d0100000000ffffffff0270aaf00800000000160014d85c2b71d0060b09c9886aeb815e50991dda124d00e1f5050000000016001400aea9a2e5f0f876a588df5546e8742d1d87008f00000000"
      )

      (() => mockWalletApi.sendFundsHandling)
        .expects()
        .returning(mockSendFundsHandlingApi)
        .anyNumberOfTimes()

      (mockWalletApi.sendFundsHandling
        .signPSBT(_: PSBT)(_: ExecutionContext))
        .expects(PSBT.empty, executor)
        .returning(Future.successful(PSBT.fromUnsignedTx(tx)))
        .anyNumberOfTimes()

      val route =
        walletRoutes.handleCommand(
          ServerCommand("signpsbt", Arr(Str(PSBT.empty.hex)))
        )

      Post() ~> route ~> check {
        assert(contentType == `application/json`)
        assert(responseAs[String] == s"""{"result":"${PSBT
            .fromUnsignedTx(tx)
            .base64}","error":null}""")
      }
    }

    "make an OP_RETURN commitment" in {

      val message = "Never gonna give you up, never gonna let you down"

      (() => mockWalletApi.sendFundsHandling)
        .expects()
        .returning(mockSendFundsHandlingApi)
        .anyNumberOfTimes()

      (mockWalletApi.sendFundsHandling
        .makeOpReturnCommitment(_: String, _: Boolean, _: Option[FeeUnit]))
        .expects(message, false, *)
        .returning(Future.successful(EmptyTransaction))

      (mockWalletApi.broadcastTransaction _)
        .expects(EmptyTransaction)
        .returning(Future.unit)
        .anyNumberOfTimes()

      val route = walletRoutes.handleCommand(
        ServerCommand("opreturncommit", Arr(message, Bool(false), Num(4)))
      )

      Post() ~> route ~> check {
        assert(contentType == `application/json`)
        assert(
          responseAs[String] == """{"result":"0000000000000000000000000000000000000000000000000000000000000000","error":null}"""
        )
      }
    }

    "bump fee with rbf" in {

      (() => mockWalletApi.sendFundsHandling)
        .expects()
        .returning(mockSendFundsHandlingApi)
        .anyNumberOfTimes()

      (mockWalletApi.sendFundsHandling
        .bumpFeeRBF(_: DoubleSha256DigestBE, _: FeeUnit))
        .expects(DoubleSha256DigestBE.empty, SatoshisPerVirtualByte.one)
        .returning(Future.successful(EmptyTransaction))

      (mockWalletApi.broadcastTransaction _)
        .expects(EmptyTransaction)
        .returning(Future.unit)
        .anyNumberOfTimes()

      val route = walletRoutes.handleCommand(
        ServerCommand(
          "bumpfeerbf",
          Arr(Str(DoubleSha256DigestBE.empty.hex), Num(1))
        )
      )

      Post() ~> route ~> check {
        assert(contentType == `application/json`)
        assert(
          responseAs[String] == """{"result":"0000000000000000000000000000000000000000000000000000000000000000","error":null}"""
        )
      }
    }

    "bump fee with CPFP" in {
      (() => mockWalletApi.sendFundsHandling)
        .expects()
        .returning(mockSendFundsHandlingApi)
        .anyNumberOfTimes()

      (mockWalletApi.sendFundsHandling
        .bumpFeeCPFP(_: DoubleSha256DigestBE, _: FeeUnit))
        .expects(DoubleSha256DigestBE.empty, SatoshisPerVirtualByte.one)
        .returning(Future.successful(EmptyTransaction))

      (mockWalletApi.broadcastTransaction _)
        .expects(EmptyTransaction)
        .returning(Future.unit)
        .anyNumberOfTimes()

      val route = walletRoutes.handleCommand(
        ServerCommand(
          "bumpfeecpfp",
          Arr(Str(DoubleSha256DigestBE.empty.hex), Num(1))
        )
      )

      Post() ~> route ~> check {
        assert(contentType == `application/json`)
        assert(
          responseAs[String] == """{"result":"0000000000000000000000000000000000000000000000000000000000000000","error":null}"""
        )
      }
    }

    "create multisig" in {
      val key1 = ECPublicKey(
        "0369c68f212ecaf3b3be52acb158a6fd87e469bc08726ef98a3b58401b75da3392"
      )
      val key2 = ECPublicKey(
        "02c23222c46b96c5976930319cc4915791fdf5e1ad1203790ff98cb1e7517eed4a"
      )

      val route = coreRoutes.handleCommand(
        ServerCommand(
          "createmultisig",
          Arr(Num(1), Arr(Str(key1.hex), Str(key2.hex)))
        )
      )

      Post() ~> route ~> check {
        assert(contentType == `application/json`)
        assert(
          responseAs[String] == """{"result":{"address":"bcrt1qjtsq4h0thsy0qftjdfxldxwa4tph7kwuplj6nglvvyehduagqqnssf4l0c","redeemScript":"47512102c23222c46b96c5976930319cc4915791fdf5e1ad1203790ff98cb1e7517eed4a210369c68f212ecaf3b3be52acb158a6fd87e469bc08726ef98a3b58401b75da339252ae"},"error":null}"""
        )
      }
    }

    "return the peer list" ignore {
      val route =
        nodeRoutes.handleCommand(ServerCommand("getpeers", Arr()))

      Get() ~> route ~> check {
        assert(contentType == `application/json`)
        assert(
          responseAs[
            String
          ] == """{"result":"TODO implement getpeers","error":null}"""
        )
      }
    }

    "run wallet rescan" in {
      // positive cases

      (() => mockWalletApi.rescanHandling)
        .expects()
        .returning(mockRescanHandlingApi)
        .anyNumberOfTimes()

      (() => mockWalletApi.rescanHandling.discoveryBatchSize())
        .expects()
        .returning(100)
        .atLeastOnce()

      (mockWalletApi.rescanHandling
        .rescanNeutrinoWallet(
          _: Option[BlockStamp],
          _: Option[BlockStamp],
          _: Int,
          _: Boolean,
          _: Boolean
        ))
        .expects(None, None, 100, false, false)
        .returning(
          Future.successful(
            RescanState
              .RescanStarted(
                Promise(),
                Future.successful(Vector.empty),
                Promise()
              )
          )
        )

      walletLoader.clearRescanState()
      val route1 =
        walletRoutes.handleCommand(
          ServerCommand("rescan", Arr(Arr(), Null, Null, true, true))
        )

      Post() ~> route1 ~> check {
        assert(contentType == `application/json`)
        assert(
          responseAs[String] == """{"result":"Rescan started.","error":null}"""
        )
      }

      (mockWalletApi.rescanHandling
        .rescanNeutrinoWallet(
          _: Option[BlockStamp],
          _: Option[BlockStamp],
          _: Int,
          _: Boolean,
          _: Boolean
        ))
        .expects(
          Some(
            BlockTime(
              ZonedDateTime.of(2018, 10, 27, 12, 34, 56, 0, ZoneId.of("UTC"))
            )
          ),
          None,
          100,
          false,
          false
        )
        .returning(
          Future.successful(
            RescanState
              .RescanStarted(
                Promise(),
                Future.successful(Vector.empty),
                Promise()
              )
          )
        )

      walletLoader.clearRescanState()
      val route2 =
        walletRoutes.handleCommand(
          ServerCommand(
            "rescan",
            Arr(Arr(), Str("2018-10-27T12:34:56Z"), Null, true, true)
          )
        )

      Post() ~> route2 ~> check {
        assert(
          responseAs[String] == """{"result":"Rescan started.","error":null}"""
        )
        assert(contentType == `application/json`)

      }

      (mockWalletApi.rescanHandling
        .rescanNeutrinoWallet(
          _: Option[BlockStamp],
          _: Option[BlockStamp],
          _: Int,
          _: Boolean,
          _: Boolean
        ))
        .expects(
          None,
          Some(BlockHash(DoubleSha256DigestBE.empty)),
          100,
          false,
          false
        )
        .returning(
          Future.successful(
            RescanState
              .RescanStarted(
                Promise(),
                Future.successful(Vector.empty),
                Promise()
              )
          )
        )

      walletLoader.clearRescanState()
      val route3 =
        walletRoutes.handleCommand(
          ServerCommand(
            "rescan",
            Arr(Null, Null, Str(DoubleSha256DigestBE.empty.hex), true, true)
          )
        )

      Post() ~> route3 ~> check {
        assert(contentType == `application/json`)
        assert(
          responseAs[String] == """{"result":"Rescan started.","error":null}"""
        )
      }

      (mockWalletApi.rescanHandling
        .rescanNeutrinoWallet(
          _: Option[BlockStamp],
          _: Option[BlockStamp],
          _: Int,
          _: Boolean,
          _: Boolean
        ))
        .expects(
          Some(BlockHeight(12345)),
          Some(BlockHeight(67890)),
          100,
          false,
          false
        )
        .returning(Future.successful(RescanState.RescanDone))

      walletLoader.clearRescanState()
      val route4 =
        walletRoutes.handleCommand(
          ServerCommand(
            "rescan",
            Arr(Arr(), Str("12345"), Num(67890), true, true)
          )
        )

      Post() ~> route4 ~> check {
        assert(contentType == `application/json`)
        assert(
          responseAs[String] == """{"result":"Rescan done.","error":null}"""
        )
      }

      // negative cases
      walletLoader.clearRescanState()
      val route5 =
        walletRoutes.handleCommand(
          ServerCommand(
            "rescan",
            Arr(Null, Str("abcd"), Str("efgh"), true, true)
          )
        )

      Post() ~> route5 ~> check {
        assert(contentType == `application/json`)
        assert(status == StatusCodes.BadRequest)
        assert(
          responseAs[
            String
          ] == s"""{"result":null,"error":"Invalid blockstamp: abcd"}"""
        )
      }

      walletLoader.clearRescanState()
      val route6 =
        walletRoutes.handleCommand(
          ServerCommand(
            "rescan",
            Arr(Arr(55), Null, Str("2018-10-27T12:34:56"), true, true)
          )
        )

      Post() ~> route6 ~> check {
        assert(contentType == `application/json`)
        assert(status == StatusCodes.BadRequest)
        assert(
          responseAs[String] == s"""{"result":null,"error":"Invalid blockstamp: 2018-10-27T12:34:56"}"""
        )
      }

      walletLoader.clearRescanState()
      val route7 =
        walletRoutes.handleCommand(
          ServerCommand("rescan", Arr(Null, Num(-1), Null, true, false))
        )

      Post() ~> route7 ~> check {
        assert(contentType == `application/json`)
        assert(status == StatusCodes.BadRequest)
        assert(
          responseAs[String] == s"""{"result":null,"error":"Expected a positive integer (data: -1)"}"""
        )
      }

      (mockWalletApi.rescanHandling
        .rescanNeutrinoWallet(
          _: Option[BlockStamp],
          _: Option[BlockStamp],
          _: Int,
          _: Boolean,
          _: Boolean
        ))
        .expects(None, None, 55, false, false)
        .returning(Future.successful(RescanState.RescanDone))

      walletLoader.clearRescanState()
      val route8 =
        walletRoutes.handleCommand(
          ServerCommand(
            "rescan",
            Arr(Arr(55), Arr(), Arr(), Bool(true), Bool(true))
          )
        )

      Post() ~> route8 ~> check {
        assert(contentType == `application/json`)
        assert(
          responseAs[String] == """{"result":"Rescan done.","error":null}"""
        )
      }
    }

    "get wallet accounting" in {
      val accounting = DLCWalletAccounting(
        myCollateral = Satoshis.one,
        theirCollateral = Satoshis.one,
        myPayout = Satoshis(2),
        theirPayout = Satoshis.zero
      )

      (() => mockWalletApi.getWalletAccounting())
        .expects()
        .returning(Future.successful(accounting))

      val route = walletRoutes.handleCommand(
        ServerCommand("getdlcwalletaccounting", Arr())
      )

      Get() ~> route ~> check {
        assert(contentType == `application/json`)
        val str = responseAs[String]
        val expected =
          s"""{"result":{"myCollateral":1,"theirCollateral":1,"myPayout":2,"theirPayout":0,"pnl":1,"rateOfReturn":1},"error":null}""".stripMargin
        assert(str == expected)
      }
    }

    "getConnectedPeerCount" in {
      (() => mockNode.getConnectionCount)
        .expects()
        .returning(Future.successful(1))

      val route =
        nodeRoutes.handleCommand(ServerCommand("getconnectioncount", Arr()))

      Get() ~> route ~> check {
        assert(contentType == `application/json`)
        assert(responseAs[String] == """{"result":1,"error":null}""")
      }
    }
  }
}
