package zio.http

import zio._

final case class Response(
  status: Status = Status.OK,
  headers: Headers = Headers.empty,
  body: Body = Body.empty
) {
  def addHeader(name: String, value: String): Response =
    copy(headers = headers.add(name, value))
    
  def clearBody: Response = copy(body = Body.empty)
}

object Response {
  def ok: Response = Response(Status.OK)
  def text(content: String): Response = Response(body = Body.fromString(content))
  def notModified: Response = Response(Status.NotModified)
  def status(status: Status): Response = Response(status = status)
  def fromResponse(response: Response): Response = response
} 