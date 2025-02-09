package dev.zio.quickstart.download

import zio._
import zio.http._
import zio.stream.ZStream
import zio.schema.codec.JsonCodec.zioJsonBinaryCodec

/** Collection of routes that:
  *   - Accept a `Request` and returns a `Response`
  *   - Do not require any environment
  */
object DownloadRoutes {
  def apply(): Routes[Any, Nothing] =
    Routes(
      // GET /download
      Method.GET / Root / "download" -> handler {
        val fileName = "file.txt"
        http.Response(
          status = Status.Ok,
          headers = Headers(
            Header.ContentType(MediaType.application.`octet-stream`),
            Header.ContentDisposition.attachment(fileName)
          ),
          body = Body.fromStream(ZStream.fromResource(fileName))
        )
      },

      // Download a large file using streams
      // GET /download/stream
      Method.GET / "download" / "stream" -> handler {
        val file = "bigfile.txt"
        http.Response(
          status = Status.Ok,
          headers = Headers(
            Header.ContentType(MediaType.application.`octet-stream`),
            Header.ContentDisposition.attachment(file)
          ),
          body = Body.fromStream(
            ZStream
              .fromResource(file)
              .schedule(Schedule.spaced(50.millis))
          )
        )
      }
    )
}
