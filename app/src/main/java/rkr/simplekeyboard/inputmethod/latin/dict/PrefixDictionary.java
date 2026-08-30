package rkr.simplekeyboard.inputmethod.latin.dict;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import rkr.simplekeyboard.inputmethod.latin.common.StringUtils;

/**
 * Lightweight in-memory Trie dictionary with Accent-Folding, Physical Proximity scoring,
 * Long Word correction bonuses, and Bigram context support.
 */
public final class PrefixDictionary {
    public static final int BASE_LEARNED_FREQUENCY = 250;

    private static final char[] EMPTY_CHARS = new char[0];
    private static final TrieNode[] EMPTY_CHILDREN = new TrieNode[0];
    private static final String[] EMPTY_WORDS = new String[0];
    private static final short[] EMPTY_FREQS = new short[0];

    private static final class TrieNode {
        char[] keys = EMPTY_CHARS;
        TrieNode[] children = EMPTY_CHILDREN;
        String[] words = EMPTY_WORDS;
        short[] freqs = EMPTY_FREQS;

        TrieNode getChild(final char c) {
            final char[] k = keys;
            for (int i = 0; i < k.length; i++) {
                if (k[i] == c) {
                    return children[i];
                }
            }
            return null;
        }

        TrieNode getOrCreateChild(final char c) {
            final char[] k = keys;
            for (int i = 0; i < k.length; i++) {
                if (k[i] == c) {
                    return children[i];
                }
            }
            final int len = k.length;
            final char[] newK = new char[len + 1];
            final TrieNode[] newC = new TrieNode[len + 1];
            System.arraycopy(k, 0, newK, 0, len);
            System.arraycopy(children, 0, newC, 0, len);
            newK[len] = c;
            final TrieNode child = new TrieNode();
            newC[len] = child;
            this.keys = newK;
            this.children = newC;
            return child;
        }

        boolean addWord(final String word, final int freq) {
            final short sFreq = (short) Math.min(Short.MAX_VALUE, Math.max(1, freq));
            final String[] w = words;
            for (int i = 0; i < w.length; i++) {
                if (w[i].equalsIgnoreCase(word)) {
                    if (sFreq > freqs[i]) {
                        freqs[i] = sFreq;
                        sortWords();
                    }
                    return false;
                }
            }
            final int len = w.length;
            final String[] newW = new String[len + 1];
            final short[] newF = new short[len + 1];
            System.arraycopy(w, 0, newW, 0, len);
            System.arraycopy(freqs, 0, newF, 0, len);
            newW[len] = word;
            newF[len] = sFreq;
            this.words = newW;
            this.freqs = newF;
            sortWords();
            return true;
        }

        private void sortWords() {
            for (int i = 1; i < words.length; i++) {
                final String w = words[i];
                final short f = freqs[i];
                int j = i - 1;
                while (j >= 0 && freqs[j] < f) {
                    words[j + 1] = words[j];
                    freqs[j + 1] = freqs[j];
                    j--;
                }
                words[j + 1] = w;
                freqs[j + 1] = f;
            }
        }
    }

    public static final class ScoredWord implements Comparable<ScoredWord> {
        public final String word;
        public final int frequency;
        public final float score;

        public ScoredWord(String word, int frequency) {
            this(word, frequency, frequency);
        }

        public ScoredWord(String word, int frequency, float score) {
            this.word = word;
            this.frequency = frequency;
            this.score = score;
        }

        @Override
        public int compareTo(ScoredWord other) {
            return Float.compare(other.score, this.score);
        }
    }

    private TrieNode mRoot = new TrieNode();
    private int mWordCount = 0;
    private float mAutoCorrectionThreshold = 1.0f;
    private final Map<String, Map<String, Short>> mTrigrams = new HashMap<>();
    private final Map<String, Map<String, Short>> mBigrams = new HashMap<>();
    private final List<ScoredWord> mTopWords = new ArrayList<>();
    private volatile rkr.simplekeyboard.inputmethod.latin.dict.binary.BinaryTrieDictionary mBinaryDict = null;

    public PrefixDictionary() {
    }

    public synchronized void setBinaryDictionary(final rkr.simplekeyboard.inputmethod.latin.dict.binary.BinaryTrieDictionary binaryDict) {
        this.mBinaryDict = binaryDict;
    }

    public rkr.simplekeyboard.inputmethod.latin.dict.binary.BinaryTrieDictionary getBinaryDictionary() {
        return mBinaryDict;
    }

    public synchronized void setAutoCorrectionThreshold(final float threshold) {
        this.mAutoCorrectionThreshold = threshold;
    }

    public synchronized float getAutoCorrectionThreshold() {
        return mAutoCorrectionThreshold;
    }

