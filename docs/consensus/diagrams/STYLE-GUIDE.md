# Graphviz Style Guide

Conventions for Tessellation `.dot` diagrams. Goal: readable, consistent, minimal whitespace waste.

## Layout

```dot
// Always set these graph-level attributes
rankdir=TB;           // Top-to-bottom (default). Use LR only for timelines.
nodesep=0.6;          // Horizontal spacing between siblings (default 0.25 is too tight)
ranksep=0.8;          // Vertical spacing between ranks (default 0.5 is tight for labels)
```

- Use `rank=same { A; B; }` to force nodes onto the same row
- Use `constraint=false` on edges that shouldn't influence rank placement
- Use invisible edges (`style=invis`) only for legend anchoring, not layout hacks

## Nodes

```dot
// Global defaults — set once at graph level
node [
    shape=box,
    style="rounded,filled",
    fontname="Helvetica",
    fontsize=12,
    margin="0.2,0.1"       // horizontal,vertical padding inside node
];
```

- **Boxes for states/actions:** `shape=box, style="rounded,filled"`
- **Diamonds for decisions:** `shape=diamond` (keep labels short — 2-3 words max)
- **Circles for start/end:** `shape=circle` or `shape=doublecircle`
- **No shape for labels:** `shape=plaintext` or `shape=none, margin=0`
- Avoid `shape=record` — use HTML labels instead

### Node sizing

- Let Graphviz auto-size based on label content (don't set `fixedsize=true` unless needed)
- For decision diamonds, keep labels to 2-3 short lines — long text in diamonds creates huge nodes
- Use `\n` in plain labels for line breaks, `<BR/>` in HTML labels

## Edges

```dot
// Global defaults
edge [
    fontname="Helvetica",
    fontsize=10,
    arrowsize=0.8           // Slightly smaller arrows look cleaner
];
```

- Label edges only when the transition isn't obvious from context
- Use `color` and `fontcolor` together (red edge + red label, not red edge + black label)
- Use `penwidth=2` for emphasis, not for all edges
- Use `style=dashed` for suppressed/optional paths
- Use `constraint=false` for back-edges that would otherwise distort ranking

## Colors

Use a limited, consistent palette. These work on both light and dark backgrounds:

| Purpose           | Fill color     | Edge/font color |
|-------------------|----------------|-----------------|
| Normal state      | `"#E8F4FD"`    | black           |
| Active/ready      | `"#D4EDDA"`    | black           |
| Warning/retry     | `"#FFF3CD"`    | black           |
| Error/terminal    | `"#F8D7DA"`    | `"#721c24"`     |
| Neutral/disabled  | `"#F0F0F0"`    | `"#666666"`     |
| Decision diamond  | `"#FFFFFF"`    | black           |
| Recovery flow     | —              | `"#CC0000"`     |
| Leaving flow      | —              | `"#E67E00"`     |

Prefer hex codes over named colors — they render consistently across viewers.
Named colors like `lightcyan`, `lightyellow` vary by renderer.

## Legends

**Always use HTML table legends**, never `subgraph cluster` legends. Cluster legends
create a parallel subgraph that Graphviz lays out side-by-side with the main flow,
wasting horizontal space.

```dot
// Good: HTML table legend, anchored at bottom
Legend [shape=plaintext, label=<
    <TABLE BORDER="0" CELLBORDER="1" CELLSPACING="0" CELLPADDING="4">
    <TR><TD COLSPAN="2"><B>Legend</B></TD></TR>
    <TR><TD BGCOLOR="#E8F4FD">  </TD><TD ALIGN="LEFT">Normal state</TD></TR>
    <TR><TD BGCOLOR="#D4EDDA">  </TD><TD ALIGN="LEFT">Active / ready</TD></TR>
    <TR><TD BGCOLOR="#FFF3CD">  </TD><TD ALIGN="LEFT">Warning / retry</TD></TR>
    <TR><TD BGCOLOR="#F8D7DA">  </TD><TD ALIGN="LEFT">Error / terminal</TD></TR>
    </TABLE>
>];

// Anchor below the last node in the flow
LastNode -> Legend [style=invis];
```

For edge-color legends, use arrows:

```dot
<TR><TD><FONT COLOR="#CC0000">→</FONT></TD><TD ALIGN="LEFT">Recovery flow</TD></TR>
```

## Multi-line labels

Prefer HTML labels over `\n`-separated strings when you need formatting:

```dot
// Plain label (simple cases)
A [label="Step 1\nDo the thing"];

// HTML label (when you need bold, color, or structure)
A [label=<
    <B>Step 1</B><BR/>
    <FONT POINT-SIZE="10">Do the thing</FONT>
>];
```

## Clusters (subgraphs)

Use sparingly — only when grouping genuinely clarifies the diagram.

```dot
subgraph cluster_phase1 {
    label="Phase 1";
    style=rounded;
    color="#CCCCCC";        // Light border, not heavy
    fontsize=11;
    A; B; C;
}
```

- Name must start with `cluster_` for Graphviz to draw the box
- Use light borders (`color="#CCCCCC"`) — dark borders compete with nodes
- Don't nest clusters more than 2 levels deep

## Rendering

```bash
# SVG for web/GitHub (scalable, searchable text)
dot -Tsvg diagram.dot -o diagram.svg

# PNG for embedding in markdown (fixed size, always renders)
dot -Tpng -Gdpi=150 diagram.dot -o diagram.png
```

- Always commit both `.dot` source and rendered `.svg`/`.png`
- PNG at 150 DPI is a good balance of quality vs file size
- SVG is preferred for GitHub — renders inline, text is selectable

## Anti-patterns

❌ **Cluster legends** — push main flow left, waste space
❌ **`shape=record`** — use HTML labels instead
❌ **`fixedsize=true`** on most nodes — let content determine size
❌ **Named colors** — inconsistent rendering (`lightcyan` vs `#E0FFFF`)
❌ **Unlabeled color-coded edges** — always pair with a legend
❌ **Labels on every edge** — only label when the transition isn't obvious
❌ **Deep cluster nesting** — more than 2 levels becomes unreadable
❌ **LR for state machines** — TB reads more naturally for state flows
