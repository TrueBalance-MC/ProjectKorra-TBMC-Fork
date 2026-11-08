package com.projectkorra.projectkorra.util;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.entity.Player;

import com.projectkorra.projectkorra.Manager;
import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.storage.DBConnection;
import com.projectkorra.projectkorra.storage.MySQL;
import com.projectkorra.projectkorra.storage.SQLite;

public class StatisticsManager extends Manager implements Runnable {

	/**
	 * HashMap which contains all current statistic values (Map<player,
	 * Map<statId, statValue>>)
	 */
	private final Map<UUID, Map<Integer, Long>> STATISTICS = new ConcurrentHashMap<>();
	/**
	 * HashMap which contains all statistic delta values (Map<player,
	 * Map<statId, statValue>>)
	 */
	private final Map<UUID, Map<Integer, Long>> DELTA = new ConcurrentHashMap<>();
	/**
	 * HashMap which contains all statistic names by ID.
	 */
	private final Map<String, Integer> KEYS_BY_NAME = new ConcurrentHashMap<>();
	/**
	 * HashMap which contains all statistic IDs by name.
	 */
	private final Map<Integer, String> KEYS_BY_ID = new ConcurrentHashMap<>();
	/**
	 * HashMap which contains all UUIDs of players who have recently logged out
	 * to have their stats saved.
	 */
	private final Set<UUID> STORAGE = new HashSet<>();
	private final int INTERVAL = 5;

	private StatisticsManager() {}

	@Override
	public void onActivate() {
		this.startSaveTask();
		this.setupStatistics();
	}

	/**
	 * Starts the task that saves the statistics of players who logged out. Called on activation, and again after
	 * a reload cancels ProjectKorra's tasks.
	 */
	public void startSaveTask() {
		if (ProjectKorra.isStatisticsEnabled()) {
			ProjectKorra.plugin.getServer().getScheduler().scheduleSyncRepeatingTask(ProjectKorra.plugin, this, 20 * this.INTERVAL, 20 * this.INTERVAL);
		}
	}

	public void setupStatistics() {
		// Create pk_statKeys table.
		if (!DBConnection.sql.tableExists("pk_statKeys")) {
			ProjectKorra.log.info("Creating pk_statKeys table");
			String query = "";
			if (DBConnection.sql instanceof MySQL) {
				query = "CREATE TABLE `pk_statKeys` (`id` INTEGER PRIMARY KEY AUTO_INCREMENT, `statName` VARCHAR(64));";
			} else if (DBConnection.sql instanceof SQLite) {
				query = "CREATE TABLE `pk_statKeys` (`id` INTEGER PRIMARY KEY AUTOINCREMENT, `statName` TEXT(64));";
			}
			DBConnection.sql.modifyQuerySync(query);
		}
		// Create pk_stats table.
		if (!DBConnection.sql.tableExists("pk_stats")) {
			ProjectKorra.log.info("Creating pk_stats table");
			String query = "";
			if (DBConnection.sql instanceof MySQL) {
				query = "CREATE TABLE `pk_stats` (`statId` INTEGER, `uuid` VARCHAR(36), `statValue` BIGINT, PRIMARY KEY (statId, uuid));";
			} else if (DBConnection.sql instanceof SQLite) {
				query = "CREATE TABLE `pk_stats` (`statId` INTEGER, `uuid` TEXT(36), `statValue` BIGINT, PRIMARY KEY (statId, uuid));";
			}
			DBConnection.sql.modifyQuerySync(query);
		}
		// Insert all abilities into pk_statKeys for all statistics.
		this.loadKeys();
		boolean inserted = false;
		for (final CoreAbility ability : CoreAbility.getAbilitiesByName()) {
			if (ability.isHarmlessAbility()) {
				continue;
			}
			for (final Statistic statistic : Statistic.values()) {
				final String statName = statistic.getStatisticName(ability);
				if (!this.KEYS_BY_NAME.containsKey(statName)) {
					DBConnection.sql.modifyQuerySync("INSERT INTO pk_statKeys (statName) VALUES (?)", statName);
					inserted = true;
				}
			}
		}
		// Populate Keys Map with all loaded statName(s) in pk_statKeys.
		if (inserted) {
			this.loadKeys();
		}
	}

	private void loadKeys() {
		try (ResultSet rs = DBConnection.sql.readQuery("SELECT * FROM pk_statKeys")) {
			while (rs.next()) {
				this.KEYS_BY_NAME.put(rs.getString("statName"), rs.getInt("id"));
				this.KEYS_BY_ID.put(rs.getInt("id"), rs.getString("statName"));
			}
		} catch (final SQLException e) {
			e.printStackTrace();
		}
	}

