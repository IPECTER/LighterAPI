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

import ca.spottedleaf.concurrentutil.executor.standard.PrioritisedExecutor;
import ca.spottedleaf.starlight.common.light.BlockStarLightEngine;
import ca.spottedleaf.starlight.common.light.SkyStarLightEngine;
import ca.spottedleaf.starlight.common.light.StarLightEngine;
import ca.spottedleaf.starlight.common.light.StarLightInterface;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.util.thread.ProcessorMailbox;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.bukkit.World;
import org.bukkit.craftbukkit.v1_20_R1.CraftWorld;
import ru.beykerykt.minecraft.lightapi.bukkit.internal.BukkitPlatformImpl;
import ru.beykerykt.minecraft.lightapi.common.api.ResultCode;
import ru.beykerykt.minecraft.lightapi.common.api.engine.LightFlag;
import ru.beykerykt.minecraft.lightapi.common.internal.engine.LightEngineType;
import ru.beykerykt.minecraft.lightapi.common.internal.utils.FlagUtils;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

public class StarlightNMSHandler extends VanillaNMSHandler {

    private final int ALL_DIRECTIONS_BITSET = (1 << 6) - 1;
    private final long FLAG_HAS_SIDED_TRANSPARENT_BLOCKS = Long.MIN_VALUE;
    private final Map<ChunkPos, Set<LightPos>> blockQueueMap = new ConcurrentHashMap<>();
    private final Map<ChunkPos, Set<LightPos>> skyQueueMap = new ConcurrentHashMap<>();
    // StarLightInterface
    private Field starInterface;
    private Field starInterface_coordinateOffset;
    private Method starInterface_getBlockLightEngine;
    private Method starInterface_getSkyLightEngine;
    // StarLightEngine
    private Method starEngine_setLightLevel;
    private Method starEngine_appendToIncreaseQueue;
    private Method starEngine_appendToDecreaseQueue;
    private Method starEngine_performLightIncrease;
    private Method starEngine_performLightDecrease;
    private Method starEngine_updateVisible;
    private Method starEngine_setupCaches;
    private Method starEngine_destroyCaches;
    private boolean isStarLightMod = false;

    @Override
    public void onEnable() {
        super.onEnable();

        BukkitPlatformImpl impl = (BukkitPlatformImpl) getPlatform();

        try {
            starInterface = ThreadedLevelLightEngine.class.getDeclaredField("starLightInterface");
            starInterface.setAccessible(true);

            starInterface_coordinateOffset = StarLightInterface.class.getDeclaredField("coordinateOffset");
            starInterface_coordinateOffset.setAccessible(true);

            starInterface_getBlockLightEngine = StarLightInterface.class.getDeclaredMethod(
                    "getBlockLightEngine");
            starInterface_getBlockLightEngine.setAccessible(true);

            starInterface_getSkyLightEngine = StarLightInterface.class.getDeclaredMethod(
                    "getSkyLightEngine");
            starInterface_getSkyLightEngine.setAccessible(true);

            // StarLightEngine methods
            starEngine_setLightLevel = StarLightEngine.class.getDeclaredMethod("setLightLevel",
                    int.class);
            starEngine_setLightLevel.setAccessible(true);

            starEngine_appendToIncreaseQueue = StarLightEngine.class.getDeclaredMethod(
                    "appendToIncreaseQueue", long.class);
            starEngine_appendToIncreaseQueue.setAccessible(true);

            starEngine_appendToDecreaseQueue = StarLightEngine.class.getDeclaredMethod(
                    "appendToDecreaseQueue", long.class);
            starEngine_appendToDecreaseQueue.setAccessible(true);

            starEngine_performLightIncrease = StarLightEngine.class.getDeclaredMethod(
                    "performLightIncrease", LightChunkGetter.class);
            starEngine_performLightIncrease.setAccessible(true);

            starEngine_performLightDecrease = StarLightEngine.class.getDeclaredMethod(
                    "performLightDecrease", LightChunkGetter.class, BooleanSupplier.class);
            starEngine_performLightDecrease.setAccessible(true);

            starEngine_updateVisible = StarLightEngine.class.getDeclaredMethod("updateVisible");
            starEngine_updateVisible.setAccessible(true);

            starEngine_setupCaches = StarLightEngine.class.getDeclaredMethod("setupCaches");
            starEngine_setupCaches.setAccessible(true);

            starEngine_destroyCaches = StarLightEngine.class.getDeclaredMethod("destroyCaches");
            starEngine_destroyCaches.setAccessible(true);

            isStarLightMod = true;
            impl.debug("StarlightNMSHandler initialized for v1_20_R1");
        } catch (NoSuchFieldException | NoSuchMethodException e) {
            isStarLightMod = false;
            impl.debug("Starlight not available, falling back to vanilla handler");
        }
    }

    @Override
    public int setLightLevel(String worldName, int blockX, int blockY, int blockZ, int lightLevel, int lightType) {
        if (!isStarLightMod) {
            return super.setLightLevel(worldName, blockX, blockY, blockZ, lightLevel, lightType);
        }

        if (!isWorldAvailable(worldName)) {
            return ResultCode.WORLD_NOT_AVAILABLE;
        }

        if (lightLevel < 0 || lightLevel > 15) {
            return ResultCode.FAILED;
        }

        // Starlight-specific implementation would go here
        // For now, fallback to vanilla
        return super.setLightLevel(worldName, blockX, blockY, blockZ, lightLevel, lightType);
    }

    @Override
    public int getLightLevel(String worldName, int blockX, int blockY, int blockZ, int lightType) {
        if (!isStarLightMod) {
            return super.getLightLevel(worldName, blockX, blockY, blockZ, lightType);
        }

        // Starlight-specific implementation would go here
        // For now, fallback to vanilla
        return super.getLightLevel(worldName, blockX, blockY, blockZ, lightType);
    }
}