    public synchronized void copyFrom(final PrefixDictionary other) {
        if (other != null) {
            synchronized (other) {
                this.mRoot = other.mRoot;
                this.mWordCount = other.mWordCount;
                this.mAutoCorrectionThreshold = other.mAutoCorrectionThreshold;
                this.mBinaryDict = other.mBinaryDict;
                this.mTrigrams.clear();
                this.mTrigrams.putAll(other.mTrigrams);
                this.mBigrams.clear();
                this.mBigrams.putAll(other.mBigrams);
                this.mTopWords.clear();
                this.mTopWords.addAll(other.mTopWords);
            }
        }
    }

    private static final ThreadLocal<float[][]> DIST_BUFFERS = new ThreadLocal<float[][]>() {
        @Override
        protected float[][] initialValue() {
            return new float[3][64];
        }
    };

    // Distance helper buffers for zero-allocation Damerau-Levenshtein calculation
    private static float getTrivialDistance(final String s1, final String s2) {
        if (s1 == null || s1.isEmpty()) {
            return (s2 == null) ? 0.0f : s2.length();
        }
        if (s2 == null || s2.isEmpty()) {
            return s1.length();
        }
        return -1.0f;
    }

    private static float[][] getDistanceBuffers(final int m) {
        float[][] buffers = DIST_BUFFERS.get();
        if (m + 1 > buffers[0].length) {
            buffers = new float[3][Math.max(m + 1, 64)];
            DIST_BUFFERS.set(buffers);
        }
        return buffers;
    }

    private static boolean isTransposition(final String s1, final String s2, final int i, final int j, final char c1, final char c2) {
        return i > 1 && j > 1 && c1 == s2.charAt(j - 2) && s1.charAt(i - 2) == c2;
    }

    private static float calculateCellDistance(final String s1, final String s2, final int i, final int j, final float[] dPrevPrev, final float[] dPrev, final float[] dCurr) {
        final char c1 = s1.charAt(i - 1);
        final char c2 = s2.charAt(j - 1);
        final float cost = (c1 == c2) ? 0.0f : ProximityKeyMap.getDistanceWeight(c1, c2);

        float min = Math.min(dPrev[j] + 1.0f, dCurr[j - 1] + 1.0f);
        min = Math.min(min, dPrev[j - 1] + cost);

        if (isTransposition(s1, s2, i, j, c1, c2)) {
            min = Math.min(min, dPrevPrev[j - 2] + 0.5f);
        }
        return min;
    }

    private static void computeDistanceRow(final String s1, final String s2, final int i, final int m, final float[] dPrevPrev, final float[] dPrev, final float[] dCurr) {
        dCurr[0] = i;
        for (int j = 1; j <= m; j++) {
            dCurr[j] = calculateCellDistance(s1, s2, i, j, dPrevPrev, dPrev, dCurr);
        }
    }

    /**
     * Calculates the weighted Damerau-Levenshtein distance between two strings,
     * incorporating physical keyboard key proximity without memory allocations.
     */
    public static float computeWeightedDistance(final String s1, final String s2) {
        final float trivialDist = getTrivialDistance(s1, s2);
        if (trivialDist >= 0.0f) {
            return trivialDist;
        }

        final int n = s1.length();
        final int m = s2.length();
        final float[][] buffers = getDistanceBuffers(m);

        float[] dPrevPrev = buffers[0];
        float[] dPrev = buffers[1];
        float[] dCurr = buffers[2];

        for (int j = 0; j <= m; j++) {
            dPrev[j] = j;
        }

        for (int i = 1; i <= n; i++) {
            computeDistanceRow(s1, s2, i, m, dPrevPrev, dPrev, dCurr);
            final float[] temp = dPrevPrev;
            dPrevPrev = dPrev;
            dPrev = dCurr;
            dCurr = temp;
        }
        return dPrev[m];
    }

    /**
     * Bonus for long words (>6 characters) inspired by LeanType so typos in longer words
     * are not unfairly penalized.
     */
    public static float getLongWordCorrectionBonus(final String typed, final String candidate) {
        if (typed == null || candidate == null) {
            return 0.0f;
        }
        final int maxLen = Math.max(typed.length(), candidate.length());
        if (maxLen > 6) {
            return (maxLen - 6) * 5.0f;
        }
        return 0.0f;
    }

