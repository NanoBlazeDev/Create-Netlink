package dev.exavolt.netlink.script;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;

/**
 * Turns the Luau that players write into plain Lua 5.2 for LuaJ, keeping line numbers intact so errors point at the
 * right line. Supported Luau extras: type annotations, type declarations, casts ({@code ::}), generics, compound
 * assignment ({@code += -= *= /= //= %= ^= ..=}), {@code continue}, string interpolation and floor division in
 * compound form. Anything else is passed through unchanged.
 */
public final class LuauTranspiler {
	private LuauTranspiler() {
	}

	public static final class TranspileError extends RuntimeException {
		public final int line;

		TranspileError(int line, String message) {
			super(message);
			this.line = line;
		}
	}

	enum K { WS, COMMENT, NAME, NUMBER, STRING, INTERP, OP, EOF }

	static final class Tok {
		K k;
		String t;
		final int line;

		Tok(K k, String t, int line) {
			this.k = k;
			this.t = t;
			this.line = line;
		}

		boolean is(String s) {
			return (k == K.OP || k == K.NAME) && t.equals(s);
		}

		@Override
		public String toString() {
			return k + ":" + t;
		}
	}

	private static final Set<String> KEYWORDS = Set.of("and", "break", "do", "else", "elseif", "end", "false", "for", "function",
		"goto", "if", "in", "local", "nil", "not", "or", "repeat", "return", "then", "true", "until", "while", "continue");

	private static final String[] OPS = {"...", "//=", "..=", "==", "~=", "<=", ">=", "+=", "-=", "*=", "/=", "%=", "^=", "//", "..", "::",
		"->", "+", "-", "*", "/", "%", "^", "#", "<", ">", "=", "(", ")", "{", "}", "[", "]", ";", ":", ",", ".", "?", "|", "&", "!", "@"};

	// ---------------------------------------------------------------- tokenizer

