package com.nnpg.glazed.modules.main;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalBlock;
import com.nnpg.glazed.GlazedAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.List;

/**
 * Auto mining dengan dua destinasi:
 *  - Destination 1 = tempat menambang
 *  - Destination 2 = tempat tujuan setelah menambang (mis. spot sell / chest)
 *
 * Alur: jalan ke Dest 1 -> tambang blok pilihan sampai inventory penuh (atau waktu habis)
 *       -> jalan ke Dest 2 -> (opsional) kirim command -> ulangi.
 */
public class AutoMine extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgDest = settings.createGroup("Destinations");

    // ---- general ----
    private final Setting<List<Block>> blocks = sgGeneral.add(new BlockListSetting.Builder()
        .name("blocks")
        .description("Blocks to mine.")
        .defaultValue(Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE)
        .build()
    );

    private final Setting<Boolean> mineUntilFull = sgGeneral.add(new BoolSetting.Builder()
        .name("mine-until-full")
        .description("Go to destination 2 once the inventory is full.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> mineMinutes = sgGeneral.add(new IntSetting.Builder()
        .name("mine-minutes")
        .description("Max minutes mining per round (0 = no time limit).")
        .defaultValue(0)
        .min(0)
        .sliderMax(60)
        .build()
    );

    private final Setting<Boolean> loop = sgGeneral.add(new BoolSetting.Builder()
        .name("loop")
        .description("Go back to destination 1 and mine again after reaching destination 2.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> chatFeedback = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-feedback")
        .description("Show status messages in chat.")
        .defaultValue(true)
        .build()
    );

    // ---- destinations ----
    private final Setting<BlockPos> dest1 = sgDest.add(new BlockPosSetting.Builder()
        .name("destination-1")
        .description("Mining spot.")
        .defaultValue(BlockPos.ZERO)
        .build()
    );

    private final Setting<Boolean> setDest1Here = sgDest.add(new BoolSetting.Builder()
        .name("set-destination-1-here")
        .description("Toggle on to save your current position as destination 1.")
        .defaultValue(false)
        .onChanged(v -> {
            if (v) {
                saveHere(dest1, "Destination 1");
                resetToggle("set-destination-1-here");
            }
        })
        .build()
    );

    private final Setting<BlockPos> dest2 = sgDest.add(new BlockPosSetting.Builder()
        .name("destination-2")
        .description("Where to go after mining.")
        .defaultValue(BlockPos.ZERO)
        .build()
    );

    private final Setting<Boolean> setDest2Here = sgDest.add(new BoolSetting.Builder()
        .name("set-destination-2-here")
        .description("Toggle on to save your current position as destination 2.")
        .defaultValue(false)
        .onChanged(v -> {
            if (v) {
                saveHere(dest2, "Destination 2");
                resetToggle("set-destination-2-here");
            }
        })
        .build()
    );

    private final Setting<String> arriveCommand = sgDest.add(new StringSetting.Builder()
        .name("command-at-destination-2")
        .description("Command to send on arrival at destination 2 (without '/'). Leave empty for none.")
        .defaultValue("")
        .build()
    );

    private final Setting<Integer> arriveDelay = sgDest.add(new IntSetting.Builder()
        .name("delay-at-destination-2")
        .description("Ticks to wait at destination 2 before heading back.")
        .defaultValue(40)
        .min(0)
        .sliderMax(200)
        .build()
    );

    private final Setting<Integer> arriveRadius = sgDest.add(new IntSetting.Builder()
        .name("arrive-radius")
        .description("How close (blocks) counts as having arrived.")
        .defaultValue(2)
        .min(1)
        .sliderMax(10)
        .build()
    );

    private final Setting<Integer> stuckSeconds = sgDest.add(new IntSetting.Builder()
        .name("travel-timeout-seconds")
        .description("Re-issue the path if travel takes longer than this (0 = off).")
        .defaultValue(120)
        .min(0)
        .sliderMax(600)
        .build()
    );

    private enum Step { GO_TO_1, MINING, GO_TO_2, WAIT_AT_2 }

    private Step step = Step.GO_TO_1;
    private int stepTicks;
    private int waitTicks;
    private boolean commandSent;

    public AutoMine() {
        super(GlazedAddon.CATEGORY, "auto-mine", "Walks to destination 1, mines, then goes to destination 2 (uses Baritone).");
    }

    @Override
    public void onActivate() {
        if (mc.player == null || mc.level == null) {
            toggle();
            return;
        }
        if (blocks.get().isEmpty()) {
            error("Select at least one block to mine.");
            toggle();
            return;
        }
        if (dest1.get().equals(BlockPos.ZERO) || dest2.get().equals(BlockPos.ZERO)) {
            error("Set destination 1 and destination 2 first.");
            toggle();
            return;
        }
        enter(Step.GO_TO_1);
    }

    @Override
    public void onDeactivate() {
        cancelBaritone();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.level == null) return;
        stepTicks++;

        switch (step) {
            case GO_TO_1 -> {
                if (arrived(dest1.get())) {
                    enter(Step.MINING);
                } else if (!travelActive() || travelTimedOut()) {
                    pathTo(dest1.get());
                    stepTicks = 0;
                }
            }
            case MINING -> {
                if (!baritone().getMineProcess().isActive()) {
                    baritone().getMineProcess().mine(blocks.get().toArray(new Block[0]));
                }
                boolean full = mineUntilFull.get() && inventoryFull();
                boolean timeUp = mineMinutes.get() > 0 && stepTicks >= mineMinutes.get() * 60 * 20;
                if (full || timeUp) {
                    enter(Step.GO_TO_2);
                }
            }
            case GO_TO_2 -> {
                if (arrived(dest2.get())) {
                    enter(Step.WAIT_AT_2);
                } else if (!travelActive() || travelTimedOut()) {
                    pathTo(dest2.get());
                    stepTicks = 0;
                }
            }
            case WAIT_AT_2 -> {
                if (!commandSent) {
                    String cmd = arriveCommand.get().trim();
                    if (!cmd.isEmpty() && mc.player.connection != null) {
                        mc.player.connection.sendCommand(cmd.startsWith("/") ? cmd.substring(1) : cmd);
                    }
                    commandSent = true;
                }
                if (++waitTicks >= arriveDelay.get()) {
                    if (loop.get()) {
                        enter(Step.GO_TO_1);
                    } else {
                        say("Done.");
                        toggle();
                    }
                }
            }
        }
    }

    // ---------- step handling ----------

    private void enter(Step next) {
        cancelBaritone();
        step = next;
        stepTicks = 0;
        waitTicks = 0;
        commandSent = false;

        switch (next) {
            case GO_TO_1 -> say("Heading to destination 1.");
            case MINING -> say("Mining.");
            case GO_TO_2 -> say("Heading to destination 2.");
            case WAIT_AT_2 -> say("Arrived at destination 2.");
        }
    }

    private void pathTo(BlockPos pos) {
        baritone().getCustomGoalProcess().setGoalAndPath(new GoalBlock(pos));
    }

    private boolean travelActive() {
        return baritone().getCustomGoalProcess().isActive();
    }

    private boolean travelTimedOut() {
        int limit = stuckSeconds.get();
        return limit > 0 && stepTicks > limit * 20;
    }

    private boolean arrived(BlockPos target) {
        double r = arriveRadius.get();
        return mc.player.blockPosition().distSqr(target) <= r * r;
    }

    private boolean inventoryFull() {
        // main inventory + hotbar = slots 0..35
        for (int i = 0; i < 36; i++) {
            if (mc.player.getInventory().getItem(i).isEmpty()) return false;
        }
        return true;
    }

    // ---------- helpers ----------

    private IBaritone baritone() {
        return BaritoneAPI.getProvider().getPrimaryBaritone();
    }

    private void cancelBaritone() {
        try {
            baritone().getPathingBehavior().cancelEverything();
        } catch (Throwable ignored) {
            // baritone not ready yet (e.g. disabling before world load)
        }
    }

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
