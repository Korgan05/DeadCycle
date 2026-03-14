package me.korgan.deadcycle.kit;

import me.korgan.deadcycle.DeadCyclePlugin;
import me.korgan.deadcycle.kit.archer.ArcherHunterMarkSkill;
import me.korgan.deadcycle.kit.archer.ArcherRicochetSkill;
import me.korgan.deadcycle.kit.archer.ArcherRainSkill;
import me.korgan.deadcycle.kit.archer.ArcherTrapArrowSkill;
import me.korgan.deadcycle.kit.berserk.BerserkBloodDashSkill;
import me.korgan.deadcycle.kit.berserk.BerserkExecutionSkill;
import me.korgan.deadcycle.kit.cloner.CloneModeSkill;
import me.korgan.deadcycle.kit.cloner.CloneSummonSkill;
import me.korgan.deadcycle.kit.duelist.DuelistAegisSkill;
import me.korgan.deadcycle.kit.duelist.DuelistBreachSkill;
import me.korgan.deadcycle.kit.duelist.DuelistCounterStanceSkill;
import me.korgan.deadcycle.kit.duelist.DuelistFeintSkill;
import me.korgan.deadcycle.kit.cyborg.CyborgSlamSkill;
import me.korgan.deadcycle.kit.exorcist.ExorcistPurgeSkill;
import me.korgan.deadcycle.kit.gravitator.GravityCrushSkill;
import me.korgan.deadcycle.kit.gravitator.LevitationStrikeSkill;
import me.korgan.deadcycle.kit.harpooner.HarpoonAnchorSkill;
import me.korgan.deadcycle.kit.harpooner.HarpoonPullSkill;
import me.korgan.deadcycle.kit.medic.MedicWaveSkill;
import me.korgan.deadcycle.kit.ping.PingBlinkSkill;
import me.korgan.deadcycle.kit.ping.PingJitterSkill;
import me.korgan.deadcycle.kit.ping.PingPulseSkill;
import me.korgan.deadcycle.kit.summoner.SummonerKitManager;
import me.korgan.deadcycle.kit.summoner.SummonerFocusCommandSkill;
import me.korgan.deadcycle.kit.summoner.SummonerRegroupSkill;
import me.korgan.deadcycle.kit.summoner.SummonerSacrificeImpulseSkill;
import me.korgan.deadcycle.kit.summoner.SummonerSummonSkill;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Менеджер для управления скиллами, кулдаунами и затратами опыта.
 */
public class SkillManager {

    private static final Set<String> DANGEROUS_SKILLS = Set.of(
            "gravity_crush",
            "levitation_strike",
            "archer_rain",
            "berserk_execution",
            "ping_pulse",
            "summoner_golem",
            "summoner_sacrifice",
            "cyborg_slam",
            "exorcist_purge");

    private final DeadCyclePlugin plugin;

    // Скиллы по ID
    private final Map<String, Skill> skills = new HashMap<>();

    // Кулдауны: UUID игрока -> (ID скилла -> время окончания кулдауна)
    private final Map<UUID, Map<String, Long>> cooldowns = new ConcurrentHashMap<>();
    private final KitSynergyManager synergyManager;

    public SkillManager(DeadCyclePlugin plugin) {
        this.plugin = plugin;
        registerSkills();
        this.synergyManager = new KitSynergyManager(plugin);
    }