	static List<Tok> tokenize(String src) {
		List<Tok> out = new ArrayList<>();
		int i = 0, n = src.length(), line = 1;
		while (i < n) {
			char c = src.charAt(i);
			int start = i;
			int startLine = line;
			if (c == '\n' || c == ' ' || c == '\t' || c == '\r') {
				while (i < n && (src.charAt(i) == '\n' || src.charAt(i) == ' ' || src.charAt(i) == '\t' || src.charAt(i) == '\r')) {
					if (src.charAt(i) == '\n') {
						line++;
					}
					i++;
				}
				out.add(new Tok(K.WS, src.substring(start, i), startLine));
				continue;
			}
			if (c == '-' && i + 1 < n && src.charAt(i + 1) == '-') {
				i += 2;
				int level = longBracketLevel(src, i);
				if (level >= 0) {
					int end = findLongClose(src, i + level + 2, level);
					if (end < 0) {
						throw new TranspileError(startLine, "unfinished long comment");
					}
					i = end;
				} else {
					while (i < n && src.charAt(i) != '\n') {
						i++;
					}
				}
				String text = src.substring(start, i);
				line += count(text, '\n');
				out.add(new Tok(K.COMMENT, text, startLine));
				continue;
			}
			if (c == '[' && longBracketLevel(src, i) >= 0) {
				int level = longBracketLevel(src, i);
				int end = findLongClose(src, i + level + 2, level);
				if (end < 0) {
					throw new TranspileError(startLine, "unfinished long string");
				}
				i = end;
				String text = src.substring(start, i);
				line += count(text, '\n');
				out.add(new Tok(K.STRING, text, startLine));
				continue;
			}
			if (c == '"' || c == '\'') {
				i++;
				while (i < n && src.charAt(i) != c) {
					if (src.charAt(i) == '\\') {
						i++;
					} else if (src.charAt(i) == '\n') {
						throw new TranspileError(line, "unfinished string");
					}
					i++;
				}
				if (i >= n) {
					throw new TranspileError(startLine, "unfinished string");
				}
				i++;
				out.add(new Tok(K.STRING, src.substring(start, i), startLine));
				continue;
			}
			if (c == '`') {
				i++;
				int depth = 0;
				while (i < n) {
					char d = src.charAt(i);
					if (d == '\\') {
						i += 2;
						continue;
					}
					if (d == '{') {
						depth++;
					} else if (d == '}') {
						depth--;
					} else if (d == '`' && depth == 0) {
						break;
					} else if (d == '\n') {
						line++;
					}
					i++;
				}
				if (i >= n) {
					throw new TranspileError(startLine, "unfinished interpolated string");
				}
				i++;
				out.add(new Tok(K.INTERP, src.substring(start, i), startLine));
				continue;
			}
			if (Character.isDigit(c) || (c == '.' && i + 1 < n && Character.isDigit(src.charAt(i + 1)))) {
				if (c == '0' && i + 1 < n && (src.charAt(i + 1) == 'x' || src.charAt(i + 1) == 'X')) {
					i += 2;
					while (i < n && (Character.isLetterOrDigit(src.charAt(i)) || src.charAt(i) == '_' || src.charAt(i) == '.')) {
						i++;
					}
				} else if (c == '0' && i + 1 < n && (src.charAt(i + 1) == 'b' || src.charAt(i + 1) == 'B')) {
					i += 2;
					int s2 = i;
					while (i < n && (src.charAt(i) == '0' || src.charAt(i) == '1' || src.charAt(i) == '_')) {
						i++;
					}
					long v = Long.parseLong(src.substring(s2, i).replace("_", ""), 2);
					out.add(new Tok(K.NUMBER, String.valueOf(v), startLine));
					continue;
				} else {
					while (i < n) {
						char d = src.charAt(i);
						if (Character.isDigit(d) || d == '.' || d == '_') {
							i++;
						} else if ((d == 'e' || d == 'E')) {
							i++;
							if (i < n && (src.charAt(i) == '+' || src.charAt(i) == '-')) {
								i++;
							}
						} else {
							break;
						}
					}
				}
				out.add(new Tok(K.NUMBER, src.substring(start, i).replace("_", ""), startLine));
				continue;
			}
			if (Character.isLetter(c) || c == '_') {
				while (i < n && (Character.isLetterOrDigit(src.charAt(i)) || src.charAt(i) == '_')) {
					i++;
				}
				out.add(new Tok(K.NAME, src.substring(start, i), startLine));
				continue;
			}
			boolean matched = false;
			for (String op : OPS) {
				if (src.startsWith(op, i)) {
					out.add(new Tok(K.OP, op, startLine));
					i += op.length();
					matched = true;
					break;
				}
			}
			if (!matched) {
				throw new TranspileError(line, "unexpected symbol '" + c + "'");
			}
		}
		out.add(new Tok(K.EOF, "", line));
		return out;
	}

	private static int count(String s, char c) {
		int k = 0;
		for (int i = 0; i < s.length(); i++) {
			if (s.charAt(i) == c) {
				k++;
			}
		}
		return k;
	}

	/** Level of a long bracket {@code [==[} starting at i, or -1. */
	private static int longBracketLevel(String s, int i) {
		if (i >= s.length() || s.charAt(i) != '[') {
			return -1;
		}
		int j = i + 1, level = 0;
		while (j < s.length() && s.charAt(j) == '=') {
			level++;
			j++;
		}
		return j < s.length() && s.charAt(j) == '[' ? level : -1;
	}

	private static int findLongClose(String s, int from, int level) {
		String close = "]" + "=".repeat(level) + "]";
		int idx = s.indexOf(close, from);
		return idx < 0 ? -1 : idx + close.length();
	}

	// ---------------------------------------------------------------- the transform

	private final static class State {
		final List<Tok> toks;

		State(List<Tok> toks) {
			this.toks = toks;
		}

		/** Index of the next significant token at or after i. */
		int sig(int i) {
			while (i < toks.size() && (toks.get(i).k == K.WS || toks.get(i).k == K.COMMENT || toks.get(i).t.isEmpty() && toks.get(i).k != K.EOF)) {
				i++;
			}
			return Math.min(i, toks.size() - 1);
		}

		/** Index of the previous significant token before i, or -1. */
		int prev(int i) {
			i--;
			while (i >= 0 && (toks.get(i).k == K.WS || toks.get(i).k == K.COMMENT || toks.get(i).t.isEmpty())) {
				i--;
			}
			return i;
		}

		Tok at(int i) {
			return toks.get(i);
		}

