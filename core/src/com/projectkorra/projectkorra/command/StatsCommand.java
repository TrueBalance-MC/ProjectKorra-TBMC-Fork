package com.projectkorra.projectkorra.command;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.projectkorra.projectkorra.util.ChatUtil;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import com.projectkorra.projectkorra.Element;
import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.storage.DBConnection;
import com.projectkorra.projectkorra.util.Statistic;
import com.projectkorra.projectkorra.util.StatisticsMethods;

public class StatsCommand extends PKCommand {

	private static final String[] getaliases = { "get", "g" };
	private static final String[] leaderboardaliases = { "leaderboard", "lb", "l" };

	private final String invalidLookup;
	private final String invalidSearchType;
	private final String invalidStatistic;
	private final String invalidPlayer;

	public StatsCommand() {
		super("stats", "/bending stats <get/leaderboard> <ability/element/all> <statistic> [player/page]", ConfigManager.languageConfig.get().getString("Commands.Stats.Description"), new String[] { "statistics", "stats" });

		this.invalidLookup = ConfigManager.languageConfig.get().getString("Commands.Stats.InvalidLookup");
		this.invalidSearchType = ConfigManager.languageConfig.get().getString("Commands.Stats.InvalidSearchType");
		this.invalidStatistic = ConfigManager.languageConfig.get().getString("Commands.Stats.InvalidStatistic");
		this.invalidPlayer = ConfigManager.languageConfig.get().getString("Commands.Stats.InvalidPlayer");
	}

	@Override
	public void execute(final CommandSender sender, final List<String> args) {
		if (!this.correctLength(sender, args.size(), 3, 4)) {
			return;
		}
		final CoreAbility ability = CoreAbility.getAbility(args.get(1));
		final Element element = Element.getElement(args.get(1));
		Object object = null;
		if (ability != null) {
			object = ability;
		} else if (element != null) {
			object = element;
		}
		final Statistic statistic = Statistic.getStatistic(args.get(2));

		final boolean containsGet = this.contains(args.get(0), Arrays.asList(getaliases));
		final boolean containsLeaderboard = this.contains(args.get(0), Arrays.asList(leaderboardaliases));
		if (!containsGet && !containsLeaderboard) {
			ChatUtil.sendBrandingMessage(sender, this.invalidLookup);
			return;
		} else if (object == null && !args.get(1).equalsIgnoreCase("all")) {
			ChatUtil.sendBrandingMessage(sender, this.invalidSearchType);
			return;
		} else if (statistic == null) {
			ChatUtil.sendBrandingMessage(sender, this.invalidStatistic);
			return;
		}
		if (containsGet) {
			Player target = null;
			if (args.size() == 4) {
				target = ProjectKorra.plugin.getServer().getPlayer(args.get(3));
				if (target == null) {
					ChatUtil.sendBrandingMessage(sender, this.invalidPlayer.replace("%player%", args.get(3)));
					return;
				}
			} else {
				if (this.isPlayer(sender)) {
					target = (Player) sender;
				} else {
					return;
				}
			}
			final String message = this.getTarget(object, statistic, target);
			ChatUtil.sendBrandingMessage(sender, ChatUtil.legacy(message));
		} else if (containsLeaderboard) {
			int page = 1;
			try {
				page = Integer.parseInt(args.get(3));
			} catch (IndexOutOfBoundsException | NumberFormatException e) {}
			final Object o = object;
			final int p = page;
			// Snapshot the online players on the main thread; the leaderboard is built on the database thread
			final List<UUID> online = ProjectKorra.plugin.getServer().getOnlinePlayers().stream().map(Player::getUniqueId).toList();
			DBConnection.sql.runAsync(() -> {
				final List<String> messages = StatsCommand.this.getLeaderboard(sender, o, statistic, p, online);
				for (final String message : messages) {
					sender.sendMessage(ChatUtil.legacy(message));
				}
			});
		}
	}

	/**
	 * Stats has no permission node, so anyone can use it
	 */
	@Override
	public boolean canUse(final CommandSender sender) {
		return true;
	}

	@Override
	protected void buildArguments(final LiteralArgumentBuilder<CommandSourceStack> node) {
		final String[] lookups = new String[getaliases.length + leaderboardaliases.length];
		System.arraycopy(getaliases, 0, lookups, 0, getaliases.length);
		System.arraycopy(leaderboardaliases, 0, lookups, getaliases.length, leaderboardaliases.length);
		this.literals(node, lookups, lookup -> lookup.then(this.word("type").then(this.word("statistic").then(this.word("target")))));
	}

	public boolean contains(final String s, final List<String> l) {
		for (final String string : l) {
			if (string.equalsIgnoreCase(s)) {
				return true;
			}
		}
		return false;
	}

