/*
 * Copyright 2020-2021 John A. De Goes and the ZIO Contributors
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

package zio

import java.io
import java.io.IOException
import java.net.{URI, URL}
import java.nio.file.Path

private[zio] trait ZManagedPlatformSpecific {

  /**
   * Returns a managed effect that describes shifting to the blocking executor
   * as the `acquire` action and shifting back to the original executor as the
   * `release` action.
   */
  val blocking: ZManaged[Any, Nothing, Unit] =
    ZIO.blockingExecutor.toManaged.flatMap(executor => ZManaged.onExecutor(executor))

  def readFile(path: Path): ZManaged[Any, IOException, ZInputStream] =
    readFile(path.toString())

  def readFile(path: String): ZManaged[Any, IOException, ZInputStream] =
    ZManaged
      .acquireReleaseWith(
        ZIO.attemptBlockingIO {
          val fis = new io.FileInputStream(path)
          (fis, ZInputStream.fromInputStream(fis))
        }
      )(tuple => ZIO.attemptBlocking(tuple._1.close()).orDie)
      .map(_._2)

  def readURL(url: URL): ZManaged[Any, IOException, ZInputStream] =
    ZManaged
      .acquireReleaseWith(
        ZIO.attemptBlockingIO {
          val fis = url.openStream()
          (fis, ZInputStream.fromInputStream(fis))
        }
      )(tuple => ZIO.attemptBlocking(tuple._1.close()).orDie)
      .map(_._2)

  def readURL(url: String): ZManaged[Any, IOException, ZInputStream] =
    ZManaged.fromZIO(ZIO.succeed(new URL(url))).flatMap(readURL)

  def readURI(uri: URI): ZManaged[Any, IOException, ZInputStream] =
    for {
      isAbsolute <- ZManaged.fromZIO(ZIO.attemptBlockingIO(uri.isAbsolute()))
      is         <- if (isAbsolute) readURL(uri.toURL()) else readFile(uri.toString())
    } yield is

  def writeFile(path: String): ZManaged[Any, IOException, ZOutputStream] =
    ZManaged
      .acquireReleaseWith(
        ZIO.attemptBlockingIO {
          val fos = new io.FileOutputStream(path)
          (fos, ZOutputStream.fromOutputStream(fos))
        }
      )(tuple => ZIO.attemptBlocking(tuple._1.close()).orDie)
      .map(_._2)

  def writeFile(path: Path): ZManaged[Any, IOException, ZOutputStream] =
    writeFile(path.toString())

}
