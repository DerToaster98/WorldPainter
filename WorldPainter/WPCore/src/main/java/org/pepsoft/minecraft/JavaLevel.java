/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package org.pepsoft.minecraft;

import org.jnbt.*;
import org.pepsoft.minecraft.datapack.DataPack;
import org.pepsoft.minecraft.datapack.Descriptor;
import org.pepsoft.minecraft.datapack.Dimension;
import org.pepsoft.minecraft.datapack.Meta;
import org.pepsoft.util.mdc.MDCCapturingRuntimeException;
import org.pepsoft.worldpainter.AccessDeniedException;
import org.pepsoft.worldpainter.Platform;
import org.pepsoft.worldpainter.Version;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.util.Arrays.asList;
import static org.pepsoft.minecraft.Constants.*;
import static org.pepsoft.worldpainter.Constants.*;
import static org.pepsoft.worldpainter.DefaultPlugin.*;

/**
 *
 * @author pepijn
 */
public abstract class JavaLevel extends AbstractNBTItem {
    protected JavaLevel(int minHeight, int maxHeight, Platform platform) {
        super(new CompoundTag(TAG_DATA, new HashMap<>()));
        this.platform = platform;
        if (! DEFAULT_JAVA_PLATFORMS.contains(platform)) {
            throw new IllegalArgumentException("Not a supported platform: " + platform);
        }
        if (maxHeight != ((platform == JAVA_MCREGION) ? DEFAULT_MAX_HEIGHT_MCREGION : DEFAULT_MAX_HEIGHT_ANVIL)) {
            setInt(TAG_MAP_HEIGHT, maxHeight);
        }
        this.minHeight = minHeight;
        this.maxHeight = maxHeight;
        extraTags = null;
        setInt(TAG_VERSION_, (platform == JAVA_MCREGION) ? VERSION_MCREGION : VERSION_ANVIL);
        final Integer dataVersion = platform.getAttribute(ATTRIBUTE_EXPORT_DATA_VERSION);
        if (dataVersion != null) {
            setInt(TAG_DATA_VERSION, dataVersion);
            Map<String, Tag> versionTag = new HashMap<>();
            versionTag.put(TAG_ID, new IntTag(TAG_ID, dataVersion));
            versionTag.put(TAG_NAME, new StringTag(TAG_NAME, "WorldPainter"));
            versionTag.put(TAG_SNAPSHOT, new ByteTag(TAG_SNAPSHOT, (byte) (Version.isSnapshot() ? 1 : 0)));
            setMap(TAG_VERSION, versionTag);
        }
    }

    protected JavaLevel(CompoundTag tag, int minHeight, int maxHeight) {
        super((CompoundTag) tag.getTag(TAG_DATA));
        final int version = getInt(TAG_VERSION_);
        if ((version != VERSION_UNKNOWN) && (version != VERSION_MCREGION) && (version != VERSION_ANVIL)) {
            // TODO refactor support for non-vanilla level.dat files (VERSION_UNKNOWN) out of here
            throw new IllegalArgumentException("Not a supported version: 0x" + Integer.toHexString(version));
        }
        if (maxHeight != ((version == VERSION_MCREGION) ? DEFAULT_MAX_HEIGHT_MCREGION : DEFAULT_MAX_HEIGHT_ANVIL)) {
            setInt(TAG_MAP_HEIGHT, maxHeight);
        }
        this.minHeight = minHeight;
        this.maxHeight = maxHeight;
        if (tag.getValue().size() == 1) {
            // No extra tags
            extraTags = null;
        } else {
            // The root tag contains extra tags, most likely from mods. Preserve them (but filter out the data tag)
            extraTags = new HashSet<>();
            tag.getValue().values().stream()
                    .filter(extraTag -> ! extraTag.getName().equals(TAG_DATA))
                    .forEach(extraTags::add);
        }
        if (version == VERSION_MCREGION) {
            platform = JAVA_MCREGION;
        } else {
            final int dataVersion = getInt(TAG_DATA_VERSION);
            for (Platform platform: DEFAULT_JAVA_PLATFORMS) {
                final Integer platformLatestDataVersion = platform.getAttribute(ATTRIBUTE_LATEST_DATA_VERSION);
                if ((platformLatestDataVersion != null) && (dataVersion <= platformLatestDataVersion)) {
                    this.platform = platform;
                    return;
                }
            }
            this.platform = DEFAULT_JAVA_PLATFORMS.get(DEFAULT_JAVA_PLATFORMS.size() - 1);
        }
    }
    
