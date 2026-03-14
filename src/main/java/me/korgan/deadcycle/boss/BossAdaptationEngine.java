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
            case "archer_rain", "archer_mark" -> 1400L;
            case "archer_trap_arrow", "archer_ricochet" -> 1500L;
            case "berserk" -> 3500L;
            case "berserk_blood_dash" -> 1700L;
            case "berserk_execution" -> 1800L;
            case "ritual_cut", "circle_trance" -> 1500L;
            case "duelist_counter_stance" -> 1700L;
            case "duelist_feint" -> 1600L;
            case "summoner_wolves", "summoner_phantom", "summoner_golem", "summoner_vex" -> 1500L;
            case "summoner_focus", "summoner_regroup", "summoner_sacrifice" -> 1600L;
            case "ping_blink", "ping_pulse" -> 1500L;
            case "ping_jitter" -> 1500L;
            case "harpoon_anchor", "harpoon_pull" -> 1500L;
            case "cyborg_slam" -> 1600L;
            case "medic_wave" -> 1700L;
            case "exorcist_purge" -> 1500L;
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
            case "archer_mark" -> {
                tier1 = 3;
                tier2 = 6;
                tier3 = 9;
            }
            case "archer_trap_arrow" -> {
                tier1 = 3;
                tier2 = 6;
                tier3 = 9;
            }
            case "archer_ricochet" -> {
                tier1 = 3;
                tier2 = 5;
                tier3 = 8;
            }
            case "berserk" -> {
                tier1 = 2;
                tier2 = 3;
                tier3 = 5;
            }
            case "berserk_blood_dash" -> {
                tier1 = 3;
                tier2 = 6;
                tier3 = 9;
            }
            case "berserk_execution" -> {
                tier1 = 3;
                tier2 = 6;
                tier3 = 9;
            }
            case "ritual_cut", "circle_trance" -> {
                tier1 = 3;
                tier2 = 6;
                tier3 = 9;
            }
            case "duelist_counter_stance" -> {
                tier1 = 2;
                tier2 = 4;
                tier3 = 7;
            }
            case "duelist_feint" -> {
                tier1 = 2;
                tier2 = 4;
                tier3 = 7;
            }
            case "fighter_combo" -> {
                tier1 = 6;
                tier2 = 12;
                tier3 = 18;
            }
            case "ping_blink", "ping_pulse" -> {
                tier1 = 3;
                tier2 = 6;
                tier3 = 9;
            }
            case "ping_jitter" -> {
                tier1 = 3;
                tier2 = 6;
                tier3 = 9;
            }
            case "harpoon_anchor", "harpoon_pull" -> {
                tier1 = 3;
                tier2 = 6;
                tier3 = 9;
            }
            case "cyborg_slam" -> {
                tier1 = 3;
                tier2 = 6;
                tier3 = 9;
            }
            case "medic_wave" -> {
                tier1 = 2;
                tier2 = 4;
                tier3 = 7;
            }
            case "exorcist_purge" -> {
                tier1 = 3;
                tier2 = 6;
                tier3 = 9;
            }
            case "summoner_wolves", "summoner_phantom", "summoner_golem", "summoner_vex" -> {
                tier1 = 3;
                tier2 = 5;
                tier3 = 8;
            }
            case "summoner_focus" -> {
                tier1 = 2;
                tier2 = 4;
                tier3 = 7;
            }
            case "summoner_regroup", "summoner_sacrifice" -> {
                tier1 = 2;
                tier2 = 4;
                tier3 = 7;
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
            case "archer_mark" -> 2600L;
            case "archer_trap_arrow" -> 2700L;
            case "archer_ricochet" -> 2600L;
            case "berserk" -> 4200L;
            case "berserk_blood_dash" -> 2600L;
            case "berserk_execution" -> 2600L;
            case "ritual_cut" -> 3000L;
            case "circle_trance" -> 3400L;
            case "duelist_counter_stance" -> 2800L;
            case "duelist_feint" -> 2600L;
            case "fighter_combo" -> 2400L;
            case "ping_blink", "ping_pulse" -> 2500L;
            case "ping_jitter" -> 2500L;
            case "harpoon_anchor", "harpoon_pull" -> 2500L;
            case "cyborg_slam" -> 2500L;
            case "medic_wave" -> 3000L;
            case "exorcist_purge" -> 2600L;
            case "summoner_wolves", "summoner_phantom", "summoner_golem", "summoner_vex" -> 2600L;
            case "summoner_focus" -> 2500L;
            case "summoner_regroup", "summoner_sacrifice" -> 2500L;
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
            case "archer_mark" -> (tier == 1 ? 0.16 : (tier == 2 ? 0.36 : 0.62));
            case "archer_trap_arrow" -> (tier == 1 ? 0.16 : (tier == 2 ? 0.36 : 0.62));
            case "archer_ricochet" -> (tier == 1 ? 0.17 : (tier == 2 ? 0.38 : 0.64));
            case "berserk" -> (tier == 1 ? 0.12 : (tier == 2 ? 0.26 : 0.48));
            case "berserk_blood_dash" -> (tier == 1 ? 0.14 : (tier == 2 ? 0.34 : 0.60));
            case "berserk_execution" -> (tier == 1 ? 0.14 : (tier == 2 ? 0.34 : 0.60));
            case "ritual_cut" -> (tier == 1 ? 0.14 : (tier == 2 ? 0.32 : 0.56));
            case "circle_trance" -> (tier == 1 ? 0.10 : (tier == 2 ? 0.28 : 0.50));
            case "duelist_counter_stance" -> (tier == 1 ? 0.16 : (tier == 2 ? 0.38 : 0.64));
            case "duelist_feint" -> (tier == 1 ? 0.16 : (tier == 2 ? 0.36 : 0.62));
            case "fighter_combo" -> (tier == 1 ? 0.08 : (tier == 2 ? 0.22 : 0.42));
            case "ping_blink", "ping_pulse" -> (tier == 1 ? 0.14 : (tier == 2 ? 0.33 : 0.60));
            case "ping_jitter" -> (tier == 1 ? 0.14 : (tier == 2 ? 0.33 : 0.60));
            case "harpoon_anchor", "harpoon_pull" -> (tier == 1 ? 0.15 : (tier == 2 ? 0.34 : 0.62));
            case "cyborg_slam" -> (tier == 1 ? 0.15 : (tier == 2 ? 0.35 : 0.62));
            case "medic_wave" -> (tier == 1 ? 0.10 : (tier == 2 ? 0.26 : 0.48));
            case "exorcist_purge" -> (tier == 1 ? 0.16 : (tier == 2 ? 0.36 : 0.63));
            case "summoner_wolves", "summoner_phantom", "summoner_golem", "summoner_vex" -> (tier == 1 ? 0.20
                    : (tier == 2 ? 0.45 : 0.72));
            case "summoner_focus" -> (tier == 1 ? 0.18 : (tier == 2 ? 0.42 : 0.68));
            case "summoner_regroup", "summoner_sacrifice" -> (tier == 1 ? 0.18 : (tier == 2 ? 0.40 : 0.66));
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
        long sinceLastCounter = info.lastCounterTime <= 0L ? Long.MAX_VALUE : (now - info.lastCounterTime);
        if (sinceLastCounter > cooldownMs * 2L) {
            chance += 0.22;
        } else if (sinceLastCounter > cooldownMs + (cooldownMs / 2L)) {
            chance += 0.12;
        }
        chance = Math.max(0.0, Math.min(0.95, chance));
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
                    scheduleCounterFollowup(skillName, tier, 6, () -> {
                        if (!manager.isFightActive())
                            return;
                        if (boss.isDead() || !player.isOnline())
                            return;
                        manager.adaptationTeleportBehind(player, boss);
                        boss.attack(player);
                    });
                }
                triggered = true;
            }

            case "levitation_strike" -> {
                if (tier == 1) {
                    manager.adaptationBackstep(boss, player);
                } else if (tier == 2) {
                    manager.adaptationDodge(boss, player);
                } else {
                    scheduleCounterFollowup(skillName, tier, 4, () -> {
                        if (!manager.isFightActive())
                            return;
                        if (boss.isDead() || !player.isOnline())
                            return;
                        manager.adaptationTeleportBehind(player, boss);
                        boss.attack(player);
                    });
                }
                triggered = true;
            }

            case "archer_rain" -> {
                if (tier == 1) {
                    manager.adaptationBackstep(boss, player);
                } else {
                    scheduleCounterFollowup(skillName, tier, 5, () -> {
                        if (!manager.isFightActive())
                            return;
                        if (boss.isDead() || !player.isOnline())
                            return;
                        manager.adaptationTeleportBehind(player, boss);
                        if (tier >= 3) {
                            boss.attack(player);
                        }
                    });
                }
                triggered = true;
            }

            case "archer_mark" -> {
                if (tier == 1) {
                    manager.adaptationBackstep(boss, player);
                } else {
                    manager.adaptationDodge(boss, player);
                    if (tier >= 3) {
                        scheduleCounterFollowup(skillName, tier, 5, () -> {
                            if (!manager.isFightActive())
                                return;
                            if (boss.isDead() || !player.isOnline())
                                return;
                            manager.adaptationTeleportBehind(player, boss);
                        });
                    }
                }
                triggered = true;
            }

            case "archer_trap_arrow", "archer_ricochet" -> {
                if (tier == 1) {
                    manager.adaptationBackstep(boss, player);
                } else {
                    manager.adaptationDodge(boss, player);
                    if (tier >= 3) {
                        scheduleCounterFollowup(skillName, tier, 5, () -> {
                            if (!manager.isFightActive())
                                return;
                            if (boss.isDead() || !player.isOnline())
                                return;
                            manager.adaptationTeleportBehind(player, boss);
                        });
                    }
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
                    scheduleCounterFollowup(skillName, tier, 4, () -> {
                        if (!manager.isFightActive())
                            return;
                        if (boss.isDead() || !player.isOnline())
                            return;
                        boss.attack(player);
                    });
                }
                triggered = true;
            }

            case "berserk_blood_dash" -> {
                if (tier == 1) {
                    manager.adaptationBackstep(boss, player);
                } else {
                    manager.adaptationDodge(boss, player);
                    if (tier >= 3) {
                        scheduleCounterFollowup(skillName, tier, 3, () -> {
                            if (!manager.isFightActive())
                                return;
                            if (boss.isDead() || !player.isOnline())
                                return;
                            boss.attack(player);
                        });
                    }
                }
                triggered = true;
            }

            case "berserk_execution" -> {
                if (tier == 1) {
                    manager.adaptationBackstep(boss, player);
                } else {
                    manager.adaptationDodge(boss, player);
                    scheduleCounterFollowup(skillName, tier, tier >= 3 ? 2 : 4, () -> {
                        if (!manager.isFightActive())
                            return;
                        if (boss.isDead() || !player.isOnline())
                            return;
                        boss.attack(player);
                    });
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
                    scheduleCounterFollowup(skillName, tier, 6, () -> {
                        if (!manager.isFightActive())
                            return;
                        if (boss.isDead() || !player.isOnline())
                            return;
                        manager.adaptationTeleportBehind(player, boss);
                        boss.attack(player);
                    });
                }
                triggered = true;
            }

            case "duelist_counter_stance" -> {
                if (tier == 1) {
                    manager.adaptationBackstep(boss, player);
                } else if (tier == 2) {
                    manager.adaptationDodge(boss, player);
                    scheduleCounterFollowup(skillName, tier, 3, () -> {
                        if (!manager.isFightActive())
                            return;
                        if (boss.isDead() || !player.isOnline())
                            return;
                        boss.attack(player);
                    });
                } else {
                    manager.adaptationDodge(boss, player);
                    scheduleCounterFollowup(skillName, tier, 5, () -> {
                        if (!manager.isFightActive())
                            return;
                        if (boss.isDead() || !player.isOnline())
                            return;
                        manager.adaptationTeleportBehind(player, boss);
                        boss.attack(player);
                    });
                }
                triggered = true;
            }

            case "duelist_feint" -> {
                if (tier == 1) {
                    manager.adaptationBackstep(boss, player);
                } else {
                    manager.adaptationDodge(boss, player);
                    if (tier >= 2) {
                        scheduleCounterFollowup(skillName, tier, 4, () -> {
                            if (!manager.isFightActive())
                                return;
                            if (boss.isDead() || !player.isOnline())
                                return;
                            manager.adaptationTeleportBehind(player, boss);
                            if (tier >= 3) {
                                boss.attack(player);
                            }
                        });
                    }
                }
                triggered = true;
            }

            case "fighter_combo" -> {
                if (tier == 1) {
                    manager.adaptationBackstep(boss, player);
                } else {
                    manager.adaptationDodge(boss, player);
                    if (tier >= 3) {
                        scheduleCounterFollowup(skillName, tier, 3, () -> {
                            if (!manager.isFightActive())
                                return;
                            if (boss.isDead() || !player.isOnline())
                                return;
                            boss.attack(player);
                        });
                    }
                }
                triggered = true;
            }

            case "ping_blink", "ping_pulse" -> {
                if (tier == 1) {
                    manager.adaptationBackstep(boss, player);
                } else {
                    manager.adaptationDodge(boss, player);
                    if (tier >= 3) {
                        scheduleCounterFollowup(skillName, tier, 4, () -> {
                            if (!manager.isFightActive())
                                return;
                            if (boss.isDead() || !player.isOnline())
                                return;
                            manager.adaptationTeleportBehind(player, boss);
                        });
                    }
                }
                triggered = true;
            }

            case "ping_jitter" -> {
                if (tier == 1) {
                    manager.adaptationBackstep(boss, player);
                } else {
                    manager.adaptationDodge(boss, player);
                    scheduleCounterFollowup(skillName, tier, tier >= 3 ? 3 : 5, () -> {
                        if (!manager.isFightActive())
                            return;
                        if (boss.isDead() || !player.isOnline())
                            return;
                        manager.adaptationTeleportBehind(player, boss);
                    });
                }
                triggered = true;
            }

            case "harpoon_anchor", "harpoon_pull" -> {
                if (tier == 1) {
                    manager.adaptationBackstep(boss, player);
                } else {
                    manager.adaptationDodge(boss, player);
                    if (tier >= 3) {
                        scheduleCounterFollowup(skillName, tier, 5, () -> {
                            if (!manager.isFightActive())
                                return;
                            if (boss.isDead() || !player.isOnline())
                                return;
                            manager.adaptationTeleportBehind(player, boss);
                        });
                    }
                }
                triggered = true;
            }

            case "summoner_wolves", "summoner_phantom", "summoner_golem", "summoner_vex" -> {
                if (tier == 1) {
                    manager.adaptationBackstep(boss, player);
                } else {
                    manager.adaptationDodge(boss, player);
                }

                scheduleCounterFollowup(skillName, tier, 2, () -> {
                    if (!manager.isFightActive())
                        return;
                    if (boss.isDead())
                        return;
                    manager.adaptationSummonerShockwave(boss, tier);
                });

                if (tier >= 3) {
                    scheduleCounterFollowup(skillName, tier, 7, () -> {
                        if (!manager.isFightActive())
                            return;
                        if (boss.isDead() || !player.isOnline())
                            return;
                        manager.adaptationTeleportBehind(player, boss);
                    });
                }
                triggered = true;
            }

            case "summoner_focus" -> {
                if (tier == 1) {
                    manager.adaptationBackstep(boss, player);
                } else {
                    manager.adaptationDodge(boss, player);
                }

                scheduleCounterFollowup(skillName, tier, 2, () -> {
                    if (!manager.isFightActive())
                        return;
                    if (boss.isDead())
                        return;
                    manager.adaptationSummonerShockwave(boss, Math.max(1, tier));
                });

                if (tier >= 3) {
                    scheduleCounterFollowup(skillName, tier, 6, () -> {
                        if (!manager.isFightActive())
                            return;
                        if (boss.isDead() || !player.isOnline())
                            return;
                        manager.adaptationTeleportBehind(player, boss);
                    });
                }
                triggered = true;
            }

            case "summoner_regroup", "summoner_sacrifice" -> {
                if (tier == 1) {
                    manager.adaptationBackstep(boss, player);
                } else {
                    manager.adaptationDodge(boss, player);
                }

                scheduleCounterFollowup(skillName, tier, 2, () -> {
                    if (!manager.isFightActive())
                        return;
                    if (boss.isDead())
                        return;
                    manager.adaptationSummonerShockwave(boss, Math.max(1, tier));
                });

                if (tier >= 3) {
                    scheduleCounterFollowup(skillName, tier, 6, () -> {
                        if (!manager.isFightActive())
                            return;
                        if (boss.isDead() || !player.isOnline())
                            return;
                        manager.adaptationTeleportBehind(player, boss);
                    });
                }
                triggered = true;
            }

            case "cyborg_slam" -> {
                if (tier == 1) {
                    manager.adaptationBackstep(boss, player);
                } else {
                    manager.adaptationDodge(boss, player);
                    if (tier >= 3) {
                        scheduleCounterFollowup(skillName, tier, 4, () -> {
                            if (!manager.isFightActive())
                                return;
                            if (boss.isDead() || !player.isOnline())
                                return;
                            manager.adaptationTeleportBehind(player, boss);
                            boss.attack(player);
                        });
                    }
                }
                triggered = true;
            }

            case "medic_wave" -> {
                if (tier == 1) {
                    manager.adaptationBackstep(boss, player);
                } else {
                    manager.adaptationDodge(boss, player);
                    if (tier >= 3) {
                        scheduleCounterFollowup(skillName, tier, 3, () -> {
                            if (!manager.isFightActive())
                                return;
                            if (boss.isDead() || !player.isOnline())
                                return;
                            boss.attack(player);
                        });
                    }
                }
                triggered = true;
            }

            case "exorcist_purge" -> {
                if (tier == 1) {
                    manager.adaptationBackstep(boss, player);
                } else {
                    manager.adaptationDodge(boss, player);
                    if (tier >= 3) {
                        scheduleCounterFollowup(skillName, tier, 4, () -> {
                            if (!manager.isFightActive())
                                return;
                            if (boss.isDead() || !player.isOnline())
                                return;
                            manager.adaptationTeleportBehind(player, boss);
                        });
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
                case SUMMONER -> {
                    manager.adaptationBackstep(boss, player);
                    manager.adaptationSummonerShockwave(boss, Math.max(1, tier));
                    triggered = true;
                }
                case PING -> {
                    manager.adaptationDodge(boss, player);
                    triggered = true;
                }
                case HARPOONER -> {
                    manager.adaptationDodge(boss, player);
                    triggered = true;
                }
                case CYBORG -> {
                    manager.adaptationDodge(boss, player);
                    triggered = true;
                }
                case MEDIC -> {
                    manager.adaptationBackstep(boss, player);
                    triggered = true;
                }
                case EXORCIST -> {
                    manager.adaptationBackstep(boss, player);
                    triggered = true;
                }
                case ARCHER, FIGHTER, DUELIST, CLONER -> {
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
            manager.announceAdaptationCounter(player, skillName, tier);
        }
    }

    private void scheduleCounterFollowup(String skillName, int tier, int baseDelayTicks, Runnable action) {
        int delay = resolveCounterDelayTicks(skillName, tier, baseDelayTicks);
        Bukkit.getScheduler().runTaskLater(plugin, action, delay);
    }

    private int resolveCounterDelayTicks(String skillName, int tier, int baseDelayTicks) {
        int delay = Math.max(1, baseDelayTicks - Math.max(0, tier - 1));

        switch (skillName) {
            case "summoner_wolves", "summoner_phantom", "summoner_golem", "summoner_vex",
                    "summoner_focus", "summoner_regroup", "summoner_sacrifice" ->
                delay += 1;

            case "berserk_execution", "duelist_counter_stance", "fighter_combo", "berserk_blood_dash" -> delay = Math
                    .max(1, delay - 1);

            case "cyborg_slam" -> delay = Math.max(1, delay - 1);

            case "ping_blink", "ping_pulse", "ping_jitter" -> delay = Math.max(1, delay);
            default -> {
            }
        }

        return Math.max(1, delay);
    }

    private long getRecentSkillWindowMs(String skillName) {
        return switch (skillName) {
            case "gravity_crush" -> 20_000L;
            case "berserk" -> 12_000L;
            case "archer_mark" -> 8_000L;
            case "archer_trap_arrow", "archer_ricochet" -> 9_000L;
            case "berserk_blood_dash" -> 9_000L;
            case "berserk_execution" -> 9_000L;
            case "duelist_counter_stance" -> 9_000L;
            case "duelist_feint" -> 9_000L;
            case "summoner_wolves", "summoner_phantom", "summoner_golem", "summoner_vex" -> 11_000L;
            case "summoner_focus", "summoner_regroup", "summoner_sacrifice" -> 10_000L;
            case "ping_blink", "ping_pulse" -> 9_000L;
            case "ping_jitter" -> 9_000L;
            case "harpoon_anchor", "harpoon_pull" -> 10_000L;
            case "cyborg_slam" -> 9_000L;
            case "medic_wave" -> 8_000L;
            case "exorcist_purge" -> 9_000L;
            default -> RECENT_SKILL_WINDOW_MS;
        };
    }

    private String[] getTrackedSkillsForKit(KitManager.Kit kit) {
        if (kit == null)
            return new String[0];

        return switch (kit) {
            case FIGHTER -> new String[] { "fighter_combo" };
            case ARCHER -> new String[] { "archer_rain", "archer_mark", "archer_trap_arrow", "archer_ricochet" };
            case BERSERK -> new String[] { "berserk", "berserk_blood_dash", "berserk_execution" };
            case GRAVITATOR -> new String[] { "gravity_crush", "levitation_strike" };
            case DUELIST -> new String[] { "ritual_cut", "circle_trance", "duelist_counter_stance", "duelist_feint" };
            case CLONER -> new String[] { "clone_summon" };
            case SUMMONER -> new String[] { "summoner_wolves", "summoner_phantom", "summoner_golem", "summoner_vex",
                    "summoner_focus", "summoner_regroup", "summoner_sacrifice" };
            case PING -> new String[] { "ping_blink", "ping_pulse", "ping_jitter" };
            case HARPOONER -> new String[] { "harpoon_anchor", "harpoon_pull" };
            case CYBORG -> new String[] { "cyborg_slam" };
            case MEDIC -> new String[] { "medic_wave" };
            case EXORCIST -> new String[] { "exorcist_purge" };
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
