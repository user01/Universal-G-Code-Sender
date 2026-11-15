/*
    Copyright 2026 ugs-bridge contributors

    This file is part of Universal Gcode Sender (UGS).

    UGS is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    UGS is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with UGS.  If not, see <http://www.gnu.org/licenses/>.
 */
/**
 * Job core for ugs-bridge (work package J1, SPEC §11): job model, safe points, resume-point finding, resume plans,
 * BitSetter math and probe sequences, the job journal and the origin store.
 *
 * <p>Pure Java: depends only on ugs-core (the G-code parser), Gson and the JDK. No NetBeans, no Swing, and
 * nothing here talks to a machine; probe and resume G-code is returned as text for the caller to send. All file
 * paths are passed in.
 *
 * <h2>Main entry points</h2>
 * <ul>
 *   <li>{@link com.willwinder.ugs.nbp.bridge.job.JobModel#load(java.nio.file.Path)}: G-code (+ sidecar) to lines,
 *       per-line modal state/position/tool, operations, tool table, clearance, safe points.</li>
 *   <li>{@link com.willwinder.ugs.nbp.bridge.job.ResumePointFinder#find}: earliest line that may not have
 *       executed, from the journal.</li>
 *   <li>{@link com.willwinder.ugs.nbp.bridge.job.ResumePlanner#plan}: the {@code ResumePlan} for
 *       {@code POST /job/resume/plan}.</li>
 *   <li>{@link com.willwinder.ugs.nbp.bridge.job.BitSetter}, {@link com.willwinder.ugs.nbp.bridge.job.ProbeSequences}.</li>
 *   <li>{@link com.willwinder.ugs.nbp.bridge.job.JobState} (+ {@link com.willwinder.ugs.nbp.bridge.job.JournalWriter}),
 *       {@link com.willwinder.ugs.nbp.bridge.job.OriginStore}: schema models with atomic writes.</li>
 * </ul>
 *
 * <h2>Post-processor header patterns relied on</h2>
 * Matched on whole-line comments only. To be reconciled with {@code bridge/docs/post-headers.md}.
 * <ul>
 *   <li>Clearance: {@code (CLEARANCE: ... Z+5 ...)} — the first {@code Z<number>} after {@code CLEARANCE:} is the
 *       work clearance height, in the file's units. Without it (and without sidecar {@code clearance_z_work}) the
 *       highest work Z the program moves to is used, with a warning.</li>
 *   <li>Operation headers, two forms:
 *     <ul>
 *       <li>{@code (---- OP 05 | T21 | clear_hi | description ----)}: number, tool, name.</li>
 *       <li>{@code (---- OP 5: name ----)}: tool taken from the last {@code M6} before the op's first motion.</li>
 *     </ul>
 *     An op runs from its header line to the line before the next header, but stops before an {@code M6} in
 *     between (trailing comment/blank lines trimmed), so tool-change blocks belong to no op.</li>
 *   <li>Op feeds: a comment line within 6 lines below the header (before any G-code) containing
 *       {@code feed <cut> / plunge <plunge>} (e.g. {@code (     tool T21 ..., feed 1143 / plunge 381 mm/min)}).</li>
 *   <li>Tool table: comment lines whose tool token {@code T<n>} sits at the comment start, after {@code "<n>. "},
 *       after a {@code LABEL:} (e.g. {@code (TOOL [FIRST]: T02 ...)}, {@code (INSTALL IN COLLET:   T21 ...)},
 *       {@code (  LOAD:  T01 ...)}) or after {@code tool}. Name = text after the token up to 3+ spaces, ", feed"
 *       or the closing paren. Feeds from {@code feed <cut> / plunge <plunge>} on the same line or on a following
 *       continuation comment line (within 2 lines); {@code feed <n> mm/min} alone gives the cut feed; a leading
 *       {@code <d> mm} is read as the diameter (FreeCAD-style tool list). Bracketed {@code [T02]} (the
 *       {@code (OPERATIONS:)} list) is ignored.</li>
 *   <li>Tool-change block feeds: {@code (FEEDS: 1143 mm/min cut, 381 mm/min plunge ...)} belongs to the next
 *       {@code M6} within 20 lines.</li>
 *   <li>FreeCAD fallback: {@code (Begin operation: X)} ... {@code (Finish operation: X)}; ops without motion
 *       (tool-change, fixture) are dropped.</li>
 *   <li>Last fallback: one segment per {@code M6}. Plunge feed falls back to the F of the first downward Z-only
 *       feed move in the op.</li>
 *   <li>Sidecar {@code <program>.job.json} (or {@code <program>.nc.job.json}) wins over headers for operations,
 *       tools and clearance.</li>
 *   <li>G-code conventions assumed from the posts: {@code G53 G0 Z<safe>} before tool changes and at the end,
 *       {@code T<n> M6} (or {@code M6 T<n>}) for tool changes, {@code S.. M3} + {@code G4 P..} after them.</li>
 * </ul>
 *
 * <h2>Conventions</h2>
 * Line numbers are 1-based file lines (the journal's numbering). Work positions are in the file's units at that
 * line; machine positions, WCO, profile values and header/sidecar feeds are mm. Header feeds are mm/min.
 */
package com.willwinder.ugs.nbp.bridge.job;
