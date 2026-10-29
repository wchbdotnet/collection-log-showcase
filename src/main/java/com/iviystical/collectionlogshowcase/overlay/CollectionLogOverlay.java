package com.iviystical.collectionlogshowcase.overlay;

import com.iviystical.collectionlogshowcase.CollectionLogShowcaseConfig;
import com.iviystical.collectionlogshowcase.droprate.DropRateResolver;
import com.iviystical.collectionlogshowcase.killcount.KillCountKind;
import com.iviystical.collectionlogshowcase.rarity.RarityTier;
import com.iviystical.collectionlogshowcase.sound.SoundManager;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.DoubleSummaryStatistics;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.Value;
import net.runelite.api.Client;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.util.QuantityFormatter;

@Singleton
public class CollectionLogOverlay extends Overlay
{
	private static final int TOP_MARGIN = 2;
	private static final int RGB_MASK = 0xFFFFFF;
	private static final long FOLD_MILLIS = 550;
	private static final long ICON_POP_MILLIS = 400;
	private final Client client;
	private final ItemManager itemManager;
	private final CollectionLogShowcaseConfig config;
	private final SoundManager soundManager;
	private final java.util.function.LongSupplier clock;
	private final Deque<PendingItem> queue = new ArrayDeque<>();
	private PendingItem current;
	private long notificationStartMillis;
	private Point lastAutoLocation;
	private int panelWidth;
	private float backgroundOpacity;
	private float foregroundOpacity;
	@Setter
	private Consumer<String> releasedFullyOpenListener;

	@Inject
	public CollectionLogOverlay(Client client, ItemManager itemManager, CollectionLogShowcaseConfig config, SoundManager soundManager)
	{
		this(client, itemManager, config, soundManager, System::currentTimeMillis);
	}

	CollectionLogOverlay(Client client, ItemManager itemManager, CollectionLogShowcaseConfig config, SoundManager soundManager,
		java.util.function.LongSupplier clock)
	{
		this.client = client;
		this.itemManager = itemManager;
		this.config = config;
		this.soundManager = soundManager;
		this.clock = clock;
		setPosition(OverlayPosition.TOP_CENTER);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setPriority(PRIORITY_HIGHEST);
		setMovable(true);
	}

	public void enqueue(String itemName, int itemId, RarityTier tier, long price, boolean highAlch,
		Double compPercent, Integer killCount, KillCountKind killCountKind, String killCountSource,
		Double dropProbability, List<DropRateResolver.SourceRate> ambiguousDropRates)
	{
		enqueue(itemName, itemId, tier, price, highAlch, compPercent, killCount, killCountKind,
			killCountSource, null, dropProbability, ambiguousDropRates);
	}

	public void enqueue(String itemName, int itemId, RarityTier tier, long price, boolean highAlch,
		Double compPercent, Integer killCount, KillCountKind killCountKind, String killCountSource,
		Integer secondaryKillCount, Double dropProbability, List<DropRateResolver.SourceRate> ambiguousDropRates)
	{
		enqueue(itemName, itemId, tier, price, highAlch, compPercent, killCount, killCountKind,
			killCountSource, secondaryKillCount, dropProbability, ambiguousDropRates, false);
	}

	/**
	 * @param held holds the item back until {@link #releaseHeld()} instead of showing it in turn -
	 *             see CollectionLogShowcasePlugin's CoX chest handling.
	 */
	public void enqueue(String itemName, int itemId, RarityTier tier, long price, boolean highAlch,
		Double compPercent, Integer killCount, KillCountKind killCountKind, String killCountSource,
		Integer secondaryKillCount, Double dropProbability, List<DropRateResolver.SourceRate> ambiguousDropRates,
		boolean held)
	{
		// The overlay was fully idle right before this item arrived, so it's the first of a fresh
		// batch - the only one that plays a sound when bulkUnlockSfx is on. A held item can't claim
		// this: it shows later, so #releaseHeld works it out again at the point it's let through.
		boolean batchStart = !held && queue.isEmpty() && current == null;
		queue.addLast(new PendingItem(itemName, itemId, tier, price, highAlch, compPercent, killCount,
			killCountKind, killCountSource, secondaryKillCount, dropProbability, ambiguousDropRates, batchStart, held, false));
	}

	public void clear()
	{
		queue.clear();
		current = null;
	}

