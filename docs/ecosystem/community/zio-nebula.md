---
id: zio-nebula
title: "ZIO NebulaGraph"
---

[zio-nebula](https://github.com/nebula-contrib/zio-nebula) is a simple wrapper around [nebula-java](https://github.com/vesoft-inc/nebula-java/) for easier integration with [NebulaGraph](https://github.com/vesoft-inc/nebula) into Scala, ZIO applications.

## Introduction

- Supports all clients: Session Pool、Pool、Storage、Meta
- Support for configuring clients with typesafe config
- Other optimizations suitable for Scala pure functional
- Support Scala 3, Scala 2.13 and Scala 2.12

## Installation

In order to use this library, we need to add the following line in our `build.sbt` file:

```scala
libraryDependencies += "io.github.jxnu-liguobin" %% "zio-nebula" % <version>
```

There are the version correspondence between zio-nebula and nebula-java:

|  zio  | zio-nebula | nebula-java |
|:-----:|:----------:|:-----------:|
| 2.0.x |   0.0.x    |    3.6.0    |
| 2.0.x |   0.1.0    |    3.6.0    |
| 2.0.x |   0.1.1    |    3.6.1    |

## Example

Usually, we use a session client, which can be conveniently used in ZIO applications like this:

```scala
import zio._
import zio.nebula._

final class NebulaSessionClientExample(sessionClient: NebulaSessionClient) {

  def execute(stmt: String): ZIO[Any, Throwable, NebulaResultSet] = {
    // your business logic
    sessionClient.execute(stmt)
  }
}

object NebulaSessionClientExample {
  lazy val layer = ZLayer.fromFunction(new NebulaSessionClientExample(_))
}

object NebulaSessionClientMain extends ZIOAppDefault {

  override def run = (for {
    _ <- ZIO.serviceWithZIO[NebulaSessionClient](_.init()) // since 0.1.1, no need to call it manually. 
    _ <- ZIO.serviceWithZIO[NebulaSessionClientExample](
             _.execute("""
                         |INSERT VERTEX person(name, age) VALUES 
                         |'Bob':('Bob', 10), 
                         |'Lily':('Lily', 9),'Tom':('Tom', 10),
                         |'Jerry':('Jerry', 13),
                         |'John':('John', 11);""".stripMargin)
           )
    _ <- ZIO.serviceWithZIO[NebulaSessionClientExample](
             _.execute("""
                         |INSERT EDGE like(likeness) VALUES
                         |'Bob'->'Lily':(80.0),
                         |'Bob'->'Tom':(70.0),
                         |'Lily'->'Jerry':(84.0),
                         |'Tom'->'Jerry':(68.3),
                         |'Bob'->'John':(97.2);""".stripMargin)
           )
    _ <- ZIO.serviceWithZIO[NebulaSessionClientExample](
             _.execute("""
                         |USE test;
                         |MATCH (p:person) RETURN p LIMIT 4;
                         |""".stripMargin)
           )
  } yield ())
    .provide(
      Scope.default,
      NebulaSessionClientExample.layer,
      SessionClientEnv
    )

}
```

Add configuration in classpath:

```hocon
{
  graph {
    address = [
      {
        host = "127.0.0.1",
        port = 9669
      }
    ]
    auth {
      username = "root"
      password = "nebula"
    }
    spaceName = "test"
    reconnect = true
  }

  meta {
    address = [
      {
        host = "127.0.0.1",
        port = 9559
      }
    ]
    timeoutMills = 30000
    connectionRetry = 3
    executionRetry = 1
    enableSSL = false
  }

  storage {
    address = [
      {
        host = "127.0.0.1",
        port = 9559
      }
    ]
    timeoutMills = 30000
    connectionRetry = 3
    executionRetry = 1
    enableSSL = false
  }

  pool {
    timeoutMills = 60000
    enableSsl = false
    minConnsSize = 10
    maxConnsSize = 10
    intervalIdleMills = 100
    waitTimeMills = 100
  }
}
```
