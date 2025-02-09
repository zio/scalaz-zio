package dev.zio.quickstart

import dev.zio.quickstart.config.HttpServerConfig
import dev.zio.quickstart.counter.CounterRoutes
import dev.zio.quickstart.download.DownloadRoutes
import dev.zio.quickstart.greet.GreetingRoutes
import dev.zio.quickstart.users.{InmemoryUserRepo, UserRoutes}
import zio._
import zio.config.typesafe._
import zio.http._
import zio.http.netty.NettyConfig

object MainApp extends ZIOAppDefault {
  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] =
    Runtime.setConfigProvider(
      ConfigProvider.fromResourcePath()
    )

  private val serverConfig: ZLayer[Any, Config.Error, Server.Config] =
    ZLayer
      .fromZIO(
        ZIO.config[HttpServerConfig](HttpServerConfig.config).map { c =>
          Server.Config.default.binding(c.host, c.port)
        }
      )

  private val nettyConfig: ZLayer[Any, Config.Error, NettyConfig] =
    ZLayer
      .fromZIO(
        ZIO.config[HttpServerConfig](HttpServerConfig.config).map { c =>
          NettyConfig.default.maxThreads(c.nThreads)
        }
      )

  def run = {
    (Server
      .install(
        GreetingRoutes() ++ DownloadRoutes() ++ CounterRoutes() ++ UserRoutes()
      )
      .flatMap(port =>
        Console.printLine(s"Started server on port: $port")
      ) *> ZIO.never)
      .provide(
        serverConfig,
        nettyConfig,
        Server.live,

        // A layer responsible for storing the state of the `counterApp`
        ZLayer.fromZIO(Ref.make(0)),

        // To use the persistence layer, provide the `PersistentUserRepo.layer` layer instead
        InmemoryUserRepo.layer
      )
  }
}
