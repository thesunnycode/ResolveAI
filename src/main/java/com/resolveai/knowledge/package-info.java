/**
 * The knowledge base: documents, chunking, embeddings, and hybrid retrieval.
 *
 * <p><b>Owns:</b> {@code knowledge_document}, {@code knowledge_chunk}, the indexing worker, and the lexical plus vector retrieval fused by Reciprocal Rank Fusion.
 *
 * <p><b>May depend on:</b> {@code platform}, {@code common}.
 *
 * <p>RRF fuses <b>ranks</b>, not scores, because {@code ts_rank_cd} and cosine distance are on incomparable scales - normalising them would be inventing a comparison that does not exist.
 */
package com.resolveai.knowledge;
