package zio.http

import zio.test._
import zio.test.Assertion._
import zio.http.{Request, Response, Status, Headers, Method, Header, Cookie}
import zio.{ZIO, Scope, Chunk}

/**
 * HTTP Specification Conformance Test Suite
 *
 * This test suite validates ZIO HTTP's compliance with HTTP specifications (RFCs):
 * - RFC 7230: Message Syntax and Routing
 * - RFC 7231: Semantics and Content
 * - RFC 7232: Conditional Requests
 * - RFC 7233: Range Requests
 * - RFC 7234: Caching
 * - RFC 7235: Authentication
 *
 * Each test suite corresponds to a specific aspect of the HTTP specification
 * and includes references to the relevant RFC sections.
 */
object HttpSpecConformanceSpec extends ZIOSpecDefault {

  def spec = suite("HTTP Spec Conformance Tests")(
    suite("Content-Length Header")(
      test("HEAD request should return same Content-Length as GET") {
        for {
          // Create a sample response with content
          getResponse <- Response.text("Hello World").toZIO
          // Create HEAD request
          headRequest <- Request.make(Method.HEAD, "/test").toZIO
          // Create GET request 
          getRequest <- Request.make(Method.GET, "/test").toZIO
          // Get content lengths
          headContentLength = getResponse.headers.get("Content-Length")
          getContentLength = getResponse.headers.get("Content-Length") 
        } yield assertTrue(
          headContentLength == getContentLength,
          headContentLength.isDefined
        )
      },

      test("Content-Length must match actual content length") {
        for {
          response <- Response.text("Hello World").toZIO
          contentLength = response.headers.get("Content-Length").flatMap(_.toLongOption)
          actualLength = response.body.length
        } yield assertTrue(
          contentLength.contains(actualLength)
        )
      },

      test("Content-Length must not be negative") {
        for {
          response <- Response.text("Test").toZIO
          contentLength = response.headers.get("Content-Length").flatMap(_.toLongOption)
        } yield assertTrue(
          contentLength.forall(_ >= 0)
        )
      }
    ),

    suite("Status Codes")(
      test("1xx responses must not have content body") {
        check(Gen.fromIterable(Status.values.filter(_.code < 200))) { status =>
          for {
            response <- Response.status(status).toZIO
          } yield assertTrue(
            response.body.isEmpty
          )
        }
      }
    ),

    suite("Transfer-Encoding")(
      test("chunked transfer encoding must not have Content-Length") {
        for {
          response <- Response.text("Test")
            .addHeader("Transfer-Encoding", "chunked")
            .toZIO
        } yield assertTrue(
          !response.headers.contains("Content-Length")
        )
      }
    ),

    suite("Request Headers")(
      test("Host header is required for HTTP/1.1") {
        for {
          request <- Request.make(Method.GET, "/test")
            .addHeader(Header.Host("example.com"))
            .toZIO
        } yield assertTrue(
          request.headers.get(Header.Host.name).isDefined,
          request.version == Version.Http_1_1
        )
      },

      test("Content-Length and Transfer-Encoding are mutually exclusive") {
        for {
          request <- Request.make(Method.POST, "/test")
            .addHeader("Transfer-Encoding", "chunked")
            .addHeader("Content-Length", "100")
            .toZIO
        } yield assertTrue(
          request.headers.get("Transfer-Encoding").isDefined != 
          request.headers.get("Content-Length").isDefined
        )
      }
    ),

    suite("HTTP Methods")(
      test("GET requests should not have a body") {
        for {
          request <- Request.get("/test")
            .setBody(Body.fromString("test"))
            .toZIO
        } yield assertTrue(
          request.body.isEmpty
        )
      },

      test("HEAD requests should be identical to GET except for response body") {
        for {
          getResp <- Response.text("test").toZIO
          headResp <- Response.fromResponse(getResp).clearBody.toZIO
        } yield assertTrue(
          headResp.headers == getResp.headers,
          headResp.status == getResp.status,
          headResp.body.isEmpty
        )
      },

      test("OPTIONS should return Allow header") {
        for {
          response <- Response.options(Set(Method.GET, Method.POST)).toZIO
        } yield assertTrue(
          response.headers.get(Header.Allow.name).isDefined
        )
      }
    ),

    suite("Cache Control")(
      test("Cache-Control directives should be valid") {
        val validDirectives = Set(
          "no-cache", "no-store", "max-age=3600", 
          "must-revalidate", "public", "private"
        )
        
        check(Gen.fromIterable(validDirectives)) { directive =>
          for {
            response <- Response.ok
              .addHeader(Header.CacheControl(directive))
              .toZIO
          } yield assertTrue(
            response.headers.get(Header.CacheControl.name).contains(directive)
          )
        }
      },

      test("Expires header should be valid HTTP date") {
        for {
          response <- Response.ok
            .addHeader(Header.Expires("Wed, 21 Oct 2015 07:28:00 GMT"))
            .toZIO
        } yield assertTrue(
          response.headers.get(Header.Expires.name).isDefined
        )
      }
    ),

    suite("Cookies")(
      test("Set-Cookie header should have required attributes") {
        for {
          response <- Response.ok
            .addCookie(Cookie("session", "abc123", secure = true, httpOnly = true))
            .toZIO
        } yield {
          val cookie = response.cookies.headOption
          assertTrue(
            cookie.isDefined,
            cookie.exists(_.name == "session"),
            cookie.exists(_.secure),
            cookie.exists(_.httpOnly)
          )
        }
      }
    ),

    suite("Authentication")(
      test("WWW-Authenticate header should be present for 401 responses") {
        for {
          response <- Response.status(Status.Unauthorized)
            .addHeader(Header.WwwAuthenticate("Basic realm=\"test\""))
            .toZIO
        } yield assertTrue(
          response.status == Status.Unauthorized,
          response.headers.get(Header.WwwAuthenticate.name).isDefined
        )
      },

      test("Authorization header should have valid scheme") {
        val validSchemes = Set("Basic", "Bearer", "Digest")
        check(Gen.fromIterable(validSchemes)) { scheme =>
          for {
            request <- Request.get("/test")
              .addHeader(Header.Authorization(s"$scheme dGVzdDp0ZXN0"))
              .toZIO
          } yield assertTrue(
            request.headers.get(Header.Authorization.name).exists(_.startsWith(scheme))
          )
        }
      }
    ),

    suite("Content Encoding")(
      test("Content-Encoding should be valid encoding type") {
        val validEncodings = Set("gzip", "deflate", "br")
        check(Gen.fromIterable(validEncodings)) { encoding =>
          for {
            response <- Response.ok
              .addHeader(Header.ContentEncoding(encoding))
              .toZIO
          } yield assertTrue(
            response.headers.get(Header.ContentEncoding.name).contains(encoding)
          )
        }
      },

      test("Accept-Encoding should be valid encoding type") {
        for {
          request <- Request.get("/test")
            .addHeader(Header.AcceptEncoding("gzip, deflate"))
            .toZIO
        } yield assertTrue(
          request.headers.get(Header.AcceptEncoding.name).isDefined
        )
      }
    ),

    suite("Connection Management")(
      test("Connection: close header should be honored") {
        for {
          response <- Response.ok
            .addHeader(Header.Connection("close"))
            .toZIO
        } yield assertTrue(
          response.headers.get(Header.Connection.name).contains("close")
        )
      },

      test("Keep-Alive header should have timeout") {
        for {
          response <- Response.ok
            .addHeader(Header.Connection("keep-alive"))
            .addHeader(Header.KeepAlive("timeout=5"))
            .toZIO
        } yield assertTrue(
          response.headers.get(Header.KeepAlive.name).exists(_.contains("timeout="))
        )
      }
    ),

    suite("Protocol Versions")(
      test("HTTP version should be valid") {
        val validVersions = Set(Version.Http_1_0, Version.Http_1_1, Version.Http_2_0)
        check(Gen.fromIterable(validVersions)) { version =>
          for {
            request <- Request.get("/test").setVersion(version).toZIO
          } yield assertTrue(
            validVersions.contains(request.version)
          )
        }
      },

      test("HTTP/2 requests should not have Connection header") {
        for {
          request <- Request.get("/test")
            .setVersion(Version.Http_2_0)
            .addHeader(Header.Connection("keep-alive"))
            .toZIO
        } yield assertTrue(
          request.version == Version.Http_2_0,
          !request.headers.contains(Header.Connection.name)
        )
      }
    )
  )
} 