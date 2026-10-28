package dev.exavolt.netlink.client.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;

/**
 * A small code editor for Luau: line numbers, syntax highlighting, auto-indent, tab indenting, undo/redo, selection,
 * clipboard, line comments and an error-line marker.
 */
public class CodeEditor extends AbstractWidget {
	private static final int LINE_H = 10;
	private static final int GUTTER_PAD = 6;
	private static final String TAB = "    ";

	private static final int C_BG = 0xFF0B1018;
	private static final int C_GUTTER = 0xFF0F1622;
	private static final int C_LINE_NO = 0xFF46556A;
	private static final int C_LINE_NO_CUR = 0xFF9FB4CC;
	private static final int C_CUR_LINE = 0xFF121B28;
	private static final int C_SELECTION = 0xFF1F4466;
	private static final int C_TEXT = 0xFFD7E3F0;
	private static final int C_KEYWORD = 0xFFC792EA;
	private static final int C_BUILTIN = 0xFF6FE6FF;
	private static final int C_STRING = 0xFFC3E88D;
	private static final int C_NUMBER = 0xFFF78C6C;
	private static final int C_COMMENT = 0xFF5C6E85;
	private static final int C_CONST = 0xFFFFCB6B;
	private static final int C_OP = 0xFF89DDFF;
	private static final int C_ERROR = 0xFFFF5370;

	private static final Set<String> KEYWORDS = Set.of("and", "break", "do", "else", "elseif", "end", "for", "function", "if", "in",
		"local", "not", "or", "repeat", "return", "then", "until", "while", "continue", "type", "export");
	private static final Set<String> CONSTS = Set.of("true", "false", "nil", "self");
	private static final Set<String> BUILTINS = Set.of("signal", "task", "elevator", "world", "print", "warn", "wait", "spawn", "delay", "tick",
		"time", "math", "string", "table", "os", "pairs", "ipairs", "next", "select", "type", "typeof", "tostring", "tonumber", "pcall",
		"xpcall", "error", "assert", "setmetatable", "getmetatable", "rawget", "rawset", "rawequal", "unpack", "coroutine", "bit32");

	private final Font font;
	private String text = "";
	private List<int[]> lines = new ArrayList<>(); // [begin, end) offsets
	private int cursor;
	private int anchor = -1;
	private int scrollY;
	private int scrollX;
	private int errorLine = -1;
	private long lastEdit;
	private boolean draggingMouse;
	private final Deque<String[]> undo = new ArrayDeque<>();
	private final Deque<String[]> redo = new ArrayDeque<>();
	private Runnable onChange = () -> {
	};

	// IntelliSense
	private List<LuauIntel.Item> suggestions = List.of();
	private int suggestion;
	private int suggestionScroll;
	private String suggestPrefix = "";
	private boolean popup;
	private int popupX, popupY, popupW, popupH;
	private static final int POPUP_ROWS = 8;

	public CodeEditor(Font font, int x, int y, int w, int h) {
		super(x, y, w, h, Component.literal("code"));
		this.font = font;
		relines();
	}

	public void setOnChange(Runnable r) {
		this.onChange = r;
	}

	public String getText() {
		return text;
	}

	public void setText(String t) {
		text = t.replace("\r", "");
		cursor = Mth.clamp(cursor, 0, text.length());
		anchor = -1;
		undo.clear();
		redo.clear();
		relines();
		clampScroll();
	}

	public void setErrorLine(int line) {
		this.errorLine = line;
	}

	// ---------------------------------------------------------------- model

	private void relines() {
		lines = new ArrayList<>();
		int start = 0;
		for (int i = 0; i <= text.length(); i++) {
			if (i == text.length() || text.charAt(i) == '\n') {
				lines.add(new int[]{start, i});
				start = i + 1;
			}
		}
	}

	private int lineOf(int pos) {
		int lo = 0, hi = lines.size() - 1;
		while (lo < hi) {
			int mid = (lo + hi + 1) / 2;
			if (lines.get(mid)[0] <= pos) {
				lo = mid;
			} else {
				hi = mid - 1;
			}
		}
		return lo;
	}

	private String line(int i) {
		int[] l = lines.get(i);
		return text.substring(l[0], l[1]);
	}

	private boolean hasSelection() {
		return anchor >= 0 && anchor != cursor;
	}

	private int selStart() {
		return hasSelection() ? Math.min(anchor, cursor) : cursor;
	}

	private int selEnd() {
		return hasSelection() ? Math.max(anchor, cursor) : cursor;
	}

	private void snapshot() {
		undo.push(new String[]{text, String.valueOf(cursor)});
		if (undo.size() > 200) {
			undo.removeLast();
		}
		redo.clear();
	}

	private void replace(int from, int to, String with) {
		snapshot();
		text = text.substring(0, from) + with + text.substring(to);
		cursor = from + with.length();
		anchor = -1;
		relines();
		lastEdit = System.currentTimeMillis();
		errorLine = -1;
		ensureCursorVisible();
		onChange.run();
	}

	private void insert(String s) {
		replace(selStart(), selEnd(), s);
	}

	private void move(int pos, boolean select) {
		if (select) {
			if (anchor < 0) {
				anchor = cursor;
			}
		} else {
			anchor = -1;
		}
		cursor = Mth.clamp(pos, 0, text.length());
		ensureCursorVisible();
	}

