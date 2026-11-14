package com.projectkorra.projectkorra.jing;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.Element;
import com.projectkorra.projectkorra.Element.SubElement;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads the jing on players' armor and turns it into bending bonuses.
 * <p>
 * Every effect follows the same curve: {@code bonus = max * (1 - (1 - jing / maxJing) ^ exponent)}. An effect reaches
 * its configured maximum at {@code maxJing} jing and stops there, so no amount of jing gets out of hand. An exponent of
 * 1 grows evenly; higher exponents give more for the first jing and less for each one after.
 * <p>
 * The general bonuses apply equally to every element. Each element also gets its own extra effects, see
 * {@link #getExclusiveBonus}: positive jing for fire, negative for air and chi, neutral for earth. Water's extra
 * effects use the player's lowest jing, so a balanced set is best for water.
 */
public final class JingManager {

	private static final Map<UUID, JingStats> STATS = new ConcurrentHashMap<>();

	private JingManager() {}

	/**
	 * @return Whether jing affects bending at all
	 */
	public static boolean isEnabled() {
		return ConfigManager.defaultConfig.get().getBoolean("Properties.Jing.Enabled");
	}

	/**
	 * Gets a player's jing from the armor they are wearing
	 * @param player The player
	 * @return Their jing totals
	 */
	public static JingStats getStats(final Player player) {
		if (player == null) {
			return JingStats.NONE;
		}
		return STATS.computeIfAbsent(player.getUniqueId(), uuid -> read(player));
	}

	/**
	 * Re-reads a player's jing from their armor. Called whenever their armor changes.
	 * @param player The player
	 */
	public static void update(final Player player) {
		STATS.put(player.getUniqueId(), read(player));
	}

	/**
	 * Forgets a player's jing, e.g. when they leave
	 * @param uuid The player's UUID
	 */
	public static void clear(final UUID uuid) {
		STATS.remove(uuid);
	}

	private static JingStats read(final Player player) {
		// Jing comes from DynamicTools armor, so there's nothing to read without it
		if (!Bukkit.getPluginManager().isPluginEnabled("DynamicTools")) {
			return JingStats.NONE;
		}
		return DynamicToolsHook.read(player);
	}

	/**
	 * Gets the bonus a player gets from a jing when bending a particular element.
	 * @param player The player
	 * @param jing The jing the effect uses
	 * @param configPath The config value under Properties.Jing that holds the effect's maximum, e.g. "Positive.Damage"
	 * @param element The element of the ability being used
	 * @return The bonus as a fraction, from 0 up to the effect's maximum
	 */
	public static double getBonus(final Player player, final Jing jing, final String configPath, final Element element) {
		if (player == null || !isEnabled()) {
			return 0;
		}
		final JingStats stats = getStats(player);
		if (stats.isEmpty()) {
			return 0;
		}
		return curve(stats.get(jing), getConfig(configPath));
	}

	/**
	 * Gets the bonus a player gets from a jing for an ability
	 * @param ability The ability being used
	 * @param jing The jing the effect uses
	 * @param configPath The config value under Properties.Jing that holds the effect's maximum
	 * @return The bonus as a fraction
	 */
	public static double getBonus(final CoreAbility ability, final Jing jing, final String configPath) {
		return getBonus(ability.getPlayer(), jing, configPath, ability.getElement());
	}

	/**
	 * Gets the best bonus a player gets from a jing across all of their elements. Used for effects that aren't tied
	 * to a single ability, like resisting damage.
	 * @param player The player
	 * @param jing The jing the effect uses
	 * @param configPath The config value under Properties.Jing that holds the effect's maximum
	 * @return The bonus as a fraction
	 */
	public static double getBestBonus(final Player player, final Jing jing, final String configPath) {
		return getBonus(player, jing, configPath, null); // General bonuses are the same for every element
	}

	/**
	 * Gets the bonus for an effect only one element gets. It applies at full strength, but only if the player has
	 * that element. Water's exclusive effects use the player's lowest jing.
	 * @param player The player
	 * @param jing The jing the effect uses
	 * @param configPath The config value under Properties.Jing that holds the effect's maximum
	 * @param element The element the effect belongs to
	 * @return The bonus as a fraction, or 0 if the player doesn't have the element
	 */
	public static double getExclusiveBonus(final Player player, final Jing jing, final String configPath, final Element element) {
		if (player == null || !isEnabled()) {
			return 0;
		}
		final BendingPlayer bPlayer = BendingPlayer.getBendingPlayer(player);
		if (bPlayer == null || !bPlayer.hasElement(element)) {
			return 0;
		}
		final JingStats stats = getStats(player);
		final double max = getConfig(configPath);
		if (element == Element.WATER) {
			// Water uses the lowest of the three jings, which rarely gets high, so it's boosted but never past the max
			return Math.min(max, curve(stats.lowest(), max) * getConfig("Water.LowestJingMultiplier"));
		}
		return curve(stats.get(jing), max);
	}

	/**
	 * Applies the jing curve
	 * @param points The amount of jing
	 * @param max The most the bonus can reach
	 * @return The bonus, which approaches max as points grow
	 */
	public static double curve(final double points, final double max) {
		if (points <= 0) {
			return 0;
		}
		final double maxJing = Math.max(1, getConfig("Curve.MaxJing"));
		final double exponent = Math.max(1, getConfig("Curve.Exponent"));
		final double progress = Math.min(points / maxJing, 1);
		return max * (1 - Math.pow(1 - progress, exponent));
	}

	/**
	 * @param element An element or subelement
	 * @return The main element it belongs to, or null if there isn't one
	 */
	public static Element getGroup(final Element element) {
		if (element instanceof SubElement subElement) {
			return subElement.getParentElement();
		}
		return element;
	}

	/**
	 * Loads the jing of everyone online. Used on startup and reload.
	 */
	public static void loadAll() {
		STATS.clear();
		Bukkit.getOnlinePlayers().forEach(JingManager::update);
	}

	static double getConfig(final String path) {
		return ConfigManager.defaultConfig.get().getDouble("Properties.Jing." + path);
	}
}
