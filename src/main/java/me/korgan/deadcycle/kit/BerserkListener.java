package me.korgan.deadcycle.kit;

import me.korgan.deadcycle.DeadCyclePlugin;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;

import java.time.Duration;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class BerserkListener implements Listener {

    private final DeadCyclePlugin plugin;

    private final Map<UUID, Long> nextAllowedAt = new HashMap<>();

    private final Map<UUID, FreezeEntry> frozen = new HashMap<>();
    private boolean freezeTaskStarted = false;

    private record FreezeEntry(Location anchor, long untilMillis) {
    }

    public BerserkListener(DeadCyclePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        nextAllowedAt.remove(id);
        frozen.remove(id);
    }

    @EventHandler
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p))
            return;

        if (plugin.kit().getKit(p.getUniqueId()) != KitManager.Kit.BERSERK)
            return;

        int lvl = plugin.progress().getBerserkLevel(p.getUniqueId());
        if (lvl < 2)
            return;

        if (e.isCancelled())
            return;

        double finalHealth = p.getHealth() - e.getFinalDamage();
        if (finalHealth <= 0)
            return; // на этой тычке всё равно умирает

        long now = System.currentTimeMillis();
        long next = nextAllowedAt.getOrDefault(p.getUniqueId(), 0L);
        if (now < next)
            return;

        double lifeThreshold = plugin.getConfig().getDouble("kit_buffs.berserk.life_threshold_hearts", 3.0) * 2.0;
        double deathThreshold = plugin.getConfig().getDouble("kit_buffs.berserk.death_threshold_hearts", 2.0) * 2.0;

        boolean shouldProc;
        int strengthSeconds;
        int resistSeconds;
        int strengthAmp;
        int resistAmp;
        int speedSeconds;
        int speedAmp;
        int delayedNauseaSeconds;
        int delayedBlindnessSeconds;

        if (lvl == 2) {
            shouldProc = finalHealth <= lifeThreshold;
            strengthSeconds = 30;
            resistSeconds = 10;
            strengthAmp = 0; // сила I
            resistAmp = 0; // сопротивление I
            speedSeconds = 0;
            speedAmp = 0;
            delayedNauseaSeconds = 0;
            delayedBlindnessSeconds = 0;
        } else if (lvl == 3) {
            shouldProc = finalHealth <= deathThreshold;
            strengthSeconds = 31;
            resistSeconds = 15;
            strengthAmp = 0; // сила I
            resistAmp = 1; // сопротивление II
            speedSeconds = 0;
            speedAmp = 0;
            delayedNauseaSeconds = 0;
            delayedBlindnessSeconds = 0;
        } else if (lvl == 4) {
            shouldProc = finalHealth <= deathThreshold;
            strengthSeconds = 36;
            resistSeconds = 20;
            strengthAmp = 0; // сила I
            resistAmp = 1; // сопротивление II
            speedSeconds = 0;
            speedAmp = 0;
            delayedNauseaSeconds = 0;
            delayedBlindnessSeconds = 0;
        } else if (lvl == 5) {
            shouldProc = finalHealth <= deathThreshold;
            strengthSeconds = 36;
            resistSeconds = 20;
            strengthAmp = 0; // сила I
            resistAmp = 1; // сопротивление II
            speedSeconds = 0;
            speedAmp = 0;
            delayedNauseaSeconds = 0;
            delayedBlindnessSeconds = 0;
        } else if (lvl == 6) {
            shouldProc = finalHealth <= deathThreshold;
            strengthSeconds = 46;
            resistSeconds = 30;
            strengthAmp = 1; // сила II
            resistAmp = 1; // сопротивление II
            speedSeconds = 0;
            speedAmp = 0;
            delayedNauseaSeconds = 0;
            delayedBlindnessSeconds = 0;
        } else if (lvl == 7) {
            shouldProc = finalHealth <= deathThreshold;
            strengthSeconds = 30;
            resistSeconds = 30;
            strengthAmp = 2; // сила III
            resistAmp = 2; // сопротивление III
            speedSeconds = 15;
            speedAmp = 0; // скорость I
            delayedNauseaSeconds = 10;
            delayedBlindnessSeconds = 0;
        } else if (lvl == 8) {
            shouldProc = finalHealth <= deathThreshold;
            strengthSeconds = 20;
            resistSeconds = 20;
            strengthAmp = 3; // сила IV
            resistAmp = 3; // сопротивление IV
            speedSeconds = 20;
            speedAmp = 0; // скорость I
            delayedNauseaSeconds = 9;
            delayedBlindnessSeconds = 0;
        } else if (lvl == 9) {
            shouldProc = finalHealth <= deathThreshold;
            strengthSeconds = 20;
            resistSeconds = 20;
            strengthAmp = 4; // сила V
            resistAmp = 4; // сопротивление V
            speedSeconds = 20;
            speedAmp = 1; // скорость II
            delayedNauseaSeconds = 8;
            delayedBlindnessSeconds = 0;
        } else { // lvl >= 6
            // lvl >= 10
            shouldProc = finalHealth <= deathThreshold;
            strengthSeconds = 30;
            resistSeconds = 30;
            strengthAmp = 4; // сила V
            resistAmp = 4; // сопротивление V
            speedSeconds = 30;
            speedAmp = 2; // скорость III
            delayedNauseaSeconds = 20;
            delayedBlindnessSeconds = 20;
        }

        if (!shouldProc)
            return;

        int cooldownSeconds = Math.max(1, plugin.getConfig().getInt("kit_buffs.berserk.proc_cooldown_seconds", 75));
        nextAllowedAt.put(p.getUniqueId(), now + cooldownSeconds * 1000L);

        PotionEffectType strength = type("strength");
        PotionEffectType resistance = type("resistance");
        PotionEffectType speed = type("speed");

        if (strength != null) {
            p.addPotionEffect(
                    new PotionEffect(strength, strengthSeconds * 20, Math.max(0, strengthAmp), true, false, true));
        }
        if (resistance != null) {
            p.addPotionEffect(
                    new PotionEffect(resistance, resistSeconds * 20, Math.max(0, resistAmp), true, false, true));
        }

        if (speed != null && speedSeconds > 0) {
            p.addPotionEffect(new PotionEffect(speed, speedSeconds * 20, Math.max(0, speedAmp), true, false, true));
        }

        if (delayedNauseaSeconds > 0 || delayedBlindnessSeconds > 0) {
            int delayTicks = Math.max(strengthSeconds, resistSeconds) * 20;
            scheduleDelayedSelfDebuffs(p, delayTicks, delayedNauseaSeconds, delayedBlindnessSeconds);
        }

        playProcParticles(p, lvl, strength, resistance);

        if (lvl >= 5) {
            // Рёв эндердракона + "ужас" вокруг
            p.getWorld().playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 1.0f, 1.0f);

            int radius;
            int fearSeconds;
            if (lvl >= 10) {
                radius = 50;
                fearSeconds = 20;
            } else if (lvl == 9) {
                radius = 25;
                fearSeconds = 15;
            } else if (lvl == 8) {
                radius = 20;
                fearSeconds = 15;
            } else if (lvl == 7) {
                radius = 15;
                fearSeconds = 10;
            } else if (lvl >= 6) {
                radius = 10;
                fearSeconds = 7;
            } else {
                radius = Math.max(1, plugin.getConfig().getInt("kit_buffs.berserk.roar_radius", 5));
                fearSeconds = Math.max(1, plugin.getConfig().getInt("kit_buffs.berserk.roar_fear_seconds", 5));
            }

            playRoarParticles(p, lvl, radius, fearSeconds);

            PotionEffectType slowness = type("slowness");
            PotionEffectType weakness = type("weakness");

            Location center = p.getLocation();
            World w = center.getWorld();
            Set<UUID> zombiesForDot = (lvl >= 10) ? new HashSet<>() : null;
            if (w != null) {
                double r2 = radius * radius;
                for (Entity ent : w.getNearbyEntities(center, radius, radius, radius)) {
                    if (!(ent instanceof LivingEntity le))
                        continue;
                    if (ent.getUniqueId().equals(p.getUniqueId()))
                        continue;

                    // только зомби и игроки
                    if (!(le instanceof Player) && !(le instanceof Zombie))
                        continue;

                    if (ent.getLocation().distanceSquared(center) > r2)
                        continue;

                    if (slowness != null)
                        le.addPotionEffect(new PotionEffect(slowness, fearSeconds * 20, 255, true, false, true));
                    if (weakness != null)
                        le.addPotionEffect(new PotionEffect(weakness, fearSeconds * 20, 255, true, false, true));

                    freeze(le, fearSeconds);

                    if (lvl >= 10) {
                        if (le instanceof Zombie) {
                            zombiesForDot.add(le.getUniqueId());
                        } else if (le instanceof Player target) {
                            showDeathTitleAndSound(p, target);
                        }
                    }
                }
            }

            if (lvl >= 10 && zombiesForDot != null && !zombiesForDot.isEmpty()) {
                startZombieDot(p, zombiesForDot, fearSeconds);
            }

            // Сам берсерк: старые уровни 5-6 получают тошноту/слепоту сразу.
            // Начиная с 7 уровня тошнота (и слепота на 10) накладываются ПОСЛЕ бафов.
            if (lvl <= 6) {
                PotionEffectType nausea = type("nausea");
                PotionEffectType blindness = type("blindness");
                if (nausea != null)
                    p.addPotionEffect(new PotionEffect(nausea, 10 * 20, 0, true, false, true));
                if (blindness != null) {
                    int blindSeconds = (lvl >= 6) ? 2 : 1;
                    p.addPotionEffect(new PotionEffect(blindness, blindSeconds * 20, 0, true, false, true));
                }
            } else if (lvl == 7) {
                PotionEffectType blindness = type("blindness");
                if (blindness != null)
                    p.addPotionEffect(new PotionEffect(blindness, 3 * 20, 0, true, false, true));
            }
        }
    }

    private void playRoarParticles(Player p, int lvl, int radius, int fearSeconds) {
        if (p == null || !p.isOnline())
            return;

        boolean enabled = plugin.getConfig().getBoolean("kit_buffs.berserk.particles.enabled", true);
        if (!enabled)
            return;

        boolean roarEnabled = plugin.getConfig().getBoolean("kit_buffs.berserk.particles.roar_enabled", true);
        if (!roarEnabled)
            return;

        int steps = Math.max(4, plugin.getConfig().getInt("kit_buffs.berserk.particles.roar_steps", 0));
        int period = Math.max(1, plugin.getConfig().getInt("kit_buffs.berserk.particles.roar_period", 1));
        int r = Math.max(1, radius);

        World w = p.getWorld();
        Location center = p.getLocation().clone();

        // Тёмно-красная "аура" волной
        Color c = Color.fromRGB(120, 0, 0);
        float dustSize = (lvl >= 6) ? 1.55f : 1.45f;

        // По умолчанию: плавная волна, зависящая от радиуса
        final int finalSteps = (plugin.getConfig().getInt("kit_buffs.berserk.particles.roar_steps", 0) <= 0)
                ? Math.max(12, r * 6)
                : steps;

        // 10 уровень: чёрная волна по земле до конца стана
        if (lvl >= 10) {
            playLevel10BlackGroundWave(p, r, Math.max(1, fearSeconds));
            return;
        }

        // Только красные партиклы: небольшой всплеск у ног
        spawnDust(w, center.clone().add(0, 0.25, 0), c, dustSize, 6);

        new BukkitRunnable() {
            int i = 0;

            @Override
            public void run() {
                if (!p.isOnline()) {
                    cancel();
                    return;
                }
                if (i >= finalSteps) {
                    cancel();
                    return;
                }

                double k = (i + 1) / (double) finalSteps;
                double ringRadius = Math.max(0.6, r * k);
                int points = Math.max(22, (int) (ringRadius * 14));
                spawnRing(w, center, ringRadius, points, c, dustSize);
                // Никаких дополнительных (не красных) частиц

                i++;
            }
        }.runTaskTimer(plugin, 0L, period);
    }

    private void playProcParticles(Player p, int lvl, PotionEffectType strength, PotionEffectType resistance) {
        if (p == null || !p.isOnline())
            return;

        boolean enabled = plugin.getConfig().getBoolean("kit_buffs.berserk.particles.enabled", true);
        if (!enabled)
            return;

        int maxTicks = Math.max(20, plugin.getConfig().getInt("kit_buffs.berserk.particles.max_ticks", 20 * 60));
        int period = Math.max(2, plugin.getConfig().getInt("kit_buffs.berserk.particles.period", 4));
        int burstTicks = Math.max(0, plugin.getConfig().getInt("kit_buffs.berserk.particles.proc_burst_ticks", 40));

        World w = p.getWorld();

        new BukkitRunnable() {
            int lived = 0;

            @Override
            public void run() {
                if (!p.isOnline()) {
                    cancel();
                    return;
                }

                // Останавливаем визуал, когда бафы исчезли (или их сняли молоком и т.п.)
                boolean hasStrength = (strength != null) && p.hasPotionEffect(strength);
                boolean hasResist = (resistance != null) && p.hasPotionEffect(resistance);
                if (!hasStrength && !hasResist) {
                    cancel();
                    return;
                }

                if (lived >= maxTicks) {
                    cancel();
                    return;
                }

                // Партиклы делаем у ног, чтобы не закрывало обзор
                Location feet = p.getLocation().clone().add(0, 0.25, 0);

                // Только красные оттенки (без розового/фиолетового)
                Color color;
                float size;
                int dustCount;
                double ringRadius;
                int ringPoints;

                if (lvl == 2) {
                    color = Color.fromRGB(255, 80, 80);
                    size = 1.05f;
                    dustCount = 5;
                    ringRadius = 0.7;
                    ringPoints = 10;
                } else if (lvl == 3) {
                    color = Color.fromRGB(255, 40, 40);
                    size = 1.15f;
                    dustCount = 6;
                    ringRadius = 0.85;
                    ringPoints = 12;
                } else if (lvl == 4) {
                    color = Color.fromRGB(220, 15, 15);
                    size = 1.25f;
                    dustCount = 7;
                    ringRadius = 1.0;
                    ringPoints = 14;
                } else if (lvl == 5) {
                    color = Color.fromRGB(180, 0, 0);
                    size = 1.35f;
                    dustCount = 8;
                    ringRadius = 1.2;
                    ringPoints = 16;
                } else if (lvl == 6) {
                    color = Color.fromRGB(140, 0, 0);
                    size = 1.45f;
                    dustCount = 9;
                    ringRadius = 1.45;
                    ringPoints = 18;
                } else if (lvl == 7) {
                    color = Color.fromRGB(120, 0, 0);
                    size = 1.55f;
                    dustCount = 9;
                    ringRadius = 1.6;
                    ringPoints = 20;
                } else if (lvl == 8) {
                    color = Color.fromRGB(105, 0, 0);
                    size = 1.65f;
                    dustCount = 10;
                    ringRadius = 1.75;
                    ringPoints = 22;
                } else if (lvl == 9) {
                    color = Color.fromRGB(90, 0, 0);
                    size = 1.75f;
                    dustCount = 10;
                    ringRadius = 1.9;
                    ringPoints = 24;
                } else { // lvl >= 10
                    color = Color.fromRGB(70, 0, 0);
                    size = 1.85f;
                    dustCount = 11;
                    ringRadius = 2.1;
                    ringPoints = 26;
                }

                // Лёгкая "аура" у ног + кольцо (красиво, но не мешает видеть)
                spawnDust(w, feet, color, size, dustCount);
                if (burstTicks > 0 && lived <= burstTicks) {
                    spawnRing(w, p.getLocation(), ringRadius, ringPoints, color, Math.max(0.9f, size - 0.1f));
                }

                lived += period;
            }
        }.runTaskTimer(plugin, 0L, period);
    }

    private void spawnDust(World w, Location loc, Color c, float size, int count) {
        if (w == null || loc == null || c == null)
            return;
        try {
            // Меньше разброса, чтобы не создавался "туман" у лица
            w.spawnParticle(Particle.DUST, loc, Math.max(1, count), 0.18, 0.10, 0.18, 0,
                    new Particle.DustOptions(c, Math.max(0.2f, size)));
        } catch (Throwable ignored) {
        }
    }

    private void scheduleDelayedSelfDebuffs(Player p, int delayTicks, int nauseaSeconds, int blindnessSeconds) {
        if (p == null)
            return;

        int safeDelay = Math.max(1, delayTicks);

        // Требование: тошнота/слепота должны прийти ПОСЛЕ того, как закончится
        // сила/сопротивление.
        // Поэтому на момент срабатывания перепроверяем и, если нужно, немного ждём.
        final int maxExtraChecks = 40; // до ~40 секунд ожидания (1 check/сек)
        Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
            int checks = 0;

            @Override
            public void run() {
                if (!p.isOnline())
                    return;

                PotionEffectType strength = type("strength");
                PotionEffectType resistance = type("resistance");
                boolean hasStrength = strength != null && p.hasPotionEffect(strength);
                boolean hasResist = resistance != null && p.hasPotionEffect(resistance);

                if ((hasStrength || hasResist) && checks < maxExtraChecks) {
                    checks++;
                    Bukkit.getScheduler().runTaskLater(plugin, this, 20L);
                    return;
                }

                PotionEffectType nausea = type("nausea");
                PotionEffectType blindness = type("blindness");

                if (nauseaSeconds > 0 && nausea != null) {
                    p.addPotionEffect(new PotionEffect(nausea, nauseaSeconds * 20, 0, true, false, true));
                }
                if (blindnessSeconds > 0 && blindness != null) {
                    p.addPotionEffect(new PotionEffect(blindness, blindnessSeconds * 20, 0, true, false, true));
                }
            }
        }, safeDelay);
    }

    private void showDeathTitleAndSound(Player berserk, Player target) {
        if (target == null || !target.isOnline())
            return;
        if (berserk != null && target.getUniqueId().equals(berserk.getUniqueId()))
            return;

        try {
            Title title = Title.title(
                    Component.text("DEATH", NamedTextColor.DARK_RED).decorate(TextDecoration.BOLD),
                    Component.empty(),
                    Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(900), Duration.ofMillis(300)));
            target.showTitle(title);
        } catch (Throwable ignored) {
        }

        try {
            target.getWorld().playSound(target.getLocation(), Sound.ENTITY_WITHER_SPAWN, 1.0f, 0.5f);
        } catch (Throwable ignored) {
        }
    }

    private void startZombieDot(Player source, Set<UUID> zombieIds, int seconds) {
        if (zombieIds == null || zombieIds.isEmpty())
            return;

        int total = Math.max(1, seconds);
        new BukkitRunnable() {
            int left = total;

            @Override
            public void run() {
                if (left <= 0) {
                    cancel();
                    return;
                }

                for (UUID id : zombieIds) {
                    Entity ent = Bukkit.getEntity(id);
                    if (!(ent instanceof Zombie z) || z.isDead())
                        continue;

                    try {
                        z.damage(1.0, source);
                    } catch (Throwable ignored) {
                    }
                }

                left--;
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    private void playLevel10BlackGroundWave(Player p, int radius, int fearSeconds) {
        if (p == null || !p.isOnline())
            return;

        World w = p.getWorld();
        Color black = Color.fromRGB(8, 8, 8);
        float size = 1.35f;

        int totalTicks = Math.max(1, fearSeconds) * 20;
        int period = 2;
        int expandTicks = Math.min(40, totalTicks); // расширение ~2 секунды

        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (!p.isOnline()) {
                    cancel();
                    return;
                }
                if (t >= totalTicks) {
                    cancel();
                    return;
                }

                Location center = p.getLocation().clone();

                double r;
                if (t < expandTicks) {
                    r = Math.max(0.8, radius * (t / (double) expandTicks));
                } else {
                    r = radius;
                }

                int points = Math.max(60, (int) (r * 5));
                points = Math.min(180, points);
                spawnRing(w, center, r, points, black, size);

                t += period;
            }
        }.runTaskTimer(plugin, 0L, period);
    }

    private void spawnRing(World w, Location center, double radius, int points, Color c, float size) {
        if (w == null || center == null)
            return;
        Location base = center.clone().add(0, 0.15, 0);
        int n = Math.max(8, points);
        for (int i = 0; i < n; i++) {
            double a = (Math.PI * 2.0) * (i / (double) n);
            double x = Math.cos(a) * radius;
            double z = Math.sin(a) * radius;
            Location at = base.clone().add(x, 0.0, z);
            spawnDust(w, at, c, size, 1);
        }
    }

    private void freeze(LivingEntity ent, int seconds) {
        if (ent == null)
            return;
        Location loc = ent.getLocation().clone();
        // фиксируем на центрах блока, чтобы не дрожало
        loc.setX(loc.getBlockX() + 0.5);
        loc.setZ(loc.getBlockZ() + 0.5);

        long until = System.currentTimeMillis() + seconds * 1000L;
        frozen.put(ent.getUniqueId(), new FreezeEntry(loc, until));
        startFreezeTaskIfNeeded();
    }

    private void startFreezeTaskIfNeeded() {
        if (freezeTaskStarted)
            return;
        freezeTaskStarted = true;

        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (frozen.isEmpty())
                return;

            long now = System.currentTimeMillis();
            Iterator<Map.Entry<UUID, FreezeEntry>> it = frozen.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<UUID, FreezeEntry> e = it.next();
                UUID id = e.getKey();
                FreezeEntry fe = e.getValue();

                if (fe.untilMillis() < now) {
                    it.remove();
                    continue;
                }

                Entity ent = Bukkit.getEntity(id);
                if (!(ent instanceof LivingEntity le) || le.isDead()) {
                    it.remove();
                    continue;
                }

                Location cur = le.getLocation();
                Location anchor = fe.anchor();
                if (cur.getWorld() == null || anchor.getWorld() == null || !cur.getWorld().equals(anchor.getWorld())) {
                    it.remove();
                    continue;
                }

                // если сдвинулся — возвращаем
                if (cur.distanceSquared(anchor) > 0.05) {
                    le.teleport(anchor);
                }

                // обнуляем скорость (чтобы не "проталкивало")
                try {
                    le.setVelocity(le.getVelocity().multiply(0));
                } catch (Throwable ignored) {
                }
            }
        }, 1L, 1L);
    }

    private PotionEffectType type(String key) {
        if (key == null || key.isBlank())
            return null;
        return PotionEffectType.getByKey(NamespacedKey.minecraft(key.toLowerCase(Locale.ROOT)));
    }
}
