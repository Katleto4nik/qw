package com.gabdab.weboptimizer;

import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Movement prediction of other players.
 *
 * - keeps a short position history for every player (one entry per tick)
 * - estimates velocity + acceleration from that history
 * - simulates the next ticks: friction-free ground movement, fading acceleration,
 *   walls, step-ups, walking off edges, and real jump/fall physics (gravity 0.08, drag 0.98)
 *   with landing on the real floor of the world
 */
public final class Predictor {
    private static final int HIST = 6;
    private static final double GRAVITY = 0.08;
    private static final double AIR_DRAG = 0.98;

    /** path[i] = predicted feet position after i ticks, landStep = tick of landing (-1 = none) */
    public record Prediction(Vec3d[] path, int landStep) {}

    private static final class Track {
        final Vec3d[] h = new Vec3d[HIST];
        int n = 0;

        void push(Vec3d p) {
            if (n > 0 && p.squaredDistanceTo(h[0]) > 16.0) n = 0; // teleport -> forget history
            for (int i = HIST - 1; i > 0; i--) h[i] = h[i - 1];
            h[0] = p;
            if (n < HIST) n++;
        }
    }

    private static final Map<UUID, Track> TRACKS = new HashMap<>();

    private Predictor() {}

    public static void clear() {
        TRACKS.clear();
    }

    /** call once per tick */
    public static void update(ClientWorld world) {
        Set<UUID> seen = new HashSet<>();
        for (PlayerEntity p : world.getPlayers()) {
            UUID id = p.getUuid();
            seen.add(id);
            TRACKS.computeIfAbsent(id, k -> new Track()).push(new Vec3d(p.getX(), p.getY(), p.getZ()));
        }
        TRACKS.keySet().retainAll(seen);
    }

    public static Prediction predict(ClientWorld world, PlayerEntity e, int steps) {
        Track t = TRACKS.get(e.getUuid());
        Vec3d cur = new Vec3d(e.getX(), e.getY(), e.getZ());

        double vx = 0, vz = 0, vy = 0, ax = 0, az = 0;
        if (t != null && t.n >= 2) {
            Vec3d v1 = t.h[0].subtract(t.h[1]);
            Vec3d v2 = t.n > 2 ? t.h[1].subtract(t.h[2]) : v1;
            Vec3d v3 = t.n > 3 ? t.h[2].subtract(t.h[3]) : v2;
            vx = v1.x * 0.5 + v2.x * 0.3 + v3.x * 0.2;
            vz = v1.z * 0.5 + v2.z * 0.3 + v3.z * 0.2;
            vy = v1.y;
            ax = MathHelper.clamp((v1.x - v3.x) * 0.5, -0.04, 0.04);
            az = MathHelper.clamp((v1.z - v3.z) * 0.5, -0.04, 0.04);
        }
        if (Math.sqrt(vx * vx + vz * vz) < 0.012) { // standing still
            vx = 0;
            vz = 0;
            ax = 0;
            az = 0;
        }

        final double v0x = vx, v0z = vz;
        double x = cur.x, y = cur.y, z = cur.z;
        boolean ground = e.isOnGround();
        int landStep = -1;

        Vec3d[] path = new Vec3d[steps + 1];
        path[0] = cur;

        for (int i = 1; i <= steps; i++) {
            // horizontal: acceleration fades out, direction can't flip by itself
            ax *= 0.7;
            az *= 0.7;
            vx += ax;
            vz += az;
            if (vx * v0x + vz * v0z < 0) {
                vx = 0;
                vz = 0;
                ax = 0;
                az = 0;
            }

            double nx = x + vx;
            double nz = z + vz;
            if (blocked(world, nx, y, nz)) { // wall
                vx = 0;
                vz = 0;
                nx = x;
                nz = z;
            }

            double ny = y;
            if (!ground) {
                vy = (vy - GRAVITY) * AIR_DRAG;
                ny = y + vy;
                double floor = groundTop(world, nx, nz, y + 0.6);
                if (vy <= 0 && ny <= floor) { // landing
                    ny = floor;
                    ground = true;
                    vy = 0;
                    if (landStep < 0) landStep = i;
                }
            } else {
                double floor = groundTop(world, nx, nz, y + 0.6);
                if (floor > y + 1e-6 && floor <= y + 0.6) {
                    ny = floor;           // small step / stairs / slab
                } else if (floor < y - 0.6) {
                    ground = false;       // walked off an edge
                    vy = 0;
                }
            }

            x = nx;
            y = ny;
            z = nz;
            path[i] = new Vec3d(x, y, z);
        }
        return new Prediction(path, landStep);
    }

    /** top of the highest collidable block at/below fromY in this column */
    private static double groundTop(ClientWorld world, double x, double z, double fromY) {
        int bx = MathHelper.floor(x);
        int bz = MathHelper.floor(z);
        int top = MathHelper.floor(fromY);
        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (int yy = top; yy >= top - 40 && yy >= world.getBottomY(); yy--) {
            pos.set(bx, yy, bz);
            BlockState state = world.getBlockState(pos);
            VoxelShape shape = state.getCollisionShape(world, pos);
            if (!shape.isEmpty()) return yy + shape.getMax(Direction.Axis.Y);
        }
        return -1000.0;
    }

    /** a block in front of the feet that is higher than a step (0.6) */
    private static boolean blocked(ClientWorld world, double x, double y, double z) {
        BlockPos.Mutable pos = new BlockPos.Mutable();
        pos.set(MathHelper.floor(x), MathHelper.floor(y + 0.1), MathHelper.floor(z));
        BlockState state = world.getBlockState(pos);
        VoxelShape shape = state.getCollisionShape(world, pos);
        if (shape.isEmpty()) return false;
        return pos.getY() + shape.getMax(Direction.Axis.Y) > y + 0.6;
    }
}