	/**
	 * Lets every held item through, in the order it was unlocked.
	 */
	public void releaseHeld()
	{
		// Whether the first released item opens a batch can't be decided at enqueue time - items may
		// have come and gone while it was held - so it's recomputed here against the state now.
		boolean batchStart = current == null && queue.stream().allMatch(PendingItem::isHeld);
		for (PendingItem item : queue)
		{
			if (!item.isHeld())
			{
				continue;
			}
			item.setHeld(false);
			item.setReleased(true);
			item.setBatchStart(batchStart);
			batchStart = false;
		}
	}

	/**
	 * @return true if nothing is currently showing or queued - used to drive the preview mode loop
	 *         (see CollectionLogShowcasePlugin#onGameTick), which re-triggers a new random item
	 *         each time the previous one finishes.
	 */
	public boolean isIdle()
	{
		return current == null && queue.isEmpty();
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		long now = clock.getAsLong();
		// Still advanced in Audio only: the queue is what plays and spaces out the sounds.
		advance(now);
		if (config.panelStyle() == PanelStyle.AUDIO_ONLY)
		{
			return null;
		}

		panelWidth = Math.round(320 * config.overlayScalePercent() / 100f);
		backgroundOpacity = config.backgroundOpacity() / 100f;
		foregroundOpacity = config.foregroundOpacity() / 100f;

		// Compared by value, not identity: an alt-click that doesn't move still saves our own point.
		Point placed = getPreferredLocation();
		boolean userPlaced = getPreferredPosition() != null || (placed != null && !placed.equals(lastAutoLocation));
		if (!userPlaced)
		{
			// Centers on the viewport rect, not the canvas: fixed/classic offsets it left of the sidebar,
			// and the Fixed Resizable Hybrid plugin narrows it in resizable mode.
			int viewportWidth = client.getViewportWidth();
			int viewportCenterX = viewportWidth > 0
				? client.getViewportXOffset() + viewportWidth / 2
				: client.getRealDimensions().width / 2;
			lastAutoLocation = new Point(viewportCenterX - (panelWidth / 2), TOP_MARGIN);
			setPreferredLocation(lastAutoLocation);
		}

		if (current == null)
		{
			// Report the panel's real width instead of 0 - RuneLite's positioning centers each frame
			// using the *previous* frame's bounds, so returning 0 here would make the panel jump left
			// once its real width is reported on the next frame it's shown.
			return new Dimension(panelWidth, 0);
		}

		return renderCompact(graphics, now - notificationStartMillis);
	}

