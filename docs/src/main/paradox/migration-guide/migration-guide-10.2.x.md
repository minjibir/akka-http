# Migration Guide to and within Akka HTTP 10.2.x

## General Notes

See the general @ref[compatibility guidelines](../compatibility-guidelines.md).

Under these guidelines, minor version updates are supposed to be binary compatible and drop-in replacements
for former versions under the condition that user code only uses public, stable, non-deprecated API. Especially
libraries should make sure not to depend on deprecated API to be compatible with both 10.1.x and 10.2.x.

If you find an unexpected incompatibility please let us know, so we can check whether the incompatibility is accidental so we might still be able to fix it.

## Akka HTTP 10.1.11 -> 10.2.0

### HTTP/2 support requires JDK 8 update 252 or later

JVM support for ALPN has been backported to JDK 8u252 which is now widely available. Support for using the Jetty ALPN
agent has been dropped in 10.2.0. HTTP/2 support therefore now requires to be run on JVM >= 8u252.

### Scalatest dependency upgraded to 3.1.0

The Scalatest dependency for akka-http-testkit was upgraded to version 3.1.0. This version is incompatible with previous
versions. This is relevant for user code if it uses methods from @scaladoc[ScalatestUtils](akka.http.scaladsl.testkit.ScalatestUtils)
(which are in scope if your test extends from @scaladoc[ScalaTestRouteTest](akka.http.scaladsl.testkit.ScalaTestRouteTest)).
In this case, the project itself needs to be updated to use Scalatest >= 3.1.0.

### Providing route settings, exception and rejection handling

Previously, the implicit conversion `route2HandlerFlow` that turns a `Route` into a
`Flow[HttpRequest, HttpResponse]` allowed implicitly passing in Routing settings,
Parser settings, custom `Materializer`, `RoutingLog` or `ExecutionContext`, and
the `RejectionHandler`/`ExceptionHandler` to use.

This has been simplified to use system defaults instead:

* To set Routing or Parser settings, set them in your actor system configuration, e.g. via `application.conf`. In the rare case where you'd like to test routes with different @apidoc[RoutingSettings] within the same test, you can use the @ref[withSettings](../routing-dsl/directives/basic-directives/withSettings.md) directive.
* To apply a custom @apidoc[http.*.RejectionHandler] or @apidoc[ExceptionHandler], use the @ref[handleRejections](../routing-dsl/directives/execution-directives/handleRejections.md) and @ref[handleExceptions](../routing-dsl/directives/execution-directives/handleExceptions.md) directives

#### In RouteTest (scaladsl)

Similarly, @apidoc[RouteTest] no longer automatically picks up such implicit values, and you
can use the same approach to explicitly set them instead.

### Strict query strings

In 10.1.x, while parsing the query string of a URI, characters were accepted that are
not allowed according to RFC 3986, even when `parsing.uri-parsing-mode` was
set to the default value of `strict`. Parsing such URIs will now fail in `strict` mode.
If you want to allow such characters in incoming URIs, set `parsing.uri-parsing-mode` to `relaxed`, in which case these characters will be percent-encoded automatically.

### 'Transparent HEAD requests' now disabled by default

Prior to 10.2.0, when a client would perform a `HEAD` request, by default Akka HTTP would call the `GET` route but discard the body.
This can save bandwidth in some cases, but is also counter-intuitive when you actually want to explicitly handle `HEAD` requests,
 and may increase resource usage in cases where the logic behind the `GET` request is heavy. For this reason we have changed
 the default value of `akka.http.server.transparent-head-requests` to `off`, making this feature opt-in.

### X-Real-Ip now takes precedence over Remote-Address in extractClientIP

The @ref[extractClientIP](../routing-dsl/directives/misc-directives/extractClientIP.md) now returns the value of the
`X-Real-Ip` header when both an`X-Real-Ip` and a `Remote-Address` header is available. This directive provides a
'best guess' of the client IP, but in a way that allows any client to provide this address in the header. For this reason
you should never trust this value for security purposes.

When you need a secure way to get the client IP, use the @apidoc[AttributeKeys.remoteAddress](AttributeKeys) @ref[attribute](../common/http-model.md#attributes),
or use the specific headers which are known to be set correctly by the infrastructure you do trust.

### max-content-length

The `max-content-length` setting can no longer be set on `akka.http.parsing`, and instead
must now be set explicitly on `akka.http.client.parsing` and/or `akka.http.server.parsing`.
The default for `akka.http.client.parsing.max-content-length` has been changed from `8m`
to `infinite`.

### Removal of legacy host connection pool

The legacy host connection pool is the original client pool implementation from the beginnings of Akka HTTP backing
`Http().singleRequest` and other client APIs. During the 10.0.x development the pool was reimplemented and could be
used in an opt-in fashion. Since 10.1.0, this new pool implementation has been the default. With 10.2.0 we took the
opportunity to remove the old pool implementation. The setting `akka.http.host-connection-pool.pool-implementation`
has been removed as well as its code representation in `ConnectionPoolSettings`.