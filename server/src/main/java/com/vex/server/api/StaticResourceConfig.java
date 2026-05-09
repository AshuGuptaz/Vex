package com.vex.server.api;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Cache headers for the SPA assets:
 *
 * <ul>
 *   <li>{@code /index.html} — never cache. The asset filenames inside it are content-hashed by
 *       Vite, so each new build references new files. We must always fetch the current index.html
 *       to learn the right hashes.
 *   <li>{@code /assets/**} — immutable + 1y cache. Filenames change on every build; the contents at
 *       any given filename never do.
 * </ul>
 */
@Configuration
public class StaticResourceConfig implements WebMvcConfigurer {

  @Override
  public void addResourceHandlers(ResourceHandlerRegistry registry) {
    registry
        .addResourceHandler("/assets/**")
        .addResourceLocations("classpath:/static/assets/")
        .setCacheControl(CacheControl.maxAge(java.time.Duration.ofDays(365)).immutable());

    registry
        .addResourceHandler("/index.html")
        .addResourceLocations("classpath:/static/")
        .setCacheControl(CacheControl.noCache().mustRevalidate());
  }
}
