
package com.jerinexmc.foprison;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.*;

public final class FOPrisonPlugin extends JavaPlugin implements Listener {

    private final Map<UUID, UUID> frozenToCaster = new HashMap<>();
    private final Map<UUID, Location> frozenLocations = new HashMap<>();
    private final Map<UUID, Float[]> frozenRotation = new HashMap<>();

    private final Map<String, String> originalBlocks = new LinkedHashMap<>();
    private final Set<String> generatedBlocks = new LinkedHashSet<>();

    // Blocks protected only while their prison is being built.
    private final Map<String, Integer> protectionCounts = new HashMap<>();

    private boolean building;
    private UUID activeCaster;

    private static final int RANGE = 20;
    private static final double LIFT_HEIGHT = 15.0;
    private static final long BUILD_INTERVAL = 1L;
    private static final int BLOCKS_PER_CYCLE = 3;

    @Override
    public void onEnable() {
        loadPrisonData();
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("FOPrison enabled.");
    }

    @Override
    public void onDisable() {
        savePrisonData();

        for (UUID id : new ArrayList<>(frozenToCaster.keySet())) {
            Entity entity = Bukkit.getEntity(id);
            if (entity instanceof LivingEntity living) {
                unfreeze(living);
            }
        }
    }

    // OP-ONLY CHAT ACTIVATION
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onChat(AsyncPlayerChatEvent event) {
        String message = event.getMessage().trim();

        if (!message.equalsIgnoreCase("FO")
                && !message.equalsIgnoreCase("FO STOP")) {
            return;
        }

        event.setCancelled(true);

        Player player = event.getPlayer();

        Bukkit.getScheduler().runTask(this, () -> {
            if (!player.isOnline()) return;

            if (!player.isOp()) {
                player.sendMessage(ChatColor.RED + "Only operators can use FO.");
                return;
            }

            if (message.equalsIgnoreCase("FO")) {
                activate(player);
            } else {
                releaseAll(player);
            }
        });
    }

