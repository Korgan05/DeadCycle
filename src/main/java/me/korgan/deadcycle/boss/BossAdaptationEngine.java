package me.korgan.deadcycle.boss;

import me.korgan.deadcycle.DeadCyclePlugin;
import me.korgan.deadcycle.kit.KitManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

final class BossAdaptationEngine {

    static final class RecentSkillSnapshot {
        private final String skillName;
        private final int adaptationTier;

        RecentSkillSnapshot(String skillName, int adaptationTier) {
            this.skillName = skillName;
            this.adaptationTier = adaptationTier;
        }

        String skillName() {
            return skillName;
        }

        int adaptationTier() {
            return adaptationTier;
        }
    }

    private static final class SkillUsageInfo {
        long lastUseTime;
        int useCount;
        int adaptationTier;
        long lastCounterTime;
        int lastAnnouncedTier;
    }

    private final DeadCyclePlugin plugin;
    private final BossDuelManager manager;
    private final Random rng;

    private final Map<UUID, Map<String, SkillUsageInfo>> playerSkillTracking = new HashMap<>();
    private final Map<UUID, Long> skillCommentCooldown = new HashMap<>();
    private BukkitTask cleanupTask;

    private static final long SKILL_TRACKING_FORGET_MS = 90_000L;
    private static final long RECENT_SKILL_WINDOW_MS = 6_000L;

    BossAdaptationEngine(DeadCyclePlugin plugin, BossDuelManager manager, Random rng) {
        this.plugin = plugin;
        this.manager = manager;
        this.rng = rng;
    }

