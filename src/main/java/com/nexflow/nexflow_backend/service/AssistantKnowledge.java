package com.nexflow.nexflow_backend.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The assistant knowledge base (assistant/prompt.md) split into sections, so providers with small
 * request limits (e.g. Groq's free tier: 8,000 tokens per minute) get only the parts relevant to the question.
 *
 * Sections are the "====\nTITLE\n====" blocks; the node reference is split further into one section per node
 * ("1. START", "2. NEXUS", …). The intro, SCOPE and OUTPUT FORMAT are always included.
 */
final class AssistantKnowledge {

    private static final Pattern BANNER = Pattern.compile("(?m)^={10,}\\n(.+)\\n={10,}\\n");
    private static final Pattern NODE_ENTRY = Pattern.compile("(?m)^\\d+\\. ([A-Z_]+)\\b.*$");
    private static final Set<String> ALWAYS = Set.of("SCOPE", "OUTPUT FORMAT — MANDATORY");
    private static final Set<String> STOPWORDS = Set.of(
            "the", "and", "for", "how", "what", "why", "can", "you", "are", "does", "with", "this", "that", "from",
            "into", "about", "have", "has", "was", "were", "will", "would", "should", "could", "there", "their",
            "when", "where", "which", "who", "use", "using", "make", "need", "want", "please", "tell", "show", "give",
            "get", "flow", "flows", "nexflow", "step", "steps", "node", "nodes");

    record Section(String title, String body) {
        String text() {
            return title.isEmpty() ? body : "## " + title + "\n" + body;
        }
    }

    private final String full;
    private final List<Section> sections = new ArrayList<>();

    AssistantKnowledge(String markdown) {
        this.full = markdown;
        Matcher m = BANNER.matcher(markdown);
        int bodyStart = 0;
        String title = "";
        while (m.find()) {
            add(title, markdown.substring(bodyStart, m.start()));
            title = m.group(1).trim();
            bodyStart = m.end();
        }
        add(title, markdown.substring(bodyStart));
    }

    private void add(String title, String body) {
        if (body.isBlank()) return;
        if (!title.startsWith("CURRENT NODE TYPES")) {
            sections.add(new Section(title, body.strip() + "\n"));
            return;
        }
        // One section per node so a question about LOOP doesn't pull in the whole node reference.
        Matcher m = NODE_ENTRY.matcher(body);
        List<Integer> starts = new ArrayList<>();
        List<String> names = new ArrayList<>();
        while (m.find()) {
            starts.add(m.start());
            names.add(m.group(1));
        }
        if (starts.isEmpty()) {
            sections.add(new Section(title, body.strip() + "\n"));
            return;
        }
        for (int i = 0; i < starts.size(); i++) {
            int end = i + 1 < starts.size() ? starts.get(i + 1) : body.length();
            sections.add(new Section("NODE " + names.get(i), body.substring(starts.get(i), end).strip() + "\n"));
        }
    }

    String full() {
        return full;
    }

    List<Section> sections() {
        return sections;
    }

    /**
     * The intro, scope and output rules, a one-line index of every section, then the sections that best match
     * {@code query}, in their original order, until {@code budgetChars} is used up.
     */
    String compact(String query, int budgetChars) {
        List<Section> chosen = new ArrayList<>();
        int used = 0;
        for (Section s : sections) {
            if (s.title().isEmpty() || ALWAYS.contains(s.title())) {
                chosen.add(s);
                used += s.text().length();
            }
        }
        String index = "Other topics you know about (ask a tool or answer generally if not detailed below): "
                + sections.stream().map(Section::title).filter(t -> !t.isEmpty() && !ALWAYS.contains(t))
                .collect(Collectors.joining(", ")) + "\n";
        used += index.length();

        Set<String> words = keywords(query);
        List<Section> ranked = sections.stream()
                .filter(s -> !chosen.contains(s))
                .filter(s -> score(s, words) > 0)
                .sorted(Comparator.comparingInt((Section s) -> score(s, words)).reversed())
                .toList();
        for (Section s : ranked) {
            int size = s.text().length();
            if (used + size > budgetChars) continue;
            chosen.add(s);
            used += size;
        }

        StringBuilder out = new StringBuilder();
        for (Section s : sections) {
            if (chosen.contains(s)) out.append(s.text()).append("\n");
        }
        return out.append(index).toString();
    }

    static Set<String> keywords(String text) {
        if (text == null) return Set.of();
        return Arrays.stream(text.toLowerCase(Locale.ROOT).split("[^a-z0-9_]+"))
                .filter(w -> w.length() >= 3 && !STOPWORDS.contains(w))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** Title hits weigh most; body hits count once per keyword so long sections don't win by size alone. */
    private static int score(Section s, Set<String> words) {
        String title = s.title().toLowerCase(Locale.ROOT).replace('_', ' ');
        String body = s.body().toLowerCase(Locale.ROOT);
        int score = 0;
        for (String w : words) {
            String plain = w.replace('_', ' ');
            if (title.contains(plain) || (plain.endsWith("s") && title.contains(plain.substring(0, plain.length() - 1)))) score += 10;
            if (body.contains(w)) score += 1;
        }
        return score;
    }
}
