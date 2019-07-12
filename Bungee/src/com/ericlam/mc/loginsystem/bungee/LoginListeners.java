package com.ericlam.mc.loginsystem.bungee;

import com.ericlam.mc.bungee.hnmc.builders.MessageBuilder;
import com.ericlam.mc.bungee.hnmc.config.ConfigManager;
import com.ericlam.mc.bungee.hnmc.container.OfflinePlayer;
import com.ericlam.mc.bungee.hnmc.events.PlayerVerifyCompletedEvent;
import com.ericlam.mc.bungee.hnmc.main.HyperNiteMC;
import com.ericlam.mc.loginsystem.bungee.events.PlayerLoggedEvent;
import com.ericlam.mc.loginsystem.bungee.managers.LoginManager;
import net.md_5.bungee.BungeeTitle;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.event.PostLoginEvent;
import net.md_5.bungee.api.event.PreLoginEvent;
import net.md_5.bungee.api.event.ServerConnectEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.api.scheduler.ScheduledTask;
import net.md_5.bungee.event.EventHandler;
import net.md_5.bungee.event.EventPriority;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class LoginListeners implements Listener {

    private final LoginManager loginManager;
    private final ConfigManager configManager;
    private final String notLoggedIn;
    private final Map<UUID, ScheduledTask> timerTasks = new HashMap<>();
    private final Map<String, String> ipMap = new ConcurrentHashMap<>();
    private final Plugin plugin;
    private final RedisHandler redisHandler;

    public LoginListeners(Plugin plugin, ConfigManager configManager, LoginManager loginManager){
        this.plugin = plugin;
        this.loginManager = loginManager;
        this.configManager = configManager;
        this.redisHandler = new RedisHandler();
        this.notLoggedIn = configManager.getMessage("not-logged-in");
        ProxyServer.getInstance().getScheduler().schedule(plugin, () -> {
            timerTasks.keySet().forEach(uuid -> {
                ProxiedPlayer player = ProxyServer.getInstance().getPlayer(uuid);
                if (player == null) return;
                MessageBuilder.sendMessage(player, configManager.getMessage("need-login"));
                player.sendTitle(new BungeeTitle().subTitle(TextComponent.fromLegacyText(configManager.getMessage("need-login"))).fadeIn(20).fadeOut(20).stay(10));
            });
        }, 0, 60, TimeUnit.SECONDS);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerLogged(final PlayerLoggedEvent e) {
        ProxiedPlayer player = e.getWho();
        UUID uuid = player.getUniqueId();
        if (e.isCancelled()){
            MessageBuilder.sendMessage(player, configManager.getMessage("logged-fail"));
        }else{
            this.clearTimer(uuid);
            MessageBuilder.sendMessage(player, configManager.getMessage("logged-in"));
            if (!e.isPremium()) redisHandler.lognPublish(uuid);
        }
    }

    @EventHandler
    public void onServerConnect(final ServerConnectEvent e) {
        String lobby = configManager.getData("lobby", String.class).orElse("lobby");
        if (!e.getPlayer().getServer().getInfo().getName().equals(lobby)) return;
        if (e.getTarget().getName().equals(lobby)) return;
        if (loginManager.notLoggedIn(e.getPlayer().getUniqueId())) {
            e.setCancelled(true);
            MessageBuilder.sendMessage(e.getPlayer(), notLoggedIn);
        }
    }

    @EventHandler
    public void onPlayerQuit(final PlayerDisconnectEvent e) {
        loginManager.clearFail(e.getPlayer());
        this.clearTimer(e.getPlayer().getUniqueId());
        this.ipMap.remove(e.getPlayer().getName());
    }

    private void clearTimer(UUID uuid){
        if (timerTasks.containsKey(uuid)){
            timerTasks.get(uuid).cancel();
            timerTasks.remove(uuid);
        }
    }

    @EventHandler
    public void onPlayerLogin(final PreLoginEvent e) {
        String ip = e.getConnection().getAddress().getHostName();
        long amount = this.ipMap.values().stream().filter(address -> address.equals(ip)).count();
        if (amount >= configManager.getData("mlpi", Integer.class).orElse(3)) {
            e.setCancelReason(new MessageBuilder(configManager.getPureMessage("max-login")).build());
            e.setCancelled(true);
            this.ipMap.forEach((k, v) -> {
                if (ProxyServer.getInstance().getPlayer(k) == null) this.ipMap.remove(k);
            });
            return;
        }
        this.ipMap.put(e.getConnection().getName(), ip);
    }

    @EventHandler
    public void onPlayerVerified(final PlayerVerifyCompletedEvent e) {
        OfflinePlayer player = e.getOfflinePlayer();
        if (!player.isPremium()) return;
        redisHandler.permissionGainPublish(player.getUniqueId());
    }

    @EventHandler
    public void onPlayerJoin(final PostLoginEvent e) {
        loginManager.updateIPTask(e.getPlayer());
        HyperNiteMC.getAPI().getPlayerManager().getOfflinePlayer(e.getPlayer().getUniqueId()).whenComplete((offlinePlayer, throwable) -> {
            if (throwable != null) {
                throwable.printStackTrace();
                return;
            }

            offlinePlayer.ifPresent(player -> {
                boolean premium = player.isPremium();
                UUID uuid = player.getUniqueId();
                if (!player.isOnline()) {
                    plugin.getLogger().info("player is not online, skipped");
                    return;
                }
                if (premium && loginManager.notLoggedIn(uuid)) {
                    loginManager.passLogin(player);
                } else if (!premium) {
                    loginManager.loadUserData(uuid);
                    if (loginManager.notLoggedIn(uuid)) {
                        long secs = configManager.getData("sbf", Integer.class).orElse(60);
                        ScheduledTask task = ProxyServer.getInstance().getScheduler().schedule(plugin, () -> {
                            player.getPlayer().disconnect(TextComponent.fromLegacyText(configManager.getPureMessage("kick-timeout")));
                        }, secs, TimeUnit.SECONDS);
                        timerTasks.put(uuid, task);
                        redisHandler.notLoginPubish(uuid);
                    }
                }
            });
        });
    }

}
