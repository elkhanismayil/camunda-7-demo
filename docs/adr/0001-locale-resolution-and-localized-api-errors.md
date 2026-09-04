# 1. Locale resolution and localized API errors

- **Status:** Accepted
- **Date:** 2026-09-04

## Context

The application has two surfaces that both render text: the Thymeleaf UI at
`/orders` and the REST API under `/api/orders`. All of that text was hardcoded —
English in the template, and English sentences built by string concatenation in
`OrderViewController`. The API had no error body at all: three code paths
returned a bare `404` with an empty response.

Adding three languages (Azerbaijani, English, Spanish) forced several decisions
that are not obvious from the code alone.

## Decisions

### One `LocaleResolver`, cookie first and `Accept-Language` second

Spring uses exactly one `LocaleResolver` per DispatcherServlet, and the two
surfaces want different behaviour from it. A plain `CookieLocaleResolver` serves
the UI and makes the API ignore `Accept-Language`; a plain
`AcceptHeaderLocaleResolver` does the reverse.

We use a `CookieLocaleResolver` whose `defaultLocaleFunction` reads the request's
`Accept-Language`, falling back to Azerbaijani. Precedence is therefore
cookie → header → `az`, which satisfies both surfaces with one bean.

### Azerbaijani lives in `messages.properties`, with no `messages_az.properties`

Azerbaijani is both the default language and the fallback for anything
unsupported, so it is the default bundle. A `messages_az.properties` would be a
second copy of the same strings and a place for them to drift apart.

This makes `spring.messages.fallback-to-system-locale: false` mandatory, not
cosmetic. With the system-locale fallback left on, a lookup for `az` would fall
through to the *host JVM's* locale bundle before reaching the default one — so a
server started with an English locale would answer Azerbaijani requests in
English.

### An unsupported language resolves to Azerbaijani, and does not stick

Both the resolver and the `LocaleChangeInterceptor` filter incoming language
values against the supported set. `?lang=fr` clears the cookie rather than
storing a language with no bundle, so the reader gets one consistent language
instead of a page that is French in `lang=` and Azerbaijani in its text.

Regions are dropped: `es-MX` resolves to `es`. The application ships three
language bundles, not regional variants, and keeping the region would make the
rendered date format depend on which browser sent the request.

### Flash messages carry a code and arguments, never finished text

`OrderViewController` flashes `messageCode` plus `messageArgs`. The redirect
that follows a POST is a separate request, and the reader may have switched
language in between; resolving the text in the controller would freeze it to the
language of the POST. It also keeps presentation strings out of Java and lets
tests assert on a stable code rather than on translated prose.

The template resolves them with `#messages.msgWithParams(...)`. The more obvious
`#{${messageCode}(${messageArgs})}` does **not** work: Thymeleaf treats the whole
argument array as a single parameter, so `{0}` renders the array's `toString`
(`[Ljava.lang.Object;@76ea6f89`) instead of the correlation id.

### `404` gains a JSON body — an accepted contract change

`OrderController` now throws `OrderNotFoundException`, and a single
`@RestControllerAdvice` turns it into a `404` carrying `status`, `errorCode`,
localized `message`, `timestamp`, `path` and `traceId`. The status code is
unchanged; existing clients that only check the status are unaffected, and
clients that parsed the (empty) body now get a documented shape.

`OrderService` still returns `Optional`/`boolean` and throws nothing: an expected
empty result is not control flow through exceptions. The translation from
"absent" to "404" happens at the HTTP boundary, where it belongs.

### `traceId` is generated in the advice, not read from an MDC

`configuration.md` describes a `OncePerRequestFilter` populating a log4j2 MDC
with a trace context. This service has no such filter and no log4j2 setup, and
building that stack was explicitly out of scope for this change. The advice
generates a UUID, logs the failure under it, returns it in the body and echoes it
in the `X-Trace-Id` header.

If the MDC stack arrives later, the advice reads the existing trace id instead of
generating one; the response contract does not change.

## Consequences

- A key added to one bundle must be added to all three. `MessageBundleParityTest`
  enforces this, because a missing key fails silently at runtime.
- Translators need to know that a single quote is `MessageFormat`'s escape
  character in any message taking `{0}` parameters.
- The badge CSS classes stay keyed on the raw `OrderStatus` enum name; only the
  label is translated, so the colours survive a language switch.
- `springdoc-openapi` was added so the new error shape is a published contract
  rather than a README paragraph. It is the first OpenAPI tooling in this repo;
  the four existing endpoints were annotated as part of the same change.
