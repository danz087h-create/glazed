package com.nnpg.glazed.modules.esp;

import com.nnpg.glazed.GlazedAddon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.MeteorToast;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.sounds.SoundSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Sound ESP for Glazed / Minecraft 1.21.11.
 *
 * Sound positions are supplied by SoundEngineMixin. Block sounds mark the
 * block at the sound origin; living-entity sounds mark the block beneath
 * the nearest living entity. This is positional inference, not guaranteed
 * identification of the exact block/entity that emitted every sound.
 */
public class SoundESP extends Module {
    private static final ConcurrentLinkedQueue<SoundHit> INCOMING = new ConcurrentLinkedQueue<>();
    private final List<SoundHitEntry> activeHits = new ArrayList<>();

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Integer> range = sgGeneral.add(new IntSetting.Builder()
        .name("range").description("Maximum sound distance from you.")
        .defaultValue(32).min(4).max(128).sliderRange(4, 64).build());

    private final Setting<Integer> lifetime = sgGeneral.add(new IntSetting.Builder()
        .name("highlight-time").description("How long a sound highlight remains, in ticks.")
        .defaultValue(30).min(5).max(200).sliderRange(5, 100).build());

    private final Setting<Boolean> detectBlocks = sgGeneral.add(new BoolSetting.Builder()
        .name("block-sounds").description("Highlight blocks that produced block-category sounds.")
        .defaultValue(true).build());

    private final Setting<Boolean> detectMobs = sgGeneral.add(new BoolSetting.Builder()
        .name("mob-sounds").description("Highlight the block beneath mobs that produced sounds.")
        .defaultValue(true).build());

    private final Setting<Boolean> detectPlayers = sgGeneral.add(new BoolSetting.Builder()
        .name("player-sounds").description("Highlight the block beneath other players that produced sounds.")
        .defaultValue(true).build());

    private final Setting<Boolean> notifications = sgGeneral.add(new BoolSetting.Builder()
        .name("notifications").description("Show a toast for newly detected sounds.")
        .defaultValue(true).build());

    private final Setting<SettingColor> blockColor = sgRender.add(new ColorSetting.Builder()
        .name("block-color").description("Highlight color for block sounds.")
        .defaultValue(new SettingColor(255, 170, 40, 35)).build());

    private final Setting<SettingColor> entityColor = sgRender.add(new ColorSetting.Builder()
        .name("entity-color").description("Highlight color for player and mob sounds.")
        .defaultValue(new SettingColor(70, 190, 255, 35)).build());

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode").description("Highlight box rendering mode.")
        .defaultValue(ShapeMode.Both).build());

    public SoundESP() {
        super(GlazedAddon.esp, "sound-esp", "Highlights blocks or entities inferred from nearby game sounds.");
    }

    /** Called by SoundEngineMixin. Must remain lightweight: no world access here. */
    public static void captureSound(SoundInstance sound) {
        if (sound == null || sound.getSource() == null) return;

        SoundSource source = sound.getSource();
        // Music and ambient are intentionally ignored.
        if (source == SoundSource.MUSIC || source == SoundSource.AMBIENT) return;

        double x = sound.getX();
        double y = sound.getY();
        double z = sound.getZ();
        String soundId = "unknown";
        if (sound.getSound() != null && sound.getSound().getSound() != null) {
            soundId = sound.getSound().getSound().getLocation().toString();
        }
        INCOMING.offer(new SoundHit(x, y, z, source, soundId, System.currentTimeMillis()));
    }

    @Override
    public void onActivate() {
        activeHits.clear();
        INCOMING.clear();
    }

    @Override
    public void onDeactivate() {
        activeHits.clear();
        INCOMING.clear();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.level == null) return;

        long now = System.currentTimeMillis();
        SoundHit hit;
        while ((hit = INCOMING.poll()) != null) {
            Vec3 soundPos = new Vec3(hit.x, hit.y, hit.z);
            if (mc.player.position().distanceToSqr(soundPos) > (double) range.get() * range.get()) continue;

            // Player-category sounds very close to our own position are treated as self-generated.
            if (hit.source == SoundSource.PLAYERS &&
                mc.player.position().distanceToSqr(soundPos) < 2.25) continue;

            BlockPos target;
            boolean entitySound = hit.source == SoundSource.HOSTILE
                || hit.source == SoundSource.NEUTRAL
                || hit.source == SoundSource.PLAYERS;

            if (entitySound) {
                LivingEntity nearest = findNearestLiving(soundPos, 5.0);
                if (nearest == null || nearest == mc.player) continue;
                boolean player = nearest instanceof net.minecraft.world.entity.player.Player;
                if (player && !detectPlayers.get()) continue;
                if (!player && !detectMobs.get()) continue;
                target = nearest.blockPosition().below();
                addHit(target, player ? "Player" : nearest.getType().getDescription().getString(),
                    hit.soundId, true, now);
            } else if (hit.source == SoundSource.BLOCKS && detectBlocks.get()) {
                target = BlockPos.containing(hit.x, hit.y, hit.z);
                if (!mc.level.hasChunkAt(target)) continue;
                String blockName = mc.level.getBlockState(target).getBlock().getName().getString();
                addHit(target, blockName, hit.soundId, false, now);
            }
        }

        long maxAge = lifetime.get() * 50L;
        activeHits.removeIf(h -> now - h.time > maxAge);
    }

    private LivingEntity findNearestLiving(Vec3 pos, double radius) {
        AABB box = new AABB(pos.x - radius, pos.y - radius, pos.z - radius,
            pos.x + radius, pos.y + radius, pos.z + radius);
        LivingEntity nearest = null;
        double best = radius * radius;
        for (LivingEntity entity : mc.level.getEntitiesOfClass(LivingEntity.class, box)) {
            if (entity == mc.player) continue;
            double distance = entity.position().distanceToSqr(pos);
            if (distance < best) {
                best = distance;
                nearest = entity;
            }
        }
        return nearest;
    }

    private void addHit(BlockPos pos, String label, String soundId, boolean entity, long now) {
        // Suppress repeated identical sounds at the same position for 350 ms.
        for (SoundHitEntry existing : snapshotEntries()) {
            if (existing.pos.equals(pos) && now - existing.time < 350) return;
        }
        activeHits.add(new SoundHitEntry(pos.immutable(), label, soundId, entity, now));
        if (notifications.get()) {
            mc.getToastManager().addToast(new MeteorToast.Builder("Sound ESP")
                .text(label + " sound detected")
                .icon(entity ? Items.PLAYER_HEAD : Items.REDSTONE)
                .build());
        }
    }

    private List<SoundHitEntry> snapshotEntries() {
        return activeHits.stream().map(h -> new SoundHitEntry(h.pos, h.label, h.soundId, h.entity, h.time)).toList();
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null || mc.level == null) return;
        for (SoundHitEntry hit : activeHits) {
            Color color = new Color(hit.entity ? entityColor.get() : blockColor.get());
            event.renderer.box(hit.pos, color, color, shapeMode.get(), 0);
        }
    }

    private record SoundHit(double x, double y, double z, SoundSource source, String soundId, long time) {}
    private record SoundHitEntry(BlockPos pos, String label, String soundId, boolean entity, long time) {}
}
