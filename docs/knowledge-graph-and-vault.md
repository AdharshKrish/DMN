# Knowledge Graph, Atomic Notes & Vault Management

Synapse DMN adapts a **Reflect / Obsidian**-style networked knowledge model directly onto standard Markdown files stored in your device's Obsidian Vault.

---

## 1. How the Reflect-Style Graph Works

Unlike flat voice-memo apps, Synapse DMN builds an interconnected knowledge graph from your Markdown vault without locking data inside a proprietary database:

| Vault Folder | Node Type | Role in Knowledge Graph |
| :--- | :--- | :--- |
| `01_Daily_Notes/YYYY-MM-DD.md` | **Daily Nodes** (`NodeGroup.DAILY`, blue) | Chronological log containing one or more timestamped `## DMN Session` / `## DMN Note Session` blocks, extracted goals, checklists, and `[[WikiLinks]]`. |
| `02_Atomic_Nodes/**/*.md` | **Atomic / Concept Nodes** (`NodeGroup.INSIGHT`, purple) | Evergreen concept, topic, or principle notes (`Concepts/`, `People/`, `Projects/`, etc.) that aggregate backlinks across multiple Daily Notes. |
| Referenced `[[Topic]]` without a `.md` file yet | **Ghost / Uncreated Nodes** (`NodeGroup.GHOST`, amber) | Any `[[WikiLink]]` mentioned inside Daily Notes or Atomic Notes that does not yet have its own dedicated Markdown file. |

### Why `02_Atomic_Nodes/` Starts Empty
When Gemini synthesizes a recording or text note, it writes the structured session into `01_Daily_Notes/YYYY-MM-DD.md` and tags key ideas with `[[WikiLinks]]`. Those referenced concepts appear immediately in the **Graph** tab as **Ghost Nodes** (dashed/amber outlines).

Instead of cluttering `02_Atomic_Nodes/` with dozens of one-off stubs automatically, Synapse DMN promotes concepts **intentionally**:
- Whenever you see a recurring Ghost Node gaining multiple backlinks across days, tap it in the Graph bottom sheet and tap **Create Atomic Hub Note** (or create a note directly in Obsidian inside `02_Atomic_Nodes/`).
- Creating the stub generates `02_Atomic_Nodes/Concepts/<Title>.md` with YAML frontmatter (`type: atomic-node`, `tags: [insight]`) and headings (`Core Summary`, `Linked Thoughts`), turning the Ghost Node into a solid **Atomic Insight Node** connected via bidirectional backlinks.

---

## 2. Physics-Based Force Graph (`ForceGraphEngine` & `GraphScreen`)

- **Deterministic Layout (`ForceGraphEngine.computeLayout`):**
  1. Initializes node coordinates using a **golden-angle spiral** (`π * (3 - √5)`) for deterministic, evenly spaced placement.
  2. Runs `95` physics simulation iterations combining:
     - **Coulomb pairwise repulsion** (`repulsion = 14000f`) separating nodes proportionally to `1 / d²`.
     - **Hooke spring attraction** along `[[WikiLink]]` edges (`springRestLength = 155f`, `stiffness = 0.045f`).
     - **Center gravity** (`0.018f`) & velocity damping (`0.82f`) to keep disconnected components centered.
  3. Scales node radius dynamically by link degree (`connectionCount`), capped at `38f`.
- **Interactive Controls (`GraphScreen`):**
  - Drag to pan, pinch or tap `+` / `-` buttons to zoom, and tap reset to center the viewport.
  - Filter in real time by node title / `#tag` search or by group chips (**All**, **Daily**, **Atomic Insights**, **Ghost Links**).
  - Tap any node on the canvas to open its **Node Inspector** sheet displaying tags, outgoing `[[WikiLinks]]`, incoming backlinks, a note preview, **Open Note in Vault**, or **Create Atomic Hub Note** (for Ghost Nodes).

---

## 3. Vault Explorer (`VaultScreen`)

The Vault tab provides three sub-views for browsing and managing vault content:
1. **Daily Notes (`01_Daily_Notes/`):**
   - Sorted reverse-chronologically; displays metadata, tags, open/completed task badges, and Markdown session viewer.
   - **Interactive Checklists:** Tap any `- [ ]` or `- [x]` task item directly in the viewer (`toggleMarkdownTask`) to check/uncheck it and persist immediately back to the `.md` file.
   - **Session Deletion:** Each `## DMN ...` session block inside a Daily Note has a delete action (`deleteCompleteSession`) to remove just that session block and all linked archives.
2. **Atomic Nodes (`02_Atomic_Nodes/`):**
   - Lists evergreen `.md` concept notes with link counts and full reader view.
3. **Archives (`98_Transcript_Archive/` & `99_Audio_Archive/`):**
   - **Transcript Archives (`.md`):** Exact verbatim transcriptions from audio recordings or original pasted/typed text notes saved as Markdown so Obsidian indexes them natively. Includes full-text preview and one-tap Copy.
   - **Audio Archives (`.m4a`):** Built-in audio playback (`DmnAudioPlayer` with play/pause, seek scrub bar, and elapsed/total duration) plus standalone deletion.

---

## 4. Complete One-Click DMN Session Deletion

Test recordings, duplicates, or unwanted sessions can be deleted cleanly from **either** `StudioScreen` (Synthesis History cards) or `VaultScreen` (Daily Note session blocks) via `ObsidianVaultRepository.deleteCompleteDmnSession()`:

1. Removes only the targeted `## DMN Session ...` block from `01_Daily_Notes/YYYY-MM-DD.md` using `removeDmnSessionFromMarkdown()`.
   - If other sessions remain on that date, the Daily Note is preserved with clean separators (`---`).
   - If that was the last session on that date, the Daily Note `.md` file itself is deleted.
2. Extracts `![[99_Audio_Archive/...]]` and `![[98_Transcript_Archive/...]]` references (plus matching timestamp stems) and deletes both the archived `.m4a` audio file and `.md` transcript file.
3. Purges matching rows in Room `processing_jobs` and `sync_records`, logs the deleted paths under `VAULT`, and refreshes the inbox/graph/vault flows.
   - If **Phone → Drive** sync runs afterward, those deleted paths are also automatically removed from Google Drive.

---

## 5. Audio Retention & Automatic Pruning

- Configure **Archived Audio Retention** in **Sync -> Archived Audio Retention** (`0` = **Keep Forever**, `7d`, `14d`, `30d`, `90d`, or custom days).
- Uses filename timestamp parsing (`yyyyMMdd_HHmmss_*`, falling back to `DocumentFile.lastModified()`) in `99_Audio_Archive/`.
- Runs automatically on vault refresh and manually via **Prune Expired Audio Now** — deleting expired `.m4a` recordings while **never deleting** your Markdown Daily Notes or `.md` transcripts.

---

Back to [README](../README.md)
