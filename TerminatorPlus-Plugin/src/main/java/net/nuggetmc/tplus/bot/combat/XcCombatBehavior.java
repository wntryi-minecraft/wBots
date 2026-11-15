package net.nuggetmc.tplus.bot.combat;

import net.nuggetmc.tplus.bot.Bot;
import net.nuggetmc.tplus.bot.loadout.BotInventory;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

/**
 * NodesKrieg: fights with xc weapons the way a player does. The bot is a real server-side player, so it drives xc through
 * xc's BotGunApi player controls (reflection; without xc 3.13.0 this behaviour stays off): it pulls the trigger and reloads
 * exactly like a right-click and the reload key, so the gun keeps xc's own ammo, shot delay, reload time, sway and stance
 * rules (a musket reloads as slowly for the bot as for anyone), and it bandages with an xc consumable as a right-click does.
 * xc blades are vanilla swords, so the regular melee path swings them; those hits are player hits and get xc's damage.
 *
 * Per tick, outside blade reach: bandage when hurt and not under pressure, else hold the gun; reload it when empty, wait out
 * a reload, and when it is loaded and the target is in sight, aim at the chest (leading a runner, off by the bot's aim
 * error), hold the aim for the bot's aim ticks (sneaking first if the gun wants it) and fire along that aim.
 */
public final class XcCombatBehavior {
    /** Closer than this the blade takes over. */
    public static final double BLADE_RANGE = 3.5;
    static final double MAX_RANGE = 70.0;
    /** Below this share of max health the bot bandages, when the target is at least [BANDAGE_DISTANCE] away or out of sight. */
    static final double BANDAGE_BELOW = 0.4;
    static final double BANDAGE_DISTANCE = 12.0;

    private static boolean looked;
    private static Method isGun, isConsumable, gunNeeds, heldGunAmmo, pullTrigger, reload, useConsumable;
    private static int needsSneak = 1, needsStationary = 2;

    private static final class State {
        int aimTicks;
        int busyUntil;          // bandaging: keep the bandage in hand until then
        int nextBandage;
        int nextReload;
        int emptyReloads;       // reloads asked for in a row that left the gun empty (no ammo left)
        boolean dry;            // out of ammo for good: fight with the blade
        Vector aimError = new Vector();
        boolean sneaking;
    }

    private final Map<UUID, State> states = new HashMap<>();

    public void clear(UUID botId) {
        states.remove(botId);
    }

    /** True if xc's BotGunApi is there. */
    public static boolean available() {
        if (!looked) {
            looked = true;
            try {
                Class<?> api = Class.forName("phonon.xc.api.BotGunApi");
                isGun = api.getMethod("isGun", ItemStack.class);
                isConsumable = api.getMethod("isConsumable", ItemStack.class);
                gunNeeds = api.getMethod("gunNeeds", ItemStack.class);
                heldGunAmmo = api.getMethod("heldGunAmmo", Player.class);
                pullTrigger = api.getMethod("pullTrigger", Player.class, Vector.class);
                reload = api.getMethod("reload", Player.class);
                useConsumable = api.getMethod("useConsumable", Player.class);
                needsSneak = api.getField("NEEDS_SNEAK").getInt(null);
                needsStationary = api.getField("NEEDS_STATIONARY").getInt(null);
            } catch (ReflectiveOperationException | LinkageError e) {
                isGun = null;
            }
        }
        return isGun != null;
    }

    private static boolean test(Method m, ItemStack it) {
        if (it == null || it.getType().isAir()) return false;
        try { return (boolean) m.invoke(null, it); } catch (ReflectiveOperationException e) { return false; }
    }

    private static Object call(Method m, Object... args) {
        try { return m.invoke(null, args); } catch (ReflectiveOperationException e) { return null; }
    }

    public static boolean isXcGun(ItemStack it) {
        return available() && test(isGun, it);
    }

    /** The first inventory slot (hotbar first) holding an item that passes [p], or -1. */
    private static int find(BotInventory inv, Predicate<ItemStack> p) {
        for (int i = 0; i < 36; i++) {
            ItemStack it = inv.raw().getItem(i);
            if (it != null && p.test(it)) return i;
        }
        return -1;
    }

    /** True if the bot carries a usable xc gun: it then fights at range until the target is within blade reach. */
    public boolean hasGun(Bot bot) {
        State st = states.get(bot.getUUID());
        if (st != null && st.dry) return false;
        return available() && find(bot.getBotInventory(), it -> test(isGun, it)) >= 0;
    }

