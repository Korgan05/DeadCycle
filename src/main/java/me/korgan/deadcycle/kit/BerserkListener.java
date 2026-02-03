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

import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
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

        if (lvl == 2) {
            shouldProc = finalHealth <= lifeThreshold;
            strengthSeconds = 30;
            resistSeconds = 10;
            strengthAmp = 0; // сила I
            resistAmp = 0; // сопротивление I
        } else if (lvl == 3) {
            shouldProc = finalHealth <= deathThreshold;
            strengthSeconds = 31;
            resistSeconds = 15;
            strengthAmp = 0; // сила I
            resistAmp = 1; // сопротивление II
        } else if (lvl == 4) {
            shouldProc = finalHealth <= deathThreshold;
            strengthSeconds = 36;
            resistSeconds = 20;
            strengthAmp = 0; // сила I
            resistAmp = 1; // сопротивление II
        } else if (lvl == 5) {
            shouldProc = finalHealth <= deathThreshold;
            strengthSeconds = 36;
            resistSeconds = 20;
            strengthAmp = 0; // сила I
            resistAmp = 1; // сопротивление II
        } else { // lvl >= 6
            shouldProc = finalHealth <= deathThreshold;
            strengthSeconds = 46;
            resistSeconds = 30;
            strengthAmp = 1; // сила II
            resistAmp = 1; // сопротивление II
        }

        if (!shouldProc)
            return;

        int cooldownSeconds = Math.max(1, plugin.getConfig().getInt("kit_buffs.berserk.proc_cooldown_seconds", 75));
        nextAllowedAt.put(p.getUniqueId(), now + cooldownSeconds * 1000L);

        PotionEffectType strength = type("strength");
        PotionEffectType resistance = type("resistance");

        if (strength != null) {
            p.addPotionEffect(
                    new PotionEffect(strength, strengthSeconds * 20, Math.max(0, strengthAmp), true, false, true));
        }
        if (resistance != null) {
            p.addPotionEffect(
                    new PotionEffect(resistance, resistSeconds * 20, Math.max(0, resistAmp), true, false, true));
        }

        playProcParticles(p, lvl);

        if (lvl >= 5) {
            // Рёв эндердракона + "ужас" вокруг
            p.getWorld().playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 1.0f, 1.0f);

            int radius;
            int fearSeconds;
            if (lvl >= 6) {
                radius = 10;
                fearSeconds = 7;
            } else {
                radius = Math.max(1, plugin.getConfig().getInt("kit_buffs.berserk.roar_radius", 5));
                fearSeconds = Math.max(1, plugin.getConfig().getInt("kit_buffs.berserk.roar_fear_seconds", 5));
            }

            PotionEffectType slowness = type("slowness");
            PotionEffectType weakness = type("weakness");

            Location center = p.getLocation();
            World w = center.getWorld();
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
                }
            }

            // Сам берсерк: тошнота + слепота
            PotionEffectType nausea = type("nausea");
            PotionEffectType blindness = type("blindness");
            if (nausea != null)
                p.addPotionEffect(new PotionEffect(nausea, 10 * 20, 0, true, false, true));
            if (blindness != null) {
                int blindSeconds = (lvl >= 6) ? 2 : 1;
                p.addPotionEffect(new PotionEffect(blindness, blindSeconds * 20, 0, true, false, true));
            }
        }
    }

    private void playProcParticles(Player p, int lvl) {
        if (p == null || !p.isOnline())
            return;

        boolean enabled = plugin.getConfig().getBoolean("kit_buffs.berserk.particles.enabled", true);
        if (!enabled)
            return;

        int durationTicks = Math.max(10, plugin.getConfig().getInt("kit_buffs.berserk.particles.duration_ticks", 32));
        int period = 2;

        World w = p.getWorld();
        Location base = p.getLocation().clone().add(0, 1.0, 0);

        // Одноразовый “всплеск” при старте
        if (lvl >= 6) {
            try {
                w.spawnParticle(Particle.SONIC_BOOM, base, 1, 0, 0, 0, 0);
            } catch (Throwable ignored) {
            }
        }

        new BukkitRunnable() {
            int lived = 0;

            @Override
            public void run() {
                if (!p.isOnline()) {
                    cancel();
                    return;
                }
                if (lived >= durationTicks) {
                    cancel();
                    return;
                }

                Location loc = p.getLocation().clone().add(0, 1.0, 0);

                if (lvl == 2) {
                    spawnDust(w, loc, Color.fromRGB(255, 60, 60), 1.2f, 10);
                    w.spawnParticle(Particle.SMOKE, loc, 6, 0.25, 0.12, 0.25, 0.01);
                } else if (lvl == 3) {
                    spawnDust(w, loc, Color.fromRGB(255, 25, 25), 1.35f, 14);
                    w.spawnParticle(Particle.CRIT, loc, 6, 0.25, 0.18, 0.25, 0.12);
                } else if (lvl == 4) {
                    spawnDust(w, loc, Color.fromRGB(200, 0, 0), 1.5f, 18);
                    w.spawnParticle(Particle.CRIMSON_SPORE, loc, 10, 0.35, 0.22, 0.35, 0.01);
                } else if (lvl == 5) {
                    spawnDust(w, loc, Color.fromRGB(255, 0, 85), 1.65f, 22);
                    w.spawnParticle(Particle.DRAGON_BREATH, loc, 8, 0.35, 0.20, 0.35, 0.01);
                    if (lived % 6 == 0) {
                        spawnRing(w, p.getLocation(), 2.2, 18, Color.fromRGB(255, 0, 85), 1.3f);
                    }
                } else { // lvl >= 6
                    spawnDust(w, loc, Color.fromRGB(170, 0, 255), 1.8f, 26);
                    w.spawnParticle(Particle.REVERSE_PORTAL, loc, 14, 0.45, 0.25, 0.45, 0.06);
                    if (lived % 4 == 0) {
                        spawnRing(w, p.getLocation(), 3.2, 26, Color.fromRGB(170, 0, 255), 1.35f);
                    }
                }

                lived += period;
            }
        }.runTaskTimer(plugin, 0L, period);
    }

    private void spawnDust(World w, Location loc, Color c, float size, int count) {
        if (w == null || loc == null || c == null)
            return;
        try {
            w.spawnParticle(Particle.DUST, loc, Math.max(1, count), 0.30, 0.25, 0.30, 0,
                    new Particle.DustOptions(c, Math.max(0.2f, size)));
        } catch (Throwable ignored) {
        }
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