	private static String expand(String s) {
		return s.replace("\t", TAB);
	}

	private int width(String s) {
		return font.width(expand(s));
	}

	private int gutter() {
		return font.width(String.valueOf(Math.max(99, lines.size()))) + GUTTER_PAD * 2;
	}

	private int visibleLines() {
		return Math.max(1, (height - 4) / LINE_H);
	}

	private void clampScroll() {
		scrollY = Mth.clamp(scrollY, 0, Math.max(0, lines.size() - visibleLines()));
		scrollX = Math.max(0, scrollX);
	}

	private void ensureCursorVisible() {
		int line = lineOf(cursor);
		if (line < scrollY) {
			scrollY = line;
		} else if (line >= scrollY + visibleLines()) {
			scrollY = line - visibleLines() + 1;
		}
		int cx = width(text.substring(lines.get(line)[0], cursor));
		int textW = width - gutter() - 8;
		if (cx < scrollX) {
			scrollX = Math.max(0, cx - 20);
		} else if (cx > scrollX + textW) {
			scrollX = cx - textW + 20;
		}
		clampScroll();
	}

	private int posAt(double mx, double my) {
		int line = Mth.clamp((int) ((my - getY() - 2) / LINE_H) + scrollY, 0, lines.size() - 1);
		String l = line(line);
		double x = mx - getX() - gutter() - 4 + scrollX;
		int best = 0;
		for (int i = 0; i <= l.length(); i++) {
			int w = width(l.substring(0, i));
			if (w > x) {
				int prev = i == 0 ? 0 : width(l.substring(0, i - 1));
				best = (x - prev < w - x) ? i - 1 : i;
				return lines.get(line)[0] + Math.max(0, best);
			}
			best = i;
		}
		return lines.get(line)[0] + best;
	}

	// ---------------------------------------------------------------- input