    /**
     * Calculates normalized candidate score based on physical key proximity,
     * base dictionary frequency, and length bonus.
     */
    public static float calcNormalizedScore(final String typed, final String candidate, final int candidateFreq) {
        if (typed == null || candidate == null) {
            return 0.0f;
        }
        final String normTyped = StringUtils.toNormalizedLower(typed);
        final String normCandidate = StringUtils.toNormalizedLower(candidate);

        if (normTyped.equals(normCandidate)) {
            return (float) candidateFreq;
        }

        final float dist = computeWeightedDistance(normTyped, normCandidate);
        final int maxLen = Math.max(normTyped.length(), normCandidate.length());
        final float relativeDist = dist / Math.max(1, maxLen);
        final float lengthBonus = getLongWordCorrectionBonus(normTyped, normCandidate);

        return (candidateFreq * (1.0f - (relativeDist * 0.4f))) - (dist * 35.0f) + lengthBonus;
    }

    public synchronized void insert(final String word, final int frequency) {
        if (word == null || word.isEmpty()) {
            return;
        }
        final String normalized = StringUtils.toNormalizedLower(word);
        TrieNode current = mRoot;
        for (int i = 0; i < normalized.length(); i++) {
            final char ch = normalized.charAt(i);
            current = current.getOrCreateChild(ch);
        }
        if (current.addWord(word, frequency)) {
            mWordCount++;
        }
        updateTopWord(word, frequency);
    }

    private void updateTopWord(final String word, final int freq) {
        if (word == null || word.isEmpty()) return;
        for (int i = 0; i < mTopWords.size(); i++) {
            if (mTopWords.get(i).word.equalsIgnoreCase(word)) {
                if (freq > mTopWords.get(i).frequency) {
                    mTopWords.remove(i);
                    break;
                } else {
                    return;
                }
            }
        }
        if (mTopWords.size() < 50 || freq > mTopWords.get(mTopWords.size() - 1).frequency) {
            int insertIdx = Collections.binarySearch(mTopWords, new ScoredWord(word, freq, freq));
            if (insertIdx < 0) {
                insertIdx = -insertIdx - 1;
            }
            mTopWords.add(insertIdx, new ScoredWord(word, freq, freq));
            if (mTopWords.size() > 50) {
                mTopWords.remove(mTopWords.size() - 1);
            }
        }
    }

    private void putNGram(final Map<String, Map<String, Short>> storage, final String contextKey, final String word, final int freq) {
        final String normWord = StringUtils.toNormalizedLower(word);
        Map<String, Short> nextMap = storage.get(contextKey);
        if (nextMap == null) {
            nextMap = new HashMap<>();
            storage.put(contextKey, nextMap);
        }
        short currentFreq = nextMap.containsKey(normWord) ? nextMap.get(normWord) : 0;
        short newFreq = (short) Math.min(Short.MAX_VALUE, currentFreq > 0 ? currentFreq + 25 : Math.max(1, freq));
        nextMap.put(normWord, newFreq);
    }

    private int getNGram(final Map<String, Map<String, Short>> storage, final String contextKey, final String word) {
        final Map<String, Short> nextMap = storage.get(contextKey);
        if (nextMap == null) {
            return 0;
        }
        final String normWord = StringUtils.toNormalizedLower(word);
        final Short freq = nextMap.get(normWord);
        return freq != null ? (freq & 0xFFFF) : 0;
    }

    public synchronized void setTrigram(final String w1, final String w2, final String word, final int freq) {
        if (w1 == null || w2 == null || word == null || w1.isEmpty() || w2.isEmpty() || word.isEmpty()) {
            return;
        }
        final String contextKey = StringUtils.toNormalizedLower(w1) + " " + StringUtils.toNormalizedLower(w2);
        putNGram(mTrigrams, contextKey, word, freq);
    }

    public synchronized int getTrigramFrequency(final String w1, final String w2, final String word) {
        if (w1 == null || w2 == null || word == null || w1.isEmpty() || w2.isEmpty() || word.isEmpty()) {
            return 0;
        }
        final String contextKey = StringUtils.toNormalizedLower(w1) + " " + StringUtils.toNormalizedLower(w2);
        return getNGram(mTrigrams, contextKey, word);
    }

    public synchronized void setBigram(final String prevWord, final String word, final int freq) {
        if (prevWord == null || word == null || prevWord.isEmpty() || word.isEmpty()) {
            return;
        }
        final String contextKey = StringUtils.toNormalizedLower(prevWord);
        putNGram(mBigrams, contextKey, word, freq);
    }

    public synchronized int getBigramFrequency(final String prevWord, final String word) {
        if (prevWord == null || word == null || prevWord.isEmpty() || word.isEmpty()) {
            return 0;
        }
        final String contextKey = StringUtils.toNormalizedLower(prevWord);
        return getNGram(mBigrams, contextKey, word);
    }