	private Dimension renderCompact(Graphics2D original, long elapsed)
	{
		float scale = config.overlayScalePercent() / 100f;
		long fadeStart = FOLD_MILLIS + ICON_POP_MILLIS + config.overlayDisplaySeconds() * 1000L;
		float exitT = clamp01((elapsed - fadeStart) / (float) Math.max(200, config.fadeOutMillis()));
		Stat left = config.showKillCount() ? resolveStat(PanelStat.KILL_COUNT, current) : null;
		boolean showExtra = left != null;
		Stat chance = resolveStat(PanelStat.DROP_RATE, current);
		String rate = chance == null ? "Rate unknown" : (current.getDropProbability() == null ? "Rate range " : "") + String.join(" / ", chance.getValueLines());
		Double ownership = config.compactShowOwnership() ? current.getCompPercent() : null;
		String detail = compactDetail(rate, current.getPrice(), current.isHighAlch(), ownership,
			config.rightPanelStat() == RightPanelStat.VALUE);
		Font statsFont = FontManager.getRunescapeBoldFont().deriveFont(16f);
		boolean wrapStats = original.getFontMetrics(statsFont).stringWidth(detail) > 243;
		int height = 80 + (showExtra ? 18 : 0) + (wrapStats ? 18 : 0);
		long animationTime = elapsed <= fadeStart ? elapsed
			: Math.round((1 - exitT) * (FOLD_MILLIS + ICON_POP_MILLIS));
		float reveal = smoothReveal((animationTime - 180) / 500f);
		Graphics2D g = (Graphics2D) original.create();
		try
		{
			g.scale(scale, scale);

			int openingWidth = 58 + Math.round(262 * reveal);
			g.clipRect(0, 0, openingWidth, height);
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
			g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_DEFAULT);
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
				RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
			Color accent = tierColor(current.getTier());
			float alpha = smoothReveal(animationTime / 180f);
			g.setComposite(AlphaComposite.SrcOver.derive(alpha * backgroundOpacity));
			g.setColor(new Color(24, 24, 23));
			g.fillRoundRect(0, 0, openingWidth, height, 4, 4);
			if (config.showGlow())
			{
				// Feather the glow inward so it stays inside the overlay's allocated bounds.
				for (int inset = 0; inset < 8; inset++)
				{
					float strength = .28f * (float) Math.pow(1 - inset / 8f, 2);
					g.setComposite(AlphaComposite.SrcOver.derive(alpha * foregroundOpacity * strength));
					g.setColor(accent);
					g.drawRoundRect(inset, inset, openingWidth - 1 - 2 * inset,
						height - 1 - 2 * inset, 5, 5);
				}
			}
			g.setComposite(AlphaComposite.SrcOver.derive(alpha * foregroundOpacity));
			g.setColor(new Color(73, 70, 63));
			g.drawRoundRect(0, 0, openingWidth - 1, height - 1, 4, 4);
			g.setColor(accent);
			g.drawLine(1, 1, openingWidth - 2, 1);
			g.setColor(new Color(83, 78, 66));
			g.drawLine(56, 8, 56, height - 9);
			// A travelling highlight follows the expanding edge and fades before the hold.
			float glint = 1 - smoothReveal((animationTime - 620) / 260f);
			if (glint > 0)
			{
				g.setComposite(AlphaComposite.SrcOver.derive(alpha * foregroundOpacity * glint));
				g.setColor(new Color(255, 238, 185));
				g.fillRect(openingWidth - 3, 3, 2, height - 6);
			}
			float itemReveal = smoothReveal(animationTime / 300f);
			BufferedImage sprite = current.getItemId() >= 0 ? itemManager.getImage(current.getItemId()) : null;
			if (sprite != null)
			{
				Graphics2D item = (Graphics2D) g.create();
				try
				{
					item.setComposite(AlphaComposite.SrcOver.derive(alpha * foregroundOpacity * itemReveal));
					item.translate(0, Math.round(-10 * (1 - itemReveal)));
					item.drawImage(sprite, 31 - sprite.getWidth() / 2, height / 2 - sprite.getHeight() / 2, null);
				}
				finally { item.dispose(); }
			}
			float textReveal = smoothReveal((animationTime - 380) / 400f);
			g.setComposite(AlphaComposite.SrcOver.derive(alpha * foregroundOpacity * textReveal));
			g.translate(0, Math.round(7 * (1 - textReveal)));
			Font small = FontManager.getRunescapeBoldFont().deriveFont(16f);
			Font title = FontManager.getRunescapeBoldFont().deriveFont(22f);
			g.setFont(small);
			compactText(g, "Collection log", 64, 20, opaque(config.colourCaption()));
			String tier = current.getTier().getLabel();
			compactText(g, tier, 307 - g.getFontMetrics().stringWidth(tier), 20, accent);
			FittedName name = fitName(g, current.getItemName(), title, 243, 16f);
			g.setFont(name.getFont());
			compactText(g, name.getText(), 64, 44, opaque(config.compactTextColour()));
			Font detailFont = small;
			FittedName fitted = fitName(g, wrapStats ? rate : detail, detailFont, 243, 14f);
			g.setFont(fitted.getFont());
			compactText(g, fitted.getText(), 64, 65, opaque(config.compactTextColour()));
			if (wrapStats)
			{
				String remaining = compactDetail("", current.getPrice(), current.isHighAlch(), ownership,
					config.rightPanelStat() == RightPanelStat.VALUE).trim();
				FittedName rest = fitName(g, remaining, detailFont, 243, 14f);
				g.setFont(rest.getFont());
				compactText(g, rest.getText(), 64, 83, opaque(config.compactTextColour()));
			}

			g.setFont(small);
			if (showExtra)
			{
				String extra = left.getLabel().trim() + " " + String.join(" / ", left.getValueLines());
				compactText(g, truncate(g, extra, small, 243), 64, wrapStats ? 101 : 83, opaque(config.compactTextColour()));
			}
			if (config.showProgressBar())
			{
				int total = client.getVarpValue(VarPlayerID.COLLECTION_COUNT_MAX);
				int obtained = client.getVarpValue(VarPlayerID.COLLECTION_COUNT);
				g.setColor(new Color(52, 51, 47));
				g.fillRect(64, height - 6, 243, 2);
				g.setColor(accent);
				g.fillRect(64, height - 6, total > 0 ? Math.round(243 * clamp01(obtained / (float) total)) : 0, 2);
			}
			if (config.compactShimmer() && elapsed >= 650 && elapsed < 1550)
			{
				Graphics2D glass = (Graphics2D) g.create();
				try
				{
					glass.clip(new java.awt.geom.RoundRectangle2D.Float(1, 1, 318, height - 2, 4, 4));
					float t = (elapsed - 650) / 900f;
					float x = -100 + 520 * t;
					glass.setComposite(AlphaComposite.SrcOver.derive(alpha * foregroundOpacity));
					glass.setPaint(new java.awt.LinearGradientPaint(x, 0, x + 85, 35,
						new float[] {0f, .45f, .55f, 1f}, new Color[] {
							new Color(255, 255, 255, 0), new Color(255, 245, 225, 25),
							new Color(255, 255, 255, 48), new Color(255, 255, 255, 0)}));
					glass.fill(new java.awt.geom.Rectangle2D.Float(x - height * .42f, 1, 85 + height * .42f, height - 2));
				}
				finally { glass.dispose(); }
			}
		}
		finally { g.dispose(); }
		return new Dimension(panelWidth, Math.round(height * scale));
	}

	static String compactDetail(String rate, long price, boolean highAlch, Double ownership, boolean valueFirst)
	{
		String value = price > 0 ? formatValue(price) + " gp" + (highAlch ? " (HA)" : "") : "";
		String players = ownership == null ? "" : String.format(java.util.Locale.ROOT, "%.1f%% players", ownership);
		java.util.List<String> parts = new java.util.ArrayList<>();
		if (valueFirst && !value.isEmpty()) { parts.add(value); }
		if (!rate.isEmpty()) { parts.add(rate); }
		if (!players.isEmpty()) { parts.add(players); }
		if (!valueFirst && !value.isEmpty()) { parts.add(value); }
		return String.join("   ", parts);
	}

	private static float smoothReveal(float t)
	{
		float p = clamp01(t);
		return 1 - (1 - p) * (1 - p) * (1 - p);
	}

	private void compactText(Graphics2D g, String text, int x, int y, Color color)
	{
		// Rasterize at the final font size, as the original overlay does, rather than
		// transforming small glyphs through the banner's layout scaling.
		Graphics2D textGraphics = (Graphics2D) g.create();
		try
		{
			float scale = config.overlayScalePercent() / 100f;
			textGraphics.scale(1 / scale, 1 / scale);
			textGraphics.setFont(g.getFont().deriveFont(g.getFont().getSize2D() * scale));
			int px = Math.round(x * scale);
			int py = Math.round(y * scale);
			textGraphics.setColor(Color.BLACK);
			textGraphics.drawString(text, px + 1, py + 1);
			textGraphics.setColor(color);
			textGraphics.drawString(text, px, py);
		}
		finally { textGraphics.dispose(); }
	}

	private PendingItem pollFirstUnheld()
	{
		for (Iterator<PendingItem> it = queue.iterator(); it.hasNext(); )
		{
			PendingItem item = it.next();
			if (!item.isHeld())
			{
				it.remove();
				return item;
			}
		}
		return null;
	}

	private void advance(long now)
	{
		if (current == null)
		{
			current = pollFirstUnheld();
			if (current != null)
			{
				notificationStartMillis = now;
				// Fired here rather than at enqueue() time so playback is spaced out to match each
				// item's visual reveal, instead of a whole burst firing its sound at once.
				if (!config.bulkUnlockSfx() || current.isBatchStart())
				{
					soundManager.play(current.getTier());
				}
			}
			return;
		}

		// Fully open: the fold has finished and the icon has popped in.
		if (current.isReleased() && now - notificationStartMillis >= FOLD_MILLIS + ICON_POP_MILLIS)
		{
			current.setReleased(false);
			if (releasedFullyOpenListener != null)
			{
				releasedFullyOpenListener.accept(current.getItemName());
			}
		}

		long totalMillis = FOLD_MILLIS + ICON_POP_MILLIS + config.overlayDisplaySeconds() * 1000L + Math.max(200, config.fadeOutMillis());
		if (now - notificationStartMillis >= totalMillis)
		{
			current = null;
		}
	}

	private static float clamp01(float value)
	{
		return Math.max(0f, Math.min(1f, value));
	}

	/** Fits item names within the compact banner without wrapping. */
	private static FittedName fitName(Graphics2D graphics, String text, Font baseFont, int maxWidth, float minFontSize)
	{
		Font font = baseFont;
		while (graphics.getFontMetrics(font).stringWidth(text) > maxWidth && font.getSize2D() > minFontSize)
		{
			font = font.deriveFont(font.getSize2D() - 1f);
		}

		return new FittedName(font, truncate(graphics, text, font, maxWidth));
	}

	private static String truncate(Graphics2D graphics, String text, Font font, int maxWidth)
	{
		FontMetrics metrics = graphics.getFontMetrics(font);
		if (metrics.stringWidth(text) <= maxWidth)
		{
			return text;
		}

		String ellipsis = "...";
		StringBuilder truncated = new StringBuilder(text);
		while (truncated.length() > 0 && metrics.stringWidth(truncated + ellipsis) > maxWidth)
		{
			truncated.setLength(truncated.length() - 1);
		}
		return truncated + ellipsis;
	}

	/**
	 * @return the label/value/color to draw for {@code stat}, or null if that stat isn't
	 *         applicable to {@code item} (e.g. no rarity data, or no kill count was detected).
	 */
	private Stat resolveStat(PanelStat stat, PendingItem item)
	{
		switch (stat)
		{
			case VALUE:
				String valueText = formatValue(item.getPrice()) + " gp" + (item.isHighAlch() ? " (HA)" : "");
				return new Stat("Value: ", List.of(valueText), opaque(config.compactTextColour()));
			case RARITY:
				if (item.getCompPercent() == null)
				{
					return null;
				}
				return new Stat("Obtained by: ", List.of(String.format("%.1f%%", item.getCompPercent())), opaque(config.compactTextColour()));
			case KILL_COUNT:
				if (item.getKillCount() == null)
				{
					return null;
				}
				String killCountText = QuantityFormatter.formatNumber(item.getKillCount());
				// The label names what was actually counted - kills, harvests, deep delves - or the
				// source itself where that would still be ambiguous, as for a clue tier or a raid
				// mode (see KillCountKind). Count and kind always arrive together, so the fallback is
				// only a guard against a future caller supplying one without the other.
				KillCountKind killCountKind = item.getKillCountKind() != null ? item.getKillCountKind() : KillCountKind.KILLS;
				if (item.getSecondaryKillCount() != null)
				{
					killCountText += " (" + QuantityFormatter.formatNumber(item.getSecondaryKillCount()) + ")";
				}
				return new Stat(killCountKind.labelFor(item.getKillCountSource()), List.of(killCountText), opaque(config.compactTextColour()));
			case DROP_RATE:
				if (item.getDropProbability() != null)
				{
					return new Stat("Drop rate: ", List.of(formatFraction(item.getDropProbability())), opaque(config.compactTextColour()));
				}
				// No single known rate - show the rarest-to-most-common range across every tracked
				// source instead of hiding the stat, regardless of how many sources there are.
				List<DropRateResolver.SourceRate> ambiguous = item.getAmbiguousDropRates();
				if (ambiguous.isEmpty())
				{
					return null;
				}
				// Both ends of every candidate, so a source with a " (max)" twin contributes its
				// range rather than only its base rate.
				DoubleSummaryStatistics stats = ambiguous.stream()
					.flatMap(rate -> rate.probabilities().stream())
					.mapToDouble(Double::doubleValue)
					.summaryStatistics();
				// Ordered by rate, not by which end the dataset calls "max" - for a couple of items
				// (Little Nightmare, Zalcano shard) the max-scale rate is the rarer one, so taking
				// the data's own order would render a range that reads backwards.
				double commonest = stats.getMax();
				double rarest = stats.getMin();
				// Commonest first: the denominators then run upwards ("1/540 - 1/1350"), which is
				// how a rate range is written everywhere else.
				if (commonest == rarest)
				{
					return new Stat("Drop rate: ", List.of(formatFraction(commonest)), opaque(config.compactTextColour()));
				}
				// Exact digits by default; the compact form is only reached if the exact one would
				// run under the icon, which the widest real range does by a few px.
				String rangeText = formatFraction(commonest) + " - " + formatFraction(rarest);
				String compactRangeText = formatCompactFraction(commonest) + " - " + formatCompactFraction(rarest);
				return new Stat("Drop rate: ", List.of(rangeText), opaque(config.compactTextColour()),
					List.of(compactRangeText));
			case NONE:
			default:
				return null;
		}
	}

	private static String formatFraction(double dropProbability)
	{
		return "1/" + formatDenominator(Math.round(1 / dropProbability));
	}

	// Same fraction, fewer characters: a 4-digit denominator becomes one decimal of k ("1/1232" ->
	// "1/1.2k"), which is what buys the width back on a range. Only used when the exact form would
	// overflow, so the precision is lost on the few widest ranges rather than on every one.
	private static String formatCompactFraction(double dropProbability)
	{
		long denominator = Math.round(1 / dropProbability);
		if (denominator < 1000 || denominator >= 10_000)
		{
			return "1/" + formatDenominator(denominator);
		}
		String thousands = BigDecimal.valueOf(denominator, 3)
			.setScale(1, RoundingMode.HALF_UP)
			.stripTrailingZeros()
			.toPlainString();
		return "1/" + thousands + "k";
	}

	// Panel width doesn't fit long denominators (e.g. "1/313168") without clipping, so anything at or
	// above 10k is truncated to the nearest thousand - approximate rarity is all this stat is for.
	private static String formatDenominator(long denominator)
	{
		if (denominator < 10_000)
		{
			return Long.toString(denominator);
		}
		return (denominator / 1000) + "k";
	}

	// One decimal for thousands/millions, two for billions; omit insignificant zeroes.
	static String formatValue(long value)
	{
		BigDecimal amount = BigDecimal.valueOf(value).abs();
		if (amount.compareTo(BigDecimal.valueOf(1000)) < 0) { return Long.toString(value); }
		String[] suffixes = {"k", "m", "b"};
		long[] units = {1_000L, 1_000_000L, 1_000_000_000L};
		for (int i = 0; i < units.length; i++)
		{
			BigDecimal rounded = amount.divide(BigDecimal.valueOf(units[i]), i == 2 ? 2 : 1, RoundingMode.HALF_UP);
			if (i == 2 || rounded.compareTo(BigDecimal.valueOf(1000)) < 0)
			{
				return (value < 0 ? "-" : "") + rounded.stripTrailingZeros().toPlainString() + suffixes[i];
			}
		}
		throw new IllegalStateException("No GP unit selected");
	}

	/**
	 * The item name and progress fill colour: the tier colour, or plain white on the neutral style.
	 */
	private Color tierColor(RarityTier tier)
	{
		switch (tier)
		{
			case COMMON:
				return new Color(0xFFFFFF);
			case UNCOMMON:
				return new Color(0x1888C9);
			case RARE:
				return new Color(0x8431A6);
			case VERY_RARE:
				return new Color(0xB19F3B);
			case PET:
				return new Color(0xDC2367);
			default:
				return new Color(0xFFFFFF);
		}
	}

	/**
	 * Drops any alpha the colour picker allowed the user to dial in - see {@link #RGB_MASK}.
	 */
	private static Color opaque(Color color)
	{
		return color.getAlpha() == 0xFF ? color : new Color(color.getRGB() & RGB_MASK);
	}

	// Not @Value like the others here: #releaseHeld rewrites batchStart, held and released after
	// construction, so only those carry a setter and the rest stay read-only.
	@Getter
	@AllArgsConstructor
	private static class PendingItem
	{
		private final String itemName;
		private final int itemId;
		private final RarityTier tier;
		private final long price;
		private final boolean highAlch;
		private final Double compPercent;
		private final Integer killCount;
		private final KillCountKind killCountKind;
		private final String killCountSource;
		// Shown in brackets beside killCount where the activity has two counts at once - Doom's
		// deep delves against its total. Null for every other source.
		private final Integer secondaryKillCount;
		private final Double dropProbability;
		private final List<DropRateResolver.SourceRate> ambiguousDropRates;
		@Setter
		private boolean batchStart;
		@Setter
		private boolean held;
		// Let through by #releaseHeld and not yet reported to releasedFullyOpenListener.
		@Setter
		private boolean released;
	}

	@Value
	private static class FittedName
	{
		Font font;
		String text;
	}

	@Value
	private static class Stat
	{
		String label;
		List<String> valueLines;
		Color valueColor;
		// A shorter rendering of the same value, used only when valueLines would not fit (see
		// drawCornerStat). Null where there is no shorter form worth showing.
		List<String> compactValueLines;

		Stat(String label, List<String> valueLines, Color valueColor)
		{
			this(label, valueLines, valueColor, null);
		}

		Stat(String label, List<String> valueLines, Color valueColor, List<String> compactValueLines)
		{
			this.label = label;
			this.valueLines = valueLines;
			this.valueColor = valueColor;
			this.compactValueLines = compactValueLines;
		}
	}
}