    /**
     * Регистрируем все скиллы в системе.
     */
    private void registerSkills() {
        // Скилл для Лучника: Rain of Arrows
        registerSkill(new ArcherRainSkill(plugin));
        registerSkill(new ArcherHunterMarkSkill(plugin, this));
        registerSkill(new ArcherTrapArrowSkill(plugin, this));
        registerSkill(new ArcherRicochetSkill(plugin, this));

        // Скилл для Берсерка
        registerSkill(new BerserkBloodDashSkill(plugin, this));
        registerSkill(new BerserkExecutionSkill(plugin, this));

        // Скиллы для Гравитатора
        registerSkill(new GravityCrushSkill(plugin, this));
        registerSkill(new LevitationStrikeSkill(plugin, this));

        // Скиллы для Ритуалиста
        registerSkill(new DuelistBreachSkill(plugin, this));
        registerSkill(new DuelistAegisSkill(plugin, this));
        registerSkill(new DuelistCounterStanceSkill(plugin, this));
        registerSkill(new DuelistFeintSkill(plugin, this));

        // Скиллы для Клонера
        registerSkill(new CloneSummonSkill(plugin, this));
        registerSkill(new CloneModeSkill(plugin, this));

        // Скиллы для Призывателя
        registerSkill(new SummonerSummonSkill(plugin, this, SummonerKitManager.SummonType.WOLF));
        registerSkill(new SummonerSummonSkill(plugin, this, SummonerKitManager.SummonType.PHANTOM));
        registerSkill(new SummonerSummonSkill(plugin, this, SummonerKitManager.SummonType.GOLEM));
        registerSkill(new SummonerSummonSkill(plugin, this, SummonerKitManager.SummonType.VEX));
        registerSkill(new SummonerFocusCommandSkill(plugin, this));
        registerSkill(new SummonerRegroupSkill(plugin, this));
        registerSkill(new SummonerSacrificeImpulseSkill(plugin, this));

        // Скиллы для Пинга
        registerSkill(new PingBlinkSkill(plugin, this));
        registerSkill(new PingPulseSkill(plugin, this));
        registerSkill(new PingJitterSkill(plugin, this));

        // Скиллы для Гарпунера
        registerSkill(new HarpoonAnchorSkill(plugin, this));
        registerSkill(new HarpoonPullSkill(plugin, this));

        // Скиллы для новых китов
        registerSkill(new CyborgSlamSkill(plugin, this));
        registerSkill(new MedicWaveSkill(plugin, this));
        registerSkill(new ExorcistPurgeSkill(plugin, this));

        // Позже добавим скиллы для других китов...
    }

    /**
     * Зарегистрировать скилл в системе.
     */
    public void registerSkill(Skill skill) {
        if (skill != null) {
            skills.put(skill.getId(), skill);
        }
    }

    /**
     * Получить скилл по ID.
     */
    public Skill getSkill(String skillId) {
        return skills.get(skillId);
    }

    /**
     * Получить скилл кита (если у кита несколько скиллов, выбирает по уровню).
     */
    public Skill getSkillForKit(KitManager.Kit kit) {
        if (kit == null)
            return null;

        return switch (kit) {
            case ARCHER -> getSkill("archer_rain");
            case GRAVITATOR -> getSkill("gravity_crush"); // По умолчанию первый скилл
            case DUELIST -> getSkill("ritual_cut");
            case CLONER -> getSkill("clone_summon");
            case SUMMONER -> getSkill("summoner_wolves");
            case PING -> getSkill("ping_blink");
            case HARPOONER -> getSkill("harpoon_anchor");
            case CYBORG -> getSkill("cyborg_slam");
            case MEDIC -> getSkill("medic_wave");
            case EXORCIST -> getSkill("exorcist_purge");
            // case FIGHTER -> getSkill("fighter_berserk");
            // case MINER -> getSkill("miner_drill");
            default -> null;
        };
    }

    /**
     * Получить скилл для гравитатора по выбору игрока.
     * Если skillName = null, возвращает основной скилл (gravity_crush).
     */
    public Skill getGravitatorSkill(String skillName) {
        if (skillName == null || skillName.isEmpty())
            return getSkill("gravity_crush");

        Skill skill = getSkill(skillName);
        if (skill != null && (skillName.equals("gravity_crush") || skillName.equals("levitation_strike"))) {
            return skill;
        }

        return getSkill("gravity_crush");
    }

    /**
     * Проверить, может ли игрок использовать скилл.
     */
    public boolean canUse(Player p, String skillId) {
        Skill skill = getSkill(skillId);
        if (skill == null)
            return false;
        return skill.canUse(p);
    }

    /**
     * Получить сообщение об ошибке, если скилл нельзя использовать.
     */
    public String getError(Player p, String skillId) {
        Skill skill = getSkill(skillId);
        if (skill == null)
            return "§cСкилл не найден";
        return skill.getErrorMessage(p);
    }

