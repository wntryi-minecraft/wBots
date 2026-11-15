package net.nuggetmc.tplus.api;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

/**
 * NodesKrieg: every block a bot places, breaks or changes goes through here, so a server can forbid it. A duel arena is
 * not a building site: with {@link #setAllowed(boolean) setAllowed(false)} bots never bridge, pillar, mine, clutch or
 * open doors, and every such call is a no-op (the bot simply does not get the block).
 */
public final class WorldEdits {
    private static volatile boolean allowed = true;

    private WorldEdits() {
    }

    public static boolean allowed() {
        return allowed;
    }

    public static void setAllowed(boolean value) {
        allowed = value;
    }

    public static void setType(Block block, Material type) {
        if (allowed) block.setType(type);
    }

    public static void setType(Block block, Material type, boolean physics) {
        if (allowed) block.setType(type, physics);
    }

    public static void setBlockData(Block block, BlockData data) {
        if (allowed) block.setBlockData(data);
    }

    public static void setBlockData(Block block, BlockData data, boolean physics) {
        if (allowed) block.setBlockData(data, physics);
    }

    public static boolean breakNaturally(Block block) {
        return allowed && block.breakNaturally();
    }
}
