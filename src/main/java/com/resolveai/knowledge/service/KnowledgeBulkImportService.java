package com.resolveai.knowledge.service;

import com.resolveai.common.error.ApiException;
import com.resolveai.common.error.ErrorCode;
import com.resolveai.knowledge.domain.DocumentSource;
import com.resolveai.knowledge.web.dto.BulkImportResponse;
import com.resolveai.knowledge.web.dto.SkippedItemResponse;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * Bulk-creates knowledge documents from uploaded CSV rows and/or PDF files, for migrating
 * an existing help center or runbook set instead of pasting each article into the
 * single-document form one at a time.
 *
 * <p>Every item - a CSV row or a whole PDF - funnels into the exact same
 * {@link KnowledgeDocumentService#create} that the single-document form and
 * {@link KnowledgeCorpusLoader} use, so dedup, the outbox publish and async indexing all
 * behave identically regardless of where the document came from. One bad item is a skip
 * with a reason, not a failure of the whole batch - the same shape
 * {@code KnowledgeCorpusLoader} already uses, just fed from an upload instead of a fixed
 * classpath resource and reporting a real result instead of only logging one.
 */
@Service
public class KnowledgeBulkImportService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBulkImportService.class);

    /** Same order of magnitude as other explicit batch caps in this codebase (e.g.
     * {@code TOO_MANY_LINKED_TICKETS}'s 500) - a runaway upload is rejected outright,
     * not silently truncated. */
    private static final int MAX_ITEMS = 500;

    private final KnowledgeDocumentService documents;

    public KnowledgeBulkImportService(KnowledgeDocumentService documents) {
        this.documents = documents;
    }

    public BulkImportResponse importBatch(MultipartFile[] files, DocumentSource sourceForPdfs) {
        if (files == null || files.length == 0) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "No files were uploaded.");
        }

        int imported = 0;
        List<SkippedItemResponse> skipped = new ArrayList<>();

        for (MultipartFile file : files) {
            String filename = file.getOriginalFilename() == null ? "file" : file.getOriginalFilename();
            String lower = filename.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".csv")) {
                Batch batch = importCsv(file, filename);
                imported += batch.imported();
                skipped.addAll(batch.skipped());
            } else if (lower.endsWith(".pdf")) {
                var skip = importPdf(file, filename, sourceForPdfs);
                if (skip.isPresent()) {
                    skipped.add(skip.get());
                } else {
                    imported++;
                }
            } else {
                skipped.add(new SkippedItemResponse(filename, filename,
                        "Unsupported file type — only .csv and .pdf are accepted."));
            }

            if (imported + skipped.size() > MAX_ITEMS) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR,
                        "This batch has more than " + MAX_ITEMS
                        + " items across all files. Split it into smaller uploads.");
            }
        }

        return new BulkImportResponse(imported, skipped);
    }

    private record Batch(int imported, List<SkippedItemResponse> skipped) {
    }

    private Batch importCsv(MultipartFile file, String filename) {
        int imported = 0;
        List<SkippedItemResponse> skipped = new ArrayList<>();
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreSurroundingSpaces(true)
                .setTrim(true)
                .build();

        try (Reader reader = new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8);
             CSVParser parser = CSVParser.parse(reader, format)) {
            int row = 0;
            for (CSVRecord record : parser) {
                row++;
                String label = filename + " row " + row;
                String title = record.isMapped("title") ? record.get("title") : null;
                String body = record.isMapped("body") ? record.get("body") : null;
                String rawSource = record.isMapped("source") ? record.get("source") : null;
                String uri = record.isMapped("uri") ? record.get("uri") : null;

                if (title == null || title.isBlank()) {
                    skipped.add(new SkippedItemResponse(label, title, "title is blank."));
                    continue;
                }
                DocumentSource source = parseImportableSource(rawSource);
                if (source == null) {
                    skipped.add(new SkippedItemResponse(label, title,
                            "source must be RUNBOOK or ARTICLE, was '" + rawSource + "'."));
                    continue;
                }

                try {
                    documents.create(source, title, body, blankToNull(uri));
                    imported++;
                } catch (ApiException e) {
                    skipped.add(new SkippedItemResponse(label, title, e.getMessage()));
                } catch (RuntimeException e) {
                    log.warn("Bulk import: '{}' failed: {}", label, e.toString());
                    skipped.add(new SkippedItemResponse(label, title, "Could not be imported."));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + filename, e);
        } catch (IllegalArgumentException e) {
            skipped.add(new SkippedItemResponse(filename, filename,
                    "Could not read this CSV — check it has a header row: title,body,source,uri."));
        }
        return new Batch(imported, skipped);
    }

    /** Returns the skip, if any — empty means the PDF was imported. */
    private Optional<SkippedItemResponse> importPdf(MultipartFile file, String filename,
                                                    DocumentSource source) {
        String title = filename.replaceAll("(?i)\\.pdf$", "");
        String text;
        try (PDDocument pdf = Loader.loadPDF(file.getBytes())) {
            text = new PDFTextStripper().getText(pdf).trim();
        } catch (IOException e) {
            return Optional.of(new SkippedItemResponse(filename, title,
                    "Could not read this PDF — it may be corrupted."));
        }

        if (text.isEmpty()) {
            return Optional.of(new SkippedItemResponse(filename, title,
                    "No extractable text found — this looks like a scanned PDF, which isn't "
                    + "supported yet."));
        }
        if (text.length() > 50_000) {
            return Optional.of(new SkippedItemResponse(filename, title,
                    "PDF text exceeds the 50,000-character document limit — split it into "
                    + "smaller files."));
        }

        try {
            documents.create(source, title, text, null);
            return Optional.empty();
        } catch (ApiException e) {
            return Optional.of(new SkippedItemResponse(filename, title, e.getMessage()));
        }
    }

    /** {@code RESOLVED_TICKET} is system-only, never admin-authored; anything else is invalid. */
    private static DocumentSource parseImportableSource(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            DocumentSource source = DocumentSource.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            return source == DocumentSource.RESOLVED_TICKET ? null : source;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
