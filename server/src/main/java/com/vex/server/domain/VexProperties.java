package com.vex.server.domain;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Top-level Vex server configuration. Bound from {@code vex.*} properties. */
@Component
@ConfigurationProperties(prefix = "vex")
public class VexProperties {

  private String dataDir = "./data";
  private String walFsync = "per-write";

  /** Directory under which collections are persisted (default {@code ./data}). */
  public String getDataDir() {
    return dataDir;
  }

  public void setDataDir(String dataDir) {
    this.dataDir = dataDir;
  }

  /**
   * WAL fsync mode: {@code per-write} (default, durable) or {@code never} (faster, lossy on crash).
   */
  public String getWalFsync() {
    return walFsync;
  }

  public void setWalFsync(String walFsync) {
    this.walFsync = walFsync;
  }
}