    public void save(File worldDir) throws IOException {
        if (! worldDir.exists()) {
            if (! worldDir.mkdirs()) {
                throw new AccessDeniedException("Could not create directory " + worldDir);
            }
        }

        // Write session.lock file
        File sessionLockFile = new File(worldDir, "session.lock");
        try (DataOutputStream sessionOut = new DataOutputStream(new FileOutputStream(sessionLockFile))) {
            sessionOut.writeLong(System.currentTimeMillis());
        }

        // Create and enable datapack(s) if necessary
        final int version = getVersion();
        int dataVersion = getInt(TAG_DATA_VERSION);
        if (dataVersion == 0) {
            CompoundTag versionTag = (CompoundTag) getTag(TAG_VERSION);
            if (versionTag != null) {
                dataVersion = ((IntTag) versionTag.getTag(TAG_ID)).getValue();
            }
        }
        if (version == VERSION_ANVIL) {
            if (((dataVersion > DATA_VERSION_MC_1_14_4) && (dataVersion <= DATA_VERSION_MC_1_17_1) && (maxHeight != DEFAULT_MAX_HEIGHT_ANVIL))
                    || ((dataVersion > DATA_VERSION_MC_1_17_1) && ((minHeight != DEFAULT_MIN_HEIGHT_1_18) || (maxHeight != DEFAULT_MAX_HEIGHT_1_18)))) {
                createWorldPainterDataPack(worldDir);
            }
        }

        // Write level.dat file
        File levelDatFile = new File(worldDir, "level.dat");
        // Make it show at the top of the single player map list:
        setLong(TAG_LAST_PLAYED, System.currentTimeMillis());
        try (NBTOutputStream out = new NBTOutputStream(new GZIPOutputStream(new FileOutputStream(levelDatFile)))) {
            out.writeTag(toNBT());
        }
        
        // If height is non-standard, write DynamicHeight mod and Height Mod
        // files
        if ((version == VERSION_MCREGION) && (maxHeight != DEFAULT_MAX_HEIGHT_MCREGION)) {
            int exp = (int) (Math.log(maxHeight) / Math.log(2));
            PrintWriter writer = new PrintWriter(new File(worldDir, "maxheight.txt"), "US-ASCII");
            try {
                writer.println("#DynamicHeight Save Format 2");
                writer.println("#" + new Date());
                writer.println("height=" + exp);
            } finally {
                writer.close();
            }

            writer = new PrintWriter(new File(worldDir, "Height.txt"), "US-ASCII");
            try {
                writer.println("#HeightMod 1.5");
                writer.println("#" + new Date());
                writer.println("height=" + exp);
                writer.println("version=1");
                writer.println("midheight=" + maxHeight + ".0");
                writer.println("waterlevel=" + (maxHeight / 2 - 2) + ".0");
                writer.println("genheight=" + maxHeight + ".0");
            } finally {
                writer.close();
            }
        }
    }

    public String getName() {
        return getString(TAG_LEVEL_NAME);
    }

    public abstract long getSeed();

    public int getSpawnX() {
        return getInt(TAG_SPAWN_X);
    }

    public int getSpawnY() {
        return getInt(TAG_SPAWN_Y);
    }

    public int getSpawnZ() {
        return getInt(TAG_SPAWN_Z);
    }

    public long getTime() {
        return getLong(TAG_TIME);
    }
    
    public int getVersion() {
        return getInt(TAG_VERSION_);
    }

    /**
     * 1.12.2: 1343
     * 18w11a: 1478
     */
    public int getDataVersion() {
        return getInt(TAG_DATA_VERSION);
    }

    public abstract boolean isMapFeatures();

    public int getMapHeight() {
        return getInt(TAG_MAP_HEIGHT);
    }
    
    public int getGameType() {
        return getInt(TAG_GAME_TYPE);
    }
    
    public boolean isHardcore() {
        return getBoolean(TAG_HARDCORE_);
    }
    
    public abstract MapGenerator getGenerator(int dim);

    public boolean isAllowCommands() {
        return getBoolean(TAG_ALLOW_COMMANDS_);
    }

    public int getMinHeight() {
        return minHeight;
    }

    public int getMaxHeight() {
        return maxHeight;
    }
    
    public int getDifficulty() {
        return getByte(TAG_DIFFICULTY);
    }
    