		/** Blanks tokens [from, to) keeping newlines so line numbers survive. */
		void blank(int from, int to) {
			for (int i = from; i < to; i++) {
				Tok t = toks.get(i);
				if (t.k == K.EOF) {
					continue;
				}
				String nl = "\n".repeat(count(t.t, '\n'));
				t.t = nl.isEmpty() ? (t.k == K.WS ? t.t : " ") : nl;
				t.k = K.WS;
			}
		}

		/** Skips a balanced group starting at the opening token i; returns the index after the closer. */
		int balanced(int i, String open, String close) {
			int depth = 0;
			for (int j = i; j < toks.size(); j++) {
				Tok t = toks.get(j);
				if (t.k == K.EOF) {
					throw new TranspileError(toks.get(i).line, "missing '" + close + "'");
				}
				if (t.is(open)) {
					depth++;
				} else if (t.is(close)) {
					depth--;
					if (depth == 0) {
						return j + 1;
					}
				}
			}
			throw new TranspileError(toks.get(i).line, "missing '" + close + "'");
		}

		/** Skips a type starting at i (significant); returns the index after it. */
		int skipType(int i) {
			i = skipSimpleType(sig(i));
			while (true) {
				int j = sig(i);
				if (at(j).is("|") || at(j).is("&")) {
					i = skipSimpleType(sig(j + 1));
				} else {
					return i;
				}
			}
		}

		int skipSimpleType(int i) {
			Tok t = at(i);
			int end;
			if (t.is("(")) {
				end = balanced(i, "(", ")");
				int j = sig(end);
				if (at(j).is("->")) {
					end = skipType(j + 1);
				}
			} else if (t.is("{")) {
				end = balanced(i, "{", "}");
			} else if (t.is("<")) {
				// generic function type: <T>(a: T) -> T
				end = balanced(i, "<", ">");
				end = skipSimpleType(sig(end));
			} else if (t.is("typeof")) {
				end = balanced(sig(i + 1), "(", ")");
			} else if (t.is("...")) {
				end = skipSimpleType(sig(i + 1));
			} else if (t.k == K.NAME || t.k == K.STRING) {
				end = i + 1;
				while (true) {
					int j = sig(end);
					if (at(j).is(".") && at(sig(j + 1)).k == K.NAME) {
						end = sig(j + 1) + 1;
					} else if (at(j).is("<")) {
						end = balanced(j, "<", ">");
					} else {
						break;
					}
				}
			} else {
				throw new TranspileError(t.line, "can't read type near '" + t.t + "'");
			}
			while (at(sig(end)).is("?")) {
				end = sig(end) + 1;
			}
			return end;
		}
	}

	/** Luau source in, Lua 5.2 source out. */
	public static String transpile(String src) {
		List<Tok> toks = tokenize(src);
		State s = new State(toks);
		interpolate(s);
		stripTypes(s);
		compoundAssignments(s);
		floorDivisions(s);
		ifExpressions(s);
		continues(s);
		StringBuilder sb = new StringBuilder(src.length() + 64);
		for (Tok t : toks) {
			sb.append(t.t);
		}
		return sb.toString();
	}

	// `Hello {name}!` -> ("Hello " .. tostring(name) .. "!")
	private static void interpolate(State s) {
		for (Tok t : s.toks) {
			if (t.k != K.INTERP) {
				continue;
			}
			String body = t.t.substring(1, t.t.length() - 1);
			List<String> parts = new ArrayList<>();
			StringBuilder lit = new StringBuilder();
			int i = 0;
			while (i < body.length()) {
				char c = body.charAt(i);
				if (c == '\\' && i + 1 < body.length()) {
					char d = body.charAt(i + 1);
					if (d == '{' || d == '}' || d == '`') {
						lit.append(d);
					} else {
						lit.append(c).append(d);
					}
					i += 2;
				} else if (c == '{') {
					int depth = 1, j = i + 1;
					while (j < body.length() && depth > 0) {
						if (body.charAt(j) == '{') {
							depth++;
						} else if (body.charAt(j) == '}') {
							depth--;
						}
						j++;
					}
					if (!lit.isEmpty()) {
						parts.add("\"" + lit.toString().replace("\"", "\\\"").replace("\n", "\\n") + "\"");
						lit.setLength(0);
					}
					String expr = transpile(body.substring(i + 1, j - 1)).replace("\n", " ");
					parts.add("tostring(" + expr + ")");
					i = j;
				} else {
					lit.append(c);
					i++;
				}
			}
			if (!lit.isEmpty() || parts.isEmpty()) {
				parts.add("\"" + lit.toString().replace("\"", "\\\"").replace("\n", "\\n") + "\"");
			}
			int newlines = count(t.t, '\n');
			t.t = "(" + String.join(" .. ", parts) + ")" + "\n".repeat(newlines);
			t.k = K.STRING;
		}
	}

