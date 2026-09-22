package com.resolveai.knowledge.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Splits a document into retrievable spans, hierarchically and never mid-sentence.
 *
 * <h2>The two things that make chunks retrievable rather than merely present</h2>
 *
 * <ol>
 *   <li><b>Offsets into the parent document.</b> {@link ChunkSpec#charStart()} and
 *       {@link ChunkSpec#charEnd()} are recorded now because retrofitting them later
 *       means reindexing the entire corpus — there is no way to recover "where in the
 *       original text was this chunk" from the chunk text alone once other chunking
 *       decisions have changed around it.
 *   <li><b>A heading-path prefix on the embedded form, never on the stored form.</b>
 *       {@link ChunkSpec#text()} is the raw chunk — what a citation quotes.
 *       {@link ChunkSpec#embeddedText()} is {@code "UPI payment failures > Reversal
 *       timeline: <the same text>"} — what actually gets embedded. A chunk reading
 *       "This usually takes 5–7 business days" is nearly unfindable in isolation; with
 *       its heading path it retrieves correctly. This is the single highest-leverage
 *       change available to retrieval quality in this phase, and it costs the ten lines
 *       below rather than a smarter model.
 * </ol>
 *
 * <h2>Splitting order: headings, then paragraphs, then sentences</h2>
 *
 * <p>A heading section that fits the token budget becomes one chunk. One that does not
 * is split at paragraph boundaries; a paragraph still too large is split at sentence
 * boundaries. <b>Never mid-sentence</b> — a chunk ending "...will be credited by" reads
 * as a fragment to both a human and an embedding model, and cites worse than either the
 * sentence before or after it in full.
 */
@Component
public class DocumentChunker {

    /** Target size. Not a hard ceiling — see {@link #TARGET_TOKENS} and the overlap note. */
    private static final int TARGET_TOKENS = 400;

    /**
     * Tokens carried from the end of one chunk into the start of the next.
     *
     * <p>Without overlap, a sentence that happens to fall exactly on a chunk boundary
     * loses the context either side of it for any query that would have matched across
     * that boundary. Fifty tokens is enough to carry the last sentence or two of context
     * without meaningfully inflating the corpus.
     */
    private static final int OVERLAP_TOKENS = 50;

    private static final Pattern HEADING = Pattern.compile("(?m)^(#{1,6})\\s+(.+)$");
    private static final Pattern SENTENCE_BOUNDARY = Pattern.compile("(?<=[.!?])\\s+(?=[A-Z«])");

    /**
     * @param text         the raw chunk, exactly as it appears in the source — what a
     *                     citation quotes back to the agent
     * @param embeddedText {@code text}, prefixed with its heading path. Embedded, never
     *                     stored, never shown.
     * @param headingPath  e.g. {@code "UPI payment failures > Reversal timeline"} — kept
     *                     on the record for {@code KnowledgeChunk} debugging, distinct
     *                     from {@code embeddedText} so the prefix logic lives in one place
     * @param charStart    inclusive offset into the parent document's body
     * @param charEnd      exclusive offset
     */
    public record ChunkSpec(String text, String embeddedText, String headingPath,
                            int charStart, int charEnd, int tokenCount) {
    }

    /** One heading section, before further splitting. */
    private record Section(String headingPath, String body, int bodyStart) {
    }

    public List<ChunkSpec> chunk(String documentBody) {
        List<Section> sections = splitByHeadings(documentBody);
        List<ChunkSpec> chunks = new ArrayList<>();
        for (Section section : sections) {
            chunks.addAll(chunkSection(section));
        }
        return chunks;
    }

    /**
     * Splits on markdown headings, carrying the running heading path (H1 {@code >} H2
     * {@code >} …) into each section.
     *
     * <p>A document with no headings at all is one section whose path is empty — the
     * chunker still works, it simply has nothing to prefix chunks with, which is exactly
     * correct for a short runbook with no structure.
     */
    private List<Section> splitByHeadings(String body) {
        Matcher matcher = HEADING.matcher(body);
        List<int[]> starts = new ArrayList<>();   // {matchStart, matchEnd, level}
        List<String> titles = new ArrayList<>();
        while (matcher.find()) {
            starts.add(new int[]{matcher.start(), matcher.end(), matcher.group(1).length()});
            titles.add(matcher.group(2).trim());
        }
        if (starts.isEmpty()) {
            return List.of(new Section("", body, 0));
        }

        List<Section> sections = new ArrayList<>();
        // Running stack of (level, title) so a level-3 heading inherits its level-1 and
        // level-2 ancestors' titles in the path, and a sibling heading replaces only its
        // own level rather than the whole stack.
        List<String> stack = new ArrayList<>();
        List<Integer> levels = new ArrayList<>();

        for (int i = 0; i < starts.size(); i++) {
            int level = starts.get(i)[2];
            while (!levels.isEmpty() && levels.get(levels.size() - 1) >= level) {
                levels.remove(levels.size() - 1);
                stack.remove(stack.size() - 1);
            }
            stack.add(titles.get(i));
            levels.add(level);

            int contentStart = starts.get(i)[1];
            int contentEnd = i + 1 < starts.size() ? starts.get(i + 1)[0] : body.length();
            String sectionBody = body.substring(contentStart, contentEnd).strip();
            if (!sectionBody.isEmpty()) {
                sections.add(new Section(String.join(" > ", stack), sectionBody,
                        indexOfStrippedStart(body, contentStart, contentEnd)));
            }
        }
        return sections;
    }

    /** Where the stripped body actually starts, so offsets stay exact. */
    private static int indexOfStrippedStart(String body, int from, int to) {
        int i = from;
        while (i < to && Character.isWhitespace(body.charAt(i))) {
            i++;
        }
        return i;
    }

    private List<ChunkSpec> chunkSection(Section section) {
        List<String> paragraphs = List.of(section.body().split("\\n\\s*\\n"));
        List<ChunkSpec> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int currentTokens = 0;
        int cursor = section.bodyStart();
        int chunkStart = cursor;

        for (String paragraph : paragraphs) {
            int paraTokens = estimateTokens(paragraph);
            if (paraTokens > TARGET_TOKENS) {
                // A single paragraph too large on its own: flush what is pending, then
                // split this one at sentence boundaries so no chunk ends mid-sentence.
                if (!current.isEmpty()) {
                    chunks.add(build(section.headingPath(), current.toString(), chunkStart,
                            chunkStart + current.length()));
                    current.setLength(0);
                    currentTokens = 0;
                }
                for (ChunkSpec bySentence : chunkBySentences(section.headingPath(), paragraph,
                        cursor)) {
                    chunks.add(bySentence);
                }
                cursor += paragraph.length() + 2;
                chunkStart = cursor;
                continue;
            }

            if (currentTokens + paraTokens > TARGET_TOKENS && !current.isEmpty()) {
                chunks.add(build(section.headingPath(), current.toString(), chunkStart,
                        chunkStart + current.length()));
                String overlap = tailByTokens(current.toString(), OVERLAP_TOKENS);
                current.setLength(0);
                current.append(overlap);
                currentTokens = estimateTokens(overlap);
                chunkStart = chunkStart + (current.length() - overlap.length());
            }

            if (!current.isEmpty()) {
                current.append("\n\n");
            }
            current.append(paragraph);
            currentTokens += paraTokens;
            cursor += paragraph.length() + 2;
        }

        if (!current.isEmpty()) {
            chunks.add(build(section.headingPath(), current.toString(), chunkStart,
                    chunkStart + current.toString().stripTrailing().length()));
        }
        return chunks;
    }

    /** A paragraph too large to be one chunk, split at sentence boundaries, never mid-clause. */
    private List<ChunkSpec> chunkBySentences(String headingPath, String paragraph, int start) {
        String[] sentences = SENTENCE_BOUNDARY.split(paragraph);
        List<ChunkSpec> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int chunkStart = start;
        int cursor = start;

        for (String sentence : sentences) {
            int sentenceTokens = estimateTokens(sentence);
            if (estimateTokens(current.toString()) + sentenceTokens > TARGET_TOKENS
                    && !current.isEmpty()) {
                chunks.add(build(headingPath, current.toString(), chunkStart,
                        chunkStart + current.length()));
                current.setLength(0);
                chunkStart = cursor;
            }
            if (!current.isEmpty()) {
                current.append(' ');
            }
            current.append(sentence);
            cursor += sentence.length() + 1;
        }
        if (!current.isEmpty()) {
            chunks.add(build(headingPath, current.toString(), chunkStart,
                    chunkStart + current.toString().stripTrailing().length()));
        }
        return chunks;
    }

    private ChunkSpec build(String headingPath, String text, int start, int end) {
        String trimmed = text.strip();
        String embedded = headingPath.isBlank() ? trimmed : headingPath + ": " + trimmed;
        return new ChunkSpec(trimmed, embedded, headingPath, start, start + trimmed.length(),
                estimateTokens(trimmed));
    }

    private static String tailByTokens(String text, int tokens) {
        int chars = tokens * 4;
        return chars >= text.length() ? text : text.substring(text.length() - chars).strip();
    }

    /**
     * Approximate token count.
     *
     * <p>The provider's own tokenizer would be exact; four characters per token is close
     * enough to keep chunks near {@link #TARGET_TOKENS} without a tokenizer dependency
     * for a budget check that only has to be approximately right — a 400-"token" chunk
     * that is actually 480 does not blow any context window this phase uses. It is not
     * used for anything billed; {@code ChatCaller} records the provider's real counts.
     */
    static int estimateTokens(String text) {
        return text == null || text.isBlank() ? 0 : Math.max(1, text.length() / 4);
    }
}
