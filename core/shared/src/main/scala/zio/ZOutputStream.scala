/*
 * Copyright 2020-2024 John A. De Goes and the ZIO Contributors
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

import zio.stacktracer.TracingImplicits.disableAutoTrace

import java.io.IOException

trait ZOutputStream {
  def write(chunk: Chunk[Byte])(implicit trace: Trace): IO[IOException, Unit]
}

object ZOutputStream {

  def fromOutputStream(os: java.io.OutputStream): ZOutputStream = new ZOutputStream {
    def write(chunk: Chunk[Byte])(implicit trace: Trace): IO[IOException, Unit] =
      ZIO.attemptBlockingIO {
        os.write(chunk.toArray)
      }
  }

}
