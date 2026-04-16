import java.io.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class LeaderboardManager {
    private static final String FILE_NAME = "leaderboard.txt";
    private static final int MAX_ENTRIES = 10;

    public static void saveScore(String name, int score) {
        // Load current scores
        List<ScoreEntry> scores = loadScores();
        
        // Find if player already exists
        ScoreEntry existing = null;
        for (ScoreEntry se : scores) {
            if (se.name.equalsIgnoreCase(name)) {
                existing = se;
                break;
            }
        }

        if (existing != null) {
            // Update only if higher - or just update if we assume accumulation is handled by the caller
            // To be safe as a "Best Score" leaderboard, we take the max
            existing.score = Math.max(existing.score, score);
        } else {
            scores.add(new ScoreEntry(name, score));
        }

        // Sort by score descending
        scores.sort(Comparator.comparingInt((ScoreEntry s) -> s.score).reversed());
        
        // Keep only top 10
        if (scores.size() > MAX_ENTRIES) {
            scores = scores.subList(0, MAX_ENTRIES);
        }

        // Rewrite the whole file
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(FILE_NAME, false))) {
            for (ScoreEntry se : scores) {
                writer.write(se.name + "," + se.score);
                writer.newLine();
            }
        } catch (IOException e) {
            System.out.println("Failed to save leaderboard: " + e.getMessage());
        }
    }

    public static List<ScoreEntry> loadScores() {
        List<ScoreEntry> scores = new ArrayList<>();
        File file = new File(FILE_NAME);
        if (!file.exists()) return scores;

        try (BufferedReader reader = new BufferedReader(new FileReader(FILE_NAME))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(",");
                if (parts.length == 2) {
                    try {
                        scores.add(new ScoreEntry(parts[0], Integer.parseInt(parts[1])));
                    } catch (NumberFormatException ignored) {}
                }
            }
        } catch (Exception e) {
            System.out.println("Failed to load leaderboard: " + e.getMessage());
        }

        scores.sort(Comparator.comparingInt((ScoreEntry s) -> s.score).reversed());
        return scores;
    }

    /** Returns true if the given score qualifies for the top 10. */
    public static boolean qualifiesForTop10(int score) {
        List<ScoreEntry> scores = loadScores();
        return scores.size() < MAX_ENTRIES || score > scores.get(scores.size() - 1).score;
    }
}