    /** True if this tick went to the gun or a bandage (the rest of the combat director then stands down). */
    public boolean tick(Bot bot, LivingEntity target, double distance) {
        if (!available()) return false;
        Player self = bot.getBukkitEntity();
        BotInventory inv = bot.getBotInventory();
        int alive = bot.getAliveTicks();
        State st = states.computeIfAbsent(bot.getUUID(), k -> new State());

        if (alive < st.busyUntil) return true;  // still bandaging: changing the held item would cancel it

        double max = maxHealth(self);
        if (bot.usesBandages() && self.getHealth() < max * BANDAGE_BELOW && alive >= st.nextBandage
                && (distance >= BANDAGE_DISTANCE || !self.hasLineOfSight(target))) {
            int slot = find(inv, it -> test(isConsumable, it));
            if (slot >= 0 && inv.selectMainInventorySlot(slot) >= 0 && Boolean.TRUE.equals(call(useConsumable, self))) {
                unsneak(bot, st);
                st.busyUntil = alive + 30;
                st.nextBandage = alive + 100;
                st.aimTicks = 0;
                CombatDebugger.log(bot, "xc-bandage", "hp=" + String.format("%.1f", self.getHealth()));
                return true;
            }
        }

        int gunSlot = st.dry ? -1 : find(inv, it -> test(isGun, it));
        if (gunSlot < 0 || distance <= BLADE_RANGE) {
            st.aimTicks = 0;
            unsneak(bot, st);
            return false;  // the blade path selects the sword and swings
        }
        if (inv.selectMainInventorySlot(gunSlot) < 0) return false;
        ItemStack gun = inv.getSelected();

        Object a = call(heldGunAmmo, self);
        int ammo = a instanceof Integer i ? i : -1;
        if (ammo == -2) {                 // reloading: keep the gun up, movement keeps its distance
            st.aimTicks = 0;
            unsneak(bot, st);
            return true;
        }
        if (ammo == 0) {
            if (alive >= st.nextReload) {
                // xc starts no reload without ammo in the inventory: after a few tries the gun is dry for this life
                if (++st.emptyReloads > 4) {
                    st.dry = true;
                    CombatDebugger.log(bot, "xc-dry", "no ammo left");
                    return false;
                }
                call(reload, self);
                st.nextReload = alive + 10;
                CombatDebugger.log(bot, "xc-reload", "dist=" + String.format("%.1f", distance));
            }
            st.aimTicks = 0;
            unsneak(bot, st);
            return true;
        }
        if (ammo > 0) st.emptyReloads = 0;
        if (ammo < 0 || distance > MAX_RANGE || !self.hasLineOfSight(target)) {
            st.aimTicks = 0;
            unsneak(bot, st);
            return false;
        }

        // aim: chest height, lead a moving target a little, plus this shot's aim error (drawn once per shot)
        if (st.aimTicks == 0) st.aimError = randomError(bot.getAimErrorDegrees());
        Location eye = self.getEyeLocation();
        Vector chest = target.getLocation().toVector().add(new Vector(0, target.getHeight() * 0.62, 0));
        Vector lead = target.getVelocity().clone().setY(0).multiply(Math.min(8.0, distance / 5.0));
        Vector aim = chest.add(lead).subtract(eye.toVector());
        aim.add(st.aimError.clone().multiply(aim.length()));
        bot.faceLocked(bot.getLocation().add(aim), 3);

        Object n = call(gunNeeds, gun);
        int needs = n instanceof Integer i ? i : 0;
        if ((needs & needsSneak) != 0 && !st.sneaking) {
            bot.sneak();
            st.sneaking = true;
        }
        if ((needs & needsStationary) != 0) {
            Vector v = bot.getVelocity();
            bot.setVelocity(new Vector(0, v.getY(), 0));
        }

        if (++st.aimTicks < Math.max(1, bot.getAimTicks())) return true;
        if (Boolean.TRUE.equals(call(pullTrigger, self, aim))) {
            CombatDebugger.log(bot, "xc-fire", "dist=" + String.format("%.1f", distance) + " ammo=" + ammo);
        }
        st.aimTicks = 0;
        return true;
    }

    private static void unsneak(Bot bot, State st) {
        if (st.sneaking) {
            bot.stand();
            st.sneaking = false;
        }
    }

    /** A random offset perpendicular-ish to the aim, about tan(degrees) long. */
    private static Vector randomError(double degrees) {
        if (degrees <= 0) return new Vector();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double len = Math.tan(Math.toRadians(degrees)) * Math.sqrt(r.nextDouble());
        Vector v = new Vector(r.nextGaussian(), r.nextGaussian() * 0.6, r.nextGaussian());
        return v.lengthSquared() < 1.0e-9 ? new Vector() : v.normalize().multiply(len);
    }

    private static double maxHealth(Player p) {
        AttributeInstance a = p.getAttribute(Attribute.MAX_HEALTH);
        return a != null ? a.getValue() : 20.0;
    }
}