	@Override
	public boolean keyPressed(KeyEvent e) {
		if (!isFocused()) {
			return false;
		}
		boolean shift = e.hasShiftDown();
		boolean ctrl = e.hasControlDownWithQuirk();
		int key = e.key();
		if (popup) {
			switch (key) {
				case 264 -> {
					suggestion = (suggestion + 1) % suggestions.size();
					scrollPopup();
					return true;
				}
				case 265 -> {
					suggestion = (suggestion - 1 + suggestions.size()) % suggestions.size();
					scrollPopup();
					return true;
				}
				case 258, 257, 335 -> {
					LuauIntel.Item item = suggestions.get(suggestion);
					if (key != 258 && item.label().equals(suggestPrefix)) {
						closePopup(); // the word is already typed in full: Enter is just a new line
					} else {
						accept(item);
						return true;
					}
				}
				case 256 -> {
					closePopup();
					return true;
				}
				case 262, 263, 268, 269 -> closePopup();
				default -> {
				}
			}
		}
		if (ctrl && key == 32) { // Ctrl+Space
			refreshPopup(true);
			return true;
		}
		if (e.isSelectAll()) {
			anchor = 0;
			cursor = text.length();
			return true;
		}
		if (e.isCopy()) {
			if (hasSelection()) {
				Minecraft.getInstance().keyboardHandler.setClipboard(text.substring(selStart(), selEnd()));
			}
			return true;
		}
		if (e.isCut()) {
			if (hasSelection()) {
				Minecraft.getInstance().keyboardHandler.setClipboard(text.substring(selStart(), selEnd()));
				insert("");
			}
			return true;
		}
		if (e.isPaste()) {
			insert(Minecraft.getInstance().keyboardHandler.getClipboard().replace("\r", ""));
			return true;
		}
		if (ctrl && key == 90) { // Z
			if (shift) {
				redo();
			} else if (!undo.isEmpty()) {
				redo.push(new String[]{text, String.valueOf(cursor)});
				restore(undo.pop());
			}
			return true;
		}
		if (ctrl && key == 89) { // Y
			redo();
			return true;
		}
		if (ctrl && key == 68) { // D duplicate line
			int ln = lineOf(cursor);
			int[] l = lines.get(ln);
			int col = cursor - l[0];
			replace(l[1], l[1], "\n" + line(ln));
			cursor = lines.get(ln + 1)[0] + col;
			return true;
		}
		if (ctrl && key == 47) { // '/' toggle comment
			toggleComment();
			return true;
		}
		int line = lineOf(cursor);
		int[] l = lines.get(line);
		switch (key) {
			case 257, 335 -> { // enter: keep indentation, indent after block openers
				String cur = text.substring(l[0], cursor);
				StringBuilder indent = new StringBuilder();
				for (char c : cur.toCharArray()) {
					if (c == ' ' || c == '\t') {
						indent.append(c);
					} else {
						break;
					}
				}
				String trimmed = cur.strip();
				String base = indent.toString();
				boolean function = trimmed.matches(".*\\bfunction\\b\\s*[\\w.:]*\\s*(<[^>]*>)?\\s*\\(.*\\)(\\s*:\\s*[^=]*)?\\s*$");
				boolean opener = endsWithWord(trimmed, "then") || endsWithWord(trimmed, "do") || function;
				boolean repeat = endsWithWord(trimmed, "repeat");
				if (opener || repeat || endsWithWord(trimmed, "else") || trimmed.endsWith("{") || trimmed.endsWith("(") || trimmed.endsWith("[")) {
					indent.append('\t');
				}
				if (!hasSelection() && !scanInside(cursor)) {
					String after = text.substring(cursor, l[1]);
					// Roblox Studio style: the block gets its 'end' (or 'until') right away when it isn't closed yet
					if ((opener || repeat) && scanDepth() > 0) {
						String closer = repeat ? "until " : "end";
						int at = cursor;
						replace(at, l[1], "\n" + indent + "\n" + base + closer + after.strip());
						cursor = at + 1 + indent.length();
						ensureCursorVisible();
						return true;
					}
					// Enter between a pair of brackets puts the closing one on its own line
					char prev = cursor > 0 ? text.charAt(cursor - 1) : 0;
					char next = cursor < text.length() ? text.charAt(cursor) : 0;
					int pi = "([{".indexOf(prev);
					if (pi >= 0 && next == ")]}".charAt(pi)) {
						int at = cursor;
						replace(at, at, "\n" + indent + "\n" + base);
						cursor = at + 1 + indent.length();
						ensureCursorVisible();
						return true;
					}
				}
				insert("\n" + indent);
				return true;
			}
			case 258 -> { // tab
				if (hasSelection() || shift) {
					indentLines(!shift);
				} else {
					insert("\t");
				}
				return true;
			}
			case 259 -> { // backspace
				if (hasSelection()) {
					insert("");
				} else if (cursor > 0) {
					int oi = OPEN_PAIRS.indexOf(text.charAt(cursor - 1));
					if (!ctrl && oi >= 0 && cursor < text.length() && text.charAt(cursor) == CLOSE_PAIRS.charAt(oi)) {
						replace(cursor - 1, cursor + 1, ""); // empty pair goes away together
					} else {
						int from = ctrl ? wordLeft(cursor) : cursor - 1;
						replace(from, cursor, "");
					}
				}
				if (popup) {
					refreshPopup(false);
				}
				return true;
			}
			case 261 -> { // delete
				if (hasSelection()) {
					insert("");
				} else if (cursor < text.length()) {
					int to = ctrl ? wordRight(cursor) : cursor + 1;
					replace(cursor, to, "");
				}
				return true;
			}
			case 262 -> {
				move(ctrl ? wordRight(cursor) : !shift && hasSelection() ? selEnd() : cursor + 1, shift);
				return true;
			}
			case 263 -> {
				move(ctrl ? wordLeft(cursor) : !shift && hasSelection() ? selStart() : cursor - 1, shift);
				return true;
			}
			case 264, 265, 266, 267 -> { // down, up, page down, page up
				int delta = key == 264 ? 1 : key == 265 ? -1 : key == 267 ? -visibleLines() : visibleLines();
				if (key == 266) {
					delta = -visibleLines();
				} else if (key == 267) {
					delta = visibleLines();
				}
				int target = Mth.clamp(line + delta, 0, lines.size() - 1);
				int x = width(text.substring(l[0], cursor));
				String tl = line(target);
				int col = 0;
				while (col < tl.length() && width(tl.substring(0, col + 1)) <= x + 2) {
					col++;
				}
				move(lines.get(target)[0] + col, shift);
				return true;
			}
			case 268 -> { // home: first non-blank, then column 0
				if (ctrl) {
					move(0, shift);
				} else {
					String s = line(line);
					int first = 0;
					while (first < s.length() && (s.charAt(first) == ' ' || s.charAt(first) == '\t')) {
						first++;
					}
					move(cursor - l[0] == first ? l[0] : l[0] + first, shift);
				}
				return true;
			}
			case 269 -> {
				move(ctrl ? text.length() : l[1], shift);
				return true;
			}
			default -> {
				return false;
			}
		}
	}

	private void redo() {
		if (!redo.isEmpty()) {
			undo.push(new String[]{text, String.valueOf(cursor)});
			restore(redo.pop());
		}
	}

	private void restore(String[] state) {
		text = state[0];
		cursor = Mth.clamp(Integer.parseInt(state[1]), 0, text.length());
		anchor = -1;
		relines();
		ensureCursorVisible();
		onChange.run();
	}

	private int wordLeft(int pos) {
		int p = pos;
		while (p > 0 && !Character.isLetterOrDigit(text.charAt(p - 1)) && text.charAt(p - 1) != '_') {
			p--;
		}
		while (p > 0 && (Character.isLetterOrDigit(text.charAt(p - 1)) || text.charAt(p - 1) == '_')) {
			p--;
		}
		return p == pos ? Math.max(0, pos - 1) : p;
	}

	private int wordRight(int pos) {
		int p = pos;
		while (p < text.length() && !Character.isLetterOrDigit(text.charAt(p)) && text.charAt(p) != '_') {
			p++;
		}
		while (p < text.length() && (Character.isLetterOrDigit(text.charAt(p)) || text.charAt(p) == '_')) {
			p++;
		}
		return p == pos ? Math.min(text.length(), pos + 1) : p;
	}

