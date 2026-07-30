import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.HashMap;
import java.util.Random;

public final class Craft4k extends JPanel implements Runnable {

    // ------------------------------------------------------------------------
    // Constants
    // ------------------------------------------------------------------------

    private static final int INTERNAL_WIDTH = 214;
    private static final int INTERNAL_HEIGHT = 120;

    // ------------------------------------------------------------------------
    // Configurable properties
    // ------------------------------------------------------------------------

    private final int worldHeight;
    private final long seed;

    // Block IDs
    private static final int BLOCK_AIR = 0;
    private static final int BLOCK_GRASS = 1;
    private static final int BLOCK_DIRT = 2;
    private static final int BLOCK_STONE = 3;
    private static final int BLOCK_COBBLESTONE = 4;
    private static final int BLOCK_PLANKS = 5;
    private static final int BLOCK_WOOD = 6;
    private static final int BLOCK_BIRCH = 7;
    private static final int BLOCK_LEAVES = 8;
    private static final int BLOCK_SAND = 9;
    private static final int BLOCK_BRICK = 10;
    private static final int BLOCK_COUNT = 11;

    // Pack three ints into one long key for the edit map.
    private static long key(int x, int y, int z) {
        return (long) x & 0x1FFFFFL
             | ((long) y & 0x1FFFFFL) << 21
             | ((long) z & 0x1FFFFFL) << 42;
    }

    // ------------------------------------------------------------------------
    // Procedural world generation (pure functions of x,z — deterministic)
    // ------------------------------------------------------------------------

    private int hash(int x, int z) {
        int h = (int)(seed * 2654435761L) ^ (x * 374761393 + z * 668265263);
        h = (h ^ (h >> 13)) * 1274126177;
        return h ^ (h >> 16);
    }

    private float smoothNoise(float x, float z) {
        int ix = (int) Math.floor(x);
        int iz = (int) Math.floor(z);
        float fx = x - ix;
        float fz = z - iz;
        fx = fx * fx * (3.0f - 2.0f * fx);
        fz = fz * fz * (3.0f - 2.0f * fz);

        float v00 = (hash(ix, iz) & 255) / 255.0f;
        float v10 = (hash(ix + 1, iz) & 255) / 255.0f;
        float v01 = (hash(ix, iz + 1) & 255) / 255.0f;
        float v11 = (hash(ix + 1, iz + 1) & 255) / 255.0f;

        return v00 + (v10 - v00) * fx
                + (v01 - v00) * fz
                + (v00 - v10 - v01 + v11) * fx * fz;
    }

    private float fbm(float x, float z, int octaves) {
        float value = 0.0f;
        float amplitude = 1.0f;
        float frequency = 1.0f;
        float maxValue = 0.0f;
        for (int i = 0; i < octaves; i++) {
            value += smoothNoise(x * frequency, z * frequency) * amplitude;
            maxValue += amplitude;
            amplitude *= 0.5f;
            frequency *= 2.0f;
        }
        return value / maxValue;
    }

    // Terrain surface height at (x, z) in world coordinates.
    // The terrain is a ceiling: blocks at y < height are AIR (underground),
    // y == height is the surface, y > height is dirt/stone.
    private int getHeight(int x, int z) {
        float nx = x / 24.0f;
        float nz = z / 24.0f;
        float h = fbm(nx, nz, 4);
        float continent = smoothNoise(x / 80.0f, z / 80.0f) * 0.5f + 0.3f;
        float detail = smoothNoise(x / 6.0f, z / 6.0f) * 0.15f;
        int height = (int) (h * 22.0f + continent * 12.0f + detail * 3.0f + 18.0f);
        if (height < 2) height = 2;
        if (height >= worldHeight - 4) height = worldHeight - 4;
        return height;
    }

    // Deterministic tree check — uses a separate seed so tree placement
    // doesn't interfere with terrain noise.
    private boolean hasTree(int x, int z) {
        int h = (int)(seed * 2654435761L) ^ (x * 374761393 + z * 668265263);
        h = (h ^ (h >> 13)) * 1274126177;
        h = h ^ (h >> 16);
        return (h & 0x7FFFFFFF) % 100 < 3;
    }

    // Deterministic trunk height for a tree at (x, z).
    private int treeTrunkHeight(int x, int z) {
        int h = (int)(seed * 2654435761L) ^ (x * 1000003 + z * 1000033);
        h = (h ^ (h >> 13)) * 1274126177;
        return 4 + ((h ^ (h >> 16)) & 1);
    }

