import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.io.IOException;
import java.nio.file.Path;

public final class Launcher {

    private static void printHelp() {
        System.out.println("Usage: java Launcher [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --seed <number>        Set the world generation seed (default: 0)");
        System.out.println("  --world-height <num>   Set the world height in blocks (default: 128)");
        System.out.println("  --save <file>          Load this save, or create it when saving");
        System.out.println("  --help                 Show this help message and exit");
    }

    public static void main(String[] args) {

        long seed = 0L;
        int worldHeight = 128;
        Path savePath = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--help" -> {
                    printHelp();
                    return;
                }
                case "--seed" -> {
                    if (i + 1 >= args.length) {
                        System.err.println("error: --seed requires a numeric argument");
                        return;
                    }
                    try {
                        seed = Long.parseLong(args[++i]);
                    } catch (NumberFormatException e) {
                        System.err.println("error: --seed must be a valid integer");
                        return;
                    }
                }
                case "--world-height" -> {
                    if (i + 1 >= args.length) {
                        System.err.println("error: --world-height requires a numeric argument");
                        return;
                    }
                    try {
                        worldHeight = Integer.parseInt(args[++i]);
                    } catch (NumberFormatException e) {
                        System.err.println("error: --world-height must be a valid integer");
                        return;
                    }
                    if (worldHeight < 16) {
                        System.err.println("error: --world-height must be at least 16");
                        return;
                    }
                }
                case "--save" -> {
                    if (i + 1 >= args.length) {
                        System.err.println("error: --save requires a file path");
                        return;
                    }
                    savePath = Path.of(args[++i]);
                }
                default -> {
                    System.err.println("error: unknown flag: " + args[i]);
                    System.out.println("Use --help for usage information.");
                    return;
                }
            }
        }

        final long finalSeed = seed;
        final int finalWorldHeight = worldHeight;
        final Path finalSavePath = savePath;

        SwingUtilities.invokeLater(() -> {

            JFrame window =
                    new JFrame("Craft4k Plus");

            final Craft4k game;
            try {
                game = new Craft4k(finalWorldHeight, finalSeed, finalSavePath);
            } catch (IOException e) {
                System.err.println("error: could not load save file: " + e.getMessage());
                return;
            }

            window.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
            window.addWindowListener(new java.awt.event.WindowAdapter() {
                @Override
                public void windowClosing(java.awt.event.WindowEvent event) {
                    game.stop();
                    game.saveGame();
                    window.dispose();
                }
            });

            window.setContentPane(game);

            window.pack();

            window.setLocationRelativeTo(null);

            window.setResizable(false);

            window.setVisible(true);

            game.requestFocusInWindow();

            game.start();
        });
    }
}
