/*
 * Copyright (C) 2026 Simple Keyboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package rkr.simplekeyboard.inputmethod.latin.dict;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lightweight in-memory Trie dictionary for prefix-based autocompletion and word suggestions.
 */
public final class PrefixDictionary {

    private static final class TrieNode {
        final Map<Character, TrieNode> children = new HashMap<>();
        boolean isEndOfWord;
        int frequency;
    }

    private static final class ScoredWord implements Comparable<ScoredWord> {
        final String word;
        final int frequency;

        ScoredWord(String word, int frequency) {
            this.word = word;
            this.frequency = frequency;
        }

        @Override
        public int compareTo(ScoredWord other) {
            // Higher frequency first
            return Integer.compare(other.frequency, this.frequency);
        }
    }

    private final TrieNode mRoot = new TrieNode();
    private int mWordCount = 0;

    public PrefixDictionary() {
    }

    public synchronized void insert(final String word, final int frequency) {
        if (word == null || word.isEmpty()) {
            return;
        }
        final String normalized = word.toLowerCase();
        TrieNode current = mRoot;
        for (int i = 0; i < normalized.length(); i++) {
            final char ch = normalized.charAt(i);
            TrieNode child = current.children.get(ch);
            if (child == null) {
                child = new TrieNode();
                current.children.put(ch, child);
            }
            current = child;
        }
        if (!current.isEndOfWord) {
            current.isEndOfWord = true;
            mWordCount++;
        }
        current.frequency = Math.max(current.frequency, frequency);
    }

    public synchronized void loadFromStream(final InputStream inputStream) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                final String[] parts = line.split("[\\s,]+");
                if (parts.length >= 2) {
                    try {
                        final String word = parts[0];
                        final int freq = Integer.parseInt(parts[1]);
                        insert(word, freq);
                    } catch (NumberFormatException e) {
                        insert(parts[0], 1);
                    }
                } else if (parts.length == 1) {
                    insert(parts[0], 1);
                }
            }
        }
    }

    public synchronized List<CharSequence> getSuggestions(final String prefix, final int maxCount) {
        if (prefix == null || prefix.trim().isEmpty() || maxCount <= 0) {
            return Collections.emptyList();
        }

        final String trimmed = prefix.trim();
        final String lowerPrefix = trimmed.toLowerCase();
        TrieNode current = mRoot;

        for (int i = 0; i < lowerPrefix.length(); i++) {
            final char ch = lowerPrefix.charAt(i);
            current = current.children.get(ch);
            if (current == null) {
                return Collections.emptyList();
            }
        }

        final List<ScoredWord> scoredWords = new ArrayList<>();
        collectWords(current, new StringBuilder(lowerPrefix), scoredWords);
        Collections.sort(scoredWords);

        final boolean isAllUpper = isAllUpperCase(trimmed);
        final boolean isFirstUpper = Character.isUpperCase(trimmed.charAt(0));

        final List<CharSequence> results = new ArrayList<>();
        for (int i = 0; i < Math.min(maxCount, scoredWords.size()); i++) {
            final String word = scoredWords.get(i).word;
            if (isAllUpper && word.length() > 1) {
                results.add(word.toUpperCase());
            } else if (isFirstUpper && word.length() > 0) {
                results.add(Character.toUpperCase(word.charAt(0)) + word.substring(1));
            } else {
                results.add(word);
            }
        }
        return results;
    }

    private void collectWords(final TrieNode node, final StringBuilder prefixBuilder,
                              final List<ScoredWord> accumulator) {
        if (node.isEndOfWord) {
            accumulator.add(new ScoredWord(prefixBuilder.toString(), node.frequency));
        }
        for (Map.Entry<Character, TrieNode> entry : node.children.entrySet()) {
            prefixBuilder.append(entry.getKey());
            collectWords(entry.getValue(), prefixBuilder, accumulator);
            prefixBuilder.setLength(prefixBuilder.length() - 1);
        }
    }

    private static boolean isAllUpperCase(final String s) {
        if (s.length() <= 1) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (Character.isLetter(s.charAt(i)) && !Character.isUpperCase(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    public synchronized boolean containsWord(final String word) {
        if (word == null || word.isEmpty()) {
            return false;
        }
        final String lower = word.toLowerCase();
        TrieNode current = mRoot;
        for (int i = 0; i < lower.length(); i++) {
            current = current.children.get(lower.charAt(i));
            if (current == null) {
                return false;
            }
        }
        return current.isEndOfWord;
    }

    public synchronized CharSequence getBestCorrection(final String word) {
        if (word == null || word.length() <= 2) {
            return null;
        }
        if (containsWord(word)) {
            return null;
        }

        final String lower = word.toLowerCase();
        final List<ScoredWord> candidates = new ArrayList<>();
        searchFuzzy(mRoot, new StringBuilder(), lower, 0, 1, candidates);

        if (candidates.isEmpty()) {
            return null;
        }
        Collections.sort(candidates);
        final String best = candidates.get(0).word;

        if (isAllUpperCase(word)) {
            return best.toUpperCase();
        } else if (Character.isUpperCase(word.charAt(0))) {
            return Character.toUpperCase(best.charAt(0)) + best.substring(1);
        }
        return best;
    }

    private void searchFuzzy(final TrieNode node, final StringBuilder currentPath,
                             final String target, final int targetIdx, final int remainingDistance,
                             final List<ScoredWord> candidates) {
        if (node.isEndOfWord && targetIdx == target.length()) {
            candidates.add(new ScoredWord(currentPath.toString(), node.frequency));
        }

        if (remainingDistance < 0) {
            return;
        }

        // 1. Deletion from target (extra character typed by user)
        if (targetIdx < target.length() && remainingDistance > 0) {
            searchFuzzy(node, currentPath, target, targetIdx + 1, remainingDistance - 1, candidates);
        }

        for (Map.Entry<Character, TrieNode> entry : node.children.entrySet()) {
            final char ch = entry.getKey();
            final TrieNode child = entry.getValue();

            currentPath.append(ch);

            if (targetIdx < target.length()) {
                if (target.charAt(targetIdx) == ch) {
                    // Exact character match
                    searchFuzzy(child, currentPath, target, targetIdx + 1, remainingDistance, candidates);
                } else if (remainingDistance > 0) {
                    // Substitution (wrong character typed by user)
                    searchFuzzy(child, currentPath, target, targetIdx + 1, remainingDistance - 1, candidates);

                    // Transposition (two adjacent characters swapped)
                    if (targetIdx + 1 < target.length() && target.charAt(targetIdx + 1) == ch) {
                        final char nextTargetChar = target.charAt(targetIdx);
                        final TrieNode transChild = child.children.get(nextTargetChar);
                        if (transChild != null) {
                            currentPath.append(nextTargetChar);
                            searchFuzzy(transChild, currentPath, target, targetIdx + 2, remainingDistance - 1, candidates);
                            currentPath.setLength(currentPath.length() - 1);
                        }
                    }
                }
            }

            // Insertion (missing character skipped by user)
            if (remainingDistance > 0) {
                searchFuzzy(child, currentPath, target, targetIdx, remainingDistance - 1, candidates);
            }

            currentPath.setLength(currentPath.length() - 1);
        }
    }

    public synchronized int getWordCount() {
        return mWordCount;
    }

    public synchronized void clear() {
        mRoot.children.clear();
        mRoot.isEndOfWord = false;
        mRoot.frequency = 0;
        mWordCount = 0;
    }
}