    private void activate(Player caster) {
        if (building) {
            caster.sendMessage(ChatColor.RED + "An FO event is already building.");
            return;
        }

        List<LivingEntity> targets = new ArrayList<>();

        for (Entity entity : caster.getNearbyEntities(RANGE, RANGE, RANGE)) {
            if (entity instanceof LivingEntity living
                    && !entity.getUniqueId().equals(caster.getUniqueId())
                    && living.getLocation().distanceSquared(caster.getLocation())
                    <= RANGE * RANGE
                    && !(living instanceof ArmorStand)) {
                targets.add(living);
            }
        }

        if (targets.isEmpty()) {
            caster.sendMessage(ChatColor.YELLOW
                    + "No living entities were found within 20 blocks.");
            return;
        }

        building = true;
        activeCaster = caster.getUniqueId();

        caster.sendMessage(ChatColor.GOLD + "FO!");

        caster.getWorld().playSound(
                caster.getLocation(),
                Sound.ENTITY_BLAZE_SHOOT,
                2.0f,
                0.45f
        );

        caster.getWorld().playSound(
                caster.getLocation(),
                Sound.ENTITY_GENERIC_EXPLODE,
                1.4f,
                0.65f
        );

        fireShockwave(caster.getLocation());

        for (LivingEntity target : targets) {
            freeze(target, caster);
        }

        new BukkitRunnable() {
            int ticks = 0;

            @Override
            public void run() {
                if (!building || !caster.isOnline()) {
                    cancel();
                    return;
                }

                ticks++;

                for (UUID id : new ArrayList<>(frozenToCaster.keySet())) {
                    if (!caster.getUniqueId().equals(frozenToCaster.get(id))) {
                        continue;
                    }

                    Entity entity = Bukkit.getEntity(id);

                    if (!(entity instanceof LivingEntity living)
                            || !living.isValid()) {
                        continue;
                    }

                    Location origin = frozenLocations.get(id);
                    Float[] rotation = frozenRotation.get(id);

                    if (origin == null || rotation == null) continue;

                    double progress = Math.min(1.0, ticks / 100.0);

                    Location lift = origin.clone().add(
                            0,
                            LIFT_HEIGHT * progress,
                            0
                    );

                    lift.setYaw(rotation[0]);
                    lift.setPitch(rotation[1]);

                    living.teleport(lift);
                    living.setVelocity(new Vector(0, 0, 0));
                    living.setFallDistance(0);

                    if (ticks % 10 == 0) {
                        living.getWorld().spawnParticle(
                                Particle.SMOKE,
                                lift.clone().add(0, 0.8, 0),
                                8,
                                0.35,
                                0.5,
                                0.35,
                                0.01
                        );
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
        frozenRotation.put(id, new Float[]{
                start.getYaw(),
                start.getPitch()
        });

        target.setVelocity(new Vector(0, 0, 0));
        target.setFallDistance(0);

        if (target instanceof Mob mob) {
            mob.setAI(false);
        }

        target.addPotionEffect(new PotionEffect(
                PotionEffectType.DARKNESS,
                120,
                0,
                false,
                false,
                false
        ));

        if (target instanceof Player player) {
            player.sendTitle(
                    ChatColor.DARK_GRAY + "FO",
                    ChatColor.GRAY + "You are being lifted...",
                    0,
                    35,
                    5
            );
        }
    }

    // CIRCULAR FIRE SHOCKWAVE
    private void fireShockwave(Location center) {
        new BukkitRunnable() {
            int radius = 1;

            @Override
            public void run() {
                if (radius > RANGE) {
                    cancel();
                    return;
                }

                World world = center.getWorld();

                for (int degree = 0; degree < 360; degree += 5) {
                    double angle = Math.toRadians(degree);

                    double x = Math.cos(angle) * radius;
                    double z = Math.sin(angle) * radius;

                    Location point = center.clone().add(x, 0.15, z);

                    world.spawnParticle(
                            Particle.FLAME,
                            point,
                            2,
                            0.08,
                            0.04,
                            0.08,
                            0.005
                    );

                    world.spawnParticle(
                            Particle.SMOKE,
                            point,
                            1,
                            0.06,
                            0.03,
                            0.06,
                            0.005
                    );
                }

                world.playSound(
                        center,
                        Sound.ITEM_FIRECHARGE_USE,
                        0.8f,
                        0.7f + radius * 0.025f
                );

                radius++;
            }
        }.runTaskTimer(this, 0L, 2L);
    }

    // SYNCHRONIZED CONSTRUCTION FOR EVERY ENTITY
    private void beginPrisonConstruction(
            Player caster,
            List<LivingEntity> targets
    ) {
        List<PrisonBuild> prisons = new ArrayList<>();

        for (LivingEntity target : targets) {
            if (!target.isValid() || target.isDead()) continue;

            Location center = target.getLocation().getBlock().getLocation();
            center.add(0, -1, 0);

            prisons.add(new PrisonBuild(target, center));
        }

        if (prisons.isEmpty()) {
            finishBuilding();
            return;
        }

        new BukkitRunnable() {
            int sharedCubeIndex = 0;
            int sharedSphereIndex = 0;

            boolean buildingSpheres = false;

            @Override
            public void run() {
                if (!building || !caster.isOnline()) {
                    cancel();
                    return;
                }

                // PHASE 1:
                // Every entity gets the same obsidian blocks each cycle.
                if (!buildingSpheres) {
                    int totalCubeBlocks =
                            prisons.get(0).cubePositions.size();

                    for (int batch = 0;
                         batch < BLOCKS_PER_CYCLE
                                 && sharedCubeIndex < totalCubeBlocks;
                         batch++) {

                        for (PrisonBuild prison : prisons) {
                            BlockPos pos =
                                    prison.cubePositions.get(sharedCubeIndex);

                            setGenerated(
                                    prison,
                                    pos.x,
                                    pos.y,
                                    pos.z,
                                    prison.center.getWorld(),
                                    Material.OBSIDIAN
                            );
                        }

                        sharedCubeIndex++;
                    }

                    // All boxes finish before any sphere starts.
                    if (sharedCubeIndex >= totalCubeBlocks) {
                        buildingSpheres = true;
                    }

                    return;
                }

                // PHASE 2:
                // Every entity gets the same sphere blocks each cycle.
                int totalSphereBlocks =
                        prisons.get(0).spherePositions.size();

                for (int batch = 0;
                     batch < BLOCKS_PER_CYCLE
                             && sharedSphereIndex < totalSphereBlocks;
                     batch++) {

                    for (PrisonBuild prison : prisons) {
                        BlockPos pos =
                                prison.spherePositions.get(sharedSphereIndex);

                        if (pos.shell) {
                            setGenerated(
                                    prison,
                                    pos.x,
                                    pos.y,
                                    pos.z,
                                    prison.center.getWorld(),
                                    chooseEarthMaterial()
                            );
                        }
                    }

                    sharedSphereIndex++;
                }

                // Complete every prison on the same final cycle.
                if (sharedSphereIndex >= totalSphereBlocks) {
                    for (PrisonBuild prison : prisons) {
                        completePrison(prison);
                    }

                    finishBuilding();
                    cancel();
                }
            }
        }.runTaskTimer(this, 0L, BUILD_INTERVAL);
    }

    // RELEASE PROTECTION WHEN THIS ENTITY'S SPHERE IS FINISHED
    private void completePrison(PrisonBuild prison) {
        if (prison.finished) return;

        prison.finished = true;

        for (String key : prison.blocks) {
            Integer count = protectionCounts.get(key);

            if (count == null || count <= 1) {
                protectionCounts.remove(key);
            } else {
                protectionCounts.put(key, count - 1);
            }
        }

        LivingEntity target = prison.target;

        if (target.isValid() && !target.isDead()) {
            double maxHealth = target.getAttribute(
                    org.bukkit.attribute.Attribute.MAX_HEALTH
            ) != null
                    ? target.getAttribute(
                            org.bukkit.attribute.Attribute.MAX_HEALTH
                    ).getValue()
                    : 20.0;

            target.setHealth(Math.max(
                    1.0,
                    Math.min(maxHealth, target.getHealth() * 0.5)
            ));

            unfreeze(target);

            if (target instanceof Player player) {
                player.sendTitle(
                        ChatColor.DARK_GRAY + "SEALED",
                        ChatColor.GRAY + "Break your way out!",
                        5,
                        35,
                        10
                );
            }
        }
    }

    private List<BlockPos> createCube(Location center) {
        List<BlockPos> result = new ArrayList<>();

        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) {
                    if (x == -1 || x == 1
                            || y == -1 || y == 1
                            || z == -1 || z == 1) {

                        result.add(new BlockPos(
                                center.getBlockX() + x,
                                center.getBlockY() + y,
                                center.getBlockZ() + z,
                                true
                        ));
                    }
                }
            }
        }

        return result;
    }

    private List<BlockPos> createSphere(Location center, int radius) {
        List<BlockPos> result = new ArrayList<>();

        for (int y = -radius; y <= radius; y++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    double distance = x * x + y * y + z * z;

                    if (distance <= radius * radius + 0.5) {
                        boolean shell = distance >=
                                (radius - 1.15) * (radius - 1.15);

                        result.add(new BlockPos(
                                center.getBlockX() + x,
                                center.getBlockY() + y,
                                center.getBlockZ() + z,
                                shell
                        ));
                    }
                }
            }
        }

