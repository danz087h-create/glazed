package com.nnpg.glazed.modules.main;

import com.nnpg.glazed.GlazedAddon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Auto mining di dalam sebuah kotak yang dibentuk dari Destination 1 dan Destination 2.
 *
 *  - BASIC: semua blok di dalam kotak ditambang.
 *  - DRILL: drill menghancurkan 3x3 datar, jadi hanya blok tengah tiap kotak 3x3 yang ditarget.
 *
 * Dibuat supaya terlihat seperti manusia:
 *  - kamera bergerak halus (tidak instan), dengan sedikit goyangan dan titik bidik acak di dalam blok
 *  - hanya mulai menambang kalau crosshair benar-benar sudah di blok itu
 *  - pindah ke blok berikutnya baru setelah blok sekarang hancur, dengan jeda reaksi acak
 *  - sesekali berhenti sebentar
 */
public class AutoMine extends Module {
    public enum Mode { Basic, Drill }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgArea = settings.createGroup("Area");
    private final SettingGroup sgHuman = settings.createGroup("Human");
    private final SettingGroup sgRender = settings.createGroup("Render");

    // ---- general ----
    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("Basic = mine every block in the box. Drill = only mine the middle block of each 3x3 square (the drill breaks the rest).")
        .defaultValue(Mode.Basic)
        .build()
    );

    private final Setting<Boolean> autoTool = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-tool")
        .description("Switch to the fastest tool in your hotbar for each block.")
        .defaultValue(true)
        .build()
    );

    private final Setting<String> drillName = sgGeneral.add(new StringSetting.Builder()
        .name("drill-item-name")
        .description("Drill mode: use the hotbar item whose name contains this text (empty = just use auto-tool).")
        .defaultValue("drill")
        .build()
    );

    private final Setting<Double> reach = sgGeneral.add(new DoubleSetting.Builder()
        .name("reach")
        .description("Max distance to a block you will mine.")
        .defaultValue(4.0)
        .min(1.0)
        .sliderRange(1.0, 5.0)
        .build()
    );

    private final Setting<Boolean> autoWalk = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-walk")
        .description("Walk toward the area when nothing is in reach.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> stuckSeconds = sgGeneral.add(new IntSetting.Builder()
        .name("stuck-timeout-seconds")
        .description("Turn off if nothing could be mined for this long (0 = never).")
        .defaultValue(15)
        .min(0)
        .sliderMax(120)
        .build()
    );

    private final Setting<Boolean> chatFeedback = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-feedback")
        .description("Show status messages in chat.")
        .defaultValue(true)
        .build()
    );

    // ---- area ----
    private final Setting<BlockPos> dest1 = sgArea.add(new BlockPosSetting.Builder()
        .name("destination-1")
        .description("First corner of the mining box.")
        .defaultValue(BlockPos.ZERO)
        .build()
    );

    private final Setting<Boolean> setDest1Here = sgArea.add(new BoolSetting.Builder()
        .name("set-destination-1-here")
        .description("Toggle on to save the block you are standing in as destination 1.")
        .defaultValue(false)
        .onChanged(v -> {
            if (v) {
                saveHere(dest1, "Destination 1");
                resetToggle("set-destination-1-here");
            }
        })
        .build()
    );

    private final Setting<BlockPos> dest2 = sgArea.add(new BlockPosSetting.Builder()
        .name("destination-2")
        .description("Opposite corner of the mining box.")
        .defaultValue(BlockPos.ZERO)
        .build()
    );

    private final Setting<Boolean> setDest2Here = sgArea.add(new BoolSetting.Builder()
        .name("set-destination-2-here")
        .description("Toggle on to save the block you are standing in as destination 2.")
        .defaultValue(false)
        .onChanged(v -> {
            if (v) {
                saveHere(dest2, "Destination 2");
                resetToggle("set-destination-2-here");
            }
        })
        .build()
    );

    private final Setting<Integer> maxVolume = sgArea.add(new IntSetting.Builder()
        .name("max-volume")
        .description("Refuse boxes bigger than this many blocks (protects against lag).")
        .defaultValue(20000)
        .min(1)
        .sliderMax(100000)
        .build()
    );

    // ---- human ----
    private final Setting<Boolean> humanize = sgHuman.add(new BoolSetting.Builder()
        .name("humanize")
        .description("Smooth camera, random aim point, reaction delays and short pauses. Off = instant and robotic.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> rotationSpeed = sgHuman.add(new DoubleSetting.Builder()
        .name("rotation-speed")
        .description("Camera speed in degrees per tick (it slows down near the target).")
        .defaultValue(9.0)
        .min(2.0)
        .sliderRange(2.0, 30.0)
        .visible(humanize::get)
        .build()
    );

    private final Setting<Integer> reactionMin = sgHuman.add(new IntSetting.Builder()
        .name("reaction-min-ticks")
        .description("Shortest wait after a block breaks before looking at the next one.")
        .defaultValue(2)
        .min(0)
        .sliderMax(20)
        .visible(humanize::get)
        .build()
    );

    private final Setting<Integer> reactionMax = sgHuman.add(new IntSetting.Builder()
        .name("reaction-max-ticks")
        .description("Longest wait after a block breaks before looking at the next one.")
        .defaultValue(6)
        .min(0)
        .sliderMax(40)
        .visible(humanize::get)
        .build()
    );

    private final Setting<Integer> pauseChance = sgHuman.add(new IntSetting.Builder()
        .name("pause-chance-percent")
        .description("Chance after each block to stop for a moment, like a distracted player.")
        .defaultValue(4)
        .min(0)
        .sliderRange(0, 30)
        .visible(humanize::get)
        .build()
    );

    // ---- render ----
    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render-box")
        .description("Draw the mining box.")
        .defaultValue(true)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the box is drawn.")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .description("Box fill color.")
        .defaultValue(new SettingColor(255, 180, 0, 40))
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("Box outline color.")
        .defaultValue(new SettingColor(255, 180, 0, 255))
        .build()
    );

    private final Setting<SettingColor> targetColor = sgRender.add(new ColorSetting.Builder()
        .name("target-color")
        .description("Color of the block being mined.")
        .defaultValue(new SettingColor(255, 60, 60, 120))
        .build()
    );

    // ---- state ----
    private static final int DRILL_FALLBACK_TICKS = 10;   // wait for the drill to clear neighbours before mining leftovers
    private static final int MAX_ALIGN_TICKS = 60;        // give up on a block we cannot aim at
    private static final int MAX_FALLING_WAIT = 100;      // never wait forever for sand/gravel

    private final List<BlockPos> targets = new ArrayList<>();
    private final Map<BlockPos, Integer> skipUntil = new HashMap<>();
    private BlockPos min, max;
    private BlockPos current;   // block we are working on
    private BlockPos breaking;  // block the server was told we are breaking
    private Vec3 aim;           // random point inside the current block
    private int tickCounter;
    private int refreshTicks;
    private int idleTicks;
    private int drillWait;
    private int cooldown;       // reaction time after a block broke
    private int alignTicks;     // ticks spent turning toward the current block
    private int fallingWait;
    private boolean waitingForDrill;
    private float noiseYaw, noisePitch;

    public AutoMine() {
        super(GlazedAddon.CATEGORY, "auto-mine", "Mines everything inside a box made from destination 1 and destination 2.");
    }

    @Override
    public void onActivate() {
        current = null;
        breaking = null;
        aim = null;
        idleTicks = 0;
        drillWait = 0;
        cooldown = 0;
        alignTicks = 0;
        fallingWait = 0;
        skipUntil.clear();
        if (!rebuildArea()) {
            toggle();
            return;
        }
        say("Mining " + (max.getX() - min.getX() + 1) + "x" + (max.getY() - min.getY() + 1) + "x"
            + (max.getZ() - min.getZ() + 1) + " box (" + mode.get() + " mode).");
    }

    @Override
    public void onDeactivate() {
        releaseKeys();
        if (mc.gameMode != null) mc.gameMode.stopDestroyBlock();
        current = null;
        breaking = null;
        aim = null;
        targets.clear();
    }

    // ---------- rendering ----------

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!render.get()) return;

        BlockPos a = dest1.get(), b = dest2.get();
        if (a.equals(BlockPos.ZERO) && b.equals(BlockPos.ZERO)) return;

        int x1 = Math.min(a.getX(), b.getX()), x2 = Math.max(a.getX(), b.getX()) + 1;
        int y1 = Math.min(a.getY(), b.getY()), y2 = Math.max(a.getY(), b.getY()) + 1;
        int z1 = Math.min(a.getZ(), b.getZ()), z2 = Math.max(a.getZ(), b.getZ()) + 1;

        event.renderer.box(x1, y1, z1, x2, y2, z2,
            new Color(sideColor.get()), new Color(lineColor.get()), shapeMode.get(), 0);

        if (current != null) {
            event.renderer.box(current, new Color(targetColor.get()), new Color(targetColor.get()), ShapeMode.Both, 0);
        }
    }

    // ---------- main loop ----------

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null || mc.gameMode == null) return;
        tickCounter++;

        // blocks change under us, so refresh the target list now and then
        if (++refreshTicks >= 20) {
            refreshTicks = 0;
            if (!rebuildArea()) {
                toggle();
                return;
            }
        }

        // sand / gravel in the air: let it land first, otherwise we chase blocks that are about to move
        boolean falling = fallingBlocksNearby();
        if (falling && fallingWait < MAX_FALLING_WAIT) {
            fallingWait++;
            releaseKeys();
            return;
        }
        if (!falling) fallingWait = 0;

        // reaction time: we hold still for a moment after a block broke
        if (cooldown > 0) {
            cooldown--;
            releaseKeys();
            return;
        }

        // check the block we are working on
        if (current != null) {
            if (!isMineable(current)) {
                onBroken();       // it is gone, only now do we think about the next block
                return;
            }
            if (!isExposed(current) || !inReach(current, reach.get() + 0.6)) {
                dropCurrent();
            } else if (alignTicks > MAX_ALIGN_TICKS) {
                skipUntil.put(current, tickCounter + 100);
                dropCurrent();
            }
        }

        // choose a new block only when we have none
        if (current == null) {
            BlockPos next = pickTarget();

            if (next == null && waitingForDrill) {
                // the drill is still clearing the 3x3 around the last block, hold still
                releaseKeys();
                return;
            }

            if (next == null) {
                if (!hasRemaining()) {
                    say("Done, the box is clear.");
                    toggle();
                    return;
                }
                walkToward();
                idle();
                return;
            }

            current = next;
            alignTicks = 0;
            chooseAim(next);
        }

        releaseKeys();
        idleTicks = 0;
        mineCurrent();
    }

    private void idle() {
        idleTicks++;
        int limit = stuckSeconds.get();
        if (limit > 0 && idleTicks > limit * 20) {
            say("Nothing in reach for " + limit + "s, stopping.");
            toggle();
        }
    }

    private void onBroken() {
        breaking = null;
        current = null;
        aim = null;
        cooldown = humanize.get() ? randomReaction() : 0;
    }

    private void dropCurrent() {
        if (mc.gameMode != null) mc.gameMode.stopDestroyBlock();
        breaking = null;
        current = null;
        aim = null;
    }

    private int randomReaction() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int lo = Math.min(reactionMin.get(), reactionMax.get());
        int hi = Math.max(reactionMin.get(), reactionMax.get());
        int d = lo + r.nextInt(hi - lo + 1);
        if (r.nextInt(100) < pauseChance.get()) d += 8 + r.nextInt(14); // distracted for a moment
        return d;
    }

    private void mineCurrent() {
        BlockState state = mc.level.getBlockState(current);

        // turn the camera toward the block, smoothly
        lookToward(aim);
        selectTool(state);
        alignTicks++;

        // only hit the block once the crosshair really is on it
        if (!(mc.hitResult instanceof BlockHitResult hit) || !hit.getBlockPos().equals(current)) return;

        alignTicks = 0;
        if (!current.equals(breaking)) {
            mc.gameMode.startDestroyBlock(current, hit.getDirection());
            breaking = current;
        } else {
            mc.gameMode.continueDestroyBlock(current, hit.getDirection());
        }
        mc.player.swing(InteractionHand.MAIN_HAND);
    }

    private void chooseAim(BlockPos pos) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double j = humanize.get() ? 0.26 : 0.0;
        aim = Vec3.atCenterOf(pos).add(
            (r.nextDouble() - 0.5) * 2 * j,
            (r.nextDouble() - 0.5) * 2 * j,
            (r.nextDouble() - 0.5) * 2 * j
        );
    }

    // ---------- target selection ----------

    /**
     * Picks a block near the crosshair and near the player. Blocks below your feet go last so you dig down
     * only after clearing the rest.
     */
    private BlockPos pickTarget() {
        Vec3 eye = mc.player.getEyePosition();
        Vec3 look = mc.player.getLookAngle();
        double r = reach.get();
        double r2 = r * r;
        int feetY = mc.player.blockPosition().getY();
        boolean drill = mode.get() == Mode.Drill;
        ThreadLocalRandom rnd = ThreadLocalRandom.current();

        waitingForDrill = false;
        BlockPos best = null, fallback = null;
        double bestScore = Double.MAX_VALUE, fallbackScore = Double.MAX_VALUE;

        for (BlockPos pos : targets) {
            Integer until = skipUntil.get(pos);
            if (until != null && until > tickCounter) continue;
            if (!isMineable(pos)) continue;
            if (!isExposed(pos)) continue;

            Vec3 center = Vec3.atCenterOf(pos);
            double d2 = center.distanceToSqr(eye);
            if (d2 > r2) continue;

            // humans pick what is close to their crosshair, not what is mathematically nearest
            double angle = Math.toDegrees(Math.acos(Mth.clamp(look.dot(center.subtract(eye).normalize()), -1.0, 1.0)));
            double score = d2 + angle * 0.15 + (pos.getY() < feetY ? 1000 : 0)
                + (humanize.get() ? rnd.nextDouble() * 0.6 : 0);

            // drill: only the middle block of each 3x3 square (square is perpendicular to the face we mine from)
            if (drill && !isDrillCenter(pos, bestFace(pos))) {
                if (score < fallbackScore) {
                    fallbackScore = score;
                    fallback = pos;
                }
                continue;
            }

            if (score < bestScore) {
                bestScore = score;
                best = pos;
            }
        }

        if (best != null) {
            drillWait = 0;
            return best;
        }

        // only leftovers that the drill did not cover: give it a moment, then mine them by hand
        if (drill && fallback != null) {
            if (++drillWait > DRILL_FALLBACK_TICKS) return fallback;
            waitingForDrill = true;
            return null;
        }

        drillWait = 0;
        return null;
    }

    private boolean isDrillCenter(BlockPos pos, Direction face) {
        return switch (face.getAxis()) {
            case X -> isCenter(pos.getY(), min.getY(), max.getY()) && isCenter(pos.getZ(), min.getZ(), max.getZ());
            case Y -> isCenter(pos.getX(), min.getX(), max.getX()) && isCenter(pos.getZ(), min.getZ(), max.getZ());
            case Z -> isCenter(pos.getX(), min.getX(), max.getX()) && isCenter(pos.getY(), min.getY(), max.getY());
        };
    }

    /** True if v is the middle of its group of 3 along one axis (the last group may be smaller). */
    private static boolean isCenter(int v, int from, int to) {
        int start = from + ((v - from) / 3) * 3;
        int end = Math.min(start + 2, to);
        return v == (start + end) / 2;
    }

    private boolean hasRemaining() {
        for (BlockPos pos : targets) {
            if (isMineable(pos)) return true;
        }
        return false;
    }

    private boolean isMineable(BlockPos pos) {
        BlockState state = mc.level.getBlockState(pos);
        if (state.isAir()) return false;
        if (!state.getFluidState().isEmpty()) return false;
        return state.getDestroySpeed(mc.level, pos) >= 0; // skips bedrock etc.
    }

    private boolean isExposed(BlockPos pos) {
        for (Direction d : Direction.values()) {
            BlockPos n = pos.relative(d);
            if (mc.level.getBlockState(n).getCollisionShape(mc.level, n).isEmpty()) return true;
        }
        return false;
    }

    private boolean inReach(BlockPos pos, double r) {
        return Vec3.atCenterOf(pos).distanceToSqr(mc.player.getEyePosition()) <= r * r;
    }

    private Direction bestFace(BlockPos pos) {
        Vec3 eye = mc.player.getEyePosition();
        Direction best = Direction.UP;
        double bestD = Double.MAX_VALUE;
        for (Direction d : Direction.values()) {
            BlockPos n = pos.relative(d);
            if (!mc.level.getBlockState(n).getCollisionShape(mc.level, n).isEmpty()) continue;
            double dist = Vec3.atCenterOf(n).distanceToSqr(eye);
            if (dist < bestD) {
                bestD = dist;
                best = d;
            }
        }
        return best;
    }

    private boolean fallingBlocksNearby() {
        AABB box = mc.player.getBoundingBox().inflate(3, 6, 3);
        return !mc.level.getEntitiesOfClass(FallingBlockEntity.class, box).isEmpty();
    }

    // ---------- area ----------

    private boolean rebuildArea() {
        BlockPos a = dest1.get(), b = dest2.get();
        if (a.equals(BlockPos.ZERO) || b.equals(BlockPos.ZERO)) {
            error("Set destination 1 and destination 2 first.");
            return false;
        }

        min = new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
        max = new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));

        long volume = (long) (max.getX() - min.getX() + 1) * (max.getY() - min.getY() + 1) * (max.getZ() - min.getZ() + 1);
        if (volume > maxVolume.get()) {
            error("Box is too big (" + volume + " blocks, limit " + maxVolume.get() + ").");
            return false;
        }

        targets.clear();
        for (int x = min.getX(); x <= max.getX(); x++)
            for (int y = min.getY(); y <= max.getY(); y++)
                for (int z = min.getZ(); z <= max.getZ(); z++)
                    targets.add(new BlockPos(x, y, z));

        skipUntil.values().removeIf(until -> until <= tickCounter);
        return true;
    }

    // ---------- movement ----------

    private void walkToward() {
        if (!autoWalk.get()) {
            releaseKeys();
            return;
        }

        int feetY = mc.player.blockPosition().getY();

        // nearest remaining block, by horizontal distance
        BlockPos nearest = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos pos : targets) {
            if (!isMineable(pos)) continue;
            double d = pos.distToCenterSqr(mc.player.getX(), pos.getY() + 0.5, mc.player.getZ());
            if (d < bestD) {
                bestD = d;
                nearest = pos;
            }
        }
        if (nearest == null) {
            releaseKeys();
            return;
        }

        BlockPos feet = mc.player.blockPosition();
        boolean outside = feet.getX() < min.getX() || feet.getX() > max.getX()
            || feet.getZ() < min.getZ() || feet.getZ() > max.getZ();
        boolean below = nearest.getY() < feetY - 1;

        // standing over the pit: dig down, do not hop around on top of it (this is what put us on the sand)
        if (below && !outside) {
            releaseKeys();
            return;
        }

        double dx = nearest.getX() + 0.5 - mc.player.getX();
        double dz = nearest.getZ() + 0.5 - mc.player.getZ();
        if (dx * dx + dz * dz < 0.7 * 0.7) { // already above/below it, nothing to walk
            releaseKeys();
            return;
        }

        // turn first, then walk
        float targetYaw = Mth.wrapDegrees((float) (Mth.atan2(dz, dx) * 57.2957795) - 90.0f);
        rotateTo(targetYaw, 12.0f);
        boolean facing = Math.abs(Mth.wrapDegrees(targetYaw - mc.player.getYRot())) < 25.0f;

        mc.options.keyUp.setDown(facing);
        // only hop up a step when the way is blocked and the target is not lower than us
        mc.options.keyJump.setDown(facing && mc.player.horizontalCollision && !below && nearest.getY() >= feetY);
    }

    private void releaseKeys() {
        if (mc.options == null) return;
        mc.options.keyUp.setDown(false);
        mc.options.keyJump.setDown(false);
    }

    // ---------- tool / camera ----------

    private void selectTool(BlockState state) {
        if (mode.get() == Mode.Drill && !drillName.get().isBlank()) {
            String key = drillName.get().trim().toLowerCase();
            for (int i = 0; i < 9; i++) {
                ItemStack stack = mc.player.getInventory().getItem(i);
                if (stack.isEmpty()) continue;
                if (stack.getHoverName().getString().toLowerCase().contains(key)) {
                    if (i != mc.player.getInventory().getSelectedSlot()) InvUtils.swap(i, false);
                    return;
                }
            }
        }
        if (autoTool.get()) selectBestTool(state);
    }

    private void selectBestTool(BlockState state) {
        int bestSlot = -1;
        float bestSpeed = 1.0f;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            float speed = stack.getDestroySpeed(state);
            if (speed > bestSpeed) {
                bestSpeed = speed;
                bestSlot = i;
            }
        }
        if (bestSlot != -1 && bestSlot != mc.player.getInventory().getSelectedSlot()) {
            InvUtils.swap(bestSlot, false);
        }
    }

    private void lookToward(Vec3 point) {
        Vec3 eye = mc.player.getEyePosition();
        double dx = point.x - eye.x;
        double dy = point.y - eye.y;
        double dz = point.z - eye.z;
        double flat = Math.sqrt(dx * dx + dz * dz);
        float yaw = Mth.wrapDegrees((float) (Mth.atan2(dz, dx) * 57.2957795) - 90.0f);
        float pitch = Mth.clamp((float) -(Mth.atan2(dy, flat) * 57.2957795), -90.0f, 90.0f);
        rotateTo(yaw, pitch);
    }

    /** Moves the camera a little toward the wanted angles each tick, like a mouse, instead of snapping. */
    private void rotateTo(float targetYaw, float targetPitch) {
        float yaw = mc.player.getYRot();
        float pitch = mc.player.getXRot();

        if (!humanize.get()) {
            mc.player.setYRot(targetYaw);
            mc.player.setXRot(targetPitch);
            return;
        }

        ThreadLocalRandom r = ThreadLocalRandom.current();

        // slow wandering offset so the crosshair is never perfectly still
        noiseYaw = Mth.clamp(noiseYaw * 0.9f + (float) (r.nextDouble() - 0.5) * 0.25f, -0.35f, 0.35f);
        noisePitch = Mth.clamp(noisePitch * 0.9f + (float) (r.nextDouble() - 0.5) * 0.25f, -0.35f, 0.35f);

        float dy = Mth.wrapDegrees(targetYaw + noiseYaw - yaw);
        float dp = targetPitch + noisePitch - pitch;
        float dist = (float) Math.sqrt(dy * dy + dp * dp);
        if (dist < 0.02f) return;

        // fast on big turns, slow for the last few degrees, with a little speed variation
        float speed = rotationSpeed.get().floatValue() * (0.7f + (float) r.nextDouble() * 0.6f);
        float step = Math.min(dist, Math.max(0.35f, Math.min(speed, dist * 0.45f)));
        float k = step / dist;

        applyRotation(yaw + dy * k, pitch + dp * k);
    }

    /** Rounds the change to what a real mouse can do at your sensitivity. */
    private void applyRotation(float newYaw, float newPitch) {
        double f = mc.options.sensitivity().get() * 0.6 + 0.2;
        double gcd = f * f * f * 8.0 * 0.15;

        float yaw = mc.player.getYRot();
        float pitch = mc.player.getXRot();
        float dy = (float) (Math.round((newYaw - yaw) / gcd) * gcd);
        float dp = (float) (Math.round((newPitch - pitch) / gcd) * gcd);

        mc.player.setYRot(yaw + dy);
        mc.player.setXRot(Mth.clamp(pitch + dp, -90.0f, 90.0f));
    }

    // ---------- helpers ----------

    private void saveHere(Setting<BlockPos> target, String label) {
        if (mc.player == null) return;
        BlockPos pos = mc.player.blockPosition();
        target.set(pos);
        info(label + " set to " + pos.getX() + " " + pos.getY() + " " + pos.getZ());
    }

    private void resetToggle(String name) {
        Setting<?> s = settings.get(name);
        if (s instanceof BoolSetting b) b.set(false);
    }

    private void say(String msg) {
        if (chatFeedback.get()) info(msg);
    }
}
