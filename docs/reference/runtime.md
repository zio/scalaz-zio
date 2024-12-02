---
id: runtime
title: "How to enable Virtual Threads (Project Loom) ?"
---

## Enabling Virtual Threads

ZIO offers two ways to utilize virtual threads:

1. For the main executor (handles non-blocking ZIO operations):
```scala
import zio._

object MainApp extends ZIOAppDefault {

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] = Runtime.enableLoomBasedExecutor

  override def run = ...
}
```

2. For the blocking executor (handles blocking operations):
```scala
import zio._

object MainApp extends ZIOAppDefault {

  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] = Runtime.enableLoomBasedBlockingExecutor

  override def run = ...
}
```