    /**
     * Активировать скилл.
     * Проверяет всё (опыт, кулдаун, условия) и вызывает skill.activate().
     */
    public boolean tryActivate(Player p, String skillId) {
        Skill skill = getSkill(skillId);
        if (skill == null)
            return false;

        long remaining = getRemainingCooldown(p.getUniqueId(), skillId);
        if (remaining > 0) {
            long seconds = (remaining + 999) / 1000;
            p.sendMessage("§cСкилл в кулдауне: " + seconds + "s");
            return false;
        }

        if (!skill.canUse(p)) {
            String err = skill.getErrorMessage(p);
            if (err != null)
                p.sendMessage(err);
            return false;
        }

        // Для gravity_crush особая логика: ресурс и удержание обрабатываются внутри
        // самого скилла
        if ("gravity_crush".equals(skillId)) {
            emitDangerTelegraph(p, skillId);
            skill.activate(p);
            if (plugin.miniBoss() != null && !"clone_mode".equals(skillId)) {
                plugin.miniBoss().onPlayerSkillUsed(p, skillId);
            }
            synergyManager.onSkillActivated(p, skillId);
            return true;
        }

        // Обычная логика для всех остальных скиллов
        int manaCost = (int) skill.getManaCost(p);
        if (!plugin.mana().consumeXp(p, manaCost)) {
            int current = plugin.mana().getCurrentXp(p);
            int max = plugin.mana().getMaxXp(p.getUniqueId());
            p.sendMessage(String.format("§cНедостаточно маны! Нужно: §e%d§c, есть: §e%d§7/§3%d",
                    manaCost, current, max));
            return false;
        }

        emitDangerTelegraph(p, skillId);
        skill.activate(p);

        if (plugin.miniBoss() != null && !"clone_mode".equals(skillId)) {
            plugin.miniBoss().onPlayerSkillUsed(p, skillId);
        }

        synergyManager.onSkillActivated(p, skillId);

        return true;
    }

    /**
     * Установить кулдаун на скилл для игрока.
     */
    public void setCooldown(UUID uuid, String skillId, long until) {
        cooldowns.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>())
                .put(skillId, until);
    }

    /**
     * Получить оставшееся время кулдауна в миллисекундах.
     * Возвращает 0, если кулдаун истёк.
     */
    public long getRemainingCooldown(UUID uuid, String skillId) {
        Map<String, Long> playerCooldowns = cooldowns.get(uuid);
        if (playerCooldowns == null)
            return 0;

        Long until = playerCooldowns.get(skillId);
        if (until == null)
            return 0;

        long remaining = until - System.currentTimeMillis();
        if (remaining <= 0) {
            playerCooldowns.remove(skillId);
            return 0;
        }

        return remaining;
    }

    /**
     * Очистить все кулдауны игрока.
     */
    public void clearCooldowns(UUID uuid) {
        cooldowns.remove(uuid);
    }

    /**
     * Перезагрузить все скиллы (при перезагрузке конфига).
     */
    public void reloadAll() {
        for (Skill skill : skills.values()) {
            skill.reset();
        }
        synergyManager.reload();
    }

    private void emitDangerTelegraph(Player p, String skillId) {
        if (p == null || !p.isOnline() || skillId == null)
            return;
        if (!plugin.getConfig().getBoolean("skills.telegraph.enabled", true))
            return;
        if (!DANGEROUS_SKILLS.contains(skillId))
            return;

        int particles = Math.max(8, plugin.getConfig().getInt("skills.telegraph.particle_count", 18));
        float volume = (float) Math.max(0.05, plugin.getConfig().getDouble("skills.telegraph.sound_volume", 0.75));
        float pitch = (float) Math.max(0.5, plugin.getConfig().getDouble("skills.telegraph.sound_pitch", 1.25));

        Location fx = p.getLocation().clone().add(0, 1.0, 0);
        p.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, fx, particles, 0.34, 0.22, 0.34, 0.04);
        p.getWorld().spawnParticle(Particle.END_ROD, fx, Math.max(6, particles / 2), 0.20, 0.28, 0.20, 0.02);
        p.getWorld().playSound(fx, Sound.BLOCK_BEACON_POWER_SELECT, volume, pitch);
    }
}