    public boolean isDifficultyLocked() {
        return getBoolean(TAG_DIFFICULTY_LOCKED);
    }
    
    public double getBorderCenterX() {
        return getDouble(TAG_BORDER_CENTER_X);
    }
    
    public double getBorderCenterZ() {
        return getDouble(TAG_BORDER_CENTER_Z);
    }
    
    public double getBorderSize() {
        return getDouble(TAG_BORDER_SIZE);
    }
    
    public double getBorderSafeZone() {
        return getDouble(TAG_BORDER_SAFE_ZONE);
    }
    
    public double getBorderWarningBlocks() {
        return getDouble(TAG_BORDER_WARNING_BLOCKS);
    }
    
    public double getBorderWarningTime() {
        return getDouble(TAG_BORDER_WARNING_TIME);
    }
    
    public double getBorderSizeLerpTarget() {
        return getDouble(TAG_BORDER_SIZE_LERP_TARGET);
    }
    
    public long getBorderSizeLerpTime() {
        return getLong(TAG_BORDER_SIZE_LERP_TIME);
    }
    
    public double getBorderDamagePerBlock() {
        return getDouble(TAG_BORDER_DAMAGE_PER_BLOCK);
    }

    public void setName(String name) {
        setString(TAG_LEVEL_NAME, name);
    }

    public abstract void setSeed(long seed);

    public void setSpawnX(int spawnX) {
        setInt(TAG_SPAWN_X, spawnX);
    }

    public void setSpawnY(int spawnY) {
        setInt(TAG_SPAWN_Y, spawnY);
    }

    public void setSpawnZ(int spawnZ) {
        setInt(TAG_SPAWN_Z, spawnZ);
    }

    public void setTime(long time) {
        setLong(TAG_TIME, time);
    }
    
    public abstract void setMapFeatures(boolean mapFeatures);
    
    public void setGameType(int gameType) {
        setInt(TAG_GAME_TYPE, gameType);
    }
    
    public void setHardcore(boolean hardcore) {
        setBoolean(TAG_HARDCORE_, hardcore);
    }

    public abstract void setGenerator(int dim, MapGenerator generator);

    public void setAllowCommands(boolean allowCommands) {
        setBoolean(TAG_ALLOW_COMMANDS_, allowCommands);
    }
    
    public void setDifficulty(int difficulty) {
        setByte(TAG_DIFFICULTY, (byte) difficulty);
    }
    
    public void setDifficultyLocked(boolean difficultyLocked) {
        setBoolean(TAG_DIFFICULTY_LOCKED, difficultyLocked);
    }
    
    public void setBorderCenterX(double borderCenterX) {
        setDouble(TAG_BORDER_CENTER_X, borderCenterX);
    }
    
    public void setBorderCenterZ(double borderCenterZ) {
        setDouble(TAG_BORDER_CENTER_Z, borderCenterZ);
    }
    
    public void setBorderSize(double borderSize) {
        setDouble(TAG_BORDER_SIZE, borderSize);
    }
    
    public void setBorderSafeZone(double borderSafeZone) {
        setDouble(TAG_BORDER_SAFE_ZONE, borderSafeZone);
    }
    
    public void setBorderWarningBlocks(double borderWarningBlocks) {
        setDouble(TAG_BORDER_WARNING_BLOCKS, borderWarningBlocks);
    }
    
    public void setBorderWarningTime(double borderWarningTime) {
        setDouble(TAG_BORDER_WARNING_TIME, borderWarningTime);
    }
    
    public void setBorderSizeLerpTarget(double borderSizeLerpTarget) {
        setDouble(TAG_BORDER_SIZE_LERP_TARGET, borderSizeLerpTarget);
    }
    
    public void setBorderSizeLerpTime(long borderSizeLerpTime) {
        setLong(TAG_BORDER_SIZE_LERP_TIME, borderSizeLerpTime);
    }
    
    public void setBorderDamagePerBlock(double borderDamagePerBlock) {
        setDouble(TAG_BORDER_DAMAGE_PER_BLOCK, borderDamagePerBlock);
    }