    public synchronized int getWordFrequency(final String word) {
        if (word == null || word.isEmpty()) return 0;
        final String norm = StringUtils.toNormalizedLower(word);
        final TrieNode current = findPrefixNode(norm);
        if (current != null) {
            for (int i = 0; i < current.words.length; i++) {
                if (current.words[i].equalsIgnoreCase(word)) {
                    return current.freqs[i] & 0xFFFF;
                }
            }
        }
        final rkr.simplekeyboard.inputmethod.latin.dict.binary.BinaryTrieDictionary bin = mBinaryDict;
        if (bin != null) {
            final int binFreq = bin.getWordFrequency(word);
            if (binFreq > 0) return binFreq;
        }
        return 0;
    }

    public synchronized List<CharSequence> getSuggestions(final String prefix, final int maxCount) {
        return getSuggestions(prefix, maxCount, null, null);
    }

    public synchronized List<CharSequence> getSuggestions(final String prefix, final int maxCount, final String prevWord) {
        return getSuggestions(prefix, maxCount, null, prevWord);
    }

    public synchronized List<CharSequence> getSuggestions(final String prefix, final int maxCount, final String w1, final String w2) {
        if (prefix == null || prefix.trim().isEmpty() || maxCount <= 0) {
            return Collections.emptyList();
        }

        final String trimmed = prefix.trim();
        final String normPrefix = StringUtils.toNormalizedLower(trimmed);
        final List<ScoredWord> rawWords = new ArrayList<>();

        final TrieNode current = findPrefixNode(normPrefix);
        if (current != null) {
            collectWords(current, rawWords, 40);
        }

        final rkr.simplekeyboard.inputmethod.latin.dict.binary.BinaryTrieDictionary bin = mBinaryDict;
        if (bin != null) {
            final List<CharSequence> binSuggestions = bin.getPrefixSuggestions(trimmed, 40);
            if (binSuggestions != null) {
                for (CharSequence s : binSuggestions) {
                    final String w = s.toString();
                    final int freq = bin.getWordFrequency(w);
                    rawWords.add(new ScoredWord(w, Math.max(1, freq), freq));
                }
            }
        }

        if (rawWords.isEmpty()) {
            return Collections.emptyList();
        }

        final List<ScoredWord> scoredWords = scorePrefixWords(rawWords, normPrefix, w1, w2);
        return formatSuggestions(scoredWords, trimmed, maxCount);
    }

