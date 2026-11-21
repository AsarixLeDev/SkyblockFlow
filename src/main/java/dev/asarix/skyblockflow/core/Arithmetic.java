package dev.asarix.skyblockflow.core;

import java.math.*;
import java.util.function.ToIntFunction;

/** Decimal arithmetic, without floating-point artifacts or executable expressions. */
public final class Arithmetic {
  private static final MathContext MC = new MathContext(100, RoundingMode.HALF_UP);
  private final String text;
  private int at, depth;

  private Arithmetic(String text) {
    this.text = text.replace(',', '.').replace('×', '*').replace('÷', '/').replace('−', '-');
  }

  public static BigDecimal evaluate(String text) {
    if (text == null || text.isBlank()) return null;
    if (text.length() > 512) throw new IllegalArgumentException("Expression too long");
    var p = new Arithmetic(text);
    var n = p.sum();
    p.space();
    if (p.at != p.text.length()) throw new IllegalArgumentException("Check expression");
    return n.decimal().stripTrailingZeros();
  }

  private record Fraction(BigInteger n, BigInteger d) {
    Fraction {
      if (d.signum() == 0) throw new IllegalArgumentException("Cannot divide by zero");
      if (d.signum() < 0) {
        n = n.negate();
        d = d.negate();
      }
      var gcd = n.gcd(d);
      n = n.divide(gcd);
      d = d.divide(gcd);
    }

    static Fraction of(BigDecimal v) {
      return new Fraction(v.unscaledValue(), BigInteger.TEN.pow(v.scale()));
    }

    Fraction add(Fraction b) {
      return new Fraction(n.multiply(b.d).add(b.n.multiply(d)), d.multiply(b.d));
    }

    Fraction multiply(Fraction b) {
      return new Fraction(n.multiply(b.n), d.multiply(b.d));
    }

    Fraction divide(Fraction b) {
      return new Fraction(n.multiply(b.d), d.multiply(b.n));
    }

    Fraction negate() {
      return new Fraction(n.negate(), d);
    }

    BigDecimal decimal() {
      try {
        return new BigDecimal(n).divide(new BigDecimal(d));
      } catch (ArithmeticException repeating) {
        return new BigDecimal(n).divide(new BigDecimal(d), MC);
      }
    }
  }

  private void space() {
    while (at < text.length() && Character.isWhitespace(text.charAt(at))) at++;
  }

  private boolean eat(char c) {
    space();
    if (at < text.length() && text.charAt(at) == c) {
      at++;
      return true;
    }
    return false;
  }

  private Fraction sum() {
    var a = product();
    while (true) {
      if (eat('+')) a = a.add(product());
      else if (eat('-')) a = a.add(product().negate());
      else return a;
    }
  }

  private Fraction product() {
    var a = atom();
    while (true) {
      if (eat('*')) a = a.multiply(atom());
      else if (eat('/')) {
        var b = atom();
        if (b.n.signum() == 0) throw new IllegalArgumentException("Cannot divide by zero");
        a = a.divide(b);
      } else return a;
    }
  }

  private Fraction atom() {
    if (++depth > 32) throw new IllegalArgumentException("Too many parentheses");
    try {
      if (eat('+')) return atom();
      if (eat('-')) return atom().negate();
      if (eat('(')) {
        var a = sum();
        if (!eat(')')) throw new IllegalArgumentException("Missing )");
        return a;
      }
      space();
      int start = at;
      while (at < text.length() && (Character.isDigit(text.charAt(at)) || text.charAt(at) == '.'))
        at++;
      if (start == at) throw new IllegalArgumentException("Enter a number");
      try {
        return Fraction.of(new BigDecimal(text.substring(start, at)));
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("Check decimal separator");
      }
    } finally {
      depth--;
    }
  }

  public static String fit(BigDecimal value, int pixels, ToIntFunction<String> width) {
    String exact = Numbers.text(value.toPlainString());
    // 100 digits is the computation precision; a repeating quotient must remain marked approximate.
    boolean approximate = value.precision() >= 100;
    if (!approximate && width.applyAsInt(exact) <= pixels) return exact;
    for (int scale = Math.min(99, Math.max(0, value.scale())); scale >= 0; scale--) {
      String s =
          Numbers.text(value.setScale(scale, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()) + "…";
      if (width.applyAsInt(s) <= pixels) return s;
    }
    for (int digits = 12; digits >= 1; digits--) {
      String s = value.round(new MathContext(digits, RoundingMode.HALF_UP)).toString() + "…";
      if (width.applyAsInt(s) <= pixels) return s;
    }
    return "…";
  }
}