    /**
     * Set the multiplayer and single player spawn point and the single player location.
     */
    public void setSpawn(int x, int y, int z) {
        setSpawnX(x);
        setSpawnY(y);
        setSpawnZ(z);
        Map<String, Tag> playerSettings = getMap(TAG_PLAYER);
        if (playerSettings == null) {
            playerSettings = new HashMap<>();
        }
        playerSettings.put(TAG_SPAWN_X, new IntTag(TAG_SPAWN_X, x));
        playerSettings.put(TAG_SPAWN_Y, new IntTag(TAG_SPAWN_Y, y));
        playerSettings.put(TAG_SPAWN_Z, new IntTag(TAG_SPAWN_Z, z));
        playerSettings.put(TAG_SPAWN_FORCED, new ByteTag(TAG_SPAWN_FORCED, (byte) 1));
        playerSettings.put(TAG_POS, new ListTag<>(TAG_POS, DoubleTag.class, asList(new DoubleTag("", x + 0.5), new DoubleTag("", y), new DoubleTag("", z + 0.5))));
        setMap(TAG_PLAYER, playerSettings);
    }

    public Platform getPlatform() {
        return platform;
    }

    @Override
    public CompoundTag toNBT() {
        Map<String, Tag> values = new HashMap<>();
        values.put(TAG_DATA, super.toNBT());
        if (extraTags != null) {
            for (Tag extraTag: extraTags) {
                values.put(extraTag.getName(), extraTag);
            }
        }
        return new CompoundTag("", values);
    }

    public static void setCachedLevel(File file, JavaLevel level) {
        if ((file != null) || (level != null)) {
            Objects.requireNonNull(file, "file");
            Objects.requireNonNull(level, "level");
            if (! file.getName().equals("level.dat")) {
                throw new IllegalArgumentException(file + " is not named level.dat");
            } else if (! file.isFile()) {
                throw new IllegalArgumentException(file + " does not exist or is not a file");
            }
        }
        cachedFile = file;
        cachedLevel = level;
    }

    public static JavaLevel create(Platform platform, int minHeight, int maxHeight) {
        final org.pepsoft.util.Version platformMcVersion = platform.getAttribute(ATTRIBUTE_MC_VERSION);
        if (platformMcVersion.isAtLeast(V_1_18)) {
            return new Java118Level(minHeight, maxHeight, platform);
        } else if (platformMcVersion.isAtLeast(V_1_16)) {
            return new Java116Level(minHeight, maxHeight, platform);
        } else {
            return new Java115Level(maxHeight, platform);
        }
    }

