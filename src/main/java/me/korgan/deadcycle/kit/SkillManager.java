package me.korgan.deadcycle.kit;

import me.korgan.deadcycle.DeadCyclePlugin;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Менеджер для управления скиллами, кулдаунами и затратами опыта.
 */
public class SkillManager {

    private final DeadCyclePlugin plugin;

    // Скиллы по ID
    private final Map<String, Skill> skills = new HashMap<>();

    // Кулдауны: UUID игрока -> (ID скилла -> время окончания кулдауна)
    private final Map<UUID, Map<String, Long>> cooldowns = new ConcurrentHashMap<>();

    public SkillManager(DeadCyclePlugin plugin) {
        this.plugin = plugin;
        registerSkills();
    }

    /**
     * Регистрируем все скиллы в системе.
     */
    private void registerSkills() {
        // Скилл для Лучника: Rain of Arrows
        registerSkill(new ArcherRainSkill(plugin));

        // Скиллы для Гравитатора
        registerSkill(new GravityCrushSkill(plugin, this));
        registerSkill(new LevitationStrikeSkill(plugin, this));

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

        // Специальная логика для Gravity Crush: сначала пытаемся потратить XP, если нет
        // Специальная логика для Gravity Crush и Levitation Strike
        if (skillId.equals("gravity_crush") || skillId.equals("levitation_strike")) {
            int xpCost = skill.getXpCost(p);
            int currentXp = p.getLevel();

            if (currentXp >= xpCost) {
                // Хватает опыта - берём его
                p.setLevel(currentXp - xpCost);
            } else {
                // Опыта не хватает
                if (skillId.equals("levitation_strike")) {
                    // Для антигравитации - просто отказ
                    p.sendMessage("§cНедостаточно опыта! Нужно: " + xpCost + ", есть: " + currentXp);
                    return false;
                }
                // Для gravity_crush - проверяем HP (будет потрачен в activate())
                // HP стоимость будет потрачена в самом activate()
                if (p.getHealth() <= 0) {
                    p.sendMessage("§cНельзя использовать скилл: недостаточно здоровья!");
                    return false;
                }
            }

            skill.activate(p);
            return true;
        }

        // Обычная логика для других скиллов
        int cost = skill.getXpCost(p);
        int current = p.getLevel();
        if (current < cost) {
            p.sendMessage("§cНедостаточно опыта! Нужно: " + cost + ", есть: " + current);
            return false;
        }

        // Берём опыт
        p.setLevel(current - cost);

        // Активируем скилл
        skill.activate(p);

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
    }
}
