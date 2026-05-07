package com.vex.server.filter;

import java.util.ArrayList;
import java.util.List;

/** Hand-rolled lexer for the Vex filter expression grammar. */
final class FilterLexer {

  enum Type {
    LPAREN,
    RPAREN,
    AND,
    OR,
    NOT,
    EQ,
    NEQ,
    LT,
    LE,
    GT,
    GE,
    IDENT,
    NUMBER,
    STRING,
    BOOL,
    EOF
  }

  record Token(Type type, String text, Object value, int pos) {}

  private final String src;
  private int pos;

  FilterLexer(String src) {
    this.src = src;
    this.pos = 0;
  }

  List<Token> tokenize() {
    List<Token> out = new ArrayList<>();
    while (pos < src.length()) {
      char c = src.charAt(pos);
      if (Character.isWhitespace(c)) {
        pos++;
        continue;
      }
      int start = pos;
      switch (c) {
        case '(' -> {
          out.add(new Token(Type.LPAREN, "(", null, start));
          pos++;
        }
        case ')' -> {
          out.add(new Token(Type.RPAREN, ")", null, start));
          pos++;
        }
        case '=' -> {
          out.add(new Token(Type.EQ, "=", null, start));
          pos++;
        }
        case '!' -> {
          if (peek(1) != '=') {
            throw new FilterParseException("Expected '!=' at " + start);
          }
          out.add(new Token(Type.NEQ, "!=", null, start));
          pos += 2;
        }
        case '<' -> {
          if (peek(1) == '=') {
            out.add(new Token(Type.LE, "<=", null, start));
            pos += 2;
          } else {
            out.add(new Token(Type.LT, "<", null, start));
            pos++;
          }
        }
        case '>' -> {
          if (peek(1) == '=') {
            out.add(new Token(Type.GE, ">=", null, start));
            pos += 2;
          } else {
            out.add(new Token(Type.GT, ">", null, start));
            pos++;
          }
        }
        case '"', '\'' -> out.add(readString(c, start));
        default -> {
          if (Character.isDigit(c) || c == '-' || c == '+') {
            out.add(readNumber(start));
          } else if (Character.isLetter(c) || c == '_') {
            out.add(readIdentifier(start));
          } else {
            throw new FilterParseException("Unexpected character '" + c + "' at " + start);
          }
        }
      }
    }
    out.add(new Token(Type.EOF, "", null, pos));
    return out;
  }

  private char peek(int offset) {
    int p = pos + offset;
    return p < src.length() ? src.charAt(p) : '\0';
  }

  private Token readString(char quote, int start) {
    StringBuilder sb = new StringBuilder();
    pos++;
    while (pos < src.length()) {
      char c = src.charAt(pos);
      if (c == '\\' && pos + 1 < src.length()) {
        char next = src.charAt(pos + 1);
        sb.append(
            switch (next) {
              case 'n' -> '\n';
              case 't' -> '\t';
              case 'r' -> '\r';
              case '\\' -> '\\';
              case '\'' -> '\'';
              case '"' -> '"';
              default -> next;
            });
        pos += 2;
      } else if (c == quote) {
        pos++;
        return new Token(Type.STRING, sb.toString(), sb.toString(), start);
      } else {
        sb.append(c);
        pos++;
      }
    }
    throw new FilterParseException("Unterminated string starting at " + start);
  }

  private Token readNumber(int start) {
    int s = pos;
    if (src.charAt(pos) == '+' || src.charAt(pos) == '-') {
      pos++;
    }
    boolean seenDot = false;
    boolean seenE = false;
    while (pos < src.length()) {
      char c = src.charAt(pos);
      if (Character.isDigit(c)) {
        pos++;
      } else if (c == '.' && !seenDot && !seenE) {
        seenDot = true;
        pos++;
      } else if ((c == 'e' || c == 'E') && !seenE) {
        seenE = true;
        pos++;
        if (pos < src.length() && (src.charAt(pos) == '+' || src.charAt(pos) == '-')) {
          pos++;
        }
      } else {
        break;
      }
    }
    String text = src.substring(s, pos);
    if (text.equals("-") || text.equals("+") || text.isEmpty()) {
      throw new FilterParseException("Invalid number at " + start);
    }
    Object value;
    if (seenDot || seenE) {
      try {
        value = Double.parseDouble(text);
      } catch (NumberFormatException e) {
        throw new FilterParseException("Invalid number '" + text + "' at " + start);
      }
    } else {
      try {
        value = Long.parseLong(text);
      } catch (NumberFormatException e) {
        try {
          value = Double.parseDouble(text);
        } catch (NumberFormatException e2) {
          throw new FilterParseException("Invalid number '" + text + "' at " + start);
        }
      }
    }
    return new Token(Type.NUMBER, text, value, start);
  }

  private Token readIdentifier(int start) {
    int s = pos;
    while (pos < src.length()) {
      char c = src.charAt(pos);
      if (Character.isLetterOrDigit(c) || c == '_' || c == '.') {
        pos++;
      } else {
        break;
      }
    }
    String text = src.substring(s, pos);
    String upper = text.toUpperCase();
    return switch (upper) {
      case "AND" -> new Token(Type.AND, text, null, start);
      case "OR" -> new Token(Type.OR, text, null, start);
      case "NOT" -> new Token(Type.NOT, text, null, start);
      case "TRUE" -> new Token(Type.BOOL, text, Boolean.TRUE, start);
      case "FALSE" -> new Token(Type.BOOL, text, Boolean.FALSE, start);
      default -> new Token(Type.IDENT, text, null, start);
    };
  }
}
