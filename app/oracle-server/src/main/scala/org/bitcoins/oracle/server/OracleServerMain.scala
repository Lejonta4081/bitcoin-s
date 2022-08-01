package org.bitcoins.oracle.server

import akka.actor.ActorSystem
import akka.stream.scaladsl.Source
import org.bitcoins.commons.util.{DatadirParser, ServerArgParser}
import org.bitcoins.dlc.oracle.DLCOracle
import org.bitcoins.dlc.oracle.config.DLCOracleAppConfig
import org.bitcoins.server.routes.{BitcoinSServerRunner, CommonRoutes, Server}
import org.bitcoins.server.util.{BitcoinSAppScalaDaemon, ServerBindings}

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}

class OracleServerMain(override val serverArgParser: ServerArgParser)(implicit
    override val system: ActorSystem,
    conf: DLCOracleAppConfig)
    extends BitcoinSServerRunner[Unit] {

  override def start(): Future[Unit] = {

    val bindConfOpt = serverArgParser.rpcBindOpt match {
      case Some(rpcbind) => Some(rpcbind)
      case None          => conf.rpcBindOpt
    }

    val commonRoutes = CommonRoutes(conf.baseDatadir)

    for {
      _ <- conf.start()
      oracle = new DLCOracle()
      routes = Seq(OracleRoutes(oracle), commonRoutes).map(Future.successful)
      server = serverArgParser.rpcPortOpt match {
        case Some(rpcport) =>
          Server(conf = conf,
                 handlersF = routes,
                 rpcbindOpt = bindConfOpt,
                 rpcport = rpcport,
                 rpcPassword = conf.rpcPassword,
                 None,
                 Source.empty)
        case None =>
          Server(conf = conf,
                 handlersF = routes,
                 rpcbindOpt = bindConfOpt,
                 rpcport = conf.rpcPort,
                 rpcPassword = conf.rpcPassword,
                 None,
                 Source.empty)
      }

      bindings <- server.start()
    } yield {
      serverBindingsOpt = Some(bindings)
      logger.info(s"Done starting oracle!")
      ()
    }
  }
  private var serverBindingsOpt: Option[ServerBindings] = None

  override def stop(): Future[Unit] = {
    for {
      _ <- conf.stop()
      _ <- serverBindingsOpt match {
        case Some(bindings) => bindings.stop()
        case None           => Future.unit
      }
    } yield {
      logger.info(s"Stopped DLC Oracle")
      ()
    }
  }
}

object OracleServerMain extends BitcoinSAppScalaDaemon {

  override val actorSystemName =
    s"bitcoin-s-oracle-${System.currentTimeMillis()}"

  /** Directory specific for current network or custom dir */
  override val customFinalDirOpt: Option[String] = Some("oracle")

  val serverCmdLineArgs = ServerArgParser(args.toVector)

  val datadirParser =
    DatadirParser(serverCmdLineArgs, customFinalDirOpt)

  System.setProperty("bitcoins.log.location", datadirParser.networkDir.toString)

  implicit lazy val conf: DLCOracleAppConfig =
    DLCOracleAppConfig(datadirParser.datadir, Vector(datadirParser.baseConfig))(
      system.dispatcher)

  val m = new OracleServerMain(serverCmdLineArgs)
  m.run()

  sys.addShutdownHook {
    logger.info(
      s"@@@@@@@@@@@@@@@@@@@@@ Shutting down ${getClass.getSimpleName} @@@@@@@@@@@@@@@@@@@@@")
    Await.result(m.stop(), 10.seconds)
  }
}