	private void indentLines(boolean in) {
		int a = lineOf(selStart()), b = lineOf(Math.max(selStart(), selEnd() - (hasSelection() ? 1 : 0)));
		int from = lines.get(a)[0], to = lines.get(b)[1];
		StringBuilder sb = new StringBuilder();
		for (int i = a; i <= b; i++) {
			String s = line(i);
			if (in) {
				s = "\t" + s;
			} else if (s.startsWith("\t")) {
				s = s.substring(1);
			} else if (s.startsWith(TAB)) {
				s = s.substring(TAB.length());
			}
			sb.append(s);
			if (i < b) {
				sb.append('\n');
			}
		}
		replace(from, to, sb.toString());
		anchor = from;
		cursor = from + sb.length();
	}

	private void toggleComment() {
		int a = lineOf(selStart()), b = lineOf(selEnd());
		boolean allCommented = true;
		for (int i = a; i <= b; i++) {
			String s = line(i).stripLeading();
			if (!s.isEmpty() && !s.startsWith("--")) {
				allCommented = false;
			}
		}
		int from = lines.get(a)[0], to = lines.get(b)[1];
		StringBuilder sb = new StringBuilder();
		for (int i = a; i <= b; i++) {
			String s = line(i);
			if (allCommented) {
				int k = s.indexOf("--");
				if (k >= 0) {
					s = s.substring(0, k) + s.substring(k + (s.startsWith("-- ", k) ? 3 : 2));
				}
			} else if (!s.isBlank()) {
				int k = 0;
				while (k < s.length() && (s.charAt(k) == ' ' || s.charAt(k) == '\t')) {
					k++;
				}
				s = s.substring(0, k) + "-- " + s.substring(k);
			}
			sb.append(s);
			if (i < b) {
				sb.append('\n');
			}
		}
		replace(from, to, sb.toString());
	}

	@Override
	public boolean charTyped(CharacterEvent e) {
		if (!isFocused()) {
			return false;
		}
		int cp = e.codepoint();
		if (cp < 32 || cp == 127) {
			return false;
		}
		String s = e.codepointAsString();
		char c = s.charAt(0);
		if (s.length() == 1 && autoPair(c)) {
			closePopup();
			return true;
		}
		// typing 'end'/'else'/'until' at the start of an indented line dedents it
		insert(s);
		if (s.equals("d") || s.equals("e") || s.equals("l")) {
			autoDedent();
		}
		if (Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == ':') {
			refreshPopup(c == '.' || c == ':');
		} else {
			closePopup();
		}
		return true;
	}

	private void autoDedent() {
		int ln = lineOf(cursor);
		String s = line(ln);
		String t = s.strip();
		if (ln == 0 || !(t.equals("end") || t.equals("else") || t.equals("elseif") || t.equals("until"))) {
			return;
		}
		if (!(s.startsWith("\t") || s.startsWith(TAB))) {
			return;
		}
		String prev = line(ln - 1);
		int prevIndent = indentWidth(prev);
		int curIndent = indentWidth(s);
		if (curIndent >= prevIndent && curIndent > 0) {
			int[] l = lines.get(ln);
			int col = cursor - l[0];
			String dedented = s.startsWith("\t") ? s.substring(1) : s.substring(TAB.length());
			text = text.substring(0, l[0]) + dedented + text.substring(l[1]);
			cursor = l[0] + col - (s.length() - dedented.length());
			relines();
		}
	}

	// ---------------------------------------------------------------- auto pairs / auto end

	private static final String OPEN_PAIRS = "([{\"'`";
	private static final String CLOSE_PAIRS = ")]}\"'`";

	/** Brackets and quotes close themselves, typing the closer steps over it, a selection gets wrapped. */
	private boolean autoPair(char c) {
		char next = cursor < text.length() ? text.charAt(cursor) : 0;
		char prev = cursor > 0 ? text.charAt(cursor - 1) : 0;
		int oi = OPEN_PAIRS.indexOf(c);
		boolean quote = oi >= 3;
		if (!hasSelection() && CLOSE_PAIRS.indexOf(c) >= 0 && next == c) {
			// step over the closer that was put there automatically
			if (!quote || scanInside(cursor)) {
				move(cursor + 1, false);
				return true;
			}
		}
		if (oi < 0) {
			return false;
		}
		char close = CLOSE_PAIRS.charAt(oi);
		if (hasSelection()) {
			int a = selStart(), b = selEnd();
			String sel = text.substring(a, b);
			replace(a, b, c + sel + close);
			anchor = a + 1;
			cursor = a + 1 + sel.length();
			return true;
		}
		if (scanInside(cursor)) {
			return false; // inside a string or a comment: type it as is
		}
		if (quote && (Character.isLetterOrDigit(prev) || prev == '_' || prev == c)) {
			return false;
		}
		if (next != 0 && !Character.isWhitespace(next) && ")]},;".indexOf(next) < 0) {
			return false;
		}
		int at = cursor;
		replace(at, at, "" + c + close);
		cursor = at + 1;
		ensureCursorVisible();
		return true;
	}