	// ---------------------------------------------------------------- types

	private static void stripTypes(State s) {
		List<Tok> toks = s.toks;
		for (int i = s.sig(0); toks.get(i).k != K.EOF; i = s.sig(i + 1)) {
			Tok t = toks.get(i);
			// type / export type declarations at statement start
			if ((t.is("type") || t.is("export")) && isStatementStart(s, i)) {
				int j = i;
				if (t.is("export")) {
					j = s.sig(i + 1);
					if (!s.at(j).is("type")) {
						continue;
					}
				}
				int name = s.sig(j + 1);
				if (s.at(name).k != K.NAME) {
					continue;
				}
				int after = s.sig(name + 1);
				if (s.at(after).is("<")) {
					after = s.sig(s.balanced(after, "<", ">"));
				}
				if (!s.at(after).is("=")) {
					continue;
				}
				int end = s.skipType(after + 1);
				s.blank(i, end);
				i = end - 1;
				continue;
			}
			if (t.is("::")) {
				int end = s.skipType(i + 1);
				s.blank(i, end);
				i = end - 1;
				continue;
			}
			if (t.is("local")) {
				int j = s.sig(i + 1);
				if (s.at(j).is("function")) {
					continue; // handled by 'function'
				}
				i = stripNameList(s, j, Set.of("=", "in")) - 1;
				continue;
			}
			if (t.is("for")) {
				i = stripNameList(s, s.sig(i + 1), Set.of("=", "in")) - 1;
				continue;
			}
			if (t.is("function")) {
				int j = s.sig(i + 1);
				// name chain (may be absent for anonymous functions)
				while (s.at(j).k == K.NAME || s.at(j).is(".") || s.at(j).is(":")) {
					j = s.sig(j + 1);
				}
				if (s.at(j).is("<")) {
					int end = s.balanced(j, "<", ">");
					s.blank(j, end);
					j = s.sig(end);
				}
				if (!s.at(j).is("(")) {
					continue;
				}
				int close = s.balanced(j, "(", ")");
				// parameters: name [: type]
				int k = s.sig(j + 1);
				while (k < close - 1) {
					Tok p = s.at(k);
					if (p.k == K.NAME || p.is("...")) {
						int c = s.sig(k + 1);
						if (s.at(c).is(":")) {
							int end = s.skipType(c + 1);
							s.blank(c, end);
							k = s.sig(end);
							continue;
						}
					}
					k = s.sig(k + 1);
				}
				int r = s.sig(close);
				if (s.at(r).is(":")) {
					int end = s.skipType(r + 1);
					s.blank(r, end);
				}
				i = close - 1;
			}
		}
	}

	/** NAME [: TYPE] {, NAME [: TYPE]} up to one of the stop tokens; returns the stop index. */
	private static int stripNameList(State s, int j, Set<String> stops) {
		while (true) {
			if (s.at(j).k != K.NAME) {
				return j;
			}
			int c = s.sig(j + 1);
			if (s.at(c).is(":")) {
				int end = s.skipType(c + 1);
				s.blank(c, end);
				c = s.sig(end);
			}
			if (s.at(c).is(",")) {
				j = s.sig(c + 1);
				continue;
			}
			return c;
		}
	}

	private static boolean isStatementStart(State s, int i) {
		int p = s.prev(i);
		if (p < 0) {
			return true;
		}
		Tok t = s.at(p);
		if (t.k == K.NAME && Set.of("do", "then", "else", "end", "repeat", "break", "continue").contains(t.t)) {
			return true;
		}
		if (t.is(";")) {
			return true;
		}
		// a newline between the previous token and this one, and the previous token closes an expression
		boolean newline = false;
		for (int k = p + 1; k < i; k++) {
			if (s.at(k).t.contains("\n")) {
				newline = true;
			}
		}
		return newline && (t.k == K.NAME || t.k == K.NUMBER || t.k == K.STRING || t.is(")") || t.is("]") || t.is("}"));
	}

