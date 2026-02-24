/*
 * The MIT License (MIT)
 *
 * Copyright 2023 Vladimir Mikhailov <beykerykt@gmail.com>
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package ru.beykerykt.minecraft.lightapi.bukkit.internal.handler.craftbukkit.nms.v1_20_R1;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.util.thread.ProcessorMailbox;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.*;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.craftbukkit.v1_20_R1.CraftWorld;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import ru.beykerykt.minecraft.lightapi.bukkit.internal.BukkitPlatformImpl;
import ru.beykerykt.minecraft.lightapi.bukkit.internal.handler.craftbukkit.nms.BaseNMSHandler;
import ru.beykerykt.minecraft.lightapi.common.api.ResultCode;
import ru.beykerykt.minecraft.lightapi.common.api.engine.LightFlag;
import ru.beykerykt.minecraft.lightapi.common.internal.chunks.data.BitChunkData;
import ru.beykerykt.minecraft.lightapi.common.internal.chunks.data.IChunkData;
import ru.beykerykt.minecraft.lightapi.common.internal.engine.LightEngineType;
import ru.beykerykt.minecraft.lightapi.common.internal.engine.LightEngineVersion;
import ru.beykerykt.minecraft.lightapi.common.internal.utils.FlagUtils;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

public class VanillaNMSHandler extends BaseNMSHandler {

    protected Field lightEngine_ThreadedMailbox;
    protected boolean isPaperRCS = false;
    private Field threadedMailbox_State;
    private Method threadedMailbox_DoLoopStep;
    private Field lightEngineLayer_d;
    private Method lightEngineStorage_d;
    private Method lightEngineGraph_a;

    private int getDeltaLight(int x, int dx) {
        return (((x ^ ((-dx >> 4) & 15)) + 1) & (-(dx & 1)));
    }

    protected void executeSync(ThreadedLevelLightEngine lightEngine, Runnable task) {
        try {
            // ##### STEP 1: Pause light engine mailbox to process its tasks. #####
            ProcessorMailbox<Runnable> threadedMailbox = (ProcessorMailbox<Runnable>) lightEngine_ThreadedMailbox.get(
                    lightEngine);
            // State flags bit mask:
            // 0x0001 - Closing flag (ThreadedMailbox is closing if non zero).
            // 0x0002 - Busy flag (ThreadedMailbox performs a task from queue if non zero).
            AtomicInteger stateFlags = (AtomicInteger) threadedMailbox_State.get(threadedMailbox);
            int flags; // to hold values from stateFlags
            long timeToWait = -1;
            // Trying to set bit 1 in state bit mask when it is not set yet.
            // This will break the loop in other thread where light engine mailbox processes the taks.
            while (!stateFlags.compareAndSet(flags = stateFlags.get() & ~2, flags | 2)) {
                if ((flags & 1) != 0) {
                    // ThreadedMailbox is closing. The light engine mailbox may also stop processing tasks.
                    // The light engine mailbox can be close due to server shutdown or unloading (closing) the
                    // world.
                    // I am not sure is it unsafe to process our tasks while the world is closing is closing,
                    // but will try it (one can throw exception here if it crashes the server).
                    return;
                }
            }

            try {
                // ##### STEP 2: Process tasks in the light engine mailbox queue. #####
                ((Runnable) threadedMailbox_DoLoopStep.invoke(threadedMailbox)).run();
            } catch (IllegalAccessException | InvocationTargetException e) {
                // Something went wrong with reflection. It is possible that new version of server has
                // different implementation.
                return;
            } finally {
                // ##### STEP 3: Restore the state of the light engine mailbox. #####
                stateFlags.addAndGet(-2);
            }

            task.run();
        } catch (IllegalAccessException e) {
            // Something went wrong with reflection. It is possible that new version of server has
            // different implementation.
        }
    }

    @Override
    public boolean isWorldAvailable(String worldName) {
        return Bukkit.getWorld(worldName) != null;
    }

    @Override
    public void onEnable() {
        BukkitPlatformImpl impl = (BukkitPlatformImpl) getPlatform();

        try {
            // Detect light engine type
            lightEngine_ThreadedMailbox = ThreadedLevelLightEngine.class.getDeclaredField("mailbox");
            lightEngine_ThreadedMailbox.setAccessible(true);

            threadedMailbox_State = ProcessorMailbox.class.getDeclaredField("state");
            threadedMailbox_State.setAccessible(true);

            threadedMailbox_DoLoopStep = ProcessorMailbox.class.getDeclaredMethod("doLoopStep");
            threadedMailbox_DoLoopStep.setAccessible(true);

            // Check for Paper RCS (Regional Compression Scheduler)
            try {
                ServerLevel.class.getDeclaredField("chunkTaskScheduler");
                isPaperRCS = true;
            } catch (NoSuchFieldException e) {
                isPaperRCS = false;
            }

            impl.debug("VanillaNMSHandler initialized for v1_20_R1 (isPaperRCS: " + isPaperRCS + ")");
        } catch (NoSuchFieldException | NoSuchMethodException e) {
            impl.warn("Failed to initialize VanillaNMSHandler for v1_20_R1");
            e.printStackTrace();
        }
    }

    @Override
    public void onDisable() {
    }

    @Override
    public void onWorldLoad(WorldLoadEvent event) {
        // No implementation needed
    }

    @Override
    public void onWorldUnload(WorldUnloadEvent event) {
        // No implementation needed
    }

    @Override
    public int setLightLevel(String worldName, int blockX, int blockY, int blockZ, int lightLevel, int lightType) {
        if (!isWorldAvailable(worldName)) {
            return ResultCode.WORLD_NOT_AVAILABLE;
        }

        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return ResultCode.WORLD_NOT_AVAILABLE;
        }

        ServerLevel serverLevel = ((CraftWorld) world).getHandle();
        BlockPos blockPos = new BlockPos(blockX, blockY, blockZ);

        if (lightLevel < 0 || lightLevel > 15) {
            return ResultCode.FAILED;
        }

        try {
            ThreadedLevelLightEngine lightEngine = serverLevel.getChunkSource().getLightEngine();
            
            if (FlagUtils.isFlagSet(lightType, LightFlag.BLOCK_LIGHTING)) {
                lightEngine.setBlockLight(blockPos, lightLevel);
            }
            if (FlagUtils.isFlagSet(lightType, LightFlag.SKY_LIGHTING)) {
                lightEngine.setSkyLight(blockPos, lightLevel);
            }

            return ResultCode.SUCCESS;
        } catch (Exception e) {
            return ResultCode.FAILED;
        }
    }

    @Override
    public int getLightLevel(String worldName, int blockX, int blockY, int blockZ, int lightType) {
        if (!isWorldAvailable(worldName)) {
            return ResultCode.WORLD_NOT_AVAILABLE;
        }

        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return ResultCode.WORLD_NOT_AVAILABLE;
        }

        ServerLevel serverLevel = ((CraftWorld) world).getHandle();
        BlockPos blockPos = new BlockPos(blockX, blockY, blockZ);

        try {
            ThreadedLevelLightEngine lightEngine = serverLevel.getChunkSource().getLightEngine();
            
            if (FlagUtils.isFlagSet(lightType, LightFlag.BLOCK_LIGHTING)) {
                return lightEngine.getBlockLight(blockPos);
            }
            if (FlagUtils.isFlagSet(lightType, LightFlag.SKY_LIGHTING)) {
                return lightEngine.getSkyLight(blockPos);
            }
        } catch (Exception e) {
            // Return default value
        }

        return 0;
    }
}
