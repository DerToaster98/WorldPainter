/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package org.pepsoft.worldpainter.exporting;

import org.pepsoft.minecraft.Chunk;
import org.pepsoft.minecraft.ChunkFactory;
import org.pepsoft.minecraft.Material;
import org.pepsoft.util.PerlinNoise;
import org.pepsoft.worldpainter.*;
import org.pepsoft.worldpainter.layers.*;
import org.pepsoft.worldpainter.objects.WPObject;
import org.pepsoft.worldpainter.plugins.BlockBasedPlatformProvider;
import org.pepsoft.worldpainter.plugins.PlatformManager;
import org.pepsoft.worldpainter.util.BiomeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.pepsoft.minecraft.ChunkFactory.Stage.TERRAIN_GENERATION;
import static org.pepsoft.minecraft.Constants.*;
import static org.pepsoft.minecraft.Material.*;
import static org.pepsoft.worldpainter.Constants.*;
import static org.pepsoft.worldpainter.Platform.Capability.*;
import static org.pepsoft.worldpainter.biomeschemes.Minecraft1_17Biomes.*;
import static org.pepsoft.worldpainter.layers.plants.Plants.SUGAR_CANE;
import static org.pepsoft.worldpainter.util.MathUtils.getLowest2D;

/**
 *
 * @author pepijn
 */
public class WorldPainterChunkFactory implements ChunkFactory {
    /**
     * Create a new {@code WorldPainterChunkFactory}.
     *
     * @param dimension           The dimension for which the factory should create chunks.
     * @param exporters           The phase one layer exporters the factory should apply to the created chunks.
     * @param platform            The platform for which the dimension is being exported.
     * @param maxHeightConstraint The height to which to constrain the created chunks. May be lower than the
     * {@code maxHeight} of the dimension, world or platform, e.g. for preview purposes.
     */
    public WorldPainterChunkFactory(Dimension dimension, Map<Layer, LayerExporter> exporters, Platform platform, int maxHeightConstraint) {
        this.dimension = dimension;
        this.exporters = exporters;
        this.platform  = platform;
        platformProvider = (BlockBasedPlatformProvider) PlatformManager.getInstance().getPlatformProvider(platform);
        this.minHeight = dimension.getMinHeight();
        this.maxHeight = Math.min(maxHeightConstraint, dimension.getMaxHeight());
        minimumLayers = dimension.getMinimumLayers();
        seed = dimension.getSeed();
        if (sugarCaneNoise.getSeed() != (seed + SUGAR_CANE_SEED_OFFSET)) {
            sugarCaneNoise.setSeed(seed + SUGAR_CANE_SEED_OFFSET);
        }
        subsurfaceMaterial = dimension.getSubsurfaceMaterial();
        roofType = dimension.getRoofType();
        bedrock = ! dimension.isBottomless();
        coverSteepTerrain = dimension.isCoverSteepTerrain();
        topLayersRelativeToTerrain = dimension.getTopLayerAnchor() == Dimension.LayerAnchor.TERRAIN;
        subSurfaceLayersRelativeToTerrain =
                subsurfaceMaterial.isCustom()
                        && (Terrain.getCustomMaterial(subsurfaceMaterial.getCustomTerrainIndex()).getMode() == MixedMaterial.Mode.LAYERED)
                        && (dimension.getSubsurfaceLayerAnchor() == Dimension.LayerAnchor.TERRAIN);
        subSurfacePatternHeight = subSurfaceLayersRelativeToTerrain ? Terrain.getCustomMaterial(subsurfaceMaterial.getCustomTerrainIndex()).getPatternHeight() : -1;
        maxY = this.maxHeight - 1;
        undergroundBiome = dimension.getUndergroundBiome();
        biomesSupported2D = platform.capabilities.contains(BIOMES);
        biomesSupported3D = platform.capabilities.contains(BIOMES_3D);
        biomesSupportedNamed = platform.capabilities.contains(NAMED_BIOMES);
        copyBiomes = (biomesSupported2D || biomesSupported3D || biomesSupportedNamed) && (! dimension.getAnchor().invert);
        // TODO [FLOATING] don't copy 2D biomes for floating dimensions
        switch (dimension.getAnchor().dim) {
            case DIM_NORMAL:
                defaultBiome = BIOME_PLAINS;
                break;
            case DIM_NETHER:
                defaultBiome = BIOME_HELL;
                break;
            case DIM_END:
                defaultBiome = BIOME_SKY;
                break;
            default:
                throw new InternalError();
        }
    }

