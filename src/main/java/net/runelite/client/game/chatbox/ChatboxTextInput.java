/*
 * Copyright (c) 2018 Abex
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.game.chatbox;

import com.google.common.primitives.Ints;
import com.google.inject.Inject;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.IntPredicate;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import java.util.regex.Pattern;
import javax.swing.SwingUtilities;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.FontID;
import net.runelite.api.FontTypeFace;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetPositionMode;
import net.runelite.api.widgets.WidgetSizeMode;
import net.runelite.api.widgets.WidgetTextAlignment;
import net.runelite.api.widgets.WidgetType;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.MouseListener;
import net.runelite.client.util.Text;

@Slf4j
public class ChatboxTextInput extends ChatboxInput implements KeyListener, MouseListener
{
	private static final int CURSOR_FLASH_RATE_MILLIS = 100;
	private static final Pattern BREAK_MATCHER = Pattern.compile("[^a-zA-Z0-9']");

	private final ChatboxPanelManager chatboxPanelManager;
	protected final ClientThread clientThread;

	private static IntPredicate getDefaultCharValidator()
	{
		return i -> i >= 32 && i < 127;
	}

	@AllArgsConstructor
	private static class Line
	{
		private final int start;
		private final int end;
		private final String text;
	}

	@Getter
	private String prompt;

	@Getter
	private int lines;

	private StringBuffer value = new StringBuffer();

	@Getter
	private int cursorStart = 0;

	@Getter
	private int cursorEnd = 0;

	private int selectionStart = -1;
	private int selectionEnd = -1;

	@Getter
	private IntPredicate charValidator = getDefaultCharValidator();

	@Getter
	private Runnable onClose = null;

	@Getter
	private Predicate<String> onDone = null;

	@Getter
	private Consumer<String> onChanged = null;

	@Getter
	private int fontID = FontID.QUILL_8;

	@Getter
	private boolean built = false;

	// These are lambdas for atomic updates
	private Predicate<MouseEvent> isInBounds = null;
	private ToIntFunction<Integer> getLineOffset = null;
	private ToIntFunction<Point> getPointCharOffset = null;

	@Inject
	protected ChatboxTextInput(ChatboxPanelManager chatboxPanelManager, ClientThread clientThread)
	{
		this.chatboxPanelManager = chatboxPanelManager;
		this.clientThread = clientThread;
	}

	public ChatboxTextInput addCharValidator(IntPredicate validator)
	{
		this.charValidator = this.charValidator.and(validator);
		return this;
	}

	public void lines(int lines)
	{
		this.lines = lines;
		if (built)
		{
			clientThread.invoke(this::update);
		}
	}

	public ChatboxTextInput prompt(String prompt)
	{
		this.prompt = prompt;
		if (built)
		{
			clientThread.invoke(this::update);
		}
		return this;
	}

	public ChatboxTextInput value(String value)
	{
		StringBuffer sb = new StringBuffer();
		for (char c : value.toCharArray())
		{
			if (charValidator.test(c))
			{
				sb.append(c);
			}
		}
		this.value = sb;
		cursorAt(this.value.length());
		return this;
	}

	public void cursorAt(int index)
	{
		cursorAt(index, index);
	}

	public void cursorAt(int indexA, int indexB)
	{
		if (indexA < 0)
		{
			indexA = 0;
		}
		if (indexB < 0)
		{
			indexB = 0;
		}
		if (indexA > value.length())
		{
			indexA = value.length();
		}
		if (indexB > value.length())
		{
			indexB = value.length();
		}
		int start = indexA;
		int end = indexB;
		if (start > end)
		{
			int v = start;
			start = end;
			end = v;
		}

		this.cursorStart = start;
		this.cursorEnd = end;

		if (built)
		{
			clientThread.invoke(this::update);
		}

	}

	public String getValue()
	{
		return value.toString();
	}

	public ChatboxTextInput charValidator(IntPredicate val)
	{
		if (val == null)
		{
			val = getDefaultCharValidator();
		}
		this.charValidator = val;
		return this;
	}

	public ChatboxTextInput onClose(Runnable onClose)
	{
		this.onClose = onClose;
		return this;
	}

	public ChatboxTextInput onDone(Consumer<String> onDone)
	{
		this.onDone = (s) ->
		{
			onDone.accept(s);
			return true;
		};
		return this;
	}

	/**
	 * Called when the user attempts to close the input by pressing enter
	 * Return false to cancel the close
	 */
	public ChatboxTextInput onDone(Predicate<String> onDone)
	{
		this.onDone = onDone;
		return this;
	}

	public void onChanged(Consumer<String> onChanged)
	{
		this.onChanged = onChanged;
	}

	public ChatboxTextInput fontID(int fontID)
	{
		this.fontID = fontID;
		return this;
	}

	protected void update()
	{
		Widget container = chatboxPanelManager.getContainerWidget();
		container.deleteAllChildren();

		Widget promptWidget = container.createChild(-1, WidgetType.TEXT);
		promptWidget.setText(this.prompt);
		promptWidget.setTextColor(0x800000);
		promptWidget.setFontId(fontID);
		promptWidget.setXPositionMode(WidgetPositionMode.ABSOLUTE_CENTER);
		promptWidget.setOriginalX(0);
		promptWidget.setYPositionMode(WidgetPositionMode.ABSOLUTE_TOP);
		promptWidget.setOriginalY(8);
		promptWidget.setOriginalHeight(24);
		promptWidget.setXTextAlignment(WidgetTextAlignment.CENTER);
		promptWidget.setYTextAlignment(WidgetTextAlignment.CENTER);
		promptWidget.setWidthMode(WidgetSizeMode.MINUS);
		promptWidget.revalidate();

		buildEdit(0, 50, container.getWidth(), 0);
	}

	protected void buildEdit(int x, int y, int width, int lineHeight) {
		Widget containerWidget = chatboxPanelManager.getContainerWidget();

		final Widget cursorWidget = containerWidget.createChild(-1, WidgetType.RECTANGLE);
		cursorWidget.setTextColor(0xFFFFFF);
		cursorWidget.setHasListener(true);
		cursorWidget.setFilled(true);
		cursorWidget.setFontId(fontID);

		FontTypeFace fontTypeFace = cursorWidget.getFont();
		if (lineHeight <= 0) {
			lineHeight = fontTypeFace.getBaseline();
		}

		List<Line> editLines = new ArrayList<>();
		int breakIndex = -1;
		StringBuilder lineBuilder = new StringBuilder();
		for (int i = 0; i < value.length(); i++) {
			int count = i - lineBuilder.length();
			final String character = value.charAt(i) + "";
			lineBuilder.append(character);
			if (BREAK_MATCHER.matcher(character).matches()) {
				breakIndex = lineBuilder.length();
			}

			if (i == value.length() - 1 || fontTypeFace.getTextWidth(lineBuilder.toString() + value.charAt(i + 1)) >= width) {
				if (editLines.size() < this.lines - 1 || this.lines == 0) {
					if (breakIndex > 1) {
						String str = lineBuilder.substring(0, breakIndex);
						Line line = new Line(count, count + str.length() - 1, str);
						editLines.add(line);

						lineBuilder.replace(0, breakIndex, "");
						breakIndex = -1;
					} else {
						Line line = new Line(count, count + lineBuilder.length() - 1, lineBuilder.toString());
						editLines.add(line);
						lineBuilder.replace(0, lineBuilder.length(), "");
					}
				} else {
					break;
				}
			}
		}

		Rectangle bounds = new Rectangle(containerWidget.getCanvasLocation().getX() + containerWidget.getWidth(), y, 0, editLines.size() * lineHeight);
        for (Line line : editLines) {
			final String text = line.text;
			final int textLength = text.length();

			String leftText = Text.escapeJagex(text);
			String middleText = "";
			String rightText = "";

			final boolean isStartLine = cursorOnLine(cursorStart, line.start, line.end)
					|| (cursorOnLine(cursorStart, line.start, line.end + 1) && line == editLines.get(editLines.size() - 1));

			final boolean isEndLine = cursorOnLine(cursorEnd, line.start, line.end);

			if (isStartLine || isEndLine || (cursorEnd > line.end && cursorStart < line.start)) {
				final int cursorIndex = Ints.constrainToRange(cursorStart - line.start, 0, textLength);
				final int cursorEndIndex = Ints.constrainToRange(cursorEnd - line.start, 0, textLength);

				leftText = Text.escapeJagex(text.substring(0, cursorIndex));
				middleText = Text.escapeJagex(text.substring(cursorIndex, cursorEndIndex));
				rightText = Text.escapeJagex(text.substring(cursorEndIndex));
			}

			final int leftTextWidth = fontTypeFace.getTextWidth(leftText);
			final int middleTextWidth = fontTypeFace.getTextWidth(middleText);
			final int rightTextWidth = fontTypeFace.getTextWidth(rightText);
			final int fullWidth = leftTextWidth + middleTextWidth + rightTextWidth;

			int leftTextX = x;
			if (width > 0) {
				leftTextX += (width - fullWidth) / 2;
			}

			final int middleTextX = leftTextX + leftTextWidth;
			final int rightTextX = middleTextX + middleTextWidth;

			if (leftTextX < bounds.x) {
				bounds.setLocation(leftTextX, bounds.y);
			}

			if (fullWidth > bounds.width) {
				bounds.setSize(fullWidth, bounds.height);
			}

			if (editLines.isEmpty() || isStartLine) {
				cursorWidget.setOriginalX(middleTextX - 1);
				cursorWidget.setOriginalY(y);
				cursorWidget.setOriginalWidth(2);
				cursorWidget.setOriginalHeight(lineHeight);
				cursorWidget.revalidate();
			}

			if (!leftText.isEmpty()) {
				Widget leftTextWidget = null;
				for (Widget w : Objects.requireNonNull(containerWidget.getChildren())) {
					if (w.getType() == WidgetType.TEXT && w.getText().equals(leftText)) {
						leftTextWidget = w;
						break;
					}
				}
				if (leftTextWidget == null) {
					leftTextWidget = containerWidget.createChild(-1, WidgetType.TEXT);
					leftTextWidget.setFontId(fontID);
					leftTextWidget.setOriginalHeight(lineHeight);
					leftTextWidget.setTextColor(0xFFFFFF);
				}
				leftTextWidget.setText(leftText);
				leftTextWidget.setOriginalX(leftTextX);
				leftTextWidget.setOriginalY(y);
				leftTextWidget.setOriginalWidth(leftTextWidth);
				leftTextWidget.revalidate();
			}

			if (!middleText.isEmpty()) {
				Widget middleTextWidget = null;
				for (Widget w : Objects.requireNonNull(containerWidget.getChildren())) {
					if (w.getType() == WidgetType.TEXT && w.getText().equals(middleText)) {
						middleTextWidget = w;
						break;
					}
				}
				if (middleTextWidget == null) {
					middleTextWidget = containerWidget.createChild(-1, WidgetType.TEXT);
					middleTextWidget.setFontId(fontID);
					middleTextWidget.setOriginalHeight(lineHeight);
					middleTextWidget.setTextColor(0xFFFFFF);
				}
				middleTextWidget.setText(middleText);
				middleTextWidget.setOriginalX(middleTextX);
				middleTextWidget.setOriginalY(y);
				middleTextWidget.setOriginalWidth(middleTextWidth);
				middleTextWidget.revalidate();
			}

			if (!rightText.isEmpty()) {
				Widget rightTextWidget = null;
				for (Widget w : Objects.requireNonNull(containerWidget.getChildren())) {
					if (w.getType() == WidgetType.TEXT && w.getText().equals(rightText)) {
						rightTextWidget = w;
						break;
					}
				}
				if (rightTextWidget == null) {
					rightTextWidget = containerWidget.createChild(-1, WidgetType.TEXT);
					rightTextWidget.setFontId(fontID);
					rightTextWidget.setOriginalHeight(lineHeight);
					rightTextWidget.setTextColor(0xFFFFFF);
				}
				rightTextWidget.setText(rightText);
				rightTextWidget.setOriginalX(rightTextX);
				rightTextWidget.setOriginalY(y);
				rightTextWidget.setOriginalWidth(rightTextWidth);
				rightTextWidget.revalidate();
			}

			y += lineHeight;
		}

		net.runelite.api.Point containerCanvasLocation = containerWidget.getCanvasLocation();
		isInBounds = ev -> bounds.contains(new Point(ev.getX() - containerCanvasLocation.getX(), ev.getY() - containerCanvasLocation.getY()));

		int finalY = y;
		int finalLineHeight = lineHeight;
		getPointCharOffset = point -> {
			if (bounds.width <= 0) {
				return 0;
			}

			int cx = point.x - containerCanvasLocation.getX() - x;
			int cy = point.y - containerCanvasLocation.getY() - finalY;

			int currentLine = Ints.constrainToRange(cy / finalLineHeight, 0, editLines.size() - 1);

			final Line line = editLines.get(currentLine);
			final String lineText = line.text;
			int charIndex = lineText.length();
			int fullWidth = fontTypeFace.getTextWidth(lineText);

			int tx = x;
			if (width > 0) {
				tx += (width - fullWidth) / 2;
			}
			cx -= tx;

			for (int i = lineText.length(); i >= 0 && charIndex >= 0 && charIndex <= lineText.length(); i--) {
				int leftCharWidth = charIndex > 0 ? fontTypeFace.getTextWidth(Text.escapeJagex(lineText.substring(0, charIndex - 1))) : 0;
				int middleCharWidth = fontTypeFace.getTextWidth(Text.escapeJagex(lineText.substring(0, charIndex)));
				int rightCharWidth = charIndex + 1 <= lineText.length() ? fontTypeFace.getTextWidth(Text.escapeJagex(lineText.substring(0, charIndex + 1))) : middleCharWidth;

				int leftBound = (leftCharWidth + middleCharWidth) / 2;
				int rightBound = (middleCharWidth + rightCharWidth) / 2;

				if (cx < leftBound) {
					charIndex--;
					continue;
				}
				if (cx > rightBound) {
					charIndex++;
					continue;
				}
				break;
			}

			charIndex = Ints.constrainToRange(charIndex, 0, lineText.length());
			return line.start + charIndex;
		};

		int finalLineHeight1 = lineHeight;
		getLineOffset = code -> {
			if (editLines.size() < 2) {
				return cursorStart;
			}

			int currentLine = -1;
			for (int i = 0; i < editLines.size(); i++) {
				Line line = editLines.get(i);
				if (cursorOnLine(cursorStart, line.start, line.end)
						|| (cursorOnLine(cursorStart, line.start, line.end + 1) && i == editLines.size() - 1)) {
					currentLine = i;
					break;
				}
			}

			if (currentLine == -1
					|| (code == KeyEvent.VK_UP && currentLine == 0)
					|| (code == KeyEvent.VK_DOWN && currentLine == editLines.size() - 1)) {
				return cursorStart;
			}

			final Line line = editLines.get(currentLine);
			final int direction = code == KeyEvent.VK_UP ? -1 : 1;
			final Point destinationPoint = new Point(cursorWidget.getCanvasLocation().getX(), cursorWidget.getCanvasLocation().getY() + (direction * finalLineHeight1));
			final int charOffset = getPointCharOffset.applyAsInt(destinationPoint);

			final Line nextLine = editLines.get(currentLine + direction);
			if ((direction == -1 && charOffset >= line.start)
					|| (direction == 1 && (charOffset > nextLine.end && (currentLine + direction != editLines.size() - 1)))) {
				return nextLine.end;
			}

			return charOffset;
		};
	}


	private boolean cursorOnLine(final int cursor, final int start, final int end)
	{
		return (cursor >= start) && (cursor <= end);
	}

	private int getCharOffset(MouseEvent ev)
	{
		if (getPointCharOffset == null)
		{
			return 0;
		}

		return getPointCharOffset.applyAsInt(ev.getPoint());
	}

	@Override
	protected void open()
	{
		this.built = true;
		update();
	}

	@Override
	protected void close()
	{
		if (this.onClose != null)
		{
			this.onClose.run();
		}
	}

	public ChatboxTextInput build()
	{
		if (prompt == null)
		{
			throw new IllegalStateException("prompt must be non-null");
		}
		chatboxPanelManager.openInput(this);

		return this;
	}

	@Override
	public void keyTyped(KeyEvent e)
	{
		if (!chatboxPanelManager.shouldTakeInput())
		{
			return;
		}

		char c = e.getKeyChar();
		if (charValidator.test(c))
		{
			if (cursorStart != cursorEnd)
			{
				value.delete(cursorStart, cursorEnd);
			}
			value.insert(cursorStart, c);
			cursorAt(cursorStart + 1);
			if (onChanged != null)
			{
				onChanged.accept(getValue());
			}
		}
	}

	@Override
	public void keyPressed(KeyEvent ev)
	{
		if (!chatboxPanelManager.shouldTakeInput())
		{
			return;
		}

		int code = ev.getKeyCode();
		if (ev.isControlDown())
		{
			switch (code)
			{
				case KeyEvent.VK_X:
				case KeyEvent.VK_C:
					if (cursorStart != cursorEnd)
					{
						String s = value.substring(cursorStart, cursorEnd);
						if (code == KeyEvent.VK_X)
						{
							value.delete(cursorStart, cursorEnd);
							cursorAt(cursorStart);
						}
						Toolkit.getDefaultToolkit()
							.getSystemClipboard()
							.setContents(new StringSelection(s), null);
					}
					return;
				case KeyEvent.VK_V:
					try
					{
						String s = Toolkit.getDefaultToolkit()
							.getSystemClipboard()
							.getData(DataFlavor.stringFlavor)
							.toString();
						if (cursorStart != cursorEnd)
						{
							value.delete(cursorStart, cursorEnd);
						}
						for (int i = 0; i < s.length(); i++)
						{
							char ch = s.charAt(i);
							if (charValidator.test(ch))
							{
								value.insert(cursorStart, ch);
								cursorStart++;
							}
						}
						cursorAt(cursorStart);
						if (onChanged != null)
						{
							onChanged.accept(getValue());
						}
					}
					catch (IOException | UnsupportedFlavorException ex)
					{
						log.warn("Unable to get clipboard", ex);
					}
					return;
				case KeyEvent.VK_A:
					selectionStart = 0;
					selectionEnd = value.length();
					cursorAt(0, selectionEnd);
					return;
			}
			return;
		}
		int newPos = cursorStart;
		if (ev.isShiftDown())
		{
			if (selectionEnd == -1 || selectionStart == -1)
			{
				selectionStart = cursorStart;
				selectionEnd = cursorStart;
			}
			newPos = selectionEnd;
		}
		else
		{
			selectionStart = -1;
			selectionEnd = -1;
		}
		switch (code)
		{
			case KeyEvent.VK_DELETE:
				if (cursorStart != cursorEnd)
				{
					value.delete(cursorStart, cursorEnd);
					cursorAt(cursorStart);
					if (onChanged != null)
					{
						onChanged.accept(getValue());
					}
					return;
				}
				if (cursorStart < value.length())
				{
					value.deleteCharAt(cursorStart);
					cursorAt(cursorStart);
					if (onChanged != null)
					{
						onChanged.accept(getValue());
					}
				}
				return;
			case KeyEvent.VK_BACK_SPACE:
				if (cursorStart != cursorEnd)
				{
					value.delete(cursorStart, cursorEnd);
					cursorAt(cursorStart);
					if (onChanged != null)
					{
						onChanged.accept(getValue());
					}
					return;
				}
				if (cursorStart > 0)
				{
					value.deleteCharAt(cursorStart - 1);
					cursorAt(cursorStart - 1);
					if (onChanged != null)
					{
						onChanged.accept(getValue());
					}
				}
				return;
			case KeyEvent.VK_LEFT:
				ev.consume();
				if (cursorStart != cursorEnd)
				{
					newPos = cursorStart;
				}
				else
				{
					newPos--;
				}
				break;
			case KeyEvent.VK_RIGHT:
				ev.consume();
				if (cursorStart != cursorEnd)
				{
					newPos = cursorEnd;
				}
				else
				{
					newPos++;
				}
				break;
			case KeyEvent.VK_UP:
				ev.consume();
				newPos = getLineOffset.applyAsInt(code);
				break;
			case KeyEvent.VK_DOWN:
				ev.consume();
				newPos = getLineOffset.applyAsInt(code);
				break;
			case KeyEvent.VK_HOME:
				ev.consume();
				newPos = 0;
				break;
			case KeyEvent.VK_END:
				ev.consume();
				newPos = value.length();
				break;
			case KeyEvent.VK_ENTER:
				ev.consume();
				if (onDone != null && !onDone.test(getValue()))
				{
					return;
				}
				chatboxPanelManager.close();
				return;
			case KeyEvent.VK_ESCAPE:
				ev.consume();
				if (cursorStart != cursorEnd)
				{
					cursorAt(cursorStart);
					return;
				}
				chatboxPanelManager.close();
				return;
			default:
				return;
		}
		if (newPos > value.length())
		{
			newPos = value.length();
		}
		if (newPos < 0)
		{
			newPos = 0;
		}
		if (ev.isShiftDown())
		{
			selectionEnd = newPos;
			cursorAt(selectionStart, newPos);
		}
		else
		{
			cursorAt(newPos);
		}
	}

	@Override
	public void keyReleased(KeyEvent e)
	{
	}

	@Override
	public MouseEvent mouseClicked(MouseEvent mouseEvent)
	{
		return mouseEvent;
	}

	@Override
	public MouseEvent mousePressed(MouseEvent mouseEvent)
	{
		if (mouseEvent.getButton() != MouseEvent.BUTTON1)
		{
			return mouseEvent;
		}
		if (isInBounds == null || !isInBounds.test(mouseEvent))
		{
			if (cursorStart != cursorEnd)
			{
				selectionStart = -1;
				selectionEnd = -1;
				cursorAt(getCharOffset(mouseEvent));
			}
			return mouseEvent;
		}

		int nco = getCharOffset(mouseEvent);

		if (mouseEvent.isShiftDown() && selectionEnd != -1)
		{
			selectionEnd = nco;
			cursorAt(selectionStart, selectionEnd);
		}
		else
		{
			selectionStart = nco;
			cursorAt(nco);
		}

		return mouseEvent;
	}

	@Override
	public MouseEvent mouseReleased(MouseEvent mouseEvent)
	{
		return mouseEvent;
	}

	@Override
	public MouseEvent mouseEntered(MouseEvent mouseEvent)
	{
		return mouseEvent;
	}

	@Override
	public MouseEvent mouseExited(MouseEvent mouseEvent)
	{
		return mouseEvent;
	}

	@Override
	public MouseEvent mouseDragged(MouseEvent mouseEvent)
	{
		if (!SwingUtilities.isLeftMouseButton(mouseEvent))
		{
			return mouseEvent;
		}

		int nco = getCharOffset(mouseEvent);
		if (selectionStart != -1)
		{
			selectionEnd = nco;
			cursorAt(selectionStart, selectionEnd);
		}

		return mouseEvent;
	}

	@Override
	public MouseEvent mouseMoved(MouseEvent mouseEvent)
	{
		return mouseEvent;
	}
}