	// ---------------------------------------------------------------- compound assignment

	private static final Set<String> COMPOUND = Set.of("+=", "-=", "*=", "/=", "//=", "%=", "^=", "..=");
	private static final Set<String> BINOPS = Set.of("+", "-", "*", "/", "//", "%", "^", "..", "==", "~=", "<", "<=", ">", ">=",
		"and", "or");

	private static void compoundAssignments(State s) {
		List<Tok> toks = s.toks;
		for (int i = 0; i < toks.size(); i++) {
			Tok t = toks.get(i);
			if (t.k != K.OP || !COMPOUND.contains(t.t)) {
				continue;
			}
			int lhsStart = lhsStart(s, i);
			StringBuilder lhs = new StringBuilder();
			for (int k = lhsStart; k < i; k++) {
				if (toks.get(k).k != K.WS && toks.get(k).k != K.COMMENT && !toks.get(k).t.isEmpty()) {
					if (!lhs.isEmpty()) {
						lhs.append(' ');
					}
					lhs.append(toks.get(k).t);
				}
			}
			int rhsStart = s.sig(i + 1);
			int rhsEnd = skipExpr(s, rhsStart);
			String op = t.t.substring(0, t.t.length() - 1);
			// lua 5.2 has no //: a //= b  ->  a = math.floor(a / (b))
			if (op.equals("//")) {
				t.t = "= math.floor(" + lhs + " / (";
				toks.get(s.prev(rhsEnd)).t = toks.get(s.prev(rhsEnd)).t + "))";
			} else {
				t.t = "= " + lhs + " " + op + " (";
				toks.get(s.prev(rhsEnd)).t = toks.get(s.prev(rhsEnd)).t + ")";
			}
			i = rhsEnd - 1;
		}
	}

	private static int lhsStart(State s, int i) {
		int j = s.prev(i);
		int start = j;
		while (j >= 0) {
			Tok t = s.at(j);
			int open;
			if (t.is("]")) {
				open = matchBack(s, j, "[", "]");
			} else if (t.is(")")) {
				open = matchBack(s, j, "(", ")");
			} else if (t.k == K.NAME && !KEYWORDS.contains(t.t)) {
				open = j;
			} else {
				break;
			}
			start = open;
			int p = s.prev(open);
			if (p < 0) {
				break;
			}
			Tok pt = s.at(p);
			if (pt.is(".") || pt.is(":")) {
				j = s.prev(p);
				continue;
			}
			if ((t.is("]") || t.is(")")) && (pt.k == K.NAME && !KEYWORDS.contains(pt.t) || pt.is("]") || pt.is(")"))) {
				j = p;
				continue;
			}
			break;
		}
		if (start < 0) {
			throw new TranspileError(s.at(i).line, "nothing to assign to before '" + s.at(i).t + "'");
		}
		return start;
	}

	private static int matchBack(State s, int j, String open, String close) {
		int depth = 0;
		for (int k = j; k >= 0; k--) {
			if (s.at(k).is(close)) {
				depth++;
			} else if (s.at(k).is(open)) {
				depth--;
				if (depth == 0) {
					return k;
				}
			}
		}
		throw new TranspileError(s.at(j).line, "unbalanced '" + close + "'");
	}

	/** Skips one expression starting at significant index i; returns the index just after it. */
	private static int skipExpr(State s, int i) {
		int end = skipUnary(s, i);
		while (true) {
			int j = s.sig(end);
			Tok t = s.at(j);
			if ((t.k == K.OP || t.k == K.NAME) && BINOPS.contains(t.t)) {
				end = skipUnary(s, s.sig(j + 1));
			} else {
				return end;
			}
		}
	}

	private static int skipUnary(State s, int i) {
		Tok t = s.at(i);
		if (t.is("not") || t.is("-") || t.is("#")) {
			return skipUnary(s, s.sig(i + 1));
		}
		return skipSimple(s, i);
	}

