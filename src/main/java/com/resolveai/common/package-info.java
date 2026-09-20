/**
 * Cross-cutting types shared by every module.
 *
 * <p><b>Owns:</b> {@code ApiProblem}, {@code ErrorCode}, the cursor and offset page wrappers, and the base DTO and mapper conventions.
 *
 * <p><b>May depend on:</b> <b>Nothing.</b> Not another module, and not Spring Data.
 *
 * <p>If something here needs to import another module, it does not belong here. This package is the leaf of the dependency graph, and keeping it a leaf is what stops it becoming the dumping ground every project eventually grows.
 */
package com.resolveai.common;
