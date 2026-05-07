# ADR 006: Spring Boot for the REST layer (instead of raw Netty)

**Date:** 2026-05-07
**Status:** Accepted

## Context

The Vex REST API needs HTTP routing, JSON marshalling, validation,
configuration binding, and lifecycle management. Two reasonable options:

- **Spring Boot 3** — opinionated, includes auto-configuration, validation,
  Jackson, embedded Tomcat, dependency injection, springdoc-openapi for
  free.
- **Raw Netty + Jackson** — minimal, you wire everything yourself,
  smallest container image, lowest startup time.

## Decision

Spring Boot 3.

## Why

- **The work isn't HTTP, it's HNSW.** Every hour spent on routing
  boilerplate is an hour not spent on the index or storage layer.
- **Validation, exception mapping, content negotiation are solved.**
  Spring's `@Valid`, `@ControllerAdvice`, and Jackson auto-configuration
  collapse 100+ lines of boilerplate per controller into annotations.
- **springdoc-openapi.** Drop-in OpenAPI + Swagger UI is essentially free
  with Spring Boot. With Netty we'd hand-roll an OpenAPI document.
- **Configuration.** `@ConfigurationProperties` binds env vars, YAML, and
  command-line flags consistently. Implementing the same with Netty
  requires picking a config library and integrating it.
- **Hireable patterns.** Senior engineers reviewing this repo will
  recognize the Spring patterns immediately. Raw Netty is more of a
  "look how clever I am" choice.

## Costs

- **Container image is bigger.** Jib produces a ~200 MB image with
  Spring Boot vs. ~80 MB for Netty alone. Acceptable.
- **Cold start is slower.** ~3-4 seconds for Spring Boot vs. ~500 ms for
  Netty. Vex is a server, not a serverless function — startup time
  doesn't dominate.
- **Reflection-heavy.** GraalVM native image support exists but isn't
  free. Skipped for v1.

## What we use

- `spring-boot-starter-web` — Tomcat + Jackson + Spring MVC.
- `spring-boot-starter-validation` — Hibernate Validator.
- `springdoc-openapi-starter-webmvc-ui` — OpenAPI + Swagger UI.
- `@ConfigurationProperties` for `vex.data-dir`, `vex.wal-fsync`.

## What we explicitly skipped

- **Spring Security.** No auth in v1. The README documents this clearly
  and treats network-level isolation as the deployment story.
- **Spring Data.** We persist with our own format; no JPA, no
  repositories.
- **Reactive (`spring-boot-starter-webflux`).** The HNSW index is
  CPU-bound and lock-bounded; reactive doesn't buy us anything here.
- **Spring Boot Actuator.** Useful for production but adds dependencies;
  out of scope for the portfolio version. `/health` is hand-written.