    public int getMinHeight() {
        return minHeight;
    }

    @Override
    public int getMaxHeight() {
        return maxHeight;
    }

    @Override
    public ChunkCreationResult createChunk(int chunkX, int chunkZ) {
        Tile tile = dimension.getTile(chunkX >> 3, chunkZ >> 3);
        if (tile != null) {
            return createChunk(tile, chunkX, chunkZ);
        } else {
            return null;
        }
    }
    
    public ChunkCreationResult createChunk(Tile tile, int chunkX, int chunkZ) {
        if (tile.getBitLayerValue(ReadOnly.INSTANCE, (chunkX << 4) & TILE_SIZE_MASK, (chunkZ << 4) & TILE_SIZE_MASK)
                || tile.getBitLayerValue(NotPresent.INSTANCE, (chunkX << 4) & TILE_SIZE_MASK, (chunkZ << 4) & TILE_SIZE_MASK)) {
            // Chunk is marked as read-only or not present; don't export or
            // merge it
            return null;
        }

        final int tileX = tile.getX();
        final int tileY = tile.getY();
        final int xOffsetInTile = (chunkX & 7) << 4;
        final int yOffsetInTile = (chunkZ & 7) << 4;
        final Random random = new Random(seed + xOffsetInTile * 3 + yOffsetInTile * 5);
        final boolean populate = platform.capabilities.contains(POPULATE)
                && (dimension.isPopulate() || tile.getBitLayerValue(Populate.INSTANCE, xOffsetInTile, yOffsetInTile));
        final BiomeUtils biomeUtils = new BiomeUtils(dimension);
        final ChunkCreationResult result = new ChunkCreationResult();
        long start = System.nanoTime();
        final Chunk chunk = platformProvider.createChunk(platform, chunkX, chunkZ, minHeight, maxHeight);
        result.chunk = chunk;

        if (copyBiomes && (biomesSupported3D || biomesSupportedNamed)) {
            final int chunkXInWorld = (tileX << TILE_SIZE_BITS) | xOffsetInTile;
            final int chunkZInWorld = (tileY << TILE_SIZE_BITS) | yOffsetInTile;
            for (int x = 0; x < 16; x += 4) {
                for (int z = 0; z < 16; z += 4) {
                    final int biome = dimension.getMostPrevalentBiome((chunkXInWorld | x) >> 2, (chunkZInWorld | z) >> 2, defaultBiome);
                    if (undergroundBiome != null) {
                        final int lowestHeight = (getLowest2D(4, (dx, dy) -> {
                            final int height = dimension.getIntHeightAt(chunkXInWorld + dx, chunkZInWorld + dy);
                            return height - dimension.getTopLayerDepth(chunkXInWorld + dx, chunkZInWorld + dy, height);
                        }) / 4) * 4;
                        for (int y = minHeight; y < lowestHeight; y += 4) {
                            biomeUtils.set3DBiome(chunk, x >> 2, y >> 2, z >> 2, undergroundBiome);
                        }
                        for (int y = lowestHeight; y < maxHeight; y += 4) {
                            biomeUtils.set3DBiome(chunk, x >> 2, y >> 2, z >> 2, biome);
                        }
                    } else {
                        biomeUtils.set2DBiome(chunk, x, z, biome);
                    }
                }
            }
        }

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                final int xInTile = xOffsetInTile | x;
                final int yInTile = yOffsetInTile | z;
                final int worldX = (tileX << TILE_SIZE_BITS) | xInTile;
                final int worldY = (tileY << TILE_SIZE_BITS) | yInTile;

                if (copyBiomes && biomesSupported2D) {
                    int biome = dimension.getLayerValueAt(Biome.INSTANCE, worldX, worldY);
                    if (biome == 255) {
                        biome = dimension.getAutoBiome(tile, xInTile, yInTile);
                        if ((biome < 0) || (biome > 255)) {
                            biome = defaultBiome;
                        }
                    }
                    biomeUtils.set2DBiome(chunk, x, z, biome);
                }

                final int intHeight = tile.getIntHeight(xInTile, yInTile);
                final int waterLevel = tile.getWaterLevel(xInTile, yInTile);
                final boolean underWater = waterLevel > intHeight;
                final boolean _void = tile.getBitLayerValue(org.pepsoft.worldpainter.layers.Void.INSTANCE, xInTile, yInTile);
                if (! _void) {
                    final Terrain terrain = tile.getTerrain(xInTile, yInTile);
                    final boolean floodWithLava;
                    if (underWater) {
                        floodWithLava = tile.getBitLayerValue(FloodWithLava.INSTANCE, xInTile, yInTile);
                        result.stats.waterArea++;
                    } else {
                        floodWithLava = false;
                        result.stats.landArea++;
                    }
                    if (bedrock) {
                        chunk.setMaterial(x, minHeight, z, BEDROCK);
                    }
                    applySubSurface(tile, chunk, xInTile, yInTile, minHeight);
                    applyTopLayer(tile, chunk, xInTile, yInTile, minHeight, false);
                    if (! underWater) {
                        // Above the surface on dry land
                        WPObject object = null;
                        if (((terrain == Terrain.GRASS) || (terrain == Terrain.DESERT) || (terrain == Terrain.RED_DESERT) || (terrain == Terrain.BEACHES))
                                && ((sugarCaneNoise.getPerlinNoise(worldX / TINY_BLOBS, worldY / TINY_BLOBS, z / TINY_BLOBS) * sugarCaneNoise.getPerlinNoise(worldX / SMALL_BLOBS, worldY / SMALL_BLOBS, z / SMALL_BLOBS)) > SUGAR_CANE_CHANCE)
                                && (isAdjacentWater(tile, intHeight, xInTile - 1, yInTile)
                                    || isAdjacentWater(tile, intHeight, xInTile + 1, yInTile)
                                    || isAdjacentWater(tile, intHeight, xInTile, yInTile - 1)
                                    || isAdjacentWater(tile, intHeight, xInTile, yInTile + 1))) {
                            final int blockTypeBelow = chunk.getBlockType(x, intHeight, z);
                            if ((random.nextInt(5) > 0) && ((blockTypeBelow == BLK_GRASS) || (blockTypeBelow == BLK_DIRT) || (blockTypeBelow == BLK_SAND) || (blockTypeBelow == BLK_SUGAR_CANE))) {
                                object = SUGAR_CANE.realise(random.nextInt(3) + 1, platform);
                            }
                        }
                        if (object == null) {
                            object = terrain.getSurfaceObject(platform, seed, worldX, worldY, 0);
                        }
                        if (object != null) {
                            renderObject(chunk, object, x, intHeight + 1, z);
                        }
                    } else if (! floodWithLava) {
                        final WPObject object = terrain.getSurfaceObject(platform, seed, worldX, worldY, waterLevel - intHeight);
                        if (object != null) {
                            renderObject(chunk, object, x, intHeight + 1, z);
                        }
                    }
                }
                if (roofType != null) {
                    chunk.setMaterial(x, maxY, z, (roofType == Dimension.WallType.BEDROCK) ? BEDROCK : BARRIER);
                    chunk.setHeight(x, z, maxY);
                } else if (_void) {
                    chunk.setHeight(x, z, minHeight);
                } else if (underWater) {
                    chunk.setHeight(x, z, (waterLevel < maxY) ? (waterLevel + 1): maxY);
                } else {
                    chunk.setHeight(x, z, (intHeight < maxY) ? (intHeight + 1): maxY);
                }
            }
        }
        chunk.setTerrainPopulated(! populate);
        result.stats.timings.put(TERRAIN_GENERATION, new AtomicLong(System.nanoTime() - start));

        for (Layer layer: tile.getLayers(minimumLayers)) {
            LayerExporter layerExporter = exporters.get(layer);
            if (layerExporter instanceof FirstPassLayerExporter) {
                if (logger.isTraceEnabled()) {
                    logger.trace("Exporting layer {} for chunk {},{}", layer, chunkX, chunkZ);
                }
                start = System.nanoTime();
                ((FirstPassLayerExporter) layerExporter).render(tile, chunk);
                result.stats.timings.put(layer, new AtomicLong(System.nanoTime() - start));
            }
        }
        result.stats.surfaceArea = 256;
        return result;
    }

    public void applyTopLayer(Tile tile, Chunk chunk, int xInTile, int yInTile, int minZ, boolean onlyWhereSolid) {
        final Terrain terrain = tile.getTerrain(xInTile, yInTile);
        final int worldX = (tile.getX() << 7) | xInTile, worldY = (tile.getY() << 7) | yInTile, x = xInTile & 0xf, z = yInTile & 0xf;
        final float height = tile.getHeight(xInTile, yInTile);
        final int intHeight = Math.round(height), waterLevel = tile.getWaterLevel(xInTile, yInTile);
        final int topLayerDepth = dimension.getTopLayerDepth(worldX, worldY, intHeight);
        final boolean floodWithLava;
        final boolean underWater = waterLevel > intHeight;
        final int topLayerLayerOffset;
        if (topLayersRelativeToTerrain
                && terrain.isCustom()
                && (Terrain.getCustomMaterial(terrain.getCustomTerrainIndex()).getMode() == MixedMaterial.Mode.LAYERED)) {
            topLayerLayerOffset = -(intHeight - Terrain.getCustomMaterial(terrain.getCustomTerrainIndex()).getPatternHeight() + 1);
        } else {
            topLayerLayerOffset = 0;
        }
        if (underWater) {
            floodWithLava = tile.getBitLayerValue(FloodWithLava.INSTANCE, xInTile, yInTile);
        } else {
            floodWithLava = false;
        }
        int subsurfaceMaxHeight = intHeight - topLayerDepth;
        if (coverSteepTerrain) {
            subsurfaceMaxHeight = Math.min(subsurfaceMaxHeight,
                    Math.min(Math.min(dimension.getIntHeightAt(worldX - 1, worldY, Integer.MAX_VALUE),
                                    dimension.getIntHeightAt(worldX + 1, worldY, Integer.MAX_VALUE)),
                            Math.min(dimension.getIntHeightAt(worldX, worldY - 1, Integer.MAX_VALUE),
                                    dimension.getIntHeightAt(worldX, worldY + 1, Integer.MAX_VALUE))));
        }
        int columnRenderHeight = Math.min(Math.max(intHeight, waterLevel), maxY);
        for (int y = Math.max(Math.max(subsurfaceMaxHeight + 1, minHeight + (bedrock ? 1 : 0)), minZ); y <= columnRenderHeight; y++) {
            if (onlyWhereSolid && (! chunk.getMaterial(x, y, z).solid)) {
                continue;
            }
            if (y < intHeight) {
                // Top/terrain layer, but not surface block
                chunk.setMaterial(x, y, z, terrain.getMaterial(platform, seed, worldX, worldY, y + topLayerLayerOffset, intHeight + topLayerLayerOffset));
            } else if (y == intHeight) {
                // Surface block
                final Material material;
                if (topLayerLayerOffset != 0) {
                    material = terrain.getMaterial(platform, seed, worldX, worldY, intHeight + topLayerLayerOffset, intHeight + topLayerLayerOffset);
                } else {
                    // Use floating point height here to make sure
                    // undulations caused by layer variation settings/
                    // blobs, etc. look continuous on the surface
                    material = terrain.getMaterial(platform, seed, worldX, worldY, height + topLayerLayerOffset, intHeight + topLayerLayerOffset);
                }
                final int blockType = material.blockType;
                if (((blockType == BLK_WOODEN_SLAB) || (blockType == BLK_SLAB) || (blockType == BLK_RED_SANDSTONE_SLAB)) && (! underWater) && (height > intHeight)) {
                    chunk.setMaterial(x, y, z, Material.get(blockType - 1, material.data));
                } else {
                    chunk.setMaterial(x, y, z, material);
                }
            } else if (y <= waterLevel) {
                // Above the surface but below the water/lava level
                if (floodWithLava) {
                    chunk.setMaterial(x, y, z, STATIONARY_LAVA);
                } else {
                    chunk.setMaterial(x, y, z, STATIONARY_WATER);
                }
            }
        }
    }

    public void applySubSurface(Tile tile, Chunk chunk, int xInTile, int yInTile, int minZ) {
        final int worldX = (tile.getX() << 7) | xInTile, worldY = (tile.getY() << 7) | yInTile, x = xInTile & 0xf, z = yInTile & 0xf;
        final int intHeight = tile.getIntHeight(xInTile, yInTile);
        final int topLayerDepth = dimension.getTopLayerDepth(worldX, worldY, intHeight);
        final int subSurfaceLayerOffset = subSurfaceLayersRelativeToTerrain ? -(intHeight - subSurfacePatternHeight + 1) : 0;
        int subsurfaceMaxHeight = intHeight - topLayerDepth;
        if (coverSteepTerrain) {
            subsurfaceMaxHeight = Math.min(subsurfaceMaxHeight,
                    Math.min(Math.min(dimension.getIntHeightAt(worldX - 1, worldY, Integer.MAX_VALUE),
                                    dimension.getIntHeightAt(worldX + 1, worldY, Integer.MAX_VALUE)),
                            Math.min(dimension.getIntHeightAt(worldX, worldY - 1, Integer.MAX_VALUE),
                                    dimension.getIntHeightAt(worldX, worldY + 1, Integer.MAX_VALUE))));
        }
        for (int y = Math.max(minHeight + (bedrock ? 1 : 0), minZ); y <= subsurfaceMaxHeight; y++) {
            // Sub surface
            chunk.setMaterial(x, y, z, subsurfaceMaterial.getMaterial(platform, seed, worldX, worldY, y + subSurfaceLayerOffset, intHeight + subSurfaceLayerOffset));
        }
    }

    public void renderObject(Chunk chunk, WPObject object, int x, int y, int z) {
        final int height = object.getDimensions().z;
        if ((y < minHeight) || (y + height >= maxHeight)) {
            return;
        }
        for (int dy = 0; dy < height; dy++) {
            if (((y + dy) < minHeight) || ((y + dy) >= maxHeight)) {
                continue;
            }
            if (object.getMask(0, 0, dy)) {
                final Material objectMaterial = object.getMaterial(0, 0, dy);
                final Material existingMaterial = chunk.getMaterial(x, y + dy, z);
                if (existingMaterial.isNamed(MC_WATER)) {
                    if (objectMaterial.containsWater()) {
                        chunk.setMaterial(x, y + dy, z, objectMaterial);
                    } else if (objectMaterial.hasProperty(WATERLOGGED)) {
                        chunk.setMaterial(x, y + dy, z, objectMaterial.withProperty(WATERLOGGED, true));
                    }
                } else {
                    chunk.setMaterial(x, y + dy, z, objectMaterial);
                }
            }
        }
    }

    public boolean isAdjacentWater(Tile tile, int height, int x, int y) {
        if ((x < 0) || (x >= TILE_SIZE) || (y < 0) || (y >= TILE_SIZE)) {
            return false;
        }
        return (tile.getWaterLevel(x, y) == height)
                && (! tile.getBitLayerValue(FloodWithLava.INSTANCE, x, y))
                && (! tile.getBitLayerValue(Frost.INSTANCE, x, y))
                && (tile.getIntHeight(x, y) < height);
    }

    private final Platform platform;
    private final BlockBasedPlatformProvider platformProvider;
    private final int minHeight, maxHeight, subSurfacePatternHeight, maxY, defaultBiome;
    private final Dimension dimension;
    private final Set<Layer> minimumLayers;
    private final PerlinNoise sugarCaneNoise = new PerlinNoise(0);
    private final Map<Layer, LayerExporter> exporters;
    private final long seed;
    private final Terrain subsurfaceMaterial;
    private final boolean bedrock, coverSteepTerrain, topLayersRelativeToTerrain, subSurfaceLayersRelativeToTerrain, biomesSupported2D, biomesSupported3D, biomesSupportedNamed, copyBiomes;
    private final Dimension.WallType roofType;
    private final Integer undergroundBiome;

    public static final long SUGAR_CANE_SEED_OFFSET = 127411424;
    public static final float SUGAR_CANE_CHANCE = PerlinNoise.getLevelForPromillage(325);

    private static final Logger logger = LoggerFactory.getLogger(WorldPainterChunkFactory.class);
}