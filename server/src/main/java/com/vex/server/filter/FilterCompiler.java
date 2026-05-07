package com.vex.server.filter;

import com.vex.server.filter.FilterLexer.Token;
import com.vex.server.filter.FilterLexer.Type;
import java.util.List;
import java.util.Map;

/**
 * Compiles a filter expression string into a {@link FilterPredicate}.
 *
 * <p>Grammar:
 *
 * <pre>
 *   expr        := orExpr
 *   orExpr      := andExpr ("OR" andExpr)*
 *   andExpr     := notExpr ("AND" notExpr)*
 *   notExpr     := "NOT"? primary
 *   primary     := "(" expr ")" | comparison
 *   comparison  := identifier op literal
 *   op          := "=" | "!=" | ">" | "<" | ">=" | "<="
 *   literal     := number | quoted-string | boolean
 * </pre>
 */
public final class FilterCompiler {

  private final List<Token> tokens;
  private int idx;

  private FilterCompiler(List<Token> tokens) {
    this.tokens = tokens;
    this.idx = 0;
  }

  /** Returns {@link FilterPredicate#ALWAYS_TRUE} for null or blank input. */
  public static FilterPredicate compile(String expression) {
    if (expression == null || expression.isBlank()) {
      return FilterPredicate.ALWAYS_TRUE;
    }
    List<Token> tokens = new FilterLexer(expression).tokenize();
    FilterCompiler c = new FilterCompiler(tokens);
    FilterPredicate p = c.parseExpr();
    if (c.peek().type() != Type.EOF) {
      throw new FilterParseException(
          "Unexpected trailing token '" + c.peek().text() + "' at " + c.peek().pos());
    }
    return p;
  }

  private FilterPredicate parseExpr() {
    return parseOr();
  }

  private FilterPredicate parseOr() {
    FilterPredicate left = parseAnd();
    while (peek().type() == Type.OR) {
      consume();
      FilterPredicate right = parseAnd();
      left = left.or(right);
    }
    return left;
  }

  private FilterPredicate parseAnd() {
    FilterPredicate left = parseNot();
    while (peek().type() == Type.AND) {
      consume();
      FilterPredicate right = parseNot();
      left = left.and(right);
    }
    return left;
  }

  private FilterPredicate parseNot() {
    if (peek().type() == Type.NOT) {
      consume();
      return parsePrimary().negate();
    }
    return parsePrimary();
  }

  private FilterPredicate parsePrimary() {
    if (peek().type() == Type.LPAREN) {
      consume();
      FilterPredicate p = parseExpr();
      expect(Type.RPAREN);
      return p;
    }
    return parseComparison();
  }

  private FilterPredicate parseComparison() {
    Token ident = expect(Type.IDENT);
    Token op = peek();
    if (op.type() != Type.EQ
        && op.type() != Type.NEQ
        && op.type() != Type.LT
        && op.type() != Type.LE
        && op.type() != Type.GT
        && op.type() != Type.GE) {
      throw new FilterParseException(
          "Expected comparison operator at " + op.pos() + ", got '" + op.text() + "'");
    }
    consume();
    Token lit = peek();
    if (lit.type() != Type.NUMBER && lit.type() != Type.STRING && lit.type() != Type.BOOL) {
      throw new FilterParseException(
          "Expected literal after operator at " + lit.pos() + ", got '" + lit.text() + "'");
    }
    consume();
    String field = ident.text();
    Object literal = lit.value();
    Type opType = op.type();
    return payload -> compare(payload.get(field), opType, literal);
  }

  private static boolean compare(Object actual, Type op, Object literal) {
    if (op == Type.EQ) {
      return equalsLoose(actual, literal);
    }
    if (op == Type.NEQ) {
      return !equalsLoose(actual, literal);
    }
    Double a = asNumber(actual);
    Double b = asNumber(literal);
    if (a == null || b == null) {
      return false;
    }
    return switch (op) {
      case LT -> a < b;
      case LE -> a <= b;
      case GT -> a > b;
      case GE -> a >= b;
      default -> false;
    };
  }

  private static boolean equalsLoose(Object actual, Object literal) {
    if (actual == null) {
      return false;
    }
    if (actual.equals(literal)) {
      return true;
    }
    Double a = asNumber(actual);
    Double b = asNumber(literal);
    if (a != null && b != null) {
      return a.doubleValue() == b.doubleValue();
    }
    if (literal instanceof String s && actual instanceof String t) {
      return s.equals(t);
    }
    return false;
  }

  private static Double asNumber(Object o) {
    if (o instanceof Number n) {
      return n.doubleValue();
    }
    return null;
  }

  private Token peek() {
    return tokens.get(idx);
  }

  private Token consume() {
    return tokens.get(idx++);
  }

  private Token expect(Type t) {
    Token tk = peek();
    if (tk.type() != t) {
      throw new FilterParseException(
          "Expected " + t + " at " + tk.pos() + ", got '" + tk.text() + "'");
    }
    return consume();
  }

  /** Lets external callers compile and immediately apply a predicate to a single payload. */
  public static boolean test(String expression, Map<String, Object> payload) {
    return compile(expression).test(payload);
  }
}