	/**
	 * Loads a player's statistics from the database on the database thread. Statistics gained before the load
	 * finishes are kept and added to the loaded values.
	 *
	 * @param uuid The player
	 */
	public void load(final UUID uuid) {
		// If they rejoined before their last session was saved, save it first. Database tasks run in order, so
		// the read below will include it.
		if (this.STORAGE.remove(uuid)) {
			this.save(uuid, true);
		}
		final Map<Integer, Long> statistics = new ConcurrentHashMap<>();
		this.STATISTICS.put(uuid, statistics);
		this.DELTA.put(uuid, new ConcurrentHashMap<>());
		DBConnection.sql.runAsync(() -> {
			try (ResultSet rs = DBConnection.sql.readQuery("SELECT * FROM pk_stats WHERE uuid = ?", uuid.toString())) {
				while (rs.next()) {
					statistics.merge(rs.getInt("statId"), rs.getLong("statValue"), Long::sum);
				}
			} catch (final SQLException e) {
				e.printStackTrace();
			}
		});
	}

	/**
	 * Writes the statistics a player has gained since the last save to the database
	 *
	 * @param uuid The player
	 * @param async Whether to write on the database thread
	 */
	public void save(final UUID uuid, final boolean async) {
		final Map<Integer, Long> delta = this.DELTA.get(uuid);
		if (delta == null) {
			return;
		}
		// Take the unsaved values and reset them, so a later save doesn't add them again
		final Map<Integer, Long> unsaved = new HashMap<>();
		for (final Integer statId : delta.keySet()) {
			final Long statValue = delta.remove(statId);
			if (statValue != null && statValue != 0) {
				unsaved.put(statId, statValue);
			}
		}
		if (unsaved.isEmpty()) {
			return;
		}

		final String query = DBConnection.sql instanceof MySQL
				? "INSERT INTO pk_stats (statId, uuid, statValue) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE statValue = statValue + VALUES(statValue);"
				: "INSERT INTO pk_stats (statId, uuid, statValue) VALUES (?, ?, ?) ON CONFLICT(statId, uuid) DO UPDATE SET statValue = statValue + excluded.statValue;";
		final Runnable write = () -> unsaved.forEach((statId, statValue) -> DBConnection.sql.modifyQuerySync(query, statId, uuid.toString(), statValue));
		if (async) {
			DBConnection.sql.runAsync(write);
		} else {
			write.run();
		}
	}

	public long getStatisticDelta(final UUID uuid, final int statId) {
		// If the player is offline, pull value from database.
		if (!this.DELTA.containsKey(uuid)) {
			return 0;
		} else if (!this.DELTA.get(uuid).containsKey(statId)) {
			return 0;
		}
		return this.DELTA.get(uuid).get(statId);
	}

	public long getStatisticCurrent(final UUID uuid, final int statId) {
		// If the player is offline, pull value from database.
		if (!this.STATISTICS.containsKey(uuid)) {
			try (ResultSet rs = DBConnection.sql.readQuery("SELECT statValue FROM pk_stats WHERE uuid = ? AND statId = ?;", uuid.toString(), statId)) {
				if (rs.next()) {
					return rs.getLong("statValue");
				}
			} catch (final SQLException e) {
				e.printStackTrace();
			}
			return 0;
		} else if (!this.STATISTICS.get(uuid).containsKey(statId)) {
			return 0;
		}
		return this.STATISTICS.get(uuid).get(statId);
	}

	public void addStatistic(final UUID uuid, final int statId, final long statDelta) {
		final Map<Integer, Long> statistics = this.STATISTICS.get(uuid);
		final Map<Integer, Long> delta = this.DELTA.get(uuid);
		if (statistics == null || delta == null || statId < 0) {
			return;
		}
		statistics.merge(statId, statDelta, Long::sum);
		delta.merge(statId, statDelta, Long::sum);

	}

	public Map<Integer, Long> getStatisticsMap(final UUID uuid) {
		final Map<Integer, Long> map = new HashMap<>();
		// If the player is offline, create a new temporary Map from the database.
		if (!this.STATISTICS.containsKey(uuid)) {
			try (ResultSet rs = DBConnection.sql.readQuery("SELECT * FROM pk_stats WHERE uuid = ?", uuid.toString())) {
				while (rs.next()) {
					final int statId = rs.getInt("statId");
					final long statValue = rs.getLong("statValue");
					map.put(statId, statValue);
				}
			} catch (final SQLException e) {
				e.printStackTrace();
			}
			return map;
		}
		return this.STATISTICS.get(uuid);
	}

	public void store(final UUID uuid) {
		this.STORAGE.add(uuid);
	}

	@Override
	public void run() {
		for (final UUID uuid : this.STORAGE) {
			// Confirm that the player is offline.
			final Player player = ProjectKorra.plugin.getServer().getPlayer(uuid);
			if (player == null) {
				this.save(uuid, true);
			}
		}
		this.STORAGE.clear();
	}

	public Map<String, Integer> getKeysByName() {
		return this.KEYS_BY_NAME;
	}

	public Map<Integer, String> getKeysById() {
		return this.KEYS_BY_ID;
	}

}
