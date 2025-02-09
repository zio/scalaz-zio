---
id: index
title: "Introduction to Configuration in ZIO"
---

Configuration is a core concern for any cloud-native application. So ZIO ships with built-in support for configuration by providing a front-end for configuration providers as well as metrics and logging.

So, ZIO provides a unified way to configure our applications, while still enabling customizability, flexibility, and significant integrations with configuration backends via ecosystem projects, most notably [ZIO Config](https://zio.dev/zio-config).

This configuration front-end allows ecosystem libraries and applications to declaratively describe their configuration needs and delegates the heavy lifting to a ConfigProvider, which may be supplied by third-party libraries such as ZIO Config.

The ZIO Core ships with a simple default config provider, which reads configuration data from environment variables and if not found, from system properties. This can be used for development purposes or to bootstrap applications toward more sophisticated config providers.

## Getting Started

To make our application configurable, we should know about three essential elements:

1. **Config Description**— To describe configuration data of type `A`, we should create an instance of `Config[A]`. If the configuration data is simple (such as `string`, `string`, `boolean`), we can use built-in configs inside companion object of `Config` data type. By combining primitive configs, we can model custom data types such as `HostPort`.

2. **Config Front-end**— By using `ZIO.config` we can load configuration data described by `Config`. It takes a `Config[A]` instance or expect implicit `Config[A]` and loads the config using the current `ConfigProvider`.

3. **Config Backend**— `ConfigProvider` is the underlying engine that `ZIO.config` uses to load configs. ZIO has a default config provider inside its default services. The default config provider reads configuration data from environment variables and if not found, from system properties. To change the default config provider, we can use `Runtime.setConfigProvider` layer to configure the ZIO runtime to use a custom config provider.

:::note
By introducing built-in config front-end in ZIO Core, the old way of reading configuration data using `ZLayer` is deprecated, and we don't recommend using layers for configuration anymore.
:::

## Primitive Configs

ZIO provides a set of primitive configs for the most common types like `int`, `long`, `string`, `boolean`, `double`, etc. All of these configs are available inside the `Config` object.

Let's start with a simple example of how to read configuration from environment variables and system properties:

```scala mdoc:compile-only
import zio._

object MainApp extends ZIOAppDefault {
  def run = {
    for {
      host <- ZIO.config(Config.string("host"))
      port <- ZIO.config(Config.int("port"))
      _    <- Console.printLine(s"Application started: $host:$port")
    } yield ()
  }
}
```

:::note
Use `ZIO.config(config: A)` overload for primitive data types instead of `ZIO.config[A]` to avoid potential implicit conflicts.
:::

If we run this application we will get the following output:

```bash
timestamp=2023-02-14T09:45:27.074151Z level=ERROR thread=#zio-fiber-0 message="" cause="Exception in thread "zio-fiber-4" zio.Config$Error$Or: ((Missing data at host: Expected HOST to be set in the environment) or (Missing data at host: Expected host to be set in properties))
```

This is because we have not provided any configuration. Let's try to run it with the following environment variables:

```bash
HOST=localhost PORT=8080 sbt "runMain MainApp"
```

Now we get the following output:

```bash
Application started: localhost:8080
```

We can also run it by setting system properties:

```bash
sbt -Dhost=localhost -Dport=8080 "runMain MainApp"
```

## Custom Configs

Other than primitive types, we can also define a configuration for custom types. To do so, we need to use primitive configs and combine them together using `Config` operators (`++`, `||`, `map`, etc) and constructors (`listOf`, `chunkOf`, `setOf`, `vectorOf`, `table`, etc).

### Example 1

Let's say we have the `HostPort` data type, which consists of two fields: `host` and `port`:

```scala mdoc:silent
case class HostPort(host: String, port: Int)
```

We can define implicit config for this data type by combining primitive `string` and `int` configs:

```scala mdoc:silent
import zio._

object HostPort {
  implicit val config: Config[HostPort] =
    (Config.string("host") ++ Config.int("port")).map { case (host, port) =>
      HostPort(host, port)
    }
}
```

:::note
The best practice is to put the implicit `Config` value in the companion object of the configuration data type and call it `config`.
:::

If we use this customized config in our application, it tries to read corresponding values from environment variables (`HOST` and `PORT`) and system properties (`host` and `port`):

```scala mdoc:compile-only
for {
  config <- ZIO.config[HostPort]
  _      <- Console.printLine(s"Application started: $config")
} yield ()
```

### Example 2

Now let's assume we want to have multiple `HostPort` configurations. We can define a config for a list of `HostPort` like bellow using the `listOf` constructor:

```scala mdoc:silent
case class HostPorts(hostPorts: List[HostPort])

object HostPorts {
  implicit val config: Config[HostPorts] =
    Config.listOf(HostPort.config).map(HostPorts(_))
}
```

Then we can use this config in our application:

```scala mdoc:silent
for {
  config <- ZIO.config[HostPorts]
  _      <- Console.printLine(s"Application started with:")
  _      <- ZIO.foreachDiscard(config.hostPorts)(e => Console.printLine(s"  - http://${e.host}:${e.port}"))
} yield ()
```

With the default config provider, we can run the application with the following environment variables:

```bash
HOST=host1,host2,host3 PORT=8080,8081,8082 sbt "runMain MainApp"
```

The output will be:

```bash
Application started with:
  - http://host1:8081
  - http://host2:8082
  - http://host3:8083
```

## Top-level and Nested Configs

So far we have seen how to define configuration in a top-level manner, whether it is a primitive or a custom type. But we can also define a nested configuration.

Assume we have a `SerivceConfig` data type that consists of two fields: `hostPort` and `timeout`:

```scala mdoc:silent
case class ServiceConfig(hostPort: HostPort, timeout: Int)
```

Let's define a config for this type in its companion object:

```scala mdoc:silent
import zio._

object ServiceConfig {
  implicit val config: Config[ServiceConfig] =
    (HostPort.config ++ Config.int("timeout")).map {
      case (a, b) => ServiceConfig(a, b)
    }
}
```

If we use this customized config in our application, it tries to read corresponding values from environment variables (`HOST`, `PORT`, and `TIMEOUT`) and, if not found from system properties (`host`, `port`, and `timeout`).

But in most circumstances, we don't want to read all the configurations from the top-level namespace. Instead, we want to nest them under a common namespace. In this case, we want to read both `HOST` and `PORT` from the `HOSTPORT` namespace, and `TIMEOUT` from the root namespace. In order to do that, we can use the `nested` combinator:

```diff
object ServiceConfig {
  implicit val config: Config[ServiceConfig] =
-    (HostPort.config ++ Config.int("timeout")).map {
+    (HostPort.config.nested("hostport") ++ Config.int("timeout")).map {
      case (a, b) => ServiceConfig(a, b)
    }
}
```

Now, if we run our application, it tries to read corresponding values from environment variables (`HOSTPORT_HOST`, `HOSTPORT_PORT` and `TIMEOUT`) and, if not found it tries to read from system properties (`hostport.host`, `hostport.port` and `timeout`).

## Built-in Config Providers

ZIO has some built-in config providers:

- `ConfigProvider.defaultProvider` - reads configuration from environment variables and if not found, from system properties
- `ConfigProvider.envProvider` - reads configuration from environment variables
- `ConfigProvider.propsProvider` - reads configuration from system properties
- `ConfigProvider.consoleProvider` - reads configuration from interactive console prompts

Other than these built-in providers, we can also use third-party providers in ZIO ecosystem libraries, such as ZIO Config which provides a rich set of backends for reading configuration from different sources such as HOCON, JSON, YAML, etc.

## Custom Config Provider

We can also define our own custom config providers.

The default config provider is used by default, but we can also override it by using `Runtime#setConfigProvider`.

In the following example, we set the default config provider to `consoleProvider` which reads configuration from the console:

```scala mdoc:compile-only
import zio._

object MainAppScoped extends ZIOAppDefault {
  override val bootstrap: ZLayer[Any, Nothing, Unit] =
    Runtime.setConfigProvider(ConfigProvider.consoleProvider)

  def run =
    for {
      host <- ZIO.config(Config.string("host"))
      port <- ZIO.config(Config.int("port"))
      _ <- Console.printLine(s"Application started: http://$host:$port")
    } yield ()
}
```

:::note
The console provider is stored inside a `FiberRef`, so we can override it in a scoped manner. This is useful for changing the config provider for a specific part of the application.
:::

## Testing Services

When testing services, we sometimes need to provide some configuration to them. So we should be able to mock any backend that we use for reading configuration data.

In order to do that, we can use the `ConfigProvider.fromMap` constructor, which takes a map of configuration data and returns a config provider that reads configuration from that map. Then we can pass that to the `Runtime.setConfigProvider`, which returns a `ZLayer` that we can use to override the default config provider for our test specs using `Spec#provideLayer` operator:

```scala mdoc:compile-only
import zio._
import zio.test._

object MyServiceTest extends ZIOSpecDefault {

  val mockConfigProvider: ZLayer[Any, Nothing, Unit] =
    Runtime.setConfigProvider(ConfigProvider.fromMap(Map("timeout" -> "5s")))

  // This service reads configuration data (host and port) inside its implementation
  def myService: ZIO[Any, Config.Error, Double] = ???

  override def spec = {
    val expected: Double = ??? // expected value
    test("test myService") {
      for {
        result <- myService
      } yield assertTrue(result == expected)
    }
  }.provideLayer(mockConfigProvider)
  
}
```
