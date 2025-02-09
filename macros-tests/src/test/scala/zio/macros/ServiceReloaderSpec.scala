/*
 * Copyright 2023-2024 John A. De Goes and the ZIO Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package zio.macros

import zio._
import zio.test._

@scala.annotation.experimental
object ServiceReloaderSpec extends ZIOSpecDefault {

  def spec = suite("ServiceReloader") {
    test("reload") {
      trait Foo { def foo: UIO[Int] }
      for {
        ref <- Ref.make(0)
        layer: ULayer[Foo] = ZLayer {
                               for {
                                 i <- ref.updateAndGet(_ + 1)
                               } yield new Foo { def foo = ZIO.succeed(i) }
                             }
        service <- ServiceReloader.register[Foo](layer)
        res1    <- service.foo
        _       <- ServiceReloader.reload[Foo]
        res2    <- service.foo
      } yield assertTrue(res1 == 1 && res2 == 2)
    }
  }.provide(ServiceReloader.live)
}
