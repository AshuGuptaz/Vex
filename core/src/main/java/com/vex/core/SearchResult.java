package com.vex.core;

/** A single (id, distance) pair returned from an index query. Smaller distance is closer. */
public record SearchResult(long id, float distance) implements Comparable<SearchResult> {

  @Override
  public int compareTo(SearchResult o) {
    return Float.compare(this.distance, o.distance);
  }
}
