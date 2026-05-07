JAVA_HOME ?= /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
MVN := JAVA_HOME=$(JAVA_HOME) PATH=$(JAVA_HOME)/bin:$(PATH) mvn

.PHONY: help build test format verify run \
        bench bench-recall bench-memory bench-jmh bench-sift sift-data \
        clean

help:
	@echo "Targets:"
	@echo "  build           — mvn clean package -DskipTests"
	@echo "  test            — run all tests"
	@echo "  format          — apply google-java-format"
	@echo "  verify          — full reactor verify (format + tests)"
	@echo "  run             — start the server on :8080"
	@echo ""
	@echo "  bench           — alias for bench-recall + bench-memory"
	@echo "  bench-recall    — synthetic 100k recall@10 sweep"
	@echo "  bench-memory    — float vs int8 heap-delta comparison"
	@echo "  bench-jmh       — JMH percentile distribution"
	@echo "  bench-sift      — SIFT-1M sweep (needs sift-data first)"
	@echo "  sift-data       — download SIFT-1M to ~/sift"
	@echo ""
	@echo "  clean           — mvn clean"

build:
	$(MVN) -B clean package -DskipTests

test:
	$(MVN) -B test

format:
	$(MVN) com.spotify.fmt:fmt-maven-plugin:format

verify:
	$(MVN) -B clean verify

run:
	$(MVN) -pl server -am spring-boot:run

bench: bench-recall bench-memory

bench-recall:
	$(MVN) -B -pl bench -am -Pbench-recall verify

bench-memory:
	$(MVN) -B -pl bench -am -Pbench-memory verify

bench-jmh:
	$(MVN) -B -pl bench -am -Pbench-jmh verify

bench-sift:
	$(MVN) -B -pl bench -am -Pbench-sift verify

sift-data:
	bash scripts/download_sift.sh

clean:
	$(MVN) clean