    // Deterministic random for leaf corner skipping.
    private boolean skipLeafCorner(int x, int z, int lx, int lz) {
        int h = (int)(seed * 2654435761L) ^ (x * 1000037 + z * 1000039 + lx * 1009 + lz * 1013);
        h = (h ^ (h >> 13)) * 1274126177;
        return ((h ^ (h >> 16)) & 1) == 0;
    }

    // Check if (x, y, z) is part of a tree centered at (tx, tz).
    // Returns the block ID (WOOD or LEAVES) or 0 if not part of the tree.
    // Trees grow downward from the surface into the air below.
    private int treeBlockAt(int tx, int tz, int x, int y, int z) {
        int height = getHeight(tx, tz);
        if (height < 8) return 0; // trees only on grass
        int trunkH = treeTrunkHeight(tx, tz);
        int leafBase = height - trunkH + 1;

        // Trunk: from height-1 down to height-trunkH (grows downward from surface)
        if (x == tx && z == tz && y >= height - trunkH && y <= height - 1) {
            return BLOCK_WOOD;
        }

        // Leaves: 3x3 pattern at leafBase, leafBase-1, leafBase-2
        // leafBase = height - trunkH + 1
        // Bottom layer: y = leafBase
        // Middle layer: y = leafBase - 1
        // Top layer:    y = leafBase - 2
        int leafY = leafBase - y;
        if (leafY >= 0 && leafY <= 2) {
            int dx = x - tx;
            int dz = z - tz;
            if (Math.abs(dx) <= 1 && Math.abs(dz) <= 1) {
                // Top layer: skip some corners
                if (leafY == 2 && Math.abs(dx) == 1 && Math.abs(dz) == 1
                        && skipLeafCorner(tx, tz, dx, dz)) {
                    return 0;
                }
                return BLOCK_LEAVES;
            }
        }
        return 0;
    }

    // ------------------------------------------------------------------------
    // Procedural block lookup
    // ------------------------------------------------------------------------

    // Returns the block at any world coordinate.
    // Player edits (stored in the HashMap) override procedural generation.
    private int getBlock(HashMap<Long, Integer> edits, int x, int y, int z) {
        // Y bounds check
        if (y < 0 || y >= worldHeight) return BLOCK_AIR;

        // Check player edits first
        Long k = key(x, y, z);
        Integer edit = edits.get(k);
        if (edit != null) return edit;

        // Procedural generation
        int height = getHeight(x, z);

        // Check for trees first — they occupy the air layer below the surface
        // (trees grow downward from the ceiling into the air).
        if (y < height) {
            for (int tx = -2; tx <= 2; tx++) {
                for (int tz = -2; tz <= 2; tz++) {
                    int cx = x + tx;
                    int cz = z + tz;
                    if (hasTree(cx, cz)) {
                        int tb = treeBlockAt(cx, cz, x, y, z);
                        if (tb != 0) return tb;
                    }
                }
            }
            return BLOCK_AIR;
        } else if (y == height) {
            return (height < 8) ? BLOCK_SAND : BLOCK_GRASS;
        } else if (y < height + 4) {
            return BLOCK_DIRT;
        } else {
            return BLOCK_STONE;
        }
    }

    // Check if a block is solid (collidable).
    private boolean isSolid(HashMap<Long, Integer> edits, int x, int y, int z) {
        return getBlock(edits, x, y, z) > 0;
    }

    private Robot mouseRobot;
    private boolean mouseCaptured = false;

    // ------------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------------

    private final int[] inputState = new int[32767];

    // ------------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------------

    private final BufferedImage framebuffer =
            new BufferedImage(
                    INTERNAL_WIDTH,
                    INTERNAL_HEIGHT,
                    BufferedImage.TYPE_INT_RGB);

    private final int[] pixels =
            ((DataBufferInt) framebuffer
                    .getRaster()
                    .getDataBuffer())
                    .getData();

    private volatile boolean running = true;

    // ------------------------------------------------------------------------
    // Construction
    // ------------------------------------------------------------------------

    public Craft4k(int worldHeight, long seed) {
        this.worldHeight = worldHeight;
        this.seed = seed;

        try {
            mouseRobot = new Robot();
        } catch (Exception ignored) {
        }

        setFocusable(true);

        installKeyboardInput();
        installMouseInput();

        setPreferredSize(
                new Dimension(856, 480));
    }