	private static int skipSimple(State s, int i) {
		Tok t = s.at(i);
		if (t.k == K.NUMBER || t.k == K.STRING || t.is("nil") || t.is("true") || t.is("false") || t.is("...")) {
			return i + 1;
		}
		if (t.is("{")) {
			return s.balanced(i, "{", "}");
		}
		if (t.is("function")) {
			return skipBlockTo(s, i);
		}
		if (t.is("if")) {
			// if-expression: if c then a elseif c then b else c
			int j = skipExpr(s, s.sig(i + 1));
			while (true) {
				int k = s.sig(j);
				if (s.at(k).is("then") || s.at(k).is("else")) {
					j = skipExpr(s, s.sig(k + 1));
				} else if (s.at(k).is("elseif")) {
					j = skipExpr(s, s.sig(k + 1));
				} else {
					return j;
				}
			}
		}
		int end;
		if (t.is("(")) {
			end = s.balanced(i, "(", ")");
		} else if (t.k == K.NAME && !KEYWORDS.contains(t.t)) {
			end = i + 1;
		} else {
			throw new TranspileError(t.line, "unexpected '" + t.t + "'");
		}
		while (true) {
			int j = s.sig(end);
			Tok n = s.at(j);
			if (n.is(".") && s.at(s.sig(j + 1)).k == K.NAME) {
				end = s.sig(j + 1) + 1;
			} else if (n.is("[")) {
				end = s.balanced(j, "[", "]");
			} else if (n.is(":") && s.at(s.sig(j + 1)).k == K.NAME) {
				end = s.sig(j + 1) + 1;
			} else if (n.is("(") && !crossesLine(s, end, j)) {
				end = s.balanced(j, "(", ")");
			} else if (n.is("{")) {
				end = s.balanced(j, "{", "}");
			} else if (n.k == K.STRING) {
				end = j + 1;
			} else {
				return end;
			}
		}
	}

	private static boolean crossesLine(State s, int from, int to) {
		for (int k = from; k < to; k++) {
			if (s.at(k).t.contains("\n")) {
				return true;
			}
		}
		return false;
	}

	/** From a 'function' keyword, skips to just after its matching 'end'. */
	private static int skipBlockTo(State s, int i) {
		int depth = 0;
		boolean pendingLoop = false;
		for (int j = i; j < s.toks.size(); j++) {
			Tok t = s.at(j);
			if (t.k != K.NAME) {
				if (t.k == K.EOF) {
					break;
				}
				continue;
			}
			switch (t.t) {
				case "function", "if" -> depth++;
				case "while", "for" -> pendingLoop = true;
				case "do" -> {
					depth++;
					pendingLoop = false;
				}
				case "repeat" -> depth++;
				case "end", "until" -> {
					depth--;
					if (depth == 0) {
						return t.t.equals("until") ? skipExpr(s, s.sig(j + 1)) : j + 1;
					}
				}
				default -> {
				}
			}
		}
		throw new TranspileError(s.at(i).line, "'function' without matching 'end'");
	}

	// ---------------------------------------------------------------- if-expressions

	static boolean expressionPosition(State s, int i) {
		int p = s.prev(i);
		if (p < 0) {
			return false;
		}
		Tok t = s.at(p);
		return t.k == K.OP && !t.is(")") && !t.is("]") && !t.is("}") && !t.is(";")
			|| t.is("return") || t.is("and") || t.is("or") || t.is("not") || t.is("in");
	}

	/** x = if a then b elseif c then d else e  ->  x = (function() if a then return b elseif c then return d else return e end end)() */
	private static void ifExpressions(State s) {
		List<Tok> toks = s.toks;
		for (int i = 0; i < toks.size(); i++) {
			Tok t = toks.get(i);
			if (t.k != K.NAME || !t.t.equals("if") || !expressionPosition(s, i)) {
				continue;
			}
			int j = skipExpr(s, s.sig(i + 1));
			int last = j;
			while (true) {
				int k = s.sig(j);
				Tok kw = s.at(k);
				if (kw.is("then") || kw.is("else")) {
					kw.t = kw.t + " return";
					j = skipExpr(s, s.sig(k + 1));
					last = j;
				} else if (kw.is("elseif")) {
					j = skipExpr(s, s.sig(k + 1));
				} else {
					break;
				}
			}
			t.t = "(function() if";
			Tok end = s.at(s.prev(last));
			end.t = end.t + " end end)()";
		}
	}

	// ---------------------------------------------------------------- floor division

