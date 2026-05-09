package com.vex.server.api;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Forwards SPA routes ({@code /}, {@code /playground}) to the bundled {@code static/index.html} so
 * React Router picks them up. The API endpoints (under {@code /collections}, {@code /health},
 * {@code /v3/api-docs}, {@code /swagger-ui*}) keep matching their controllers because Spring's
 * mapping table prefers more-specific routes first.
 */
@Controller
public class SpaController {

  /** Catches the SPA's client-side routes and serves the React shell. */
  @GetMapping({"/", "/playground"})
  public String forwardToSpa() {
    return "forward:/index.html";
  }
}
