package dev.zio.quickstart.config

import zio.Config
import zio.config.magnolia._

case class HttpServerConfig(host: String, port: Int, nThreads: Int)

object HttpServerConfig {
  // Automatic Config Derivation
  // Import zio.config.magnolia for automatic derivation
  val config: Config[HttpServerConfig] =
    deriveConfig[HttpServerConfig].nested("HttpServerConfig")

  // Manual Config Derivation
  val config_manual: Config[HttpServerConfig] =
    (Config.int.nested("port") ++
      Config.string.nested("host") ++
      Config.int.nested("nThreads"))
      .map { case (port, host, nThreads) =>
        HttpServerConfig(host, port, nThreads)
      }
      .nested("HttpServerConfig")
}
