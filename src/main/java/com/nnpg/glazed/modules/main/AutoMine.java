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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Auto mining di dalam sebuah kotak (area) yang dibentuk dari Destination 1 dan Destination 2
 * (dua sudut yang berseberangan).
 *
 *  - BASIC: semua blok di dalam kotak ditambang satu per satu dengan tool terbaik.
 *  - DRILL: drill menghancurkan 3x3 datar (atas, bawah, kiri, kanan) dari blok tengah, jadi hanya blok
 *    tengah tiap kotak 3x3 (tegak lurus arah menambang) yang ditarget.
 *
 * Tidak perlu memilih blok. Tidak butuh Baritone.
 */
public class AutoMine extends Module {
    public enum Mode { Basic, Drill }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgArea = settings.createGroup("Area");
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
    private final List<BlockPos> targets = new ArrayList<>();
    private BlockPos min, max;
    private BlockPos current;
    private int refreshTicks;
    private int idleTicks;
    private int drillWait;
    private boolean waitingForDrill;

    // how long to wait for the drill to clear the neighbours before mining leftovers by hand
    private static final int DRILL_FALLBACK_TICKS = 10;

    public AutoMine() {
        super(GlazedAddon.CATEGORY, "auto-mine", "Mines everything inside a box made from destination 1 and destination 2.");
    }

    @Override
    public void onActivate() {
        current = null;
        idleTicks = 0;
        drillWait = 0;
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

        // blocks change under us, so refresh the target list now and then
        if (++refreshTicks >= 20) {
            refreshTicks = 0;
            if (!rebuildArea()) {
                toggle();
                return;
            }
        }

        BlockPos target = pickTarget();

        if (target == null && waitingForDrill) {
            // the drill is still clearing the 3x3 around the last block, just hold still
            releaseKeys();
            return;
        }

        if (target == null) {
            current = null;
            if (!hasRemaining()) {
                say("Done, the box is clear.");
                toggle();
                return;
            }
            walkToward();
            idle();
            return;
        }

        releaseKeys();
        idleTicks = 0;
        mine(target);
    }

    private void idle() {
        idleTicks++;
        int limit = stuckSeconds.get();
        if (limit > 0 && idleTicks > limit * 20) {
            say("Nothing in reach for " + limit + "s, stopping.");
            toggle();
        }
    }

    private void mine(BlockPos pos) {
        BlockState state = mc.level.getBlockState(pos);
        Direction face = bestFace(pos);

        lookAt(Vec3.atCenterOf(pos));

        selectTool(state);

        if (!pos.equals(current)) {
            mc.gameMode.startDestroyBlock(pos, face);
            current = pos;
        } else {
            mc.gameMode.continueDestroyBlock(pos, face);
        }
        mc.player.swing(InteractionHand.MAIN_HAND);
    }

    // ---------- target selection ----------

    /** Nearest mineable block in reach. Blocks below your feet go last so you dig down only after clearing the rest. */
    private BlockPos pickTarget() {
        Vec3 eye = mc.player.getEyePosition();
        double r = reach.get();
        double r2 = r * r;
        int feetY = mc.player.blockPosition().getY();
        boolean drill = mode.get() == Mode.Drill;

        waitingForDrill = false;
        BlockPos best = null, fallback = null;
        double bestScore = Double.MAX_VALUE, fallbackScore = Double.MAX_VALUE;

        for (BlockPos pos : targets) {
            if (!isMineable(pos)) continue;
            if (!isExposed(pos)) continue;

            double d2 = Vec3.atCenterOf(pos).distanceToSqr(eye);
            if (d2 > r2) continue;

            double score = d2 + (pos.getY() < feetY ? 1000 : 0);

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
        return true;
    }

    // ---------- movement / tool / look ----------

    private void walkToward() {
        if (!autoWalk.get()) {
            releaseKeys();
            return;
        }

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

        double dx = nearest.getX() + 0.5 - mc.player.getX();
        double dz = nearest.getZ() + 0.5 - mc.player.getZ();
        if (dx * dx + dz * dz < 0.7 * 0.7) { // already above/below it, nothing to walk
            releaseKeys();
            return;
        }

        mc.player.setYRot(Mth.wrapDegrees((float) (Mth.atan2(dz, dx) * 57.2957795) - 90.0f));
        mc.options.keyUp.setDown(true);
        mc.options.keyJump.setDown(mc.player.horizontalCollision);
    }

    private void releaseKeys() {
        if (mc.options == null) return;
        mc.options.keyUp.setDown(false);
        mc.options.keyJump.setDown(false);
    }

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

    private void lookAt(Vec3 target) {
        Vec3 eye = mc.player.getEyePosition();
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        double flat = Math.sqrt(dx * dx + dz * dz);
        mc.player.setYRot(Mth.wrapDegrees((float) (Mth.atan2(dz, dx) * 57.2957795) - 90.0f));
        mc.player.setXRot(Mth.clamp((float) -(Mth.atan2(dy, flat) * 57.2957795), -90.0f, 90.0f));
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
