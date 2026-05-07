package com.vex.server.api;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Liveness probe endpoint at {@code /health}. Returns {@code {"status":"ok"}}. */
@RestController
public class HealthController {

  /** Returns a fixed JSON body that platforms / load balancers can poll. */
  @GetMapping("/health")
  public Map<String, Object> health() {
    return Map.of("status", "ok");
  }
}
