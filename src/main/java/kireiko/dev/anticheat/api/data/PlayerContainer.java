package kireiko.dev.anticheat.api.data;

import kireiko.dev.anticheat.api.player.PlayerProfile;
import kireiko.dev.anticheat.core.AsyncScheduler;
import kireiko.dev.anticheat.utils.LogUtils;
import lombok.Getter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PlayerContainer {

    private static final Object INIT_LOCK = new Object();

    @Getter
    private static final Map<UUID, PlayerProfile> uuidPlayerProfileMap = new ConcurrentHashMap<>();
    /**
     * Tracks profile initializations that have not finished yet. The value is a
     * per-init token, so a rejoin invalidates an older still-running task.
     */
    private static final Map<UUID, Object> pendingInits = new ConcurrentHashMap<>();

    public static void init(Player player) {
        final UUID uuid = player.getUniqueId();
        final Object token = new Object();
        pendingInits.put(uuid, token);
        AsyncScheduler.run(() -> {
            try {
                PlayerProfile profile = new PlayerProfile(player);
                profile.initChecks(profile.getInstance());
                synchronized (INIT_LOCK) {
                    // Only publish if this init was not cancelled by unload()/a newer login
                    // and the player is still online.
                    if (pendingInits.remove(uuid, token) && player.isOnline()) {
                        uuidPlayerProfileMap.put(uuid, profile);
                    }
                }
            } finally {
                pendingInits.remove(uuid, token);
            }
        });
    }

    public static void unload(Player player) {
        final UUID uuid = player.getUniqueId();
        final PlayerProfile profile;
        synchronized (INIT_LOCK) {
            // Cancel an init that has not published its profile yet; otherwise the
            // async task could create a profile for an already-disconnected player
            // that would never be removed.
            pendingInits.remove(uuid);
            profile = uuidPlayerProfileMap.remove(uuid);
        }
        if (profile == null) return;
        saveLogs(profile);
        if (profile.getBanAnimInfo() != null && !profile.isIgnoreExitBan()) {
            profile.forcePunish(profile.getBanAnimInfo().getX(), profile.getBanAnimInfo().getY());
        }
    }

    private static void saveLogs(PlayerProfile profile) {
        if (profile.getLogs().isEmpty()) return;
        final String playerName = profile.getPlayer().getName();
        final StringBuilder logBuilder = new StringBuilder();
        LogUtils.createLog(playerName);
        for (final String l : profile.getLogs()) logBuilder.append("\n").append(l);
        LogUtils.addLog(playerName, logBuilder.toString());
        profile.getLogs().clear();
    }

    @Nullable
    public static PlayerProfile getProfile(Player player) {
        return uuidPlayerProfileMap.get(player.getUniqueId());
    }

    @Nullable
    public static PlayerProfile getProfileByName(String name) {
        for (PlayerProfile profile : uuidPlayerProfileMap.values()) {
            if (profile.getPlayer().getName().equalsIgnoreCase(name)) {
                return profile;
            }
        }
        return null;
    }
}
