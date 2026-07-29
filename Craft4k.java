import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Random;

public final class Craft4k extends JPanel implements Runnable {

    // ------------------------------------------------------------------------
    // Constants
    // ------------------------------------------------------------------------

    private static final int INTERNAL_WIDTH = 214;
    private static final int INTERNAL_HEIGHT = 120;

    private static final int WORLD_SIZE = 128;
    private static final int WORLD_OFFSET = WORLD_SIZE / 2;
    private static final int WORLD_VOLUME =
            WORLD_SIZE * WORLD_SIZE * WORLD_SIZE;

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
    // Noise functions for procedural terrain
    // ------------------------------------------------------------------------

    private static int hash(int x, int z) {
        int h = x * 374761393 + z * 668265263;
        h = (h ^ (h >> 13)) * 1274126177;
        return h ^ (h >> 16);
    }

    private static float smoothNoise(float x, float z) {
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

    private static float fbm(float x, float z, int octaves) {
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

    // ------------------------------------------------------------------------
    // World generation
    // ------------------------------------------------------------------------

    private void generateWorld(int[] blocks, Random random) {

        // Generate heightmap
        int[] heightmap = new int[WORLD_SIZE * WORLD_SIZE];

        for (int x = 0; x < WORLD_SIZE; x++) {
            for (int z = 0; z < WORLD_SIZE; z++) {
                float nx = x / 24.0f;
                float nz = z / 24.0f;
                float h = fbm(nx, nz, 4);
                float continent = smoothNoise(x / 80.0f, z / 80.0f) * 0.5f + 0.3f;
                float detail = smoothNoise(x / 6.0f, z / 6.0f) * 0.15f;
                int height = (int) (h * 22.0f + continent * 12.0f + detail * 3.0f + 18.0f);
                if (height < 2) height = 2;
                if (height >= WORLD_SIZE - 4) height = WORLD_SIZE - 4;
                heightmap[x + z * WORLD_SIZE] = height;
            }
        }

        // Fill blocks based on heightmap
        for (int x = 0; x < WORLD_SIZE; x++) {
            for (int z = 0; z < WORLD_SIZE; z++) {
                int height = heightmap[x + z * WORLD_SIZE];

                for (int y = 0; y < WORLD_SIZE; y++) {
                    int index = x + y * WORLD_SIZE + z * WORLD_SIZE * WORLD_SIZE;

                    if (y < height) {
                        blocks[index] = BLOCK_AIR;
                    } else if (y == height) {
                        blocks[index] = (height < 8) ? BLOCK_SAND : BLOCK_GRASS;
                    } else if (y < height + 4) {
                        blocks[index] = BLOCK_DIRT;
                    } else {
                        blocks[index] = BLOCK_STONE;
                    }
                }
            }
        }

        // Place trees
        Random treeRandom = new Random(18295169L);

        for (int x = 4; x < WORLD_SIZE - 4; x++) {
            for (int z = 4; z < WORLD_SIZE - 4; z++) {
                int height = heightmap[x + z * WORLD_SIZE];

                if (height >= 8 && treeRandom.nextInt(100) < 3) {
                    int trunkHeight = 4 + treeRandom.nextInt(2);

                    if (height - trunkHeight - 2 >= 0) {
                        // Trunk (grows downward from surface)
                        for (int ty = 1; ty <= trunkHeight; ty++) {
                            int idx = x + (height - ty) * WORLD_SIZE + z * WORLD_SIZE * WORLD_SIZE;
                            blocks[idx] = BLOCK_WOOD;
                        }

                        // Leaves
                        int leafBase = height - trunkHeight + 1;

                        // Bottom layer: 3x3
                        for (int lx = -1; lx <= 1; lx++) {
                            for (int lz = -1; lz <= 1; lz++) {
                                int bx = x + lx;
                                int bz = z + lz;
                                if (bx >= 0 && bx < WORLD_SIZE && bz >= 0 && bz < WORLD_SIZE) {
                                    int idx = bx + leafBase * WORLD_SIZE + bz * WORLD_SIZE * WORLD_SIZE;
                                    if (blocks[idx] == BLOCK_AIR) {
                                        blocks[idx] = BLOCK_LEAVES;
                                    }
                                }
                            }
                        }

                        // Middle layer: 3x3
                        for (int lx = -1; lx <= 1; lx++) {
                            for (int lz = -1; lz <= 1; lz++) {
                                int bx = x + lx;
                                int bz = z + lz;
                                if (bx >= 0 && bx < WORLD_SIZE && bz >= 0 && bz < WORLD_SIZE) {
                                    int idx = bx + (leafBase - 1) * WORLD_SIZE + bz * WORLD_SIZE * WORLD_SIZE;
                                    if (blocks[idx] == BLOCK_AIR) {
                                        blocks[idx] = BLOCK_LEAVES;
                                    }
                                }
                            }
                        }

                        // Top layer: 3x3 with optional corners
                        for (int lx = -1; lx <= 1; lx++) {
                            for (int lz = -1; lz <= 1; lz++) {
                                if ((Math.abs(lx) == 1 && Math.abs(lz) == 1)
                                        && treeRandom.nextInt(2) == 0) {
                                    continue;
                                }
                                int bx = x + lx;
                                int bz = z + lz;
                                if (bx >= 0 && bx < WORLD_SIZE && bz >= 0 && bz < WORLD_SIZE) {
                                    int idx = bx + (leafBase - 2) * WORLD_SIZE + bz * WORLD_SIZE * WORLD_SIZE;
                                    if (blocks[idx] == BLOCK_AIR) {
                                        blocks[idx] = BLOCK_LEAVES;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------------
    // Compute block visibility for culling
    // ------------------------------------------------------------------------

    private boolean[] computeVisibility(int[] blocks) {
        boolean[] visible = new boolean[WORLD_VOLUME];

        for (int x = 0; x < WORLD_SIZE; x++) {
            for (int y = 0; y < WORLD_SIZE; y++) {
                for (int z = 0; z < WORLD_SIZE; z++) {
                    int idx = x + y * WORLD_SIZE + z * WORLD_SIZE * WORLD_SIZE;

                    if (blocks[idx] == BLOCK_AIR) {
                        continue;
                    }

                    visible[idx] =
                            (x == 0 || blocks[idx - 1] == BLOCK_AIR)
                            || (x == WORLD_SIZE - 1 || blocks[idx + 1] == BLOCK_AIR)
                            || (y == 0 || blocks[idx - WORLD_SIZE] == BLOCK_AIR)
                            || (y == WORLD_SIZE - 1 || blocks[idx + WORLD_SIZE] == BLOCK_AIR)
                            || (z == 0 || blocks[idx - WORLD_SIZE * WORLD_SIZE] == BLOCK_AIR)
                            || (z == WORLD_SIZE - 1 || blocks[idx + WORLD_SIZE * WORLD_SIZE] == BLOCK_AIR);
                }
            }
        }

        return visible;
    }

    // ------------------------------------------------------------------------
    // Construction
    // ------------------------------------------------------------------------

    public Craft4k() {

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
                            inputState[1] = 1;
                        } else {
                            inputState[0] = 1;
                        }
                    }

                    @Override
                    public void mouseReleased(MouseEvent e) {

                        if (SwingUtilities.isRightMouseButton(e)) {
                            inputState[1] = 0;
                        } else {
                            inputState[0] = 0;
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
                .name("Craft4k")
                .start(this);
    }

    // ------------------------------------------------------------------------
    // Main game loop
    // ------------------------------------------------------------------------

    @Override
    public void run() {

        Random random = new Random();

        // --------------------------------------------------------------------
        // Generate world
        // --------------------------------------------------------------------

        int[] blocks = new int[WORLD_VOLUME];

        random.setSeed(18295169L);

        generateWorld(blocks, random);

        // Compute visibility for culling
        boolean[] visibleBlocks = computeVisibility(blocks);

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
        for (int y = 0; y < WORLD_SIZE; y++) {
            int idx = spawnX + y * WORLD_SIZE + spawnZ * WORLD_SIZE * WORLD_SIZE;
            if (blocks[idx] != BLOCK_AIR) {
                surfaceY = y;
                break;
            }
        }
        if (surfaceY < 1) surfaceY = 30;

        // Spawn below the surface (in the air, terrain is a ceiling above)
        float cameraX = WORLD_OFFSET + spawnX + 0.5f;
        float cameraY = WORLD_OFFSET + surfaceY - 1.5f;
        float cameraZ = WORLD_OFFSET + spawnZ + 0.5f;

        // Make sure spawn is clear of blocks
        int safetyCheck = 0;
        while (safetyCheck < 20) {
            int bx = (int) Math.floor(cameraX) - WORLD_OFFSET;
            int by = (int) Math.floor(cameraY) - WORLD_OFFSET;
            int bz = (int) Math.floor(cameraZ) - WORLD_OFFSET;
            if (bx >= 0 && bx < WORLD_SIZE && by >= 0 && by < WORLD_SIZE && bz >= 0 && bz < WORLD_SIZE) {
                int idx = bx + by * WORLD_SIZE + bz * WORLD_SIZE * WORLD_SIZE;
                if (blocks[idx] == BLOCK_AIR) break;
            }
            cameraY -= 1.0f;
            safetyCheck++;
        }

        float velocityX = 0.0f;
        float velocityY = 0.0f;
        float velocityZ = 0.0f;

        float yaw = 0.0f;
        float pitch = 0.0f;

        long lastTickTime = System.currentTimeMillis();

        int selectedBlockIndex = -1;
        int selectedFaceOffset = 0;

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
                                (int) (nextX + (corner & 1) * 0.6f - 0.3f)
                                        - WORLD_OFFSET;

                        int blockY =
                                (int) (nextY + ((corner >> 2) - 1) * 0.8f + 0.65f)
                                        - WORLD_OFFSET;

                        int blockZ =
                                (int) (nextZ + ((corner >> 1) & 1) * 0.6f - 0.3f)
                                        - WORLD_OFFSET;

                        if (blockX < 0 || blockY < 0 || blockZ < 0
                                || blockX >= WORLD_SIZE
                                || blockY >= WORLD_SIZE
                                || blockZ >= WORLD_SIZE
                                || blocks[
                                blockX
                                + blockY * WORLD_SIZE
                                + blockZ * WORLD_SIZE * WORLD_SIZE]
                                > 0) {

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
                // Void respawn
                // ------------------------------------------------------------

                if (cameraY < WORLD_OFFSET - 30.0f) {
                    cameraX = WORLD_OFFSET + spawnX + 0.5f;
                    cameraY = WORLD_OFFSET + surfaceY - 1.5f;
                    cameraZ = WORLD_OFFSET + spawnZ + 0.5f;
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
                if (inputState[1] > 0 && selectedBlockIndex >= 0) {
                    blocks[selectedBlockIndex] = 0;
                    visibleBlocks = computeVisibility(blocks);
                    inputState[1] = 0;
                }

                if (inputState[0] > 0 && selectedBlockIndex >= 0) {
                    int placeIndex = selectedBlockIndex + selectedFaceOffset;
                    if (placeIndex >= 0 && placeIndex < WORLD_VOLUME
                            && blocks[placeIndex] == BLOCK_AIR) {
                        blocks[placeIndex] = selectedBlockType;
                        visibleBlocks = computeVisibility(blocks);
                    }
                    inputState[0] = 0;
                }
            }

            // ----------------------------------------------------------------
            // Raycasting
            // ----------------------------------------------------------------

            int hitBlock = -1;
            int hitFace = 0;

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

                        float fraction = cameraX - (int) cameraX;

                        if (axis == 1) {
                            fraction = cameraY - (int) cameraY;
                        }

                        if (axis == 2) {
                            fraction = cameraZ - (int) cameraZ;
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

                            int worldX = (int) rayX - WORLD_OFFSET;
                            int worldY = (int) rayY - WORLD_OFFSET;
                            int worldZ = (int) rayZ - WORLD_OFFSET;

                            if (worldX < 0 || worldY < 0 || worldZ < 0
                                    || worldX >= WORLD_SIZE
                                    || worldY >= WORLD_SIZE
                                    || worldZ >= WORLD_SIZE) {
                                break;
                            }

                            int blockIndex =
                                    worldX
                                    + worldY * WORLD_SIZE
                                    + worldZ * WORLD_SIZE * WORLD_SIZE;

                            int blockId = blocks[blockIndex];

                            if (blockId > 0 && visibleBlocks[blockIndex]) {

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

                                if (blockIndex != hitBlock
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
                                if (distance < 5.0f
                                        && pixelX == 107
                                        && pixelY == 60) {

                                    hitBlock = blockIndex;
                                    hitFace = 0;

                                    if (axis == 0) {
                                        hitFace = rayAxis > 0 ? -1 : 1;
                                    } else if (axis == 1) {
                                        hitFace = rayAxis > 0
                                                ? -WORLD_SIZE
                                                : WORLD_SIZE;
                                    } else {
                                        hitFace = rayAxis > 0
                                                ? -WORLD_SIZE * WORLD_SIZE
                                                : WORLD_SIZE * WORLD_SIZE;
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

            selectedBlockIndex = hitBlock;
            selectedFaceOffset = hitFace;

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
