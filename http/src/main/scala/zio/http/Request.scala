package zio.http

import zio._

final case class Request(
  method: Method,
  url: String,
  headers: Headers = Headers.empty,
  body: Body = Body.empty,
  version: Version = Version.Http_1_1
)

object Request {
  def get(url: String): Request = Request(Method.GET, url)
  def post(url: String): Request = Request(Method.POST, url)
} 