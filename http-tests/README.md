# ZIO HTTP Conformance Tests

This module contains specification conformance tests for ZIO HTTP to ensure compliance with HTTP standards.

## Running Tests

bash
sbt http-tests/test


## Test Coverage

The test suite validates compliance with HTTP specifications (RFCs):

### RFC 7230: Message Syntax and Routing
- Content-Length header handling
- Transfer-Encoding requirements 
- Connection management
- Message routing

### RFC 7231: Semantics and Content
- HTTP Method semantics (GET, HEAD, OPTIONS etc)
- Status Code requirements
- Header field requirements
- Content negotiation

### RFC 7232: Conditional Requests
- Precondition headers
- ETag validation
- Last-Modified validation

### RFC 7234: Caching
- Cache-Control directives
- Expires headers
- Validation model
- Freshness

### RFC 7235: Authentication
- WWW-Authenticate headers
- Authorization schemes
- Authentication protocols

### RFC 6265: HTTP State Management (Cookies)
- Cookie handling
- Set-Cookie attributes
- Cookie security flags

## Adding New Tests

When adding new tests:

1. Identify the relevant RFC section being tested
2. Add tests to the appropriate test suite section
3. Include RFC references in test documentation
4. Verify behavior matches specification requirements
5. Run full test suite to check for regressions

## Test Organization

Tests are organized into logical suites based on HTTP features:

- Content-Length Header
- Status Codes
- Transfer-Encoding
- Request Headers
- HTTP Methods
- Cache Control
- Cookies
- Authentication
- Content Encoding
- Connection Management
- Protocol Versions

## Contributing

When contributing new tests:

1. Review the relevant RFC sections
2. Add meaningful test descriptions
3. Test both valid and invalid cases
4. Include references to specific RFC sections
5. Follow existing test patterns and naming conventions

## References

- [RFC 7230](https://tools.ietf.org/html/rfc7230) - HTTP/1.1 Message Syntax and Routing
- [RFC 7231](https://tools.ietf.org/html/rfc7231) - HTTP/1.1 Semantics and Content
- [RFC 7232](https://tools.ietf.org/html/rfc7232) - HTTP/1.1 Conditional Requests
- [RFC 7233](https://tools.ietf.org/html/rfc7233) - HTTP/1.1 Range Requests
- [RFC 7234](https://tools.ietf.org/html/rfc7234) - HTTP/1.1 Caching
- [RFC 7235](https://tools.ietf.org/html/rfc7235) - HTTP/1.1 Authentication
- [RFC 6265](https://tools.ietf.org/html/rfc6265) - HTTP State Management (Cookies)