	private static boolean endsWithWord(String s, String word) {
		if (!s.endsWith(word)) {
			return false;
		}
		int i = s.length() - word.length() - 1;
		return i < 0 || !(Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_' || s.charAt(i) == '.' || s.charAt(i) == ':');
	}

	private boolean scanInsideResult;

	/** Whether the position is inside a string or a comment. */
	private boolean scanInside(int pos) {
		scan(pos);
		return scanInsideResult;
	}

	/** How many blocks (function/if/do/repeat) are still open in the whole script. */
	private int scanDepth() {
		return scan(Integer.MAX_VALUE);
	}

	/** Opening level of a long bracket "[==[" at j, or -1. */
	private static int longOpen(String t, int j) {
		if (j >= t.length() || t.charAt(j) != '[') {
			return -1;
		}
		int k = j + 1;
		while (k < t.length() && t.charAt(k) == '=') {
			k++;
		}
		return k < t.length() && t.charAt(k) == '[' ? k - j - 1 : -1;
	}

	/**
	 * A light Luau scanner: counts block openers against end/until, skipping strings and comments. Stops at {@code stop}
	 * and records whether that position is inside a string or a comment.
	 */
	private int scan(int stop) {
		String t = text;
		int n = t.length();
		int depth = 0;
		int i = 0;
		char prevSig = 0;
		String prevWord = "";
		scanInsideResult = false;
		while (i < n && i < stop) {
			char c = t.charAt(i);
			int end = -1;
			boolean terminated = true;
			if (c == '-' && i + 1 < n && t.charAt(i + 1) == '-') {
				int lvl = longOpen(t, i + 2);
				if (lvl >= 0) {
					String close = "]" + "=".repeat(lvl) + "]";
					int k = t.indexOf(close, i + 2);
					terminated = k >= 0;
					end = k < 0 ? n : k + close.length();
				} else {
					int k = t.indexOf('\n', i + 2);
					terminated = false; // the end of the line still belongs to the comment
					end = k < 0 ? n : k;
				}
			} else if (c == '"' || c == '\'' || c == '`') {
				int j = i + 1;
				terminated = false;
				while (j < n) {
					char ch = t.charAt(j);
					if (ch == '\\') {
						j += 2;
					} else if (ch == c) {
						j++;
						terminated = true;
						break;
					} else if (ch == '\n' && c != '`') {
						break;
					} else {
						j++;
					}
				}
				end = Math.min(j, n);
			} else if (c == '[' && longOpen(t, i) >= 0) {
				int lvl = longOpen(t, i);
				String close = "]" + "=".repeat(lvl) + "]";
				int k = t.indexOf(close, i + 2 + lvl);
				terminated = k >= 0;
				end = k < 0 ? n : k + close.length();
			}
			if (end >= 0) {
				if (stop > i && (stop < end || stop == end && !terminated)) {
					scanInsideResult = true;
					return depth;
				}
				i = end;
				prevSig = '"';
				prevWord = "";
				continue;
			}
			if (Character.isDigit(c)) {
				while (i < n && (Character.isLetterOrDigit(t.charAt(i)) || t.charAt(i) == '.' || t.charAt(i) == '_')) {
					i++;
				}
				prevSig = '0';
				prevWord = "";
				continue;
			}
			if (Character.isLetter(c) || c == '_') {
				int j = i;
				while (j < n && (Character.isLetterOrDigit(t.charAt(j)) || t.charAt(j) == '_')) {
					j++;
				}
				String w = t.substring(i, j);
				boolean field = prevSig == '.' || prevSig == ':';
				if (!field) {
					switch (w) {
						case "function", "do", "repeat" -> depth++;
						case "if" -> {
							// Luau if-expressions (local x = if a then b else c) have no 'end'
							boolean expression = "=(,{[+-*/<>~%^".indexOf(prevSig) >= 0 && prevSig != 0
								|| prevWord.equals("return") || prevWord.equals("and") || prevWord.equals("or") || prevWord.equals("not");
							if (!expression) {
								depth++;
							}
						}
						case "end", "until" -> depth--;
						default -> {
						}
					}
				}
				prevSig = 'a';
				prevWord = w;
				i = j;
				continue;
			}
			if (!Character.isWhitespace(c)) {
				prevSig = c;
				prevWord = "";
			}
			i++;
		}
		return depth;
	}

	private static int indentWidth(String s) {
		int w = 0;
		for (char c : s.toCharArray()) {
			if (c == '\t') {
				w += 4;
			} else if (c == ' ') {
				w++;
			} else {
				break;
			}
		}
		return w;
	}

	@Override
	public void onClick(MouseButtonEvent event, boolean doubleClick) {
		setFocused(true);
		closePopup();
		int pos = posAt(event.x(), event.y());
		if (doubleClick) {
			anchor = wordLeft(Math.min(text.length(), pos + 1));
			cursor = wordRight(Math.max(0, pos - 1));
			if (anchor > cursor) {
				int t = anchor;
				anchor = cursor;
				cursor = t;
			}
			return;
		}
		move(pos, event.hasShiftDown());
		if (!event.hasShiftDown()) {
			anchor = cursor;
		}
		draggingMouse = true;
	}

	@Override
	protected void onDrag(MouseButtonEvent event, double dx, double dy) {
		if (draggingMouse) {
			int pos = posAt(event.x(), event.y());
			if (anchor < 0) {
				anchor = cursor;
			}
			cursor = pos;
			ensureCursorVisible();
		}
	}

	@Override
	public void onRelease(MouseButtonEvent event) {
		draggingMouse = false;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollXAmount, double scrollYAmount) {
		if (!isMouseOver(mouseX, mouseY)) {
			return false;
		}
		if (Minecraft.getInstance().hasShiftDown() || scrollXAmount != 0) {
			scrollX = Math.max(0, scrollX - (int) ((scrollXAmount != 0 ? scrollXAmount : scrollYAmount) * 24));
		} else {
			scrollY -= (int) (scrollYAmount * 3);
		}
		clampScroll();
		return true;
	}

	// ---------------------------------------------------------------- IntelliSense

	public boolean popupOpen() {
		return popup;
	}

	public void closePopup() {
		popup = false;
	}

	private void refreshPopup(boolean force) {
		String[] prefix = new String[1];
		List<LuauIntel.Item> list = LuauIntel.complete(text, cursor, prefix);
		suggestPrefix = prefix[0];
		boolean exact = list.size() == 1 && list.getFirst().label().equals(suggestPrefix);
		if (list.isEmpty() || exact || (!force && suggestPrefix.isEmpty() && !afterAccessor())) {
			popup = false;
			return;
		}
		suggestions = list;
		suggestion = 0;
		suggestionScroll = 0;
		popup = true;
	}

	private boolean afterAccessor() {
		int i = cursor - suggestPrefix.length();
		return i > 0 && (text.charAt(i - 1) == '.' || text.charAt(i - 1) == ':');
	}

	private void scrollPopup() {
		if (suggestion < suggestionScroll) {
			suggestionScroll = suggestion;
		} else if (suggestion >= suggestionScroll + POPUP_ROWS) {
			suggestionScroll = suggestion - POPUP_ROWS + 1;
		}
	}

	private void accept(LuauIntel.Item item) {
		popup = false;
		String insert = item.insert();
		// keep the indentation of the current line on every inserted line
		int ln = lineOf(cursor);
		String cur = line(ln);
		StringBuilder indent = new StringBuilder();
		for (char c : cur.toCharArray()) {
			if (c == ' ' || c == '\t') {
				indent.append(c);
			} else {
				break;
			}
		}
		insert = insert.replace("\n", "\n" + indent);
		int caret = insert.indexOf('|');
		String clean = insert.replace("|", "");
		int from = cursor - suggestPrefix.length();
		replace(from, cursor, clean);
		if (caret >= 0) {
			cursor = from + caret;
			ensureCursorVisible();
		}
	}

	/** Whether a click hit the suggestion popup (it may stick out of the editor). */
	public boolean popupClick(double mx, double my) {
		if (!popup || mx < popupX || mx >= popupX + popupW || my < popupY || my >= popupY + popupH) {
			return false;
		}
		int row = (int) ((my - popupY - 2) / LINE_H) + suggestionScroll;
		if (row >= 0 && row < suggestions.size()) {
			accept(suggestions.get(row));
		}
		return true;
	}

	/** Suggestions and signature help, drawn above everything else by the screen. */
	public void renderOverlay(GuiGraphicsExtractor g) {
		if (!isFocused()) {
			return;
		}
		int line = lineOf(cursor);
		if (line < scrollY || line >= scrollY + visibleLines()) {
			return;
		}
		int textX = getX() + gutter() + 4 - scrollX;
		int cx = textX + width(text.substring(lines.get(line)[0], cursor - (popup ? suggestPrefix.length() : 0)));
		int cy = getY() + 2 + (line - scrollY) * LINE_H;
		g.nextStratum();
		if (popup && !suggestions.isEmpty()) {
			int rows = Math.min(POPUP_ROWS, suggestions.size());
			int w = 0;
			for (LuauIntel.Item it : suggestions) {
				w = Math.max(w, font.width(it.label()));
			}
			w = Math.max(90, w + 34);
			int h = rows * LINE_H + 4;
			int px = Math.min(cx - 18, g.guiWidth() - w - 4);
			int py = cy + LINE_H + 1;
			if (py + h > g.guiHeight() - 2) {
				py = cy - h - 1;
			}
			popupX = px;
			popupY = py;
			popupW = w;
			popupH = h;
			g.fill(px, py, px + w, py + h, 0xF20E1520);
			g.outline(px, py, w, h, 0xFF2D4560);
			for (int r = 0; r < rows; r++) {
				int idx = r + suggestionScroll;
				if (idx >= suggestions.size()) {
					break;
				}
				LuauIntel.Item it = suggestions.get(idx);
				int ry = py + 2 + r * LINE_H;
				if (idx == suggestion) {
					g.fill(px + 1, ry - 1, px + w - 1, ry + LINE_H - 1, 0xFF1F4466);
				}
				g.text(font, it.kind().tag, px + 4, ry, it.kind().color & 0x99FFFFFF, false);
				// matched prefix brighter
				int lx = px + 22;
				String label = it.label();
				int match = label.toLowerCase().startsWith(suggestPrefix.toLowerCase()) ? suggestPrefix.length() : 0;
				g.text(font, label.substring(0, match), lx, ry, 0xFF6FE6FF, false);
				g.text(font, label.substring(match), lx + font.width(label.substring(0, match)), ry, idx == suggestion ? 0xFFFFFFFF : 0xFFD7E3F0,
					false);
			}
			if (suggestions.size() > rows) {
				int bar = Math.max(6, h * rows / suggestions.size());
				int by = py + (h - bar) * suggestionScroll / Math.max(1, suggestions.size() - rows);
				g.fill(px + w - 3, by, px + w - 1, by + bar, 0xFF3A5A80);
			}
			// details of the highlighted item
			LuauIntel.Item sel = suggestions.get(suggestion);
			if (!sel.signature().equals(sel.label()) || !sel.doc().isEmpty()) {
				List<String> detail = new ArrayList<>();
				detail.add(sel.signature());
				if (!sel.doc().isEmpty()) {
					detail.add(sel.doc());
				}
				int dw = 0;
				for (String d : detail) {
					dw = Math.max(dw, font.width(d));
				}
				dw += 10;
				int dx = px + w + 2;
				if (dx + dw > g.guiWidth() - 2) {
					dx = Math.max(2, px - dw - 2);
				}
				int dh = detail.size() * LINE_H + 4;
				g.fill(dx, py, dx + dw, py + dh, 0xF20E1520);
				g.outline(dx, py, dw, dh, 0xFF2D4560);
				for (int k = 0; k < detail.size(); k++) {
					g.text(font, detail.get(k), dx + 5, py + 3 + k * LINE_H, k == 0 ? 0xFF82AAFF : 0xFF8FA6BF, false);
				}
			}
			return;
		}
		// signature help while typing arguments
		int[] arg = new int[1];
		LuauIntel.Item sig = LuauIntel.signatureAt(text, cursor, arg);
		if (sig != null) {
			String full = sig.signature();
			int open = full.indexOf('('), close = full.lastIndexOf(')');
			if (open < 0 || close < open) {
				return;
			}
			String[] params = full.substring(open + 1, close).split(",\\s*(?![^()]*\\))");
			int w = font.width(full) + 10;
			int sx = Math.min(textX + width(text.substring(lines.get(line)[0], cursor)) - 10, g.guiWidth() - w - 4);
			int sy = cy - LINE_H - 4;
			if (sy < 2) {
				sy = cy + LINE_H + 1;
			}
			g.fill(sx, sy, sx + w, sy + LINE_H + 2, 0xF20E1520);
			g.outline(sx, sy, w, LINE_H + 2, 0xFF2D4560);
			int px = sx + 5;
			String head = full.substring(0, open + 1);
			g.text(font, head, px, sy + 2, 0xFF82AAFF, false);
			px += font.width(head);
			for (int k = 0; k < params.length; k++) {
				String p = params[k] + (k < params.length - 1 ? ", " : "");
				g.text(font, p, px, sy + 2, k == arg[0] ? 0xFFFFCB6B : 0xFF8FA6BF, false);
				px += font.width(p);
			}
			g.text(font, full.substring(close), px, sy + 2, 0xFF82AAFF, false);
		}
	}

	// ---------------------------------------------------------------- rendering

	private record Span(int from, int to, int color) {
	}

	/** Highlights one line; {@code state[0]} carries an open long comment/string level between lines (-1 = none). */
	private static List<Span> highlight(String s, int[] state) {
		List<Span> out = new ArrayList<>();
		int i = 0, n = s.length();
		if (state[0] >= 0) {
			String close = "]" + "=".repeat(state[0]) + "]";
			int end = s.indexOf(close);
			int color = state[1] == 1 ? C_COMMENT : C_STRING;
			if (end < 0) {
				out.add(new Span(0, n, color));
				return out;
			}
			out.add(new Span(0, end + close.length(), color));
			i = end + close.length();
			state[0] = -1;
		}
		while (i < n) {
			char c = s.charAt(i);
			int start = i;
			if (c == '-' && i + 1 < n && s.charAt(i + 1) == '-') {
				int lvl = longLevel(s, i + 2);
				if (lvl >= 0) {
					String close = "]" + "=".repeat(lvl) + "]";
					int end = s.indexOf(close, i + 2 + lvl + 2);
					if (end < 0) {
						out.add(new Span(i, n, C_COMMENT));
						state[0] = lvl;
						state[1] = 1;
						return out;
					}
					out.add(new Span(i, end + close.length(), C_COMMENT));
					i = end + close.length();
					continue;
				}
				out.add(new Span(i, n, C_COMMENT));
				return out;
			}
			if (c == '[' && longLevel(s, i) >= 0) {
				int lvl = longLevel(s, i);
				String close = "]" + "=".repeat(lvl) + "]";
				int end = s.indexOf(close, i + lvl + 2);
				if (end < 0) {
					out.add(new Span(i, n, C_STRING));
					state[0] = lvl;
					state[1] = 0;
					return out;
				}
				out.add(new Span(i, end + close.length(), C_STRING));
				i = end + close.length();
				continue;
			}
			if (c == '"' || c == '\'' || c == '`') {
				i++;
				while (i < n && s.charAt(i) != c) {
					if (s.charAt(i) == '\\') {
						i++;
					}
					i++;
				}
				i = Math.min(n, i + 1);
				out.add(new Span(start, i, C_STRING));
				continue;
			}
			if (Character.isDigit(c)) {
				while (i < n && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '.' || s.charAt(i) == '_')) {
					i++;
				}
				out.add(new Span(start, i, C_NUMBER));
				continue;
			}
			if (Character.isLetter(c) || c == '_') {
				while (i < n && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_')) {
					i++;
				}
				String w = s.substring(start, i);
				boolean field = start > 0 && (s.charAt(start - 1) == '.' || s.charAt(start - 1) == ':');
				int color = field ? C_TEXT : KEYWORDS.contains(w) ? C_KEYWORD : CONSTS.contains(w) ? C_CONST : BUILTINS.contains(w) ? C_BUILTIN
					: (i < n && s.charAt(i) == '(') ? 0xFF82AAFF : C_TEXT;
				out.add(new Span(start, i, color));
				continue;
			}
			if (c == ' ' || c == '\t') {
				while (i < n && (s.charAt(i) == ' ' || s.charAt(i) == '\t')) {
					i++;
				}
				out.add(new Span(start, i, C_TEXT));
				continue;
			}
			i++;
			out.add(new Span(start, i, "(){}[],;".indexOf(c) >= 0 ? 0xFFA6B3C2 : C_OP));
		}
		return out;
	}