        result.sort(Comparator.comparingInt(p -> p.y));
        return result;
    }

    // NO ORES
    private Material chooseEarthMaterial() {
        Material[] materials = {
                Material.DIRT,
                Material.ROOTED_DIRT,
                Material.COARSE_DIRT,
                Material.PODZOL,
                Material.MUD,
                Material.STONE,
                Material.GRANITE,
                Material.ANDESITE,
                Material.DIORITE,
                Material.TUFF,
                Material.COBBLESTONE,
                Material.MOSS_BLOCK,
                Material.DEEPSLATE,
                Material.COBBLED_DEEPSLATE
        };

        return materials[new Random().nextInt(materials.length)];
    }

    private void setGenerated(
            PrisonBuild prison,
            int x,
            int y,
            int z,
            World world,
            Material material
    ) {
        Block block = world.getBlockAt(x, y, z);
        String key = key(world, x, y, z);

        originalBlocks.putIfAbsent(
                key,
                block.getBlockData().getAsString()
        );

        generatedBlocks.add(key);

        if (prison.blocks.add(key)) {
            protectionCounts.merge(key, 1, Integer::sum);
        }

        block.setType(material, false);

        savePrisonData();
    }

    private void unfreeze(LivingEntity entity) {
        UUID id = entity.getUniqueId();

        frozenToCaster.remove(id);
        frozenLocations.remove(id);
        frozenRotation.remove(id);

        if (entity instanceof Mob mob) {
            mob.setAI(true);
        }

        if (entity instanceof Player player) {
            player.removePotionEffect(PotionEffectType.DARKNESS);
        }
    }

    private void finishBuilding() {
        for (UUID id : new ArrayList<>(frozenToCaster.keySet())) {
            Entity entity = Bukkit.getEntity(id);

            if (entity instanceof LivingEntity living) {
                unfreeze(living);
            }
        }

        building = false;
        activeCaster = null;

        savePrisonData();
    }

    // FO STOP
    private void releaseAll(Player operator) {
        if (generatedBlocks.isEmpty() && !building) {
            operator.sendMessage(
                    ChatColor.YELLOW
                            + "There are no active FO prisons to release."
            );
            return;
        }

        Bukkit.getScheduler().cancelTasks(this);

        for (UUID id : new ArrayList<>(frozenToCaster.keySet())) {
            Entity entity = Bukkit.getEntity(id);

            if (entity instanceof LivingEntity living) {
                unfreeze(living);
            }
        }

        List<String> keys = new ArrayList<>(generatedBlocks);
        Collections.reverse(keys);

        for (String key : keys) {
            restoreBlock(key);
        }

        generatedBlocks.clear();
        originalBlocks.clear();
        protectionCounts.clear();

        building = false;
        activeCaster = null;

        savePrisonData();

        operator.sendMessage(
                ChatColor.GREEN
                        + "FO STOP: all prisons released and terrain restored."
        );

        operator.getWorld().playSound(
                operator.getLocation(),
                Sound.BLOCK_BEACON_DEACTIVATE,
                1.5f,
                0.7f
        );
    }

    private void restoreBlock(String key) {
        String[] parts = key.split("\\|", 4);

        if (parts.length != 4) return;

        World world = Bukkit.getWorld(parts[0]);
        if (world == null) return;

        try {
            int x = Integer.parseInt(parts[1]);
            int y = Integer.parseInt(parts[2]);
            int z = Integer.parseInt(parts[3]);

            String data = originalBlocks.get(key);

            if (data != null) {
                world.getBlockAt(x, y, z).setBlockData(
                        Bukkit.createBlockData(data),
                        false
                );
            }
        } catch (Exception ex) {
            getLogger().warning(
                    "Could not restore block " + key + ": " + ex.getMessage()
            );
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
            for (String key :
                    getConfig().getConfigurationSection("originals").getKeys(false)) {

                originalBlocks.put(
                        key,
                        getConfig().getString("originals." + key)
                );
            }
        }

        getLogger().info(
                "Loaded " + generatedBlocks.size()
                        + " persistent FO prison blocks."
        );
    }

    private boolean isFrozen(Player player) {
        return frozenToCaster.containsKey(player.getUniqueId());
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event instanceof PlayerTeleportEvent) return;

        Player player = event.getPlayer();

        if (!isFrozen(player)) return;

        Location from = event.getFrom();
        Location to = event.getTo();

        if (to != null && (
                from.getX() != to.getX()
                        || from.getY() != to.getY()
                        || from.getZ() != to.getZ()
                        || from.getYaw() != to.getYaw()
                        || from.getPitch() != to.getPitch()
        )) {
            Location locked = from.clone();

            Float[] rotation = frozenRotation.get(player.getUniqueId());

            if (rotation != null) {
                locked.setYaw(rotation[0]);
                locked.setPitch(rotation[1]);
            }

            event.setTo(locked);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        String blockKey = key(
                event.getBlock().getWorld(),
                event.getBlock().getX(),
                event.getBlock().getY(),
                event.getBlock().getZ()
        );

        if (isFrozen(event.getPlayer())
                || protectionCounts.containsKey(blockKey)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (isFrozen(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (isFrozen(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (frozenToCaster.containsKey(event.getEntity().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player
                && isFrozen(player)) {
            event.setCancelled(true);
        }

        if (frozenToCaster.containsKey(event.getDamager().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    private class PrisonBuild {
        final LivingEntity target;
        final Location center;
        final List<BlockPos> cubePositions;
        final List<BlockPos> spherePositions;
        final Set<String> blocks = new LinkedHashSet<>();

        boolean finished = false;

        PrisonBuild(LivingEntity target, Location center) {
            this.target = target;
            this.center = center;

            this.cubePositions = createCube(center);
            this.spherePositions = createSphere(center, 3);
        }
    }

    private record BlockPos(int x, int y, int z, boolean shell) {}
}
