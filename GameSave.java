import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** The on-disk representation of a Craft4k world. */
public final class GameSave {

    private static final int MAGIC = 0x43344B53; // C4KS
    private static final int VERSION = 1;

    public final long seed;
    public final HashMap<Long, Integer> editedBlocks;

    private GameSave(long seed, HashMap<Long, Integer> editedBlocks) {
        this.seed = seed;
        this.editedBlocks = editedBlocks;
    }

    public static GameSave load(Path path) throws IOException {
        try (DataInputStream input = new DataInputStream(Files.newInputStream(path))) {
            if (input.readInt() != MAGIC) {
                throw new IOException("not a Craft4k save file");
            }
            if (input.readInt() != VERSION) {
                throw new IOException("unsupported Craft4k save version");
            }

            long seed = input.readLong();
            int blockCount = input.readInt();
            if (blockCount < 0 || blockCount > 10_000_000) {
                throw new IOException("invalid edited block count");
            }

            HashMap<Long, Integer> editedBlocks = new HashMap<>(blockCount);
            for (int i = 0; i < blockCount; i++) {
                int x = input.readInt();
                int y = input.readInt();
                int z = input.readInt();
                int blockId = input.readInt();
                if (blockId < 0 || blockId > 10) {
                    throw new IOException("invalid block ID in save file");
                }
                editedBlocks.put(key(x, y, z), blockId);
            }
            return new GameSave(seed, editedBlocks);
        }
    }

    public static void save(Path path, long seed, Map<Long, Integer> editedBlocks)
            throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        try (DataOutputStream output = new DataOutputStream(Files.newOutputStream(path))) {
            output.writeInt(MAGIC);
            output.writeInt(VERSION);
            output.writeLong(seed);
            output.writeInt(editedBlocks.size());
            for (Map.Entry<Long, Integer> entry : editedBlocks.entrySet()) {
                long packed = entry.getKey();
                output.writeInt(unpack(packed, 0));
                output.writeInt(unpack(packed, 1));
                output.writeInt(unpack(packed, 2));
                output.writeInt(entry.getValue());
            }
        }
    }

    private static long key(int x, int y, int z) {
        return (long) x & 0x1FFFFFL
             | ((long) y & 0x1FFFFFL) << 21
             | ((long) z & 0x1FFFFFL) << 42;
    }

    private static int unpack(long packed, int axis) {
        int value = (int) ((packed >>> (axis * 21)) & 0x1FFFFF);
        return (value & 0x100000) != 0 ? value | ~0x1FFFFF : value;
    }
}
