# MPT Diagrams

This directory contains Graphviz DOT files for MPT architecture diagrams.

## Rendering

### Command Line (Graphviz)

```bash
# Install graphviz
brew install graphviz  # macOS
apt install graphviz   # Ubuntu/Debian

# Render PNG
dot -Tpng trie-example.dot -o trie-example.png

# Render SVG (better for docs)
dot -Tsvg trie-example.dot -o trie-example.svg

# Render all
for f in *.dot; do
  dot -Tpng "$f" -o "${f%.dot}.png"
  dot -Tsvg "$f" -o "${f%.dot}.svg"
done
```

### Online Tools

- [Graphviz Online](https://dreampuf.github.io/GraphvizOnline/)
- [Edotor](https://edotor.net/)
- [Viz.js](http://viz-js.com/)

### VS Code

Install the "Graphviz Preview" extension, then use `Ctrl+Shift+V` to preview.

## Diagrams

| File | Description |
|------|-------------|
| `trie-example.dot` | Example trie structure with keys a1b2, a1b3, a2c4 |
| `node-types.dot` | Node type class hierarchy (Leaf, Branch, Extension) |
| `proof-verification.dot` | Inclusion proof verification flowchart |
| `key-structure.dot` | GlobalStateKey structure and field IDs |
| `snapshot-flow.dot` | End-to-end snapshot → MPT → proof flow |

## Mermaid Diagrams

The markdown documentation files also contain Mermaid diagrams which render natively in GitHub and most markdown viewers. See:

- `../architecture.md` - Architecture flowcharts
- `../data-structures.md` - Class diagrams
- `../proof-system.md` - Sequence diagrams
- `../integration.md` - Integration flows