    @SuppressWarnings("unchecked") // Guaranteed by save()/Minecraft
    public static JavaLevel load(File levelDatFile) throws IOException {
        if (levelDatFile.equals(cachedFile)) {
            return cachedLevel;
        }

        final Tag tag;
        try (NBTInputStream in = new NBTInputStream(new GZIPInputStream(new FileInputStream(levelDatFile)))) {
            tag = in.readTag();
        }

        final CompoundTag data = (CompoundTag) ((CompoundTag) tag).getTag(TAG_DATA);
        int minHeight, maxHeight;
        final File worldDir = levelDatFile.getParentFile();
        final int version = ((IntTag) data.getTag(TAG_VERSION_)).getValue();
        final int dataVersion = data.containsTag (TAG_DATA_VERSION) ? ((IntTag) data.getTag(TAG_DATA_VERSION)).getValue() : -1;
        if (data.containsTag("isCubicWorld") && (((ByteTag) data.getTag("isCubicWorld")).getValue() == (byte) 1)) { // TODO hardcoded support for CC plugin; make this dynamic
            minHeight = 0;
            maxHeight = MAX_HEIGHT;
        } else if (data.containsTag(TAG_VERSION_)) {
            minHeight = (version == VERSION_MCREGION) ? 0 : ((dataVersion <= DATA_VERSION_MC_1_17_1) ? 0 : -64);
            maxHeight = (version == VERSION_MCREGION) ? DEFAULT_MAX_HEIGHT_MCREGION : ((dataVersion <= DATA_VERSION_MC_1_17_1) ? DEFAULT_MAX_HEIGHT_ANVIL : DEFAULT_MAX_HEIGHT_1_18);
            // TODO get rid of this hardcoded stuff and move it into the platform provider plugin API
            if (version == VERSION_MCREGION) {
                File maxheightFile = new File(worldDir, "maxheight.txt");
                if (! maxheightFile.isFile()) {
                    maxheightFile = new File(worldDir, "Height.txt");
                }
                if (maxheightFile.isFile()) {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(maxheightFile), US_ASCII))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (line.startsWith("height=")) {
                                int exp = Integer.parseInt(line.substring(7));
                                maxHeight = 1 << exp;
                                logger.debug("Map height {} detected from {} while loading {}", maxHeight, maxheightFile.getName(), levelDatFile);
                                break;
                            }
                        }
                    }
                }
            } else if (data.containsTag("DataPacks")) {
                CompoundTag dataPacksTag = (CompoundTag) data.getTag("DataPacks");
                ListTag<StringTag> enabledTag = (ListTag<StringTag>) dataPacksTag.getTag("Enabled");
                if (enabledTag != null) {
                    boolean buildLimitsEncountered = false;
                    for (StringTag datapackTag: enabledTag.getValue()) {
                        String name = datapackTag.getValue();
                        if (name.startsWith("file/")) {
                            try {
                                DataPack datapack = DataPack.load(worldDir, name);
                                for (Map.Entry<String, Descriptor> entry: datapack.getDescriptors().entrySet()) {
                                    if ((entry.getValue() instanceof org.pepsoft.minecraft.datapack.Dimension) && entry.getKey().endsWith("overworld.json")) {
                                        int minY = ((org.pepsoft.minecraft.datapack.Dimension) entry.getValue()).getMinY();
                                        int height = ((org.pepsoft.minecraft.datapack.Dimension) entry.getValue()).getHeight();
                                        if (height != 0) {
                                            if (buildLimitsEncountered && (minY != minHeight)) {
                                                throw new IllegalArgumentException(String.format("Multiple different minHeights (%d and %d) encountered in data packs", minHeight, minY));
                                            } else if (buildLimitsEncountered && ((minY + height) != maxHeight)) {
                                                throw new IllegalArgumentException(String.format("Multiple different maxHeights (%d and %d) encountered in data packs", maxHeight, minY + height));
                                            } else {
                                                logger.debug("Map build limits {} to {} detected from data pack {} while loading {}", minY, minY + height, entry.getKey(), levelDatFile);
                                                minHeight = minY;
                                                maxHeight = minY + height;
                                                buildLimitsEncountered = true;
                                            }
                                        }
                                    }
                                }
                            } catch (RuntimeException e) {
                                logger.error("{} while loading data pack {}; skipping data pack (message: \"{}\")", e.getClass().getSimpleName(), name, e.getMessage(), e);
                            }
                        } else {
                            logger.trace("Skipping internal data pack {} while loading level {}", name, levelDatFile);
                            // TODO add support for internal datapacks
                        }
                    }
                }
                // TODO
            } else if (data.getTag(TAG_MAP_HEIGHT) != null) {
                maxHeight = ((IntTag) data.getTag(TAG_MAP_HEIGHT)).getValue();
                logger.debug("Map height {} detected from {} tag while loading {}", maxHeight, TAG_MAP_HEIGHT, levelDatFile);
            }
        } else {
            // TODO refactor map importing
            throw new UnsupportedOperationException("Don't know how to determine height of this map");
        }

        if (dataVersion <= DATA_VERSION_MC_1_15_2) {
            return new Java115Level((CompoundTag) tag, maxHeight);
        } else if (dataVersion <= DATA_VERSION_MC_1_17_1) {
            return new Java116Level((CompoundTag) tag, minHeight, maxHeight);
        } else {
            return new Java118Level((CompoundTag) tag, minHeight, maxHeight);
        }
    }

    private void createWorldPainterDataPack(File worldDir) {
        try {
            final File datapackDir = new File(worldDir, "datapacks");
            datapackDir.mkdirs();
            final File datapackFile = new File(datapackDir, "worldpainter.zip");
            final DataPack datapack = new DataPack();
            datapack.addDescriptor("pack.mcmeta", Meta.builder()
                    .pack(Meta.Pack.builder()
                            .packFormat((platform == JAVA_ANVIL_1_17) ? 7 : 9)
                            .description("WorldPainter Settings").build()).build());
            datapack.addDescriptor("data/minecraft/dimension_type/overworld.json", Dimension.createDefault(platform, DIM_NORMAL, minHeight, maxHeight));
            datapack.write(new FileOutputStream(datapackFile));
        } catch (IOException e) {
            throw new MDCCapturingRuntimeException("I/O error creating datapack", e);
        }
    }

    protected final Platform platform;
    protected final int minHeight, maxHeight;
    protected final Set<Tag> extraTags;

    private static volatile File cachedFile;
    private static volatile JavaLevel cachedLevel;

    private static final Logger logger = LoggerFactory.getLogger(JavaLevel.class);
    private static final long serialVersionUID = 1L;
}