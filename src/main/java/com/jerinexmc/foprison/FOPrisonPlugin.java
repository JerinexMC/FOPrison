package com.jerinexmc.foprison;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.*;

public final class FOPrisonPlugin extends JavaPlugin implements Listener {
    private final Map<UUID, Capture> captures = new HashMap<>();
    private final Map<UUID, UUID> frozenToCaster = new HashMap<>();
    private final Map<UUID, Location> frozenLocations = new HashMap<>();
    private final Map<UUID, Float[]> frozenRotation = new HashMap<>();
    private final Map<String, String> originalBlocks = new LinkedHashMap<>();
    private final Set<String> generatedBlocks = new LinkedHashSet<>();
    private boolean building;
    private UUID activeCaster;
    private int taskId = -1;

    private static final int RANGE = 20;
    private static final double LIFT_HEIGHT = 15.0;
    private static final long BUILD_INTERVAL = 2L;

    @Override
    public void onEnable() {
        // config.yml is created automatically when the first prison is saved.
        loadPrisonData();
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("FOPrison enabled. Say FO as an operator to activate it.");
    }

    @Override
    public void onDisable() {
        savePrisonData();
        if (taskId != -1) Bukkit.getScheduler().cancelTask(taskId);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onChat(AsyncPlayerChatEvent event) {
        String message = event.getMessage().trim();
        if (!message.equalsIgnoreCase("FO") && !message.equalsIgnoreCase("FO STOP")) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(this, () -> {
            if (!player.isOnline()) return;
            if (!player.isOp()) {
                player.sendMessage(ChatColor.RED + "Only operators can use FO.");
                return;
            }
            if (message.equalsIgnoreCase("FO")) activate(player);
            else releaseAll(player);
        });
    }

    private void activate(Player caster) {
        if (building) {
            caster.sendMessage(ChatColor.RED + "An FO event is already building.");
            return;
        }
        List<LivingEntity> targets = new ArrayList<>();
        for (Entity entity : caster.getNearbyEntities(RANGE, RANGE, RANGE)) {
            if (entity instanceof LivingEntity living && !entity.getUniqueId().equals(caster.getUniqueId())
                    && living.getLocation().distanceSquared(caster.getLocation()) <= RANGE * RANGE
                    && !(living instanceof ArmorStand)) {
                targets.add(living);
            }
        }
        if (targets.isEmpty()) {
            caster.sendMessage(ChatColor.YELLOW + "No living entities were found within 20 blocks.");
            return;
        }

        building = true;
        activeCaster = caster.getUniqueId();
        caster.sendMessage(ChatColor.GOLD + "FO!");
        caster.getWorld().playSound(caster.getLocation(), Sound.ENTITY_BLAZE_SHOOT, 2.0f, 0.45f);
        caster.getWorld().playSound(caster.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 1.4f, 0.65f);
        fireShockwave(caster.getLocation());

        for (LivingEntity target : targets) freeze(target, caster);
        new BukkitRunnable() {
            int ticks = 0;
            @Override public void run() {
                if (!building || !caster.isOnline()) { cancel(); return; }
                ticks++;
                for (UUID id : new ArrayList<>(frozenToCaster.keySet())) {
                    if (!frozenToCaster.get(id).equals(caster.getUniqueId())) continue;
                    Entity entity = Bukkit.getEntity(id);
                    if (!(entity instanceof LivingEntity living) || !living.isValid()) continue;
                    Location origin = frozenLocations.get(id);
                    if (origin == null) continue;
                    double progress = Math.min(1.0, ticks / 100.0);
                    Location lift = origin.clone().add(0, LIFT_HEIGHT * progress, 0);
                    lift.setYaw(frozenRotation.get(id)[0]);
                    lift.setPitch(frozenRotation.get(id)[1]);
                    living.teleport(lift);
                    living.setVelocity(new Vector(0, 0, 0));
                    living.setFallDistance(0);
                    if (ticks % 10 == 0) {
                        living.getWorld().spawnParticle(Particle.SMOKE, lift.clone().add(0, 0.8, 0),
                                8, 0.35, 0.5, 0.35, 0.01);
                    }
                }
                if (ticks >= 100) {
                    cancel();
                    beginPrisonConstruction(caster, targets);
                }
            }
        }.runTaskTimer(this, 0L, 1L);
    }

    private void freeze(LivingEntity target, Player caster) {
        UUID id = target.getUniqueId();
        frozenToCaster.put(id, caster.getUniqueId());
        Location start = target.getLocation().clone();
        frozenLocations.put(id, start);
        frozenRotation.put(id, new Float[]{start.getYaw(), start.getPitch()});
        target.setVelocity(new Vector(0, 0, 0));
        target.setFallDistance(0);
        if (target instanceof Mob mob) mob.setAI(false);
        target.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 120, 0, false, false, false));
        if (target instanceof Player player) player.sendTitle(ChatColor.DARK_GRAY + "FO", ChatColor.GRAY + "You are being lifted...", 0, 35, 5);
    }

    private void fireShockwave(Location center) {
        new BukkitRunnable() {
            int radius = 1;
            @Override public void run() {
                if (radius > RANGE) { cancel(); return; }
                World world = center.getWorld();
                for (int degree = 0; degree < 360; degree += 5) {
                    double angle = Math.toRadians(degree);
                    double x = Math.cos(angle) * radius;
                    double z = Math.sin(angle) * radius;
                    Location point = center.clone().add(x, 0.15, z);
                    world.spawnParticle(Particle.FLAME, point, 2, 0.08, 0.04, 0.08, 0.005);
                    world.spawnParticle(Particle.SMOKE, point, 1, 0.06, 0.03, 0.06, 0.005);
                }
                world.playSound(center, Sound.ITEM_FIRECHARGE_USE, 0.8f, 0.7f + radius * 0.025f);
                radius++;
            }
        }.runTaskTimer(this, 0L, 2L);
    }

    private void beginPrisonConstruction(Player caster, List<LivingEntity> targets) {
        List<LivingEntity> valid = new ArrayList<>();
        for (LivingEntity target : targets) if (target.isValid() && !target.isDead()) valid.add(target);
        if (valid.isEmpty()) { finishBuilding(); return; }

        Queue<LivingEntity> queue = new ArrayDeque<>(valid);
        new BukkitRunnable() {
            LivingEntity current;
            Location center;
            List<BlockPos> layers;
            int index;
            boolean obsidianDone;
            @Override public void run() {
                if (current == null) {
                    current = queue.poll();
                    if (current == null) {
                        finishBuilding();
                        cancel();
                        return;
                    }
                    center = current.getLocation().getBlock().getLocation();
                    center.add(0, -1, 0);
                    layers = createSpherePositions(center, 3);
                    index = 0;
                    obsidianDone = false;
                }

                if (!obsidianDone) {
                    // Build a sealed 3x3x3 obsidian cube, one block at a time.
                    int[][] cube = cubePositions();
                    if (index < cube.length) {
                        int[] p = cube[index++];
                        setGenerated(center.getBlockX() + p[0], center.getBlockY() + p[1],
                                center.getBlockZ() + p[2], center.getWorld(), Material.OBSIDIAN);
                        return;
                    }
                    obsidianDone = true;
                    index = 0;
                    return;
                }

                if (index < layers.size()) {
                    BlockPos pos = layers.get(index++);
                    if (pos.shell) {
                        setGenerated(pos.x, pos.y, pos.z, center.getWorld(), chooseEarthMaterial());
                    }
                    if (index % 8 == 0) center.getWorld().playSound(center, Sound.BLOCK_STONE_PLACE, 0.7f, 0.65f);
                    return;
                }

                // Halve current health after the full shell has formed.
                double max = current.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH) != null
                        ? current.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue() : 20.0;
                current.setHealth(Math.max(1.0, Math.min(max, current.getHealth() * 0.5)));
                unfreeze(current);
                if (current instanceof Player player) {
                    player.sendTitle(ChatColor.DARK_GRAY + "SEALED", ChatColor.GRAY + "The prison is complete.", 5, 35, 10);
                }
                current = null;
            }
        }.runTaskTimer(this, 0L, BUILD_INTERVAL);
    }

    private int[][] cubePositions() {
        List<int[]> result = new ArrayList<>();
        for (int x = -1; x <= 1; x++)
            for (int y = -1; y <= 1; y++)
                for (int z = -1; z <= 1; z++)
                    if (x == -1 || x == 1 || y == -1 || y == 1 || z == -1 || z == 1)
                        result.add(new int[]{x, y, z});
        return result.toArray(new int[0][]);
    }

    private List<BlockPos> createSpherePositions(Location c, int radius) {
        List<BlockPos> result = new ArrayList<>();
        for (int y = -radius; y <= radius; y++)
            for (int x = -radius; x <= radius; x++)
                for (int z = -radius; z <= radius; z++) {
                    double d = x*x + y*y + z*z;
                    if (d <= radius * radius + 0.5) {
                        boolean shell = d >= (radius - 1.15) * (radius - 1.15);
                        result.add(new BlockPos(c.getBlockX()+x, c.getBlockY()+y,
                                c.getBlockZ()+z, shell));
                    }
                }
        result.sort(Comparator.comparingInt(p -> p.y));
        return result;
    }

    private Material chooseEarthMaterial() {
        Material[] materials = {
                Material.DIRT, Material.STONE, Material.GRANITE, Material.ANDESITE,
                Material.DIORITE, Material.COAL_ORE, Material.IRON_ORE, Material.COPPER_ORE,
                Material.GOLD_ORE, Material.REDSTONE_ORE, Material.LAPIS_ORE, Material.DEEPSLATE
        };
        return materials[new Random().nextInt(materials.length)];
    }

    private void setGenerated(int x, int y, int z, World world, Material material) {
        Block block = world.getBlockAt(x, y, z);
        String key = key(world, x, y, z);
        originalBlocks.putIfAbsent(key, block.getBlockData().getAsString());
        generatedBlocks.add(key);
        block.setType(material, false);
        savePrisonData();
    }

    private void unfreeze(LivingEntity entity) {
        UUID id = entity.getUniqueId();
        frozenToCaster.remove(id);
        frozenLocations.remove(id);
        frozenRotation.remove(id);
        if (entity instanceof Mob mob) mob.setAI(true);
        if (entity instanceof Player player) player.removePotionEffect(PotionEffectType.DARKNESS);
    }

    private void finishBuilding() {
        for (UUID id : new ArrayList<>(frozenToCaster.keySet())) {
            Entity entity = Bukkit.getEntity(id);
            if (entity instanceof LivingEntity living) unfreeze(living);
        }
        building = false;
        activeCaster = null;
        savePrisonData();
    }

    private void releaseAll(Player operator) {
        if (generatedBlocks.isEmpty() && !building) {
            operator.sendMessage(ChatColor.YELLOW + "There are no active FO prisons to release.");
            return;
        }
        // Stop any in-progress lift, shockwave, or construction tasks first.
        Bukkit.getScheduler().cancelTasks(this);
        // Release entities at their current positions, then restore the terrain.
        for (UUID id : new ArrayList<>(frozenToCaster.keySet())) {
            Entity entity = Bukkit.getEntity(id);
            if (entity instanceof LivingEntity living) unfreeze(living);
        }
        if (taskId != -1) Bukkit.getScheduler().cancelTask(taskId);
        List<String> keys = new ArrayList<>(generatedBlocks);
        Collections.reverse(keys);
        for (String key : keys) restoreBlock(key);
        generatedBlocks.clear();
        originalBlocks.clear();
        building = false;
        activeCaster = null;
        savePrisonData();
        operator.sendMessage(ChatColor.GREEN + "FO STOP: all prisons released and terrain restored.");
        operator.getWorld().playSound(operator.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE, 1.5f, 0.7f);
    }

    private void restoreBlock(String key) {
        String[] parts = key.split("\\|", 4);
        if (parts.length != 4) return;
        World world = Bukkit.getWorld(parts[0]);
        if (world == null) return;
        try {
            int x = Integer.parseInt(parts[1]), y = Integer.parseInt(parts[2]), z = Integer.parseInt(parts[3]);
            String data = originalBlocks.get(key);
            if (data != null) world.getBlockAt(x, y, z).setBlockData(Bukkit.createBlockData(data), false);
        } catch (Exception ex) {
            getLogger().warning("Could not restore block " + key + ": " + ex.getMessage());
        }
    }

    private String key(World world, int x, int y, int z) {
        return world.getName() + "|" + x + "|" + y + "|" + z;
    }

    private void savePrisonData() {
        getConfig().set("generated", new ArrayList<>(generatedBlocks));
        getConfig().set("originals", originalBlocks);
        saveConfig();
    }

    private void loadPrisonData() {
        generatedBlocks.clear();
        generatedBlocks.addAll(getConfig().getStringList("generated"));
        originalBlocks.clear();
        if (getConfig().isConfigurationSection("originals")) {
            for (String key : getConfig().getConfigurationSection("originals").getKeys(false))
                originalBlocks.put(key, getConfig().getString("originals." + key));
        }
        getLogger().info("Loaded " + generatedBlocks.size() + " persistent FO prison blocks.");
    }

    private boolean isFrozen(Player player) {
        return frozenToCaster.containsKey(player.getUniqueId());
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        // The plugin's own teleports are what create the slow upward lift.
        if (event instanceof PlayerTeleportEvent) return;
        if (!isFrozen(event.getPlayer())) return;
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to != null && (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ())) {
            Location locked = from.clone();
            locked.setYaw(frozenRotation.get(event.getPlayer().getUniqueId())[0]);
            locked.setPitch(frozenRotation.get(event.getPlayer().getUniqueId())[1]);
            event.setTo(locked);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (isFrozen(event.getPlayer()) || generatedBlocks.contains(key(event.getBlock().getWorld(),
                event.getBlock().getX(), event.getBlock().getY(), event.getBlock().getZ()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (isFrozen(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (isFrozen(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && isFrozen(player)) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player && isFrozen(player)) event.setCancelled(true);
    }

    private record BlockPos(int x, int y, int z, boolean shell) {}
    private record Capture(UUID entity, Location start) {}
}