    private TrieNode findPrefixNode(final String normPrefix) {
        TrieNode current = mRoot;
        for (int i = 0; i < normPrefix.length(); i++) {
            current = current.getChild(normPrefix.charAt(i));
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    private List<ScoredWord> scorePrefixWords(final List<ScoredWord> rawWords, final String normPrefix, final String w1, final String w2) {
        final List<ScoredWord> scoredWords = new ArrayList<>(rawWords.size());
        for (ScoredWord sw : rawWords) {
            final float score = calcPrefixWordScore(sw, normPrefix, w1, w2);
            scoredWords.add(new ScoredWord(sw.word, sw.frequency, score));
        }
        Collections.sort(scoredWords);
        return scoredWords;
    }

    private float calcPrefixWordScore(final ScoredWord sw, final String normPrefix, final String w1, final String w2) {
        float score = sw.frequency;
        if (StringUtils.toNormalizedLower(sw.word).equals(normPrefix)) {
            score += 500.0f;
        }
        return score + getPrefixContextBonus(w1, w2, sw.word);
    }

    private float calcContextBonus(final String w1, final String w2, final String word,
            final float triBase, final float triWeight, final float biBase, final float biWeight) {
        final int triFreq = getTrigramFrequency(w1, w2, word);
        if (triFreq > 0) {
            return triBase + (triFreq * triWeight);
        }
        final int biFreq = getBigramFrequency(w2, word);
        if (biFreq > 0) {
            return biBase + (biFreq * biWeight);
        }
        return 0.0f;
    }

    private float getPrefixContextBonus(final String w1, final String w2, final String word) {
        return calcContextBonus(w1, w2, word, 800.0f, 3.0f, 400.0f, 2.0f);
    }

    private List<CharSequence> formatSuggestions(final List<ScoredWord> scoredWords, final String originalPrefix, final int maxCount) {
        final List<CharSequence> results = new ArrayList<>();
        final Set<String> added = new HashSet<>();
        for (ScoredWord sw : scoredWords) {
            if (results.size() >= maxCount) {
                break;
            }
            final String formatted = StringUtils.applyCasing(originalPrefix, sw.word);
            if (added.add(formatted.toLowerCase())) {
                results.add(formatted);
            }
        }
        return results;
    }

    private void collectWords(final TrieNode node, final List<ScoredWord> accumulator, final int maxLimit) {
        for (int i = 0; i < node.words.length; i++) {
            accumulator.add(new ScoredWord(node.words[i], node.freqs[i], node.freqs[i]));
            if (accumulator.size() >= maxLimit) {
                return;
            }
        }
        for (TrieNode child : node.children) {
            collectWords(child, accumulator, maxLimit);
            if (accumulator.size() >= maxLimit) {
                return;
            }
        }
    }

    public static boolean hasDigits(final String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) {
            if (Character.isDigit(s.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasUrlOrEmailSymbol(final String s) {
        if (s == null) return false;
        return s.indexOf('@') >= 0 || s.indexOf('.') >= 0;
    }

    public static boolean hasIntermediateUpperCase(final String s) {
        if (s == null || s.length() <= 1) {
            return false;
        }
        if (StringUtils.isAllUpperCase(s)) {
            return false;
        }
        for (int i = 1; i < s.length(); i++) {
            if (Character.isUpperCase(s.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    public static boolean shouldSkipAutoCorrection(final String word) {
        if (word == null || word.isEmpty()) {
            return true;
        }
        if (hasDigits(word)) {
            return true;
        }
        if (hasUrlOrEmailSymbol(word)) {
            return true;
        }
        if (hasIntermediateUpperCase(word)) {
            return true;
        }
        return false;
    }

    public synchronized boolean containsWord(final String word) {
        if (word == null || word.isEmpty()) {
            return false;
        }
        final String norm = StringUtils.toNormalizedLower(word);
        final TrieNode current = findPrefixNode(norm);
        if (current != null) {
            for (String w : current.words) {
                if (w.equalsIgnoreCase(word)) {
                    return true;
                }
            }
        }
        final rkr.simplekeyboard.inputmethod.latin.dict.binary.BinaryTrieDictionary bin = mBinaryDict;
        return bin != null && bin.containsWord(word);
    }

    private boolean isValidForExactCorrection(final String word) {
        return word != null && word.length() > 1 && !shouldSkipAutoCorrection(word);
    }

    private boolean losesAccent(final String word, final String candidate) {
        return StringUtils.hasAccents(word) && !StringUtils.hasAccents(candidate);
    }

    private boolean isAcceptableExactMatch(final String word, final String candidate, final int candidateFreq) {
        if (losesAccent(word, candidate)) {
            return false;
        }
        final int typedFreq = getWordFrequency(word);
        return typedFreq <= 0 || candidateFreq >= (typedFreq * 1.5f) || Character.isUpperCase(candidate.charAt(0));
    }

    private int findBestTrieWordIndex(final TrieNode node) {
        int bestIdx = 0;
        int bestFreq = node.freqs.length > 0 ? node.freqs[0] : 0;
        for (int i = 1; i < node.words.length; i++) {
            if (node.freqs[i] > bestFreq) {
                bestFreq = node.freqs[i];
                bestIdx = i;
            }
        }
        return bestIdx;
    }

    private CharSequence getCorrectionFromTrie(final String word, final String norm) {
        final TrieNode current = findPrefixNode(norm);
        if (current == null || current.words.length == 0) {
            return null;
        }
        final int bestIdx = findBestTrieWordIndex(current);
        final String bestWord = current.words[bestIdx];
        final int bestFreq = current.freqs.length > bestIdx ? current.freqs[bestIdx] : 0;
        if (isAcceptableExactMatch(word, bestWord, bestFreq)) {
            return StringUtils.applyCasing(word, bestWord);
        }
        return null;
    }

    private CharSequence getCorrectionFromBinary(final String word) {
        final rkr.simplekeyboard.inputmethod.latin.dict.binary.BinaryTrieDictionary bin = mBinaryDict;
        if (bin == null) {
            return null;
        }
        final String canonical = bin.getCanonicalWord(word);
        if (canonical == null || canonical.equalsIgnoreCase(word)) {
            return null;
        }
        final int binFreq = bin.getWordFrequency(canonical);
        if (isAcceptableExactMatch(word, canonical, binFreq)) {
            return StringUtils.applyCasing(word, canonical);
        }
        return null;
    }

    public synchronized CharSequence getExactNormalizedCorrection(final String word) {
        if (!isValidForExactCorrection(word)) {
            return null;
        }
        final String norm = StringUtils.toNormalizedLower(word);
        final CharSequence trieCorr = getCorrectionFromTrie(word, norm);
        if (trieCorr != null) {
            return trieCorr;
        }
        return getCorrectionFromBinary(word);
    }

    public synchronized CharSequence getBestCorrection(final String word) {
        return getBestCorrection(word, null, null);
    }

    public synchronized CharSequence getBestCorrection(final String word, final String prevWord) {
        return getBestCorrection(word, null, prevWord);
    }

    public synchronized CharSequence getBestCorrection(final String word, final String w1, final String w2) {
        if (isSkipCorrection(word)) {
            return null;
        }

        // 1. Instant O(L) exact normalized match (e.g. "mas" -> "más", "autocorreccion" -> "autocorrección", "mexico" -> "México")
        final CharSequence exactNorm = getExactNormalizedCorrection(word);
        if (exactNorm != null) {
            return exactNorm;
        }

        final int typedFreq = getWordFrequency(word);
        final String norm = StringUtils.toNormalizedLower(word);

        // 2. Search fuzzy corrections (Levenshtein + keyboard proximity)
        final ScoredWord bestFuzzy = findBestFuzzyCandidate(norm, w1, w2);
        final CharSequence fuzzyCorr = evaluateFuzzyCorrection(bestFuzzy, word, norm, typedFreq, w1, w2);
        if (fuzzyCorr != null) {
            return fuzzyCorr;
        }

        // 3. Multi-word space segmentation (only if no close fuzzy match was found!)
        // e.g. "notengo" -> "no tengo", "delos" -> "de los", "comoteva" -> "cómo te va"
        return evaluateMultiWordSplit(word, w2, typedFreq);
    }

    private CharSequence evaluateFuzzyCorrection(final ScoredWord bestFuzzy, final String word, final String norm, final int typedFreq, final String w1, final String w2) {
        if (bestFuzzy == null) {
            return null;
        }
        if (isFuzzyCandidateAccepted(bestFuzzy, word, norm, typedFreq, w1, w2)) {
            return StringUtils.applyCasing(word, bestFuzzy.word);
        }
        return null;
    }

    private boolean isFuzzyCandidateAccepted(final ScoredWord bestFuzzy, final String word, final String norm, final int typedFreq, final String w1, final String w2) {
        if (typedFreq == 0) {
            return bestFuzzy.score >= getMinCandidateScore();
        }
        final float dist = computeWeightedDistance(norm, StringUtils.toNormalizedLower(bestFuzzy.word));
        final boolean isHighFreqValidWord = typedFreq >= 70;
        if (!isHighFreqValidWord && bestFuzzy.frequency >= (typedFreq * 2) && dist <= 1.0f) {
            return true;
        }
        return isValidCorrection(bestFuzzy, word, norm, w1, w2, true);
    }

    private CharSequence evaluateMultiWordSplit(final String word, final String w2, final int typedFreq) {
        if (typedFreq != 0) {
            return null;
        }
        final MultiWordSplitter.SplitResult split = MultiWordSplitter.findBestSplit(this, word, w2);
        if (split != null && split.score >= 80.0f) {
            return split.combined;
        }
        return null;
    }

    private boolean isSkipCorrection(final String word) {
        return mAutoCorrectionThreshold <= 0.0f || word == null || word.length() <= 1 || shouldSkipAutoCorrection(word);
    }

    private ScoredWord findBestFuzzyCandidate(final String norm, final String w1, final String w2) {
        final List<ScoredWord> candidates = searchAndScoreFuzzyCandidates(norm, w1, w2);
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    private List<ScoredWord> searchAndScoreFuzzyCandidates(final String norm, final String w1, final String w2) {
        final int maxDistance = getMaxFuzzyDistance(norm.length());
        final List<ScoredWord> rawCandidates = new ArrayList<>();
        searchFuzzy(mRoot, new StringBuilder(), norm, 0, maxDistance, rawCandidates);

        final rkr.simplekeyboard.inputmethod.latin.dict.binary.BinaryTrieDictionary bin = mBinaryDict;
        if (bin != null) {
            bin.searchFuzzy(bin.getRootNode(), new StringBuilder(), norm, 0, maxDistance, rawCandidates);
        }

        if (rawCandidates.isEmpty()) {
            return Collections.emptyList();
        }
        return scoreFuzzyCandidates(rawCandidates, norm, w1, w2);
    }

    private int getMaxFuzzyDistance(final int normLen) {
        if (normLen >= 8) return 3;
        if (normLen >= 4) return 2;
        return 1;
    }

    private List<ScoredWord> scoreFuzzyCandidates(final List<ScoredWord> rawCandidates, final String norm, final String w1, final String w2) {
        final List<ScoredWord> candidates = new ArrayList<>(rawCandidates.size());
        for (ScoredWord cw : rawCandidates) {
            final float score = calcFuzzyCandidateScore(norm, cw.word, cw.frequency, w1, w2);
            candidates.add(new ScoredWord(cw.word, cw.frequency, score));
        }
        Collections.sort(candidates);
        return candidates;
    }

    private float calcFuzzyCandidateScore(final String norm, final String candidateWord, final int candidateFreq, final String w1, final String w2) {
        float score = calcNormalizedScore(norm, candidateWord, candidateFreq);
        return score + getFuzzyContextBonus(w1, w2, candidateWord);
    }

    private float getFuzzyContextBonus(final String w1, final String w2, final String word) {
        return calcContextBonus(w1, w2, word, 600.0f, 2.5f, 300.0f, 1.5f);
    }

    private boolean isValidCorrection(final ScoredWord best, final String word, final String norm, final String w1, final String w2, final boolean isWordValid) {
        if (best.score < getMinCandidateScore()) {
            return false;
        }
        if (isWordValid && !isSuperiorToValidWord(best.score, word, norm, w1, w2)) {
            return false;
        }
        return true;
    }

    private float getMinCandidateScore() {
        if (mAutoCorrectionThreshold >= 3.0f) {
            return -60.0f;
        }
        if (mAutoCorrectionThreshold >= 2.0f) {
            return -25.0f;
        }
        return 0.0f;
    }

    private float getValidWordDelta() {
        if (mAutoCorrectionThreshold >= 3.0f) {
            return 60.0f;
        }
        if (mAutoCorrectionThreshold >= 2.0f) {
            return 120.0f;
        }
        return 200.0f;
    }

    private boolean isSuperiorToValidWord(final float bestScore, final String word, final String norm, final String w1, final String w2) {
        final int selfFreq = getWordFrequency(word);
        float selfScore = calcNormalizedScore(norm, norm, selfFreq) + getFuzzyContextBonus(w1, w2, word);
        return bestScore >= selfScore + getValidWordDelta();
    }

    public synchronized List<CharSequence> getFuzzySuggestions(final String word, final int maxCount) {
        return getFuzzySuggestions(word, maxCount, null, null);
    }

    public synchronized List<CharSequence> getFuzzySuggestions(final String word, final int maxCount, final String prevWord) {
        return getFuzzySuggestions(word, maxCount, null, prevWord);
    }

    public synchronized List<CharSequence> getFuzzySuggestions(final String word, final int maxCount, final String w1, final String w2) {
        if (word == null || word.length() <= 1 || maxCount <= 0) {
            return Collections.emptyList();
        }
        final String norm = StringUtils.toNormalizedLower(word);
        final List<ScoredWord> candidates = searchAndScoreFuzzyCandidates(norm, w1, w2);
        return formatSuggestions(candidates, word, maxCount);
    }

    private void searchFuzzy(final TrieNode node, final StringBuilder currentPath,
                             final String target, final int targetIdx, final int remainingDistance,
                             final List<ScoredWord> candidates) {
        if (remainingDistance < 0 || candidates.size() >= 40) {
            return;
        }
        recordExactLengthMatches(node, target, targetIdx, candidates);

        // 1. Deletion from target (extra character typed by user)
        if (targetIdx < target.length() && remainingDistance > 0) {
            searchFuzzy(node, currentPath, target, targetIdx + 1, remainingDistance - 1, candidates);
        }

        for (int i = 0; i < node.keys.length; i++) {
            exploreFuzzyBranch(node.children[i], node.keys[i], currentPath, target, targetIdx, remainingDistance, candidates);
        }
    }

    private void recordExactLengthMatches(final TrieNode node, final String target, final int targetIdx, final List<ScoredWord> candidates) {
        if (targetIdx == target.length() && node.words.length > 0) {
            for (int i = 0; i < node.words.length; i++) {
                candidates.add(new ScoredWord(node.words[i], node.freqs[i], node.freqs[i]));
            }
        }
    }

    private void exploreFuzzyBranch(final TrieNode child, final char ch, final StringBuilder currentPath,
                                    final String target, final int targetIdx, final int remainingDistance,
                                    final List<ScoredWord> candidates) {
        currentPath.append(ch);
        if (targetIdx < target.length()) {
            exploreMatchOrEdit(child, ch, currentPath, target, targetIdx, remainingDistance, candidates);
        }
        if (remainingDistance > 0) {
            searchFuzzy(child, currentPath, target, targetIdx, remainingDistance - 1, candidates);
        }
        currentPath.setLength(currentPath.length() - 1);
    }

    private void exploreMatchOrEdit(final TrieNode child, final char ch, final StringBuilder currentPath,
                                    final String target, final int targetIdx, final int remainingDistance,
                                    final List<ScoredWord> candidates) {
        if (target.charAt(targetIdx) == ch) {
            searchFuzzy(child, currentPath, target, targetIdx + 1, remainingDistance, candidates);
        } else if (remainingDistance > 0) {
            searchFuzzy(child, currentPath, target, targetIdx + 1, remainingDistance - 1, candidates);
            exploreTransposition(child, ch, currentPath, target, targetIdx, remainingDistance, candidates);
        }
    }

    private void exploreTransposition(final TrieNode child, final char ch, final StringBuilder currentPath,
                                      final String target, final int targetIdx, final int remainingDistance,
                                      final List<ScoredWord> candidates) {
        if (targetIdx + 1 >= target.length() || target.charAt(targetIdx + 1) != ch) {
            return;
        }
        final char nextTargetChar = target.charAt(targetIdx);
        final TrieNode transChild = child.getChild(nextTargetChar);
        if (transChild != null) {
            currentPath.append(nextTargetChar);
            searchFuzzy(transChild, currentPath, target, targetIdx + 2, remainingDistance - 1, candidates);
            currentPath.setLength(currentPath.length() - 1);
        }
    }

    public synchronized String getCanonicalWord(final String word) {
        if (word == null || word.isEmpty()) return word;
        final String norm = StringUtils.toNormalizedLower(word);
        final TrieNode current = findPrefixNode(norm);
        if (current == null) return word;
        if (current.words.length > 0) {
            return current.words[0];
        }
        return word;
    }

    private boolean collectTopNGramWords(final Map<String, Short> nextMap, final List<CharSequence> results, final Set<String> added, final int limit) {
        if (nextMap == null || nextMap.isEmpty()) {
            return false;
        }
        final List<Map.Entry<String, Short>> sortedEntries = new ArrayList<>(nextMap.entrySet());
        Collections.sort(sortedEntries, (a, b) -> Integer.compare(b.getValue() & 0xFFFF, a.getValue() & 0xFFFF));
        for (Map.Entry<String, Short> entry : sortedEntries) {
            final String candidate = getCanonicalWord(entry.getKey());
            if (added.add(candidate.toLowerCase())) {
                results.add(candidate);
                if (results.size() >= limit) {
                    return true;
                }
            }
        }
        return false;
    }

    private static final String[] DEFAULT_FALLBACK_PREDICTIONS = {
            "the", "to", "and", "a", "in", "is", "it", "you", "that", "he",
            "que", "de", "no", "la", "el", "es", "en"
    };

    private boolean predictTrigrams(final String w1, final String w2, final List<CharSequence> results, final Set<String> added, final int limit) {
        if (isNonEmptyText(w1) && isNonEmptyText(w2)) {
            final String key = StringUtils.toNormalizedLower(w1.trim()) + " " + StringUtils.toNormalizedLower(w2.trim());
            return collectTopNGramWords(mTrigrams.get(key), results, added, limit);
        }
        return false;
    }

    private boolean predictBigrams(final String w2, final List<CharSequence> results, final Set<String> added, final int limit) {
        if (isNonEmptyText(w2)) {
            final String key = StringUtils.toNormalizedLower(w2.trim());
            return collectTopNGramWords(mBigrams.get(key), results, added, limit);
        }
        return false;
    }

    private boolean isNonEmptyText(final String text) {
        return text != null && !text.trim().isEmpty();
    }

    private void collectTopDictionaryWords(final List<CharSequence> results, final Set<String> added, final int limit) {
        if (results.size() >= limit) {
            return;
        }
        for (ScoredWord sw : mTopWords) {
            if (addPredictionIfAbsent(sw.word, results, added, limit)) {
                return;
            }
        }
    }

    private void collectDefaultFallbackWords(final List<CharSequence> results, final Set<String> added, final int limit) {
        if (results.size() >= limit) {
            return;
        }
        for (String w : DEFAULT_FALLBACK_PREDICTIONS) {
            if (addPredictionIfAbsent(w, results, added, limit)) {
                return;
            }
        }
    }

    private boolean addPredictionIfAbsent(final String word, final List<CharSequence> results, final Set<String> added, final int limit) {
        if (added.add(word.toLowerCase())) {
            results.add(word);
            return results.size() >= limit;
        }
        return false;
    }

    public synchronized List<CharSequence> getNextWordPredictions(final String prevWord, final int limit) {
        return getNextWordPredictions(null, prevWord, limit);
    }

    public synchronized List<CharSequence> getNextWordPredictions(final String w1, final String w2, final int limit) {
        if (limit <= 0) {
            return Collections.emptyList();
        }
        final List<CharSequence> results = new ArrayList<>(limit);
        final Set<String> added = new HashSet<>();

        if (predictTrigrams(w1, w2, results, added, limit) || predictBigrams(w2, results, added, limit)) {
            return results;
        }

        collectTopDictionaryWords(results, added, limit);
        collectDefaultFallbackWords(results, added, limit);

        return results;
    }

    public synchronized int getWordCount() {
        return mWordCount;
    }

    public synchronized void clear() {
        mRoot = new TrieNode();
        mWordCount = 0;
        mTrigrams.clear();
        mBigrams.clear();
        mTopWords.clear();
    }
}

