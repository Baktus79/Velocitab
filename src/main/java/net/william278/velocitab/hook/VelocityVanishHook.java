package net.william278.velocitab.hook;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.player.TabList;
import com.velocitypowered.api.proxy.server.ServerInfo;
import ir.syrent.velocityvanish.velocity.event.VelocityUnVanishEvent;
import ir.syrent.velocityvanish.velocity.event.VelocityVanishEvent;
import net.william278.velocitab.Velocitab;
import net.william278.velocitab.player.TabPlayer;
import net.william278.velocitab.tab.PlayerTabList;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

public class VelocityVanishHook extends Hook {

	private final ConcurrentLinkedQueue<UUID> vanishedPlayers;

	public VelocityVanishHook(@NotNull Velocitab plugin) throws IllegalStateException {
		super(plugin);
		this.vanishedPlayers = new ConcurrentLinkedQueue<>();
	}

	public boolean isPlayerVanished(UUID uuid) {
		return vanishedPlayers.contains(uuid);
	}

	@Subscribe
	public void onVanish(VelocityVanishEvent e) {
		vanishedPlayers.add(e.getPlayer().orElseThrow().getUniqueId());
		final PlayerTabList tablist = plugin.getTabList();

		// Remove the player from the tracking list, Print warning if player was not removed
		if (!tablist.getPlayers().removeIf(player -> player.getPlayer().getUniqueId().equals(e.getPlayer().orElseThrow().getUniqueId()))) {
			plugin.log("Failed to remove disconnecting player " + e.getPlayer().orElseThrow().getUsername() + " (UUID: " + e.getPlayer().orElseThrow().getUniqueId() + ")");
		}

		// Remove the player from the tab list of all other players except players with permission "velocityvanish.admin.seevanished"
		plugin.getServer().getAllPlayers().stream().filter(player -> player.hasPermission("velocityvanish.admin.seevanished")).forEach(player -> player.getTabList().removeEntry(e.getPlayer().orElseThrow().getUniqueId()));

		// Update the tab list of all players
		plugin.getServer().getScheduler()
				.buildTask(plugin, () -> tablist.getPlayers().forEach(player -> {
					player.getPlayer().getTabList().removeEntry(e.getPlayer().orElseThrow().getUniqueId());
					player.sendHeaderAndFooter(tablist);
				}))
				.delay(500, TimeUnit.MILLISECONDS)
				.schedule();
	}

	@Subscribe
	public void onUnVanish(VelocityUnVanishEvent e) {
		vanishedPlayers.remove(e.getPlayer().orElseThrow().getUniqueId());

		final PlayerTabList tablist = plugin.getTabList();
		final Player player = e.getPlayer().orElseThrow();
		plugin.getScoreboardManager().ifPresent(manager -> manager.resetCache(player));

		// Get the servers in the group from the joined server name
		// If the server is not in a group, use fallback
		Optional<List<String>> serversInGroup = tablist.getSiblings(player.getCurrentServer()
				.map(ServerConnection::getServerInfo)
				.map(ServerInfo::getName)
				.orElse("?"));

		// Add the player to the tracking list
		final TabPlayer tabPlayer = plugin.getTabPlayer(player);
		tablist.addPlayerCLQ(tabPlayer);

		// Update lists
		plugin.getServer().getScheduler()
				.buildTask(plugin, () -> {
					final TabList tabList = player.getTabList();
					final Map<String, String> playerRoles = new HashMap<>();

					for (TabPlayer p : tablist.getPlayers()) {
						// Skip players on other servers if the setting is enabled
						if (plugin.getSettings().isOnlyListPlayersInSameGroup() && serversInGroup.isPresent()
								&& !serversInGroup.get().contains(p.getServerName())) {
							continue;
						}

						playerRoles.put(p.getPlayer().getUsername(), p.getTeamName(plugin));
						tabList.getEntries().stream()
								.filter(entry -> entry.getProfile().getId().equals(p.getPlayer().getUniqueId())).findFirst()
								.ifPresentOrElse(
										entry -> p.getDisplayName(plugin).thenAccept(entry::setDisplayName),
										() -> tablist.createEntry(p, tabList).thenAccept(tabList::addEntry)
								);
						tablist.addPlayerToTabList(p, tabPlayer);
						p.sendHeaderAndFooter(tablist);
					}

					plugin.getScoreboardManager().ifPresent(manager -> manager.setRoles(player, playerRoles));
				})
				.delay(500, TimeUnit.MILLISECONDS)
				.schedule();
	}


}