    private void installKeyboardInput() {

        addKeyListener(new KeyAdapter() {

            @Override
            public void keyPressed(KeyEvent event) {

                char key = Character.toLowerCase(event.getKeyChar());

                if (key < inputState.length) {
                    inputState[key] = 1;
                }
            }

            @Override
            public void keyReleased(KeyEvent event) {

                char key = Character.toLowerCase(event.getKeyChar());

                if (key < inputState.length) {
                    inputState[key] = 0;
                }
            }
        });
    }

    private void installMouseInput() {

        addMouseMotionListener(
                new MouseMotionAdapter() {

                    @Override
                    public void mouseMoved(MouseEvent event) {

                        inputState[2] = event.getX();
                        inputState[3] = event.getY();
                    }

                    @Override
                    public void mouseDragged(MouseEvent event) {

                        inputState[2] = event.getX();
                        inputState[3] = event.getY();
                    }
                });

        addMouseListener(
                new MouseAdapter() {

                    private void captureMouse() {

                        if (!mouseCaptured) {

                            mouseCaptured = true;

                            setCursor(
                                    Toolkit.getDefaultToolkit()
                                            .createCustomCursor(
                                                    new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB),
                                                    new Point(),
                                                    ""));
                        }
                    }

                    @Override
                    public void mouseEntered(MouseEvent e) {
                        captureMouse();
                    }

                    @Override
                    public void mousePressed(MouseEvent e) {

                        inputState[2] = e.getX();
                        inputState[3] = e.getY();

                        if (SwingUtilities.isRightMouseButton(e)) {
                            inputState[0] = 1;
                        } else {
                            inputState[1] = 1;
                        }
                    }

                    @Override
                    public void mouseReleased(MouseEvent e) {

                        if (SwingUtilities.isRightMouseButton(e)) {
                            inputState[0] = 0;
                        } else {
                            inputState[1] = 0;
                        }
                    }

                    @Override
                    public void mouseExited(MouseEvent e) {

                        inputState[2] = 0;
                        inputState[3] = 0;
                    }
                });
    }

    // ------------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------------

    @Override
    protected void paintComponent(Graphics graphics) {

        super.paintComponent(graphics);

        graphics.drawImage(
                framebuffer,
                0,
                0,
                getWidth(),
                getHeight(),
                null);
    }

    // ------------------------------------------------------------------------
    // Game start
    // ------------------------------------------------------------------------

    public void start() {

        Thread.ofPlatform()
                .name("Craft4k Plus")
                .start(this);
    }

    // ------------------------------------------------------------------------
    // Main game loop
    // ------------------------------------------------------------------------

    @Override
    public void run() {

        Random random = new Random();

        // Player edits override procedural terrain
        HashMap<Long, Integer> editedBlocks = new HashMap<>();

        // --------------------------------------------------------------------
        // Generate textures
        // --------------------------------------------------------------------

        int[] textures =
                new int[BLOCK_COUNT * 48 * 16];

        for (int blockId = 1; blockId < BLOCK_COUNT; blockId++) {

            for (int textureY = 0;
                 textureY < 48;
                 textureY++) {

                for (int textureX = 0;
                     textureX < 16;
                     textureX++) {

                    int color = 0x966C4A;
                    int brightness = 255 - random.nextInt(96);

                    // --- Block 1: Grass ---
                    if (blockId == BLOCK_GRASS) {
                        int grassHeight =
                                ((textureX * textureX * 3
                                        + textureX * 81)
                                        >> 2 & 3) + 18;

                        if (textureY < grassHeight) {
                            color = 0x6AAA40;
                        } else if (textureY < grassHeight + 1) {
                            brightness = brightness * 2 / 3;
                        } else {
                            color = 0x8B6B3D;
                        }
                    }

                    // --- Block 2: Dirt ---
                    if (blockId == BLOCK_DIRT) {
                        color = 0x8B6B3D;
                        int speckle = ((textureX * 7 + textureY * 13) % 5);
                        if (speckle == 0) {
                            brightness = brightness * 3 / 4;
                        }
                    }

                    // --- Block 3: Stone ---
                    if (blockId == BLOCK_STONE) {
                        color = 0x7F7F7F;
                        int speckle = ((textureX * 3 + textureY * 7) % 6);
                        if (speckle < 2) {
                            brightness = brightness * 85 / 100;
                        } else if (speckle < 4) {
                            brightness = brightness * 115 / 100;
                        }
                    }

                    // --- Block 4: Cobblestone ---
                    if (blockId == BLOCK_COBBLESTONE) {
                        color = 0x7F7F7F;
                        if (random.nextInt(3) == 0) {
                            brightness = 255 - random.nextInt(96);
                        }
                        int pattern = ((textureX / 4) + (textureY / 4)) % 2;
                        if (pattern == 0) {
                            brightness = brightness * 90 / 100;
                        }
                    }

                    // --- Block 5: Wood Planks ---
                    if (blockId == BLOCK_PLANKS) {
                        color = 0xBC9862;
                        if ((textureX + textureY / 4 * 4) % 8 == 0
                                || textureY % 4 == 0) {
                            color = 0xD4B48C;
                        }
                    }

                    // --- Block 6: Wood Trunk ---
                    if (blockId == BLOCK_WOOD) {
                        color = 0x675231;
                        if (textureX > 0
                                && textureX < 15
                                && ((textureY > 0
                                && textureY < 15)
                                || (textureY > 32
                                && textureY < 47))) {

                            color = 0x8B6914;

                            int edgeX = Math.abs(textureX - 7);
                            int edgeY = Math.abs((textureY & 15) - 7);
                            int edge = Math.max(edgeX, edgeY);

                            brightness = 196
                                    - random.nextInt(32)
                                    + edge % 3 * 32;

                        } else if (random.nextInt(2) == 0) {
                            brightness = brightness
                                    * (150 - (textureX & 1) * 100) / 100;
                        }
                    }

                    // --- Block 7: Birch Wood ---
                    if (blockId == BLOCK_BIRCH) {
                        color = 0xC8B88A;
                        if (textureX > 0
                                && textureX < 15
                                && ((textureY > 0
                                && textureY < 15)
                                || (textureY > 32
                                && textureY < 47))) {

                            color = 0xDCC8A0;

                            int edgeX = Math.abs(textureX - 7);
                            int edgeY = Math.abs((textureY & 15) - 7);
                            int edge = Math.max(edgeX, edgeY);

                            brightness = 196
                                    - random.nextInt(32)
                                    + edge % 3 * 32;

                        } else if (random.nextInt(2) == 0) {
                            brightness = brightness
                                    * (150 - (textureX & 1) * 100) / 100;
                        }
                    }

                    // --- Block 8: Leaves ---
                    if (blockId == BLOCK_LEAVES) {
                        color = 0x50D937;
                        if (random.nextInt(3) == 0) {
                            color = 0;
                            brightness = 255;
                        }
                    }

                    // --- Block 9: Sand ---
                    if (blockId == BLOCK_SAND) {
                        color = 0xE8D5A0;
                        int speckle = ((textureX * 5 + textureY * 11) % 4);
                        if (speckle == 0) {
                            brightness = brightness * 90 / 100;
                        }
                    }

                    // --- Block 10: Brick ---
                    if (blockId == BLOCK_BRICK) {
                        color = 0xB53A15;
                        int brickX = textureX / 4;
                        int brickY = textureY / 8;
                        if ((brickX + brickY) % 2 == 0) {
                            color = 0xC84C2A;
                        }
                        if (textureX % 4 == 0 || textureY % 8 == 0) {
                            color = 0x8B7355;
                        }
                    }

                    // Bottom half is darker
                    if (textureY >= 32) {
                        brightness /= 2;
                    }

                    int red = ((color >> 16) & 255) * brightness / 255;
                    int green = ((color >> 8) & 255) * brightness / 255;
                    int blue = (color & 255) * brightness / 255;

                    textures[
                            textureX
                            + textureY * 16
                            + blockId * 16 * 48
                            ] = red << 16 | green << 8 | blue;
                }
            }
        }

        // --------------------------------------------------------------------
        // Camera state
        // --------------------------------------------------------------------

        // Find surface at spawn (terrain is at high Y, air below)
        int spawnX = 8;
        int spawnZ = 8;
        int surfaceY = 0;
        for (int y = 0; y < worldHeight; y++) {
            if (getBlock(editedBlocks, spawnX, y, spawnZ) != BLOCK_AIR) {
                surfaceY = y;
                break;
            }
        }
        if (surfaceY < 1) surfaceY = 30;

        // Spawn below the surface (in the air, terrain is a ceiling above)
        float cameraX = spawnX + 0.5f;
        float cameraY = surfaceY - 1.5f;
        float cameraZ = spawnZ + 0.5f;

        // Make sure spawn is clear of blocks
        int safetyCheck = 0;
        while (safetyCheck < 20) {
            int bx = (int) Math.floor(cameraX);
            int by = (int) Math.floor(cameraY);
            int bz = (int) Math.floor(cameraZ);
            if (getBlock(editedBlocks, bx, by, bz) == BLOCK_AIR) break;
            cameraY -= 1.0f;
            safetyCheck++;
        }

        float velocityX = 0.0f;
        float velocityY = 0.0f;
        float velocityZ = 0.0f;

        float yaw = 0.0f;
        float pitch = 0.0f;

        long lastTickTime = System.currentTimeMillis();

        // Hit block coordinates and face normal for block editing
        int hitBlockX = 0;
        int hitBlockY = 0;
        int hitBlockZ = 0;
        int hitFaceNX = 0;
        int hitFaceNY = 0;
        int hitFaceNZ = 0;
        boolean hasHitBlock = false;

        // Block selection menu state
        boolean blockMenuOpen = false;
        int selectedBlockType = BLOCK_GRASS;

        // --------------------------------------------------------------------
        // Main loop
        // --------------------------------------------------------------------

        while (running) {

            float sinYaw = (float) Math.sin(yaw);
            float cosYaw = (float) Math.cos(yaw);
            float sinPitch = (float) Math.sin(pitch);
            float cosPitch = (float) Math.cos(pitch);

            while (System.currentTimeMillis()
                    - lastTickTime > 10L) {

                // ------------------------------------------------------------
                // Block selection menu toggle
                // ------------------------------------------------------------

                if (inputState['i'] > 0) {
                    blockMenuOpen = !blockMenuOpen;
                    inputState['i'] = 0;
                }

                // ------------------------------------------------------------
                // Mouse look
                // ------------------------------------------------------------

                if (inputState[2] > 0 && !blockMenuOpen) {

                    float mouseX =
                            (inputState[2] - 428) / 214.0f * 2.0f;

                    float mouseY =
                            (inputState[3] - 240) / 120.0f * 2.0f;

                    float mouseDistance =
                            (float) Math.sqrt(
                                    mouseX * mouseX + mouseY * mouseY)
                                    - 1.2f;

                    if (mouseDistance < 0) {
                        mouseDistance = 0;
                    }

                    if (mouseDistance > 0) {

                        yaw -= mouseX * mouseDistance / 400.0f;
                        pitch -= mouseY * mouseDistance / 400.0f;

                        if (pitch < -1.57f) {
                            pitch = -1.57f;
                        }

                        if (pitch > 1.57f) {
                            pitch = 1.57f;
                        }
                    }
                }

                lastTickTime += 10L;

                // ------------------------------------------------------------
                // Movement
                // ------------------------------------------------------------

                float forwardMovement = 0.0f;
                float strafeMovement = 0.0f;

                forwardMovement +=
                        (inputState[100] - inputState[97]) * 0.02f;

                strafeMovement +=
                        (inputState[119] - inputState[115]) * 0.02f;

                velocityX *= 0.5f;
                velocityZ *= 0.5f;
                velocityY *= 0.99f;

                velocityX += sinYaw * forwardMovement + cosYaw * strafeMovement;
                velocityZ += cosYaw * forwardMovement - sinYaw * strafeMovement;

                velocityY += 0.003f;

                // ------------------------------------------------------------
                // Collision movement
                // ------------------------------------------------------------

                movement:
                for (int axis = 0; axis < 3; axis++) {

                    float nextX = cameraX + velocityX * ((axis + 0) % 3 / 2);
                    float nextY = cameraY + velocityY * ((axis + 1) % 3 / 2);
                    float nextZ = cameraZ + velocityZ * ((axis + 2) % 3 / 2);

                    for (int corner = 0; corner < 12; corner++) {

                        int blockX =
                                (int) (nextX + (corner & 1) * 0.6f - 0.3f);

                        int blockY =
                                (int) (nextY + ((corner >> 2) - 1) * 0.8f + 0.65f);

                        int blockZ =
                                (int) (nextZ + ((corner >> 1) & 1) * 0.6f - 0.3f);

                        if (isSolid(editedBlocks, blockX, blockY, blockZ)) {

                            if (axis == 1) {
                                if (inputState[32] > 0 && velocityY > 0) {
                                    inputState[32] = 0;
                                    velocityY = -0.1f;
                                } else {
                                    velocityY = 0.0f;
                                }
                            }

                            continue movement;
                        }
                    }

                    cameraX = nextX;
                    cameraY = nextY;
                    cameraZ = nextZ;
                }

                // ------------------------------------------------------------
                // Unstuck: if player is inside a block, push them out
                // ------------------------------------------------------------

                {
                    int cx = (int) Math.floor(cameraX);
                    int cy = (int) Math.floor(cameraY);
                    int cz = (int) Math.floor(cameraZ);
                    if (isSolid(editedBlocks, cx, cy, cz)) {
                        // Check all 6 directions, find the nearest air block
                        float bestDist = Float.MAX_VALUE;
                        float pushX = 0, pushY = 0, pushZ = 0;
                        int[][] dirs = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
                        for (int[] d : dirs) {
                            if (!isSolid(editedBlocks, cx + d[0], cy + d[1], cz + d[2])) {
                                float dist = (float) Math.sqrt(
                                        d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
                                if (dist < bestDist) {
                                    bestDist = dist;
                                    pushX = d[0];
                                    pushY = d[1];
                                    pushZ = d[2];
                                }
                            }
                        }
                        if (bestDist < Float.MAX_VALUE) {
                            cameraX += pushX;
                            cameraY += pushY;
                            cameraZ += pushZ;
                            velocityX = 0;
                            velocityY = 0;
                            velocityZ = 0;
                        }
                    }
                }

                // ------------------------------------------------------------
                // Void respawn
                // ------------------------------------------------------------

                if (cameraY < -30.0f) {
                    cameraX = spawnX + 0.5f;
                    cameraY = surfaceY - 1.5f;
                    cameraZ = spawnZ + 0.5f;
                    velocityX = 0.0f;
                    velocityY = 0.0f;
                    velocityZ = 0.0f;
                }
            }

            // ---------------------------------------------------------------
            // Block editing
            // ---------------------------------------------------------------

            if (blockMenuOpen) {
                if (inputState[0] > 0) {
                    int mouseMenuX = inputState[2] / 4;
                    int mouseMenuY = inputState[3] / 4;

                    if (mouseMenuY >= 100 && mouseMenuY < 120) {
                        int menuSlots = 9;
                        int slotWidth = 18;
                        int totalWidth = menuSlots * slotWidth;
                        int startX = (INTERNAL_WIDTH - totalWidth) / 2;

                        int clickedSlot = (mouseMenuX - startX) / slotWidth;
                        if (clickedSlot >= 0 && clickedSlot < menuSlots) {
                            selectedBlockType = clickedSlot + 1;
                        }
                    }
                    inputState[0] = 0;
                }
            } else {
                if (inputState[1] > 0 && hasHitBlock) {
                    editedBlocks.put(key(hitBlockX, hitBlockY, hitBlockZ), BLOCK_AIR);
                    inputState[1] = 0;
                }

                if (inputState[0] > 0 && hasHitBlock) {
                    int placeX = hitBlockX + hitFaceNX;
                    int placeY = hitBlockY + hitFaceNY;
                    int placeZ = hitBlockZ + hitFaceNZ;
                    if (placeY >= 0 && placeY < worldHeight
                            && getBlock(editedBlocks, placeX, placeY, placeZ) == BLOCK_AIR) {
                        editedBlocks.put(key(placeX, placeY, placeZ), selectedBlockType);
                    }
                    inputState[0] = 0;
                }
            }

            // ----------------------------------------------------------------
            // Raycasting
            // ----------------------------------------------------------------

            hasHitBlock = false;

            for (int pixelX = 0; pixelX < INTERNAL_WIDTH; pixelX++) {

                float rayScreenX = (pixelX - 107) / 90.0f;

                for (int pixelY = 0; pixelY < INTERNAL_HEIGHT; pixelY++) {

                    float rayScreenY = (pixelY - 60) / 90.0f;

                    float rayLength = 1.0f;

                    float directionX =
                            rayLength * cosPitch + rayScreenY * sinPitch;

                    float directionY =
                            rayScreenY * cosPitch - rayLength * sinPitch;

                    float directionZ = rayScreenX;

                    float rotatedX =
                            directionX * cosYaw + directionZ * sinYaw;

                    float rotatedZ =
                            directionZ * cosYaw - directionX * sinYaw;

                    int pixelColor = 0;
                    int brightness = 255;

                    double closestDistance = 20.0;

                    int textureU = 0;
                    int textureV = 0;

                    for (int axis = 0; axis < 3; axis++) {

                        float rayAxis;

                        if (axis == 0) {
                            rayAxis = rotatedX;
                        } else if (axis == 1) {
                            rayAxis = directionY;
                        } else {
                            rayAxis = rotatedZ;
                        }

                        float inverseAxis =
                                1.0f / (rayAxis < 0 ? -rayAxis : rayAxis);
        
                        float stepX = rotatedX * inverseAxis;
                        float stepY = directionY * inverseAxis;
                        float stepZ = rotatedZ * inverseAxis;
        
                        // Use Math.floor for the fraction so it works correctly
                        // when the camera position is negative (Java's (int) cast
                        // truncates toward zero, giving a wrong fraction).
                        float fraction = cameraX - (float) Math.floor(cameraX);
        
                        if (axis == 1) {
                            fraction = cameraY - (float) Math.floor(cameraY);
                        }
        
                        if (axis == 2) {
                            fraction = cameraZ - (float) Math.floor(cameraZ);
                        }

                        if (rayAxis > 0) {
                            fraction = 1 - fraction;
                        }

                        float distance = inverseAxis * fraction;

                        float rayX = cameraX + stepX * fraction;
                        float rayY = cameraY + stepY * fraction;
                        float rayZ = cameraZ + stepZ * fraction;

                        if (rayAxis < 0) {
                            if (axis == 0) rayX--;
                            if (axis == 1) rayY--;
                            if (axis == 2) rayZ--;
                        }

                        while (distance < closestDistance) {

                            int worldX = (int) Math.floor(rayX);
                            int worldY = (int) Math.floor(rayY);
                            int worldZ = (int) Math.floor(rayZ);

                            int blockId = getBlock(editedBlocks, worldX, worldY, worldZ);

                            if (blockId > 0) {

                                textureU =
                                        ((int) ((rayX + rayZ) * 16.0f)) & 15;

                                textureV =
                                        ((int) (rayY * 16.0f)) & 15;

                                if (axis == 1) {
                                    textureU = ((int) (rayX * 16.0f)) & 15;
                                    textureV = ((int) (rayZ * 16.0f)) & 15;

                                    if (stepY < 0) {
                                        textureV += 32;
                                    }
                                }

                                int color = 0xFFFFFF;

                                // Only draw the wireframe outline on the crosshair hit
                                boolean isCrosshairHit = distance < 5.0f
                                        && pixelX == 107
                                        && pixelY == 60;

                                if (!isCrosshairHit
                                        || textureU > 0
                                        && textureV % 16 > 0
                                        && textureU < 15
                                        && textureV % 16 < 15) {

                                    color = textures[
                                            textureU
                                            + textureV * 16
                                            + blockId * 16 * 48];
                                }

                                // Crosshair hit detection - center pixel of internal res
                                if (isCrosshairHit) {
                                    hasHitBlock = true;
                                    hitBlockX = worldX;
                                    hitBlockY = worldY;
                                    hitBlockZ = worldZ;
                                    hitFaceNX = 0;
                                    hitFaceNY = 0;
                                    hitFaceNZ = 0;

                                    if (axis == 0) {
                                        hitFaceNX = rayAxis > 0 ? -1 : 1;
                                    } else if (axis == 1) {
                                        hitFaceNY = rayAxis > 0 ? -1 : 1;
                                    } else {
                                        hitFaceNZ = rayAxis > 0 ? -1 : 1;
                                    }
                                }

                                pixelColor = color;

                                brightness = 255
                                        - (int) (distance / 20.0f * 255.0f);

                                brightness = brightness
                                        * (255 - (axis + 2) % 3 * 50) / 255;

                                closestDistance = distance;
                            }

                            rayX += stepX;
                            rayY += stepY;
                            rayZ += stepZ;
                            distance += inverseAxis;
                        }
                    }

                    int red = ((pixelColor >> 16) & 255) * brightness / 255;
                    int green = ((pixelColor >> 8) & 255) * brightness / 255;
                    int blue = (pixelColor & 255) * brightness / 255;

                    pixels[pixelX + pixelY * INTERNAL_WIDTH] =
                            red << 16 | green << 8 | blue;
                }
            }

            // ----------------------------------------------------------------
            // Block selection menu overlay
            // ----------------------------------------------------------------

            if (blockMenuOpen) {
                renderBlockMenu(selectedBlockType, textures);
            }

            repaint();

            try {
                Thread.sleep(2L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    // ------------------------------------------------------------------------
    // Block selection menu rendering
    // ------------------------------------------------------------------------

    private void renderBlockMenu(int selectedBlockType, int[] textures) {

        int menuSlots = 9;
        int slotWidth = 18;
        int slotHeight = 18;
        int totalWidth = menuSlots * slotWidth;
        int startX = (INTERNAL_WIDTH - totalWidth) / 2;
        int menuY = 100;
        int menuHeight = 20;

        // Draw dark background bar
        for (int y = menuY; y < menuY + menuHeight && y < INTERNAL_HEIGHT; y++) {
            for (int x = 0; x < INTERNAL_WIDTH; x++) {
                int idx = x + y * INTERNAL_WIDTH;
                int existing = pixels[idx];
                int r = ((existing >> 16) & 255) * 40 / 100;
                int g = ((existing >> 8) & 255) * 40 / 100;
                int b = (existing & 255) * 40 / 100;
                pixels[idx] = r << 16 | g << 8 | b;
            }
        }

        // Draw border around the bar
        for (int x = 0; x < INTERNAL_WIDTH; x++) {
            int topIdx = x + menuY * INTERNAL_WIDTH;
            int botIdx = x + (menuY + menuHeight - 1) * INTERNAL_WIDTH;
            pixels[topIdx] = 0x555555;
            pixels[botIdx] = 0x555555;
        }
        for (int y = menuY; y < menuY + menuHeight; y++) {
            int leftIdx = 0 + y * INTERNAL_WIDTH;
            int rightIdx = (INTERNAL_WIDTH - 1) + y * INTERNAL_WIDTH;
            pixels[leftIdx] = 0x555555;
            pixels[rightIdx] = 0x555555;
        }

        // Draw each block slot
        for (int slot = 0; slot < menuSlots; slot++) {
            int blockId = slot + 1;
            int slotX = startX + slot * slotWidth;

            boolean isSelected = (blockId == selectedBlockType);
            int bgColor = isSelected ? 0x444466 : 0x333333;

            for (int sy = 0; sy < slotHeight - 2; sy++) {
                for (int sx = 0; sx < slotWidth - 2; sx++) {
                    int px = slotX + 1 + sx;
                    int py = menuY + 1 + sy;
                    if (px >= 0 && px < INTERNAL_WIDTH && py < INTERNAL_HEIGHT) {
                        pixels[px + py * INTERNAL_WIDTH] = bgColor;
                    }
                }
            }

            // Draw block texture in the slot
            int texStartY = menuY + 2;
            int texStartX = slotX + 2;

            for (int ty = 0; ty < 14; ty++) {
                for (int tx = 0; tx < 14; tx++) {
                    int px = texStartX + tx;
                    int py = texStartY + ty;
                    if (px >= 0 && px < INTERNAL_WIDTH && py < INTERNAL_HEIGHT) {
                        int texel = textures[
                                tx + ty * 16 + blockId * 16 * 48];
                        pixels[px + py * INTERNAL_WIDTH] = texel;
                    }
                }
            }

            // Draw selection highlight border
            if (isSelected) {
                for (int sx = 0; sx < slotWidth; sx++) {
                    int topPx = slotX + sx;
                    int botPx = slotX + sx;
                    int topPy = menuY;
                    int botPy = menuY + slotHeight - 1;
                    if (topPx >= 0 && topPx < INTERNAL_WIDTH) {
                        pixels[topPx + topPy * INTERNAL_WIDTH] = 0xFFFFFF;
                        pixels[botPx + botPy * INTERNAL_WIDTH] = 0xFFFFFF;
                    }
                }
                for (int sy = 0; sy < slotHeight; sy++) {
                    int leftPx = slotX;
                    int rightPx = slotX + slotWidth - 1;
                    int py = menuY + sy;
                    if (py >= 0 && py < INTERNAL_HEIGHT) {
                        if (leftPx >= 0 && leftPx < INTERNAL_WIDTH) {
                            pixels[leftPx + py * INTERNAL_WIDTH] = 0xFFFFFF;
                        }
                        if (rightPx >= 0 && rightPx < INTERNAL_WIDTH) {
                            pixels[rightPx + py * INTERNAL_WIDTH] = 0xFFFFFF;
                        }
                    }
                }
            }
        }

        // Draw slot indicator lines
        for (int slot = 0; slot < menuSlots; slot++) {
            int slotX = startX + slot * slotWidth;
            int labelY = menuY + slotHeight - 1;
            int numColor = 0xFFFFFF;

            for (int lx = 2; lx < slotWidth - 2; lx++) {
                int px = slotX + lx;
                int py = labelY;
                if (px >= 0 && px < INTERNAL_WIDTH && py < INTERNAL_HEIGHT) {
                    pixels[px + py * INTERNAL_WIDTH] = numColor;
                }
            }
        }
    }
}