	private static final Set<String> MUL_OPS = Set.of("*", "/", "%", "//");

	/** a // b  ->  math.floor(a / b), with the left operand reaching back over a chain of * / % at the same level. */
	private static void floorDivisions(State s) {
		List<Tok> toks = s.toks;
		for (int i = 0; i < toks.size(); i++) {
			Tok t = toks.get(i);
			if (t.k != K.OP || !t.t.equals("//")) {
				continue;
			}
			int left = operandStart(s, s.prev(i));
			while (true) {
				int p = s.prev(left);
				if (p >= 0 && s.at(p).k == K.OP && MUL_OPS.contains(s.at(p).t)) {
					left = operandStart(s, s.prev(p));
				} else {
					break;
				}
			}
			int right = skipUnary(s, s.sig(i + 1));
			while (s.at(s.sig(right)).is("^")) {
				right = skipUnary(s, s.sig(s.sig(right) + 1));
			}
			s.at(left).t = "math.floor(" + s.at(left).t;
			t.t = "/";
			Tok last = s.at(s.prev(right));
			last.t = last.t + ")";
		}
	}

	/** Start of the operand that ends at significant index j (numbers, names with suffixes, brackets, unary minus). */
	private static int operandStart(State s, int j) {
		Tok t = s.at(j);
		int start;
		if (t.k == K.NUMBER || t.k == K.STRING) {
			start = j;
		} else {
			start = lhsStart(s, j + 1);
		}
		int p = s.prev(start);
		while (p >= 0 && (s.at(p).is("-") || s.at(p).is("#") || s.at(p).is("not"))) {
			int pp = s.prev(p);
			// a binary minus is preceded by an operand
			if (s.at(p).is("-") && pp >= 0 && (s.at(pp).k == K.NUMBER || s.at(pp).k == K.STRING || s.at(pp).is(")") || s.at(pp).is("]")
				|| s.at(pp).k == K.NAME && !KEYWORDS.contains(s.at(pp).t))) {
				break;
			}
			start = p;
			p = pp;
		}
		return start;
	}

	// ---------------------------------------------------------------- continue

	private record Block(String kind, int id) {
	}

	private static void continues(State s) {
		Deque<Block> stack = new ArrayDeque<>();
		int ids = 0;
		boolean pendingLoop = false;
		List<Boolean> used = new ArrayList<>();
		List<Tok> toks = s.toks;
		for (int i = 0; i < toks.size(); i++) {
			Tok t = toks.get(i);
			if (t.k != K.NAME) {
				continue;
			}
			switch (t.t) {
				case "while", "for" -> pendingLoop = true;
				case "do" -> {
					if (pendingLoop) {
						stack.push(new Block("loop", ids++));
						used.add(false);
					} else {
						stack.push(new Block("do", -1));
					}
					pendingLoop = false;
				}
				case "repeat" -> {
					stack.push(new Block("repeat", ids++));
					used.add(false);
				}
				case "function" -> stack.push(new Block(t.t, -1));
				case "if" -> {
					// if-expressions (x = if a then b else c) have no 'end'
					if (!expressionPosition(s, i)) {
						stack.push(new Block("if", -1));
					}
				}
				case "continue" -> {
					// 'continue' is only a keyword when it stands alone as a statement
					int next = s.sig(i + 1);
					Tok n = s.at(next);
					if (n.is("=") || n.is("(") || n.is(".") || n.is("[") || n.is(":") || n.k == K.STRING || n.is("{")
						|| n.k == K.OP && COMPOUND.contains(n.t)) {
						continue;
					}
					Block loop = null;
					for (Block b : stack) {
						if (b.kind.equals("loop") || b.kind.equals("repeat")) {
							loop = b;
							break;
						}
						if (b.kind.equals("function")) {
							break;
						}
					}
					if (loop == null) {
						throw new TranspileError(t.line, "'continue' outside a loop");
					}
					used.set(loop.id, true);
					t.t = "goto __continue" + loop.id;
				}
				case "end", "until" -> {
					if (stack.isEmpty()) {
						continue;
					}
					Block b = stack.pop();
					if (b.id >= 0 && used.get(b.id)) {
						t.t = "::__continue" + b.id + ":: " + t.t;
					}
				}
				default -> {
				}
			}
		}
	}
}
