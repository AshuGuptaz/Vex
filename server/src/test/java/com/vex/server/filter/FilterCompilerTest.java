package com.vex.server.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FilterCompilerTest {

  private static Map<String, Object> payload(Object... kv) {
    Map<String, Object> m = new HashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      m.put((String) kv[i], kv[i + 1]);
    }
    return m;
  }

  @Test
  void blankExpressionAcceptsEverything() {
    assertThat(FilterCompiler.compile("").test(Map.of())).isTrue();
    assertThat(FilterCompiler.compile(null).test(Map.of("a", 1))).isTrue();
    assertThat(FilterCompiler.compile("   ").test(Map.of())).isTrue();
  }

  @Test
  void numericEqualityMatches() {
    assertThat(FilterCompiler.test("year = 2026", payload("year", 2026))).isTrue();
    assertThat(FilterCompiler.test("year = 2026", payload("year", 2025))).isFalse();
  }

  @Test
  void stringEqualityIsCaseSensitive() {
    assertThat(FilterCompiler.test("category = \"books\"", payload("category", "books"))).isTrue();
    assertThat(FilterCompiler.test("category = \"books\"", payload("category", "Books"))).isFalse();
  }

  @Test
  void singleQuotedStringsAreSupported() {
    assertThat(FilterCompiler.test("category = 'books'", payload("category", "books"))).isTrue();
  }

  @Test
  void notEqualsOperatorFlipsResult() {
    assertThat(FilterCompiler.test("year != 2026", payload("year", 2026))).isFalse();
    assertThat(FilterCompiler.test("year != 2026", payload("year", 2025))).isTrue();
  }

  @Test
  void comparisonOperatorsWorkOnNumbers() {
    assertThat(FilterCompiler.test("price > 10", payload("price", 11))).isTrue();
    assertThat(FilterCompiler.test("price > 10", payload("price", 10))).isFalse();
    assertThat(FilterCompiler.test("price >= 10", payload("price", 10))).isTrue();
    assertThat(FilterCompiler.test("price < 10", payload("price", 9))).isTrue();
    assertThat(FilterCompiler.test("price <= 10", payload("price", 10))).isTrue();
  }

  @Test
  void comparisonOnMixedIntAndDoubleWorks() {
    assertThat(FilterCompiler.test("price > 9.5", payload("price", 10))).isTrue();
    assertThat(FilterCompiler.test("price = 9.5", payload("price", 9.5))).isTrue();
  }

  @Test
  void booleanLiteralsAreSupported() {
    assertThat(FilterCompiler.test("active = true", payload("active", true))).isTrue();
    assertThat(FilterCompiler.test("active = false", payload("active", true))).isFalse();
  }

  @Test
  void andHasHigherPrecedenceThanOr() {
    Map<String, Object> p = payload("a", 1, "b", 2, "c", 3);
    assertThat(FilterCompiler.test("a = 1 AND b = 2 OR c = 99", p)).isTrue();
    assertThat(FilterCompiler.test("a = 1 OR b = 99 AND c = 99", p)).isTrue();
    assertThat(FilterCompiler.test("a = 99 OR b = 99 AND c = 99", p)).isFalse();
  }

  @Test
  void parenthesesOverrideDefaultPrecedence() {
    Map<String, Object> p = payload("a", 1, "b", 2, "c", 3);
    assertThat(FilterCompiler.test("(a = 1 OR b = 99) AND c = 3", p)).isTrue();
    assertThat(FilterCompiler.test("(a = 99 OR b = 99) AND c = 3", p)).isFalse();
  }

  @Test
  void notNegatesPrimary() {
    assertThat(FilterCompiler.test("NOT year = 2026", payload("year", 2025))).isTrue();
    assertThat(FilterCompiler.test("NOT year = 2026", payload("year", 2026))).isFalse();
  }

  @Test
  void notWithParensWorks() {
    Map<String, Object> p = payload("a", 1, "b", 2);
    assertThat(FilterCompiler.test("NOT (a = 1 AND b = 2)", p)).isFalse();
    assertThat(FilterCompiler.test("NOT (a = 99 AND b = 99)", p)).isTrue();
  }

  @Test
  void caseInsensitiveKeywords() {
    Map<String, Object> p = payload("a", 1, "b", 2);
    assertThat(FilterCompiler.test("a = 1 and b = 2", p)).isTrue();
    assertThat(FilterCompiler.test("a = 99 or b = 2", p)).isTrue();
    assertThat(FilterCompiler.test("not a = 99", p)).isTrue();
  }

  @Test
  void missingFieldYieldsFalseOnComparison() {
    assertThat(FilterCompiler.test("year = 2026", payload())).isFalse();
    assertThat(FilterCompiler.test("year > 2026", payload())).isFalse();
  }

  @Test
  void unterminatedStringThrows() {
    assertThatThrownBy(() -> FilterCompiler.compile("a = \"hello"))
        .isInstanceOf(FilterParseException.class);
  }

  @Test
  void unbalancedParensThrow() {
    assertThatThrownBy(() -> FilterCompiler.compile("(a = 1"))
        .isInstanceOf(FilterParseException.class);
  }

  @Test
  void missingOperatorThrows() {
    assertThatThrownBy(() -> FilterCompiler.compile("a 1"))
        .isInstanceOf(FilterParseException.class);
  }

  @Test
  void trailingTokensThrow() {
    assertThatThrownBy(() -> FilterCompiler.compile("a = 1 b = 2"))
        .isInstanceOf(FilterParseException.class);
  }

  @Test
  void identifierWithDotSegmentsIsAllowed() {
    assertThat(FilterCompiler.test("metadata.year = 2026", payload("metadata.year", 2026)))
        .isTrue();
  }

  @Test
  void negativeNumbersParse() {
    assertThat(FilterCompiler.test("score = -1", payload("score", -1))).isTrue();
    assertThat(FilterCompiler.test("score < -0.5", payload("score", -1.0))).isTrue();
  }

  @Test
  void escapedCharactersInStringsResolve() {
    assertThat(FilterCompiler.test("name = \"a\\\"b\"", payload("name", "a\"b"))).isTrue();
  }
}
