import javax.swing.JFrame;
import javax.swing.SwingUtilities;

public final class Launcher {

    private static void printHelp() {
        System.out.println("Usage: java Launcher [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --seed <number>        Set the world generation seed (default: 0)");
        System.out.println("  --world-height <num>   Set the world height in blocks (default: 128)");
        System.out.println("  --help                 Show this help message and exit");
    }

    public static void main(String[] args) {

        long seed = 0L;
        int worldHeight = 128;

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
                default -> {
                    System.err.println("error: unknown flag: " + args[i]);
                    System.out.println("Use --help for usage information.");
                    return;
                }
            }
        }

        final long finalSeed = seed;
        final int finalWorldHeight = worldHeight;

        SwingUtilities.invokeLater(() -> {

            JFrame window =
                    new JFrame("Craft4k Plus");

            Craft4k game =
                    new Craft4k(finalWorldHeight, finalSeed);

            window.setDefaultCloseOperation(
                    JFrame.EXIT_ON_CLOSE);

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
