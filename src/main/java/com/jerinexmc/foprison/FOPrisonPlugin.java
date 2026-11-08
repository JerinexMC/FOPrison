
package com.jerinexmc.foprison;

import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
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
    private final Map<String, Integer> protectionCounts = new HashMap<>();

    private final List<MovingBlock> movingBlocks = new ArrayList<>();

    private boolean building;
    private UUID activeCaster;

    private static final int RANGE = 20;
    private static final double LIFT_HEIGHT = 15.0;

    private static final long BUILD_INTERVAL = 1L;
    private static final int BLOCKS_PER_CYCLE = 3;

    private static final int SOURCE_RADIUS = 3;
    private static final int SOURCE_DEPTH = 12;
    private static final int BLOCK_TRAVEL_TICKS = 8;

    @Override
    public void onEnable() {
        loadPrisonData();
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("FOPrison enabled.");
    }

    @Override
    public void onDisable() {
        removeMovingDisplays();
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

    // BUILD ALL BOXES, THEN ALL EARTHEN SPHERES
    private void beginPrisonConstruction(
            Player caster,
            List<LivingEntity> targets
    ) {
        List<PrisonBuild> prisons = new ArrayList<>();
        Set<String> reservedSources = new HashSet<>();

        for (LivingEntity target : targets) {
            if (!target.isValid() || target.isDead()) continue;

            Location originalLocation = frozenLocations.get(
                    target.getUniqueId()
            );

            if (originalLocation == null) continue;

            Location center = target.getLocation().getBlock().getLocation();
            center.add(0, -1, 0);

            PrisonBuild prison = new PrisonBuild(
                    target,
                    center,
                    originalLocation.clone()
            );

            prepareSourceBlocks(prison, reservedSources);
            prisons.add(prison);
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

                updateMovingBlocks();

                // PHASE 1: OBSIDIAN BOXES
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

                    if (sharedCubeIndex >= totalCubeBlocks) {
                        buildingSpheres = true;
                        caster.sendMessage(
                                ChatColor.GRAY
                                        + "The ground begins rising..."
                        );
                    }

                    savePrisonData();
                    return;
                }

                // PHASE 2: PULL REAL BLOCKS FROM UNDER EACH TARGET
                int totalSphereBlocks = 0;

                for (PrisonBuild prison : prisons) {
                    totalSphereBlocks = Math.max(
                            totalSphereBlocks,
                            prison.sourceBlocks.size()
                    );
                }

                for (int batch = 0;
                     batch < BLOCKS_PER_CYCLE
                             && sharedSphereIndex < totalSphereBlocks;
                     batch++) {

                    for (PrisonBuild prison : prisons) {
                        if (sharedSphereIndex >= prison.sourceBlocks.size()) {
                            continue;
                        }

                        SourceBlock source =
                                prison.sourceBlocks.get(sharedSphereIndex);

                        BlockPos destination =
                                prison.spherePositions.get(sharedSphereIndex);

                        startMovingBlock(prison, source, destination);
                    }

                    sharedSphereIndex++;
                }

                if (sharedSphereIndex >= totalSphereBlocks
                        && movingBlocks.isEmpty()) {

                    for (PrisonBuild prison : prisons) {
                        completePrison(prison);
                    }

                    savePrisonData();
                    finishBuilding();
                    cancel();
                    return;
                }

                savePrisonData();
            }
        }.runTaskTimer(this, 0L, BUILD_INTERVAL);
    }

    // FIND REAL BLOCKS DIRECTLY UNDER THE TARGET
    private void prepareSourceBlocks(
            PrisonBuild prison,
            Set<String> reservedSources
    ) {
        Location origin = prison.originalLocation;
        World world = origin.getWorld();

        List<SourceBlock> candidates = new ArrayList<>();

        int baseX = origin.getBlockX();
        int baseY = origin.getBlockY();
        int baseZ = origin.getBlockZ();

        for (int depth = 1; depth <= SOURCE_DEPTH; depth++) {
            for (int x = -SOURCE_RADIUS; x <= SOURCE_RADIUS; x++) {
                for (int z = -SOURCE_RADIUS; z <= SOURCE_RADIUS; z++) {

                    Block block = world.getBlockAt(
                            baseX + x,
                            baseY - depth,
                            baseZ + z
                    );

                    String sourceKey = key(
                            world,
                            block.getX(),
                            block.getY(),
                            block.getZ()
                    );

                    if (reservedSources.contains(sourceKey)
                            || generatedBlocks.contains(sourceKey)) {
                        continue;
                    }

                    if (!isUsableSource(block)) continue;

                    candidates.add(new SourceBlock(
                            block.getX(),
                            block.getY(),
                            block.getZ(),
                            block.getBlockData().clone()
                    ));
                }
            }
        }

        // Take the closest surface blocks first, then dig deeper.
        candidates.sort(
                Comparator.comparingInt(
                        (SourceBlock source) -> baseY - source.y
                ).thenComparingDouble(source ->
                        Math.pow(source.x - baseX, 2)
                                + Math.pow(source.z - baseZ, 2)
                )
        );

        int needed = prison.spherePositions.size();
        int amount = Math.min(needed, candidates.size());

        for (int i = 0; i < amount; i++) {
            SourceBlock source = candidates.get(i);

            String sourceKey = key(
                    world,
                    source.x,
                    source.y,
                    source.z
            );

            reservedSources.add(sourceKey);
            prison.sourceBlocks.add(source);
        }

        if (amount < needed) {
            getLogger().warning(
                    "Only found " + amount
                            + " usable ground blocks for a prison."
            );
        }
    }

    private boolean isUsableSource(Block block) {
        Material type = block.getType();

        if (type.isAir() || !type.isSolid() || type.isLiquid()) {
            return false;
        }

        if (type == Material.BEDROCK
                || type == Material.BARRIER
                || type == Material.END_PORTAL_FRAME
                || type == Material.END_PORTAL
                || type == Material.NETHER_PORTAL
                || type == Material.STRUCTURE_VOID) {
            return false;
        }

        BlockState state = block.getState();

        return !(state instanceof TileState);
    }

    // REMOVE THE SOURCE BLOCK AND ANIMATE IT TOWARD ITS DESTINATION
    private void startMovingBlock(
            PrisonBuild prison,
            SourceBlock source,
            BlockPos destination
    ) {
        World world = prison.center.getWorld();

        Block sourceBlock = world.getBlockAt(
                source.x,
                source.y,
                source.z
        );

        String sourceKey = key(
                world,
                source.x,
                source.y,
                source.z
        );

        String destinationKey = key(
                world,
                destination.x,
                destination.y,
                destination.z
        );

        // Save the original ground block before removing it.
        originalBlocks.putIfAbsent(
                sourceKey,
                sourceBlock.getBlockData().getAsString()
        );

        generatedBlocks.add(sourceKey);

        // Save the original destination before the display arrives.
        Block destinationBlock = world.getBlockAt(
                destination.x,
                destination.y,
                destination.z
        );

        originalBlocks.putIfAbsent(
                destinationKey,
                destinationBlock.getBlockData().getAsString()
        );

        generatedBlocks.add(destinationKey);

        if (prison.blocks.add(destinationKey)) {
            protectionCounts.merge(destinationKey, 1, Integer::sum);
        }

        sourceBlock.setType(Material.AIR, false);

        Location start = new Location(
                world,
                source.x,
                source.y,
                source.z
        );

        Location end = new Location(
                world,
                destination.x,
                destination.y,
                destination.z
        );

        BlockDisplay display = world.spawn(
                start,
                BlockDisplay.class
        );

        display.setBlock(source.data);
        display.setGravity(false);
        display.setInvulnerable(true);
        display.setPersistent(false);
        display.setInterpolationDuration(2);
        display.setTeleportDuration(2);

        movingBlocks.add(new MovingBlock(
                display,
                start,
                end,
                source.data,
                BLOCK_TRAVEL_TICKS
        ));

        world.playSound(
                start,
                Sound.BLOCK_ROOTED_DIRT_BREAK,
                0.35f,
                0.8f
        );
    }

    // MOVE ALL BLOCK DISPLAYS EACH TICK
    private void updateMovingBlocks() {
        Iterator<MovingBlock> iterator = movingBlocks.iterator();

        while (iterator.hasNext()) {
            MovingBlock moving = iterator.next();

            if (!moving.display.isValid()) {
                iterator.remove();
                continue;
            }

            moving.elapsed++;

            double progress = Math.min(
                    1.0,
                    (double) moving.elapsed / moving.duration
            );

            // Smooth upward arc as the block travels.
            double x = lerp(
                    moving.start.getX(),
                    moving.end.getX(),
                    progress
            );

            double y = lerp(
                    moving.start.getY(),
                    moving.end.getY(),
                    progress
            );

            double z = lerp(
                    moving.start.getZ(),
                    moving.end.getZ(),
                    progress
            );

            double arc = Math.sin(progress * Math.PI) * 2.0;
            y += arc;

            Location current = new Location(
                    moving.start.getWorld(),
                    x,
                    y,
                    z
            );

            moving.display.teleport(current);

            if (moving.elapsed >= moving.duration) {
                Block finalBlock = moving.end.getBlock();

                finalBlock.setBlockData(
                        moving.data,
                        false
                );

                moving.display.remove();
                iterator.remove();

                moving.end.getWorld().spawnParticle(
                        Particle.BLOCK,
                        moving.end.clone().add(0.5, 0.5, 0.5),
                        5,
                        0.2,
                        0.2,
                        0.2,
                        moving.data
                );
            }
        }
    }

    private double lerp(double start, double end, double progress) {
        return start + (end - start) * progress;
    }

    private void removeMovingDisplays() {
        for (MovingBlock moving : new ArrayList<>(movingBlocks)) {
            if (moving.display.isValid()) {
                moving.display.remove();
            }
        }

        movingBlocks.clear();
    }

    // RELEASE PROTECTION WHEN THE PRISON IS FINISHED
    private void completePrison(PrisonBuild prison) {
        if (prison.finished) return;

        prison.finished = true;

        for (String blockKey : prison.blocks) {
            Integer count = protectionCounts.get(blockKey);

            if (count == null || count <= 1) {
                protectionCounts.remove(blockKey);
            } else {
                protectionCounts.put(blockKey, count - 1);
            }
        }

        LivingEntity target = prison.target;

        if (target.isValid() && !target.isDead()) {
            Attribute maxHealthAttribute = Attribute.MAX_HEALTH;

            double maxHealth = target.getAttribute(maxHealthAttribute) != null
                    ? target.getAttribute(maxHealthAttribute).getValue()
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
                                center.getBlockZ() + z
                        ));
                    }
                }
            }
        }

        return result;
    }

    private List<BlockPos> createSphereShell(Location center, int radius) {
        List<BlockPos> result = new ArrayList<>();

        for (int y = -radius; y <= radius; y++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    double distance = x * x + y * y + z * z;

                    if (distance <= radius * radius + 0.5
                            && distance >= (radius - 1.15)
                            * (radius - 1.15)) {

                        result.add(new BlockPos(
                                center.getBlockX() + x,
                                center.getBlockY() + y,
                                center.getBlockZ() + z
                        ));
                    }
                }
            }
        }

        result.sort(Comparator.comparingInt(pos -> pos.y));
        return result;
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
        String blockKey = key(world, x, y, z);

        originalBlocks.putIfAbsent(
                blockKey,
                block.getBlockData().getAsString()
        );

        generatedBlocks.add(blockKey);

        if (prison.blocks.add(blockKey)) {
            protectionCounts.merge(blockKey, 1, Integer::sum);
        }

        block.setType(material, false);
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

        removeMovingDisplays();

        for (UUID id : new ArrayList<>(frozenToCaster.keySet())) {
            Entity entity = Bukkit.getEntity(id);

            if (entity instanceof LivingEntity living) {
                unfreeze(living);
            }
        }

        List<String> keys = new ArrayList<>(generatedBlocks);
        Collections.reverse(keys);

        for (String blockKey : keys) {
            restoreBlock(blockKey);
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

    private void restoreBlock(String blockKey) {
        String[] parts = blockKey.split("\\|", 4);

        if (parts.length != 4) return;

        World world = Bukkit.getWorld(parts[0]);
        if (world == null) return;

        try {
            int x = Integer.parseInt(parts[1]);
            int y = Integer.parseInt(parts[2]);
            int z = Integer.parseInt(parts[3]);

            String data = originalBlocks.get(blockKey);

            if (data != null) {
                world.getBlockAt(x, y, z).setBlockData(
                        Bukkit.createBlockData(data),
                        false
                );
            }
        } catch (Exception ex) {
            getLogger().warning(
                    "Could not restore block " + blockKey
                            + ": " + ex.getMessage()
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
            for (String blockKey :
                    getConfig().getConfigurationSection("originals").getKeys(false)) {

                originalBlocks.put(
                        blockKey,
                        getConfig().getString("originals." + blockKey)
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
        final Location originalLocation;

        final List<BlockPos> cubePositions;
        final List<BlockPos> spherePositions;

        final List<SourceBlock> sourceBlocks = new ArrayList<>();
        final Set<String> blocks = new LinkedHashSet<>();

        boolean finished = false;

        PrisonBuild(
                LivingEntity target,
                Location center,
                Location originalLocation
        ) {
            this.target = target;
            this.center = center;
            this.originalLocation = originalLocation;

            this.cubePositions = createCube(center);
            this.spherePositions = createSphereShell(center, 3);
        }
    }

    private static class SourceBlock {
        final int x;
        final int y;
        final int z;
        final BlockData data;

        SourceBlock(int x, int y, int z, BlockData data) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.data = data;
        }
    }

    private static class MovingBlock {
        final BlockDisplay display;
        final Location start;
        final Location end;
        final BlockData data;
        final int duration;

        int elapsed = 0;

        MovingBlock(
                BlockDisplay display,
                Location start,
                Location end,
                BlockData data,
                int duration
        ) {
            this.display = display;
            this.start = start;
            this.end = end;
            this.data = data;
            this.duration = duration;
        }
    }

    private record BlockPos(int x, int y, int z) {}
}
