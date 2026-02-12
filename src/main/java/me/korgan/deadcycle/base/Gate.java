package me.korgan.deadcycle.base;
import java.util.ArrayList;
import java.util.List;

public class Gate {

    private final String worldName;
    private final List<int[]> blocks = new ArrayList<>(); // each int[] = {x,y,z}
    private boolean open = false;

    public Gate(String worldName) {
        this.worldName = worldName;
    }

    public String getWorldName() {
        return worldName;
    }

    public void addBlock(int x, int y, int z) {
        blocks.add(new int[]{x, y, z});
    }

    public List<int[]> getBlocks() {
        return blocks;
    }

    public boolean isOpen() {
        return open;
    }

    public void setOpen(boolean open) {
        this.open = open;
    }

    public boolean contains(org.bukkit.Location loc) {
        if (loc == null || loc.getWorld() == null)
            return false;
        if (!loc.getWorld().getName().equals(worldName))
            return false;
        int bx = loc.getBlockX();
        int by = loc.getBlockY();
        int bz = loc.getBlockZ();
        for (int[] a : blocks) {
            if (a[0] == bx && a[1] == by && a[2] == bz)
                return true;
        }
        return false;
    }
}
