package zio.stream

import zio.stacktracer.TracingImplicits.disableAutoTrace
import zio.stream.compression.{CompressionException, Gunzipper}
import zio.{Chunk, ZIO, ZTraceElement}

object Gunzip {
  def makeGunzipper[Done](
    bufferSize: Int = 64 * 1024
  )(implicit
    trace: ZTraceElement
  ): ZChannel[Any, CompressionException, Chunk[Byte], Done, CompressionException, Chunk[Byte], Done] =
    ZChannel.scoped(
      ZIO.acquireRelease(
        Gunzipper.make(bufferSize)
      )(gunzipper => ZIO.succeed(gunzipper.close()))
    ) {
      case gunzipper => {

        lazy val loop: ZChannel[Any, CompressionException, Chunk[Byte], Done, CompressionException, Chunk[Byte], Done] =
          ZChannel.readWithCause(
            chunk =>
              ZChannel.fromZIO {
                gunzipper.onChunk(chunk)
              }.flatMap(chunk => ZChannel.write(chunk) *> loop),
            ZChannel.failCause(_),
            done =>
              ZChannel.fromZIO {
                gunzipper.onNone
              }.flatMap(chunk => ZChannel.write(chunk).as(done))
          )

        loop
      }
    }

}