	public String getTarget(final Object object, final Statistic statistic, final Player target) {
		String message = "&8- &f%object% " + statistic.getDisplayName() + " &e%player%: " + "&f%value%";
		long value = 0;
		if (object == null) {
			value = StatisticsMethods.getStatisticTotal(target.getUniqueId(), statistic);
		} else {
			value = StatisticsMethods.getStatistic(target.getUniqueId(), object, statistic);
		}
		if (object instanceof CoreAbility) {
			final CoreAbility ability = (CoreAbility) object;
			message = message.replace("%object%", ability.getName()).replace("%player%", target.getName()).replace("%value%", String.valueOf(value));
		} else if (object instanceof Element) {
			final Element element = (Element) object;
			message = message.replace("%object%", element.getName()).replace("%player%", target.getName()).replace("%value%", String.valueOf(value));
		} else {
			message = message.replace("%object%", "Total").replace("%player%", target.getName()).replace("%value%", String.valueOf(value));
		}
		return message;
	}

	public List<String> getLeaderboard(final CommandSender sender, final Object object, final Statistic statistic, final int page) {
		return this.getLeaderboard(sender, object, statistic, page, onlineUUIDs());
	}

	private List<String> getLeaderboard(final CommandSender sender, final Object object, final Statistic statistic, final int page, final Collection<UUID> online) {
		final List<String> messages = new ArrayList<>();
		final Map<UUID, Long> values = this.pullValues(statistic, object, online);
		final List<UUID> uuids = sortByValue(values);
		final int maxPage = (uuids.size() / 10) + 1;
		int p = page > maxPage ? maxPage : page;
		p = p < 1 ? 1 : p;
		String title = "%object% " + statistic.getDisplayName() + " Leaderboard";
		if (object instanceof CoreAbility) {
			final CoreAbility ability = (CoreAbility) object;
			title = title.replace("%object%", ability.getName());
		} else if (object instanceof Element) {
			final Element element = (Element) object;
			title = title.replace("%object%", element.getName());
		} else {
			title = title.replace("%object%", "Total");
		}
		ChatUtil.sendBrandingMessage(sender, ChatUtil.legacy("&8- &f" + title + " &8- [&7" + p + "/" + maxPage + "&8]"));
		final int maxIndex = (10 * p) - 1;
		final int minIndex = maxIndex - 9;
		try {
			uuids.get(minIndex);
		} catch (final IndexOutOfBoundsException e) {
			messages.add("&7No statistics found.");
			return messages;
		}
		for (int index = minIndex; index < maxIndex; index++) {
			if (index < 0 || index >= uuids.size()) {
				break;
			}
			final UUID uuid = uuids.get(index);
			final long value = values.get(uuid);
			final OfflinePlayer oPlayer = ProjectKorra.plugin.getServer().getOfflinePlayer(uuid);
			messages.add("&7" + (index + 1) + ") &e" + oPlayer.getName() + " &f" + value);
		}
		return messages;
	}

	public List<UUID> pullUUIDs(final Statistic statistic) {
		return this.pullUUIDs(statistic, null);
	}

	public List<UUID> pullUUIDs(final Statistic statistic, final Object object) {
		return sortByValue(this.pullValues(statistic, object, onlineUUIDs()));
	}

	private static List<UUID> onlineUUIDs() {
		return ProjectKorra.plugin.getServer().getOnlinePlayers().stream().map(Player::getUniqueId).toList();
	}

	/**
	 * Reads each player's value once. The old version looked values up again for every comparison while sorting,
	 * which is a database query per offline player per comparison.
	 * @return The players with a value above 0, mapped to their value
	 */
	private Map<UUID, Long> pullValues(final Statistic statistic, final Object object, final Collection<UUID> online) {
		final Set<UUID> uuids = new HashSet<>(online);
		try (ResultSet rs = DBConnection.sql.readQuery("SELECT DISTINCT uuid FROM pk_stats")) {
			while (rs.next()) {
				uuids.add(UUID.fromString(rs.getString("uuid")));
			}
		} catch (final SQLException e) {
			e.printStackTrace();
		}
		final Map<UUID, Long> values = new HashMap<>();
		for (final UUID uuid : uuids) {
			final long value = object == null ? StatisticsMethods.getStatisticTotal(uuid, statistic) : StatisticsMethods.getStatistic(uuid, object, statistic);
			if (value > 0) {
				values.put(uuid, value);
			}
		}
		return values;
	}

	private static List<UUID> sortByValue(final Map<UUID, Long> values) {
		final List<UUID> list = new ArrayList<>(values.keySet());
		list.sort((u1, u2) -> Long.compare(values.get(u2), values.get(u1)));
		return list;
	}

}