    void start() {
        stop();
        cleanupTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!manager.isFightActive())
                return;
            checkPlayerSkillUsage();
        }, 20L, 10L);
    }

    void stop() {
        if (cleanupTask != null) {
            cleanupTask.cancel();
            cleanupTask = null;
        }
    }

    void clearData() {
        playerSkillTracking.clear();
        skillCommentCooldown.clear();
    }

    void registerSkillUsage(Player player, String skillName) {
        if (player == null || skillName == null || skillName.isBlank())
            return;
        if (!manager.isFightActive())
            return;
        if (!manager.isDuelPlayer(player.getUniqueId()))
            return;

        String normalizedSkill = skillName.toLowerCase(Locale.ROOT);
        UUID playerUuid = player.getUniqueId();
        playerSkillTracking.putIfAbsent(playerUuid, new HashMap<>());

        Map<String, SkillUsageInfo> playerSkills = playerSkillTracking.get(playerUuid);
        SkillUsageInfo info = playerSkills.computeIfAbsent(normalizedSkill, ignored -> new SkillUsageInfo());

        long now = System.currentTimeMillis();
        long learnInterval = getLearningIntervalMs(normalizedSkill);
        if (info.lastUseTime == 0L || now - info.lastUseTime >= learnInterval) {
            info.useCount++;
        }

        info.lastUseTime = now;

        int prevTier = info.adaptationTier;
        int nextTier = resolveAdaptationTier(normalizedSkill, info.useCount);
        info.adaptationTier = nextTier;

        if (nextTier > prevTier && nextTier > info.lastAnnouncedTier) {
            info.lastAnnouncedTier = nextTier;
            announceAdaptationTierUp(player, normalizedSkill, nextTier);
        }

        KitManager.Kit playerKit = plugin.kit().getKit(playerUuid);
        applySkillCounterMechanic(player, normalizedSkill, playerKit, info, now);
    }

    RecentSkillSnapshot getRecentSkillSnapshot(UUID playerUuid, KitManager.Kit kit) {
        Map<String, SkillUsageInfo> skills = playerSkillTracking.get(playerUuid);
        if (skills == null || skills.isEmpty())
            return null;

        long now = System.currentTimeMillis();
        String bestSkill = null;
        SkillUsageInfo bestInfo = null;

        for (String trackedSkill : getTrackedSkillsForKit(kit)) {
            SkillUsageInfo info = skills.get(trackedSkill);
            if (info == null)
                continue;
            long recentWindow = getRecentSkillWindowMs(trackedSkill);
            if (now - info.lastUseTime > recentWindow)
                continue;

            if (bestInfo == null || info.lastUseTime > bestInfo.lastUseTime) {
                bestSkill = trackedSkill;
                bestInfo = info;
            }
        }

        if (bestInfo == null)
            return null;

        return new RecentSkillSnapshot(bestSkill, bestInfo.adaptationTier);
    }

    private void announceAdaptationTierUp(Player player, String skillName, int tier) {
        long now = System.currentTimeMillis();
        long lastComment = skillCommentCooldown.getOrDefault(player.getUniqueId(), 0L);
        if (now - lastComment < 4000L) {
            return;
        }
        skillCommentCooldown.put(player.getUniqueId(), now);
        manager.announceSkillAdaptation(player, skillName, tier);
    }

    private long getLearningIntervalMs(String skillName) {
        return switch (skillName) {
            case "gravity_crush" -> 2000L;
            case "levitation_strike" -> 1800L;
            case "archer_rain" -> 1400L;
            case "berserk" -> 3500L;
            case "ritual_cut", "circle_trance" -> 1500L;
            case "fighter_combo" -> 1250L;
            default -> 1600L;
        };
    }

    private int resolveAdaptationTier(String skillName, int useCount) {
        int tier1;
        int tier2;
        int tier3;

        switch (skillName) {
            case "gravity_crush" -> {
                tier1 = 4;
                tier2 = 8;
                tier3 = 12;
            }
            case "levitation_strike" -> {
                tier1 = 3;
                tier2 = 6;
                tier3 = 9;
            }
            case "archer_rain" -> {
                tier1 = 3;
                tier2 = 5;
                tier3 = 8;
            }
            case "berserk" -> {
                tier1 = 2;
                tier2 = 3;
                tier3 = 5;
            }
            case "ritual_cut", "circle_trance" -> {
                tier1 = 3;
                tier2 = 6;
                tier3 = 9;
            }
            case "fighter_combo" -> {
                tier1 = 6;
                tier2 = 12;
                tier3 = 18;
            }
            default -> {
                tier1 = 4;
                tier2 = 8;
                tier3 = 12;
            }
        }

        if (useCount >= tier3)
            return 3;
        if (useCount >= tier2)
            return 2;
        if (useCount >= tier1)
            return 1;
        return 0;
    }

    private long getCounterCooldownMs(String skillName, int tier) {
        long base = switch (skillName) {
            case "gravity_crush" -> 3200L;
            case "levitation_strike" -> 3400L;
            case "archer_rain" -> 2800L;
            case "berserk" -> 4200L;
            case "ritual_cut" -> 3000L;
            case "circle_trance" -> 3400L;
            case "fighter_combo" -> 2400L;
            default -> 3200L;
        };

        int tierOffset = Math.max(0, tier - 1);
        return Math.max(1000L, base - tierOffset * 500L);
    }

    private double getCounterChance(String skillName, int tier) {
        return switch (skillName) {
            case "gravity_crush" -> (tier == 1 ? 0.16 : (tier == 2 ? 0.38 : 0.65));
            case "levitation_strike" -> (tier == 1 ? 0.12 : (tier == 2 ? 0.30 : 0.55));
            case "archer_rain" -> (tier == 1 ? 0.18 : (tier == 2 ? 0.40 : 0.68));
            case "berserk" -> (tier == 1 ? 0.12 : (tier == 2 ? 0.26 : 0.48));
            case "ritual_cut" -> (tier == 1 ? 0.14 : (tier == 2 ? 0.32 : 0.56));
            case "circle_trance" -> (tier == 1 ? 0.10 : (tier == 2 ? 0.28 : 0.50));
            case "fighter_combo" -> (tier == 1 ? 0.08 : (tier == 2 ? 0.22 : 0.42));
            default -> (tier == 1 ? 0.12 : (tier == 2 ? 0.26 : 0.45));
        };
    }

    private void applySkillCounterMechanic(Player player, String skillName, KitManager.Kit kit, SkillUsageInfo info,
            long now) {
        Zombie boss = manager.adaptationBoss();
        if (boss == null || boss.isDead())
            return;

        int tier = info.adaptationTier;
        if (tier <= 0)
            return;

        long cooldownMs = getCounterCooldownMs(skillName, tier);
        if (now - info.lastCounterTime < cooldownMs)
            return;

        double chance = getCounterChance(skillName, tier);
        if (rng.nextDouble() >= chance)
            return;

        boolean triggered = false;

        switch (skillName) {
            case "gravity_crush" -> {
                if (tier == 1) {
                    manager.adaptationBackstep(boss, player);
                } else if (tier == 2) {
                    manager.adaptationDodge(boss, player);
                } else {
                    manager.adaptationDodge(boss, player);
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (!manager.isFightActive())
                            return;
                        if (boss.isDead() || !player.isOnline())
                            return;
                        manager.adaptationTeleportBehind(player, boss);
                        boss.attack(player);
                    }, 6L);
                }
                triggered = true;
            }

            case "levitation_strike" -> {
                if (tier == 1) {
                    manager.adaptationBackstep(boss, player);
                } else if (tier == 2) {
                    manager.adaptationDodge(boss, player);
                } else {
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (!manager.isFightActive())
                            return;
                        if (boss.isDead() || !player.isOnline())
                            return;
                        manager.adaptationTeleportBehind(player, boss);
                        boss.attack(player);
                    }, 4L);
                }
                triggered = true;
            }

            case "archer_rain" -> {
                if (tier == 1) {
                    manager.adaptationBackstep(boss, player);
                } else {
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (!manager.isFightActive())
                            return;
                        if (boss.isDead() || !player.isOnline())
                            return;
                        manager.adaptationTeleportBehind(player, boss);
                        if (tier >= 3) {
                            boss.attack(player);
                        }
                    }, 5L);
                }
                triggered = true;
            }

            case "berserk" -> {
                Vector awayFromBoss = player.getLocation().toVector()
                        .subtract(boss.getLocation().toVector())
                        .setY(0.0);
                if (awayFromBoss.lengthSquared() < 0.0001) {
                    awayFromBoss = player.getLocation().getDirection().multiply(-1).setY(0.0);
                }
                if (awayFromBoss.lengthSquared() < 0.0001) {
                    awayFromBoss = new Vector(0, 0, 1);
                }
                double power = 1.1 + (tier * 0.35);
                player.setVelocity(awayFromBoss.normalize().multiply(power).setY(0.22 + tier * 0.05));
                if (tier >= 3) {
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (!manager.isFightActive())
                            return;
                        if (boss.isDead() || !player.isOnline())
                            return;
                        boss.attack(player);
                    }, 4L);
                }
                triggered = true;
            }

            case "ritual_cut", "circle_trance" -> {
                if (tier == 1) {
                    manager.adaptationBackstep(boss, player);
                } else if (tier == 2) {
                    manager.adaptationDodge(boss, player);
                } else {
                    manager.adaptationBackstep(boss, player);
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (!manager.isFightActive())
                            return;
                        if (boss.isDead() || !player.isOnline())
                            return;
                        manager.adaptationTeleportBehind(player, boss);
                        boss.attack(player);
                    }, 6L);
                }
                triggered = true;
            }

            case "fighter_combo" -> {
                if (tier == 1) {
                    manager.adaptationBackstep(boss, player);
                } else {
                    manager.adaptationDodge(boss, player);
                    if (tier >= 3) {
                        Bukkit.getScheduler().runTaskLater(plugin, () -> {
                            if (!manager.isFightActive())
                                return;
                            if (boss.isDead() || !player.isOnline())
                                return;
                            boss.attack(player);
                        }, 3L);
                    }
                }
                triggered = true;
            }
            default -> {
            }
        }

        if (!triggered) {
            switch (kit) {
                case GRAVITATOR -> {
                    manager.adaptationDodge(boss, player);
                    triggered = true;
                }
                case ARCHER, FIGHTER, DUELIST, CLONER, SUMMONER -> {
                    manager.adaptationBackstep(boss, player);
                    triggered = true;
                }
                case BERSERK -> {
                    player.setVelocity(player.getLocation().getDirection().multiply(-1.2).setY(0.28));
                    triggered = true;
                }
                default -> {
                }
            }
        }

        if (triggered) {
            info.lastCounterTime = now;
        }
    }

    private long getRecentSkillWindowMs(String skillName) {
        return switch (skillName) {
            case "gravity_crush" -> 20_000L;
            case "berserk" -> 12_000L;
            default -> RECENT_SKILL_WINDOW_MS;
        };
    }

    private String[] getTrackedSkillsForKit(KitManager.Kit kit) {
        if (kit == null)
            return new String[0];

        return switch (kit) {
            case FIGHTER -> new String[] { "fighter_combo" };
            case ARCHER -> new String[] { "archer_rain" };
            case BERSERK -> new String[] { "berserk" };
            case GRAVITATOR -> new String[] { "gravity_crush", "levitation_strike" };
            case DUELIST -> new String[] { "ritual_cut", "circle_trance" };
            case CLONER -> new String[] { "clone_summon" };
            case SUMMONER -> new String[] { "summoner_wolves", "summoner_phantom", "summoner_golem", "summoner_vex" };
            case MINER, BUILDER -> new String[0];
        };
    }

    private void checkPlayerSkillUsage() {
        long now = System.currentTimeMillis();

        for (UUID playerUuid : new ArrayList<>(playerSkillTracking.keySet())) {
            Map<String, SkillUsageInfo> skills = playerSkillTracking.get(playerUuid);
            if (skills == null) {
                playerSkillTracking.remove(playerUuid);
                continue;
            }

            skills.entrySet().removeIf(entry -> (now - entry.getValue().lastUseTime) > SKILL_TRACKING_FORGET_MS);
            if (skills.isEmpty()) {
                playerSkillTracking.remove(playerUuid);
            }
        }

        skillCommentCooldown.entrySet().removeIf(entry -> (now - entry.getValue()) > SKILL_TRACKING_FORGET_MS);
    }
}