	private static int longLevel(String s, int i) {
		if (i >= s.length() || s.charAt(i) != '[') {
			return -1;
		}
		int j = i + 1, lvl = 0;
		while (j < s.length() && s.charAt(j) == '=') {
			lvl++;
			j++;
		}
		return j < s.length() && s.charAt(j) == '[' ? lvl : -1;
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		int x = getX(), y = getY(), w = getWidth(), h = getHeight();
		int gut = gutter();
		g.fill(x, y, x + w, y + h, C_BG);
		g.fill(x, y, x + gut, y + h, C_GUTTER);
		g.outline(x, y, w, h, isFocused() ? 0xFF22B6DC : 0xFF2A3341);

		int curLine = lineOf(cursor);
		int textX = x + gut + 4 - scrollX;
		int[] state = {-1, 0};
		// carry long comment/string state from lines above the viewport
		for (int i = 0; i < Math.min(scrollY, lines.size()); i++) {
			highlight(line(i), state);
		}
		g.enableScissor(x + 1, y + 1, x + w - 1, y + h - 1);
		int vis = visibleLines();
		for (int i = scrollY; i < Math.min(lines.size(), scrollY + vis + 1); i++) {
			int ly = y + 2 + (i - scrollY) * LINE_H;
			String s = line(i);
			if (i == curLine && isFocused()) {
				g.fill(x + gut, ly - 1, x + w - 1, ly + LINE_H - 1, C_CUR_LINE);
			}
			if (i + 1 == errorLine) {
				g.fill(x + gut, ly - 1, x + w - 1, ly + LINE_H - 1, 0x40FF5370);
				g.fill(x + 1, ly - 1, x + 3, ly + LINE_H - 1, C_ERROR);
			}
			// selection
			if (hasSelection()) {
				int[] l = lines.get(i);
				int a0 = Math.max(selStart(), l[0]), a1 = Math.min(selEnd(), l[1]);
				if (a0 < a1 || selStart() <= l[0] && selEnd() > l[1]) {
					int sx = textX + width(text.substring(l[0], Math.min(a0, l[1])));
					int ex = textX + width(text.substring(l[0], Math.max(a0, a1))) + (selEnd() > l[1] ? 4 : 0);
					g.fill(Math.max(x + gut, sx), ly - 1, ex, ly + LINE_H - 1, C_SELECTION);
				}
			}
			String no = String.valueOf(i + 1);
			g.disableScissor();
			g.text(font, no, x + gut - GUTTER_PAD - font.width(no), ly, i == curLine ? C_LINE_NO_CUR : C_LINE_NO, false);
			g.enableScissor(x + gut, y + 1, x + w - 1, y + h - 1);
			int px = textX;
			for (Span sp : highlight(s, state)) {
				String part = expand(s.substring(sp.from, sp.to));
				if (px > x + w) {
					break;
				}
				int pw = font.width(part);
				if (px + pw >= x + gut) {
					g.text(font, part, px, ly, sp.color, false);
				}
				px += pw;
			}
			g.disableScissor();
			g.enableScissor(x + 1, y + 1, x + w - 1, y + h - 1);
		}
		// cursor
		if (isFocused() && ((System.currentTimeMillis() - lastEdit) < 500 || (System.currentTimeMillis() / 530) % 2 == 0)) {
			int line = lineOf(cursor);
			if (line >= scrollY && line < scrollY + vis) {
				int cx = textX + width(text.substring(lines.get(line)[0], cursor));
				int cy = y + 2 + (line - scrollY) * LINE_H;
				if (cx >= x + gut) {
					g.fill(cx, cy - 1, cx + 1, cy + LINE_H - 1, 0xFFFFFFFF);
				}
			}
		}
		g.disableScissor();
		// scrollbar
		if (lines.size() > vis) {
			int bar = Math.max(12, h * vis / lines.size());
			int by = y + (h - bar) * scrollY / Math.max(1, lines.size() - vis);
			g.fill(x + w - 3, by, x + w - 1, by + bar, 0xFF2D4560);
		}
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
	}
}
