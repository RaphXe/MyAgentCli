package com.raph.memory;

import com.huaban.analysis.jieba.JiebaSegmenter;
import com.huaban.analysis.jieba.SegToken;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

public class MemoryRetriever {

    private static final int DEFAULT_TOP_K = 5;
    private static final double SOURCE_WEIGHT_SUMMARY = 1.2;
    private static final double SOURCE_WEIGHT_DEFAULT = 1.0;
    private static final long HALF_LIFE_DAYS = 30;

    private final JiebaSegmenter segmenter;

    public MemoryRetriever() {
        this.segmenter = new JiebaSegmenter();
    }

    public List<ScoredMemory> retrieve(String query, List<MemoryEntry> memories) {
        return retrieve(query, memories, DEFAULT_TOP_K);
    }

    public List<ScoredMemory> retrieve(String query, List<MemoryEntry> memories, int topK) {
        if (memories.isEmpty() || query == null || query.isBlank()) {
            return List.of();
        }

        Set<String> queryTokens = tokenize(query);
        if (queryTokens.isEmpty()) {
            return List.of();
        }

        Instant now = Instant.now();
        List<ScoredMemory> scored = new ArrayList<>();

        for (MemoryEntry memory : memories) {
            Set<String> memTokens = tokenize(memory.content());
            double keywordScore = jaccardSimilarity(queryTokens, memTokens);
            double ageDays = memory.timestamp() != null
                    ? Duration.between(memory.timestamp(), now).toDays()
                    : 0;
            double timeDecay = Math.exp(-ageDays / (double) HALF_LIFE_DAYS);
            double sourceWeight = memory.type() == MemoryType.SUMMARY
                    ? SOURCE_WEIGHT_SUMMARY
                    : SOURCE_WEIGHT_DEFAULT;

            double score = keywordScore * timeDecay * sourceWeight;

            if (score > 0) {
                scored.add(new ScoredMemory(memory, score));
            }
        }

        scored.sort((a, b) -> Double.compare(b.score, a.score));

        return scored.stream().limit(topK).collect(Collectors.toList());
    }

    private Set<String> tokenize(String text) {
        List<SegToken> tokens = segmenter.process(text, JiebaSegmenter.SegMode.SEARCH);
        return tokens.stream()
                .map(t -> t.word.trim().toLowerCase())
                .filter(w -> w.length() >= 2 && !isStopWord(w))
                .collect(Collectors.toSet());
    }

    private double jaccardSimilarity(Set<String> set1, Set<String> set2) {
        if (set1.isEmpty() || set2.isEmpty()) return 0.0;

        Set<String> intersection = new HashSet<>(set1);
        intersection.retainAll(set2);

        Set<String> union = new HashSet<>(set1);
        union.addAll(set2);

        return (double) intersection.size() / union.size();
    }

    private boolean isStopWord(String word) {
        return word.matches("[\\d.,;:!?，。；：！？、\"'\"（）()\\[\\]【】\\s]+");
    }

    public record ScoredMemory(MemoryEntry memory, double score) {}
}
