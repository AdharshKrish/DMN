# Project Context & Implementation Blueprint: Automated DMN Voice Journal & Knowledge Graph

> **HOW TO USE THIS FILE IN A NEW CHAT:**  
> Copy and paste this entire Markdown document at the start of a new conversation with an AI assistant and say:  
> *"Here is the architecture and implementation blueprint for my Automated DMN Voice Journal project. Let's start implementing Phase [1 / 2 / 3 / 4]. Guide me step-by-step."*

---

## 1. System Overview & User Constraints

### Objective
Build a free, private, automated **Default Mode Network (DMN) Voice Journaling & Personal Knowledge Graph** system that replicates and surpasses **Reflect.app** and **AudioPen**.

### Hardware & Environment
- **Mobile Device:** Samsung Galaxy S26 Ultra (Android)
- **Home Server:** Ubuntu Server (`ask-enterprises`) running Docker & WireGuard VPN
- **Note-Taking & Graph Frontend:** Obsidian (Android & Desktop)
- **AI Processing Engine:** Google Gemini API (`gemini-2.5-flash` via Google AI Studio Free Tier)
- **Sync Layer:** Syncthing (Docker container on Ubuntu Server + `Syncthing-Fork` on Android, configured for Local LAN / WireGuard discovery only, avoiding Nextcloud and third-party internet relays)

### Core User Requirements
1. **No 5-Minute Recording Cutoff:** Bypass Gboard Rambler's 5-minute timeout by recording raw audio directly inside Obsidian (using the Core Audio Recorder plugin or Samsung Voice Recorder) into an inbox folder (`00_Audio_Inbox/`).
2. **Offline-First Recording, Deferred Cloud Processing:** The user may be offline while recording a 15–45 minute stream-of-consciousness DMN session. Whenever network connectivity is available, the queued audio file is sent in a single pass (zero audio chunking required) to the Gemini API.
3. **99.9% Gboard Rambler Cleanup + DMN Structuring:** Gemini must first transform the wandering spoken recording into clean, well-structured first-person prose (removing filler words, false starts, and repetitions while preserving exact voice and tone), and then extract structured DMN insights (**Core Realizations**, **Past Solutions & Patterns**, **Future Goals & Action Items**).
4. **Reflect.app-Style Bi-Directional Knowledge Graph:** Before sending the audio to Gemini, the automation script scans the Obsidian Vault for existing note titles/nodes (`[[Concept]]`, `[[Project]]`, `[[Person]]`, `[[Mental Model]]`) and feeds that list into the prompt. Gemini automatically weaves `[[WikiLinks]]` into the transcript and synthesis, linking today's thought to past thoughts and creating a navigable graph inside Obsidian.

---

## 2. Architectural Secret Bonus: Where Should the Python Processor Run?

Because **Step 3 (Syncthing)** mirrors your `ObsidianVault` folder between your Galaxy S26 Ultra and your Ubuntu Server, you have **two choices** for where the Python Gemini Watcher script lives:

- **Deployment Mode A: Run on the Ubuntu Server (Recommended for Zero Phone Battery Drain)**  
  You record offline on your S26 Ultra into `00_Audio_Inbox/`. As soon as your phone has Wi-Fi or WireGuard connectivity, Syncthing pushes the `.m4a`/`.webm` file to your Ubuntu Server. The Docker/systemd Python watcher on your Ubuntu Server detects the new audio file, calls the Gemini API with your Vault's existing note index, appends the linked Markdown to `01_Daily_Notes/YYYY-MM-DD.md`, and Syncthing instantly syncs the finished note back to your phone.
- **Deployment Mode B: Run Directly on S26 Ultra via Termux**  
  The exact same Python script runs in Termux on your phone, processes the audio whenever mobile data/Wi-Fi is active, and Syncthing backs up the finished Markdown to your Ubuntu Server when you get home.

*(The Python script below is 100% compatible with both modes!)*

---

## 3. Phase 1: Obsidian Vault & Graph Setup

### 3.1 Directory Structure
Create the following folders inside your root `ObsidianVault` directory:
```text
ObsidianVault/
├── 00_Audio_Inbox/          # Raw voice recordings land here
├── 01_Daily_Notes/          # Daily DMN entries (YYYY-MM-DD.md)
├── 02_Atomic_Nodes/         # Evergreen topic hubs, projects, people, mental models
│   ├── Concepts/
│   ├── Projects/
│   └── People/
├── 03_Templates/            # Note templates
└── 99_Audio_Archive/        # Processed audio files moved here
```

### 3.2 Core Settings in Obsidian (Android & Desktop)
1. **Core Plugins -> Audio Recorder:**
   - Enable **Audio recorder**.
   - Set **Save location for new recordings** to `00_Audio_Inbox`.
2. **Core Plugins -> Daily Notes:**
   - Date format: `YYYY-MM-DD`
   - New file location: `01_Daily_Notes`
3. **Files & Links:**
   - Automatically update internal links: `ON`
   - Default location for new notes: `02_Atomic_Nodes` (so clicking any newly generated `[[WikiLink]]` creates its hub note neatly inside `02_Atomic_Nodes`).
4. **Graph View Configuration (To Match Reflect.app):**
   - Open Graph View -> **Filters**: Enable *Tags*, *Attachments (Off)*, *Existing files only (Off — keep this OFF so uncreated `[[WikiLinks]]` still appear as connecting nodes in your graph!)*.
   - **Groups (Color Coding):**
     - `path:01_Daily_Notes` -> Blue (Temporal Daily Stream)
     - `tag:#insight` or `tag:#mental-model` -> Purple (Core DMN Nodes)
     - `tag:#goal` or `tag:#project` -> Green (Actionable Future Nodes)

### 3.3 Optional Power-User Graph Plugins (Community Plugins)
- **Excalibrain** or **Juggl:** Renders an interactive, hierarchical mind-map style graph centered on the currently active note (showing Parents, Children, and Friends).
- **Smart Connections:** Uses embeddings to show a sidebar of "Semantically Similar Past Thoughts" even when an exact `[[WikiLink]]` wasn't used.
- **Dataview:** Allows you to query all `Future Goals` or `Past Solutions` across your entire vault automatically.

---

## 4. Phase 2: The Gemini Rambler + Graph Engine (Complete Python Code)

### 4.1 Prerequisites
Install the official Google GenAI SDK and file watcher library:
```bash
pip install google-genai watchdog python-dotenv
```

Get a free API key from [Google AI Studio](https://aistudio.google.com/) and export it:
```bash
export GEMINI_API_KEY="your_free_ai_studio_key"
```

### 4.2 Complete Daemon Script (`dmn_graph_daemon.py`)

```python
import os
import time
import shutil
import socket
from datetime import datetime
from pathlib import Path
from google import genai
from google.genai import types

# ================= CONFIGURATION =================
VAULT_ROOT = Path("/path/to/ObsidianVault")  # Update for Ubuntu Server or Termux
AUDIO_INBOX = VAULT_ROOT / "00_Audio_Inbox"
DAILY_NOTES_DIR = VAULT_ROOT / "01_Daily_Notes"
ATOMIC_NODES_DIR = VAULT_ROOT / "02_Atomic_Nodes"
AUDIO_ARCHIVE = VAULT_ROOT / "99_Audio_Archive"

GEMINI_API_KEY = os.environ.get("GEMINI_API_KEY", "YOUR_API_KEY_HERE")
MODEL_ID = "gemini-2.5-flash"  # Supports up to 9.5 hours of single-pass audio

SUPPORTED_EXTENSIONS = {".m4a", ".mp3", ".wav", ".webm", ".ogg", ".aac"}
POLL_INTERVAL_SECONDS = 15
# =================================================


def has_internet() -> bool:
    """Checks if Google's API endpoint is reachable."""
    try:
        socket.create_connection(("generativelanguage.googleapis.com", 443), timeout=3)
        return True
    except OSError:
        return False


def get_existing_vault_nodes(vault_root: Path) -> list[str]:
    """
    Scans the Obsidian Vault for all existing Markdown note titles (excluding daily dates)
    so Gemini can link to existing nodes just like Reflect.app.
    """
    nodes = []
    for md_file in vault_root.rglob("*.md"):
        # Skip hidden folders (.obsidian) and standard YYYY-MM-DD daily note filenames
        if any(part.startswith(".") for part in md_file.parts):
            continue
        stem = md_file.stem
        if len(stem) == 10 and stem[4] == "-" and stem[7] == "-":
            continue
        nodes.append(stem)
    return sorted(list(set(nodes)))


def build_prompt(existing_nodes: list[str], timestamp_str: str) -> str:
    nodes_context = ", ".join(f"[[{n}]]" for n in existing_nodes[:500]) if existing_nodes else "None yet (propose new atomic [[Concept]] links freely)"

    return f"""
You are an elite Default Mode Network (DMN) cognitive synthesizer and personal knowledge graph architect for an Obsidian vault.

### EXISTING KNOWLEDGE GRAPH NODES IN VAULT:
{nodes_context}

### YOUR TASK:
Listen to the attached raw voice recording and output a structured Obsidian Markdown entry. Follow these exact rules:

1. **PART 1: Rambler-Grade Clean Prose (`### 🎙️ Stream of Consciousness (Cleaned)`)**
   - Act like Gboard Rambler at 99.9% accuracy: transform the natural, wandering spoken recording into clean, articulate, first-person ("I") written prose.
   - Strip all filler words ("um", "uh", "like", "you know"), false starts, stutters, and accidental repetitions.
   - Preserve my exact voice, emotional nuance, vocabulary, and depth of thought. Do NOT over-summarize or water down this section—keep the full richness of the monologue, broken into logical paragraphs.
   - **Bi-Directional Graph Linking:** Whenever I mention a concept, person, project, recurring problem, or mental model that matches an item in the `EXISTING KNOWLEDGE GRAPH NODES` list (or warrants a new evergreen atomic topic node), naturally wrap it in Obsidian WikiLinks like `[[Concept Name]]` directly inline!

2. **PART 2: DMN Cognitive Synthesis (`### 🧠 DMN Synthesis & Graph Connections`)**
   Extract and structure the deeper mental connections from the monologue into four bulleted subsections:
   - **💡 Core Realizations & Insights:** The breakthrough ideas or underlying truths uncovered during this session (with inline `[[WikiLinks]]`).
   - **🔄 Past Solutions & Recognized Patterns:** How today's thoughts connect to past habits, previous problems, or historical solutions.
   - **🎯 Future Goals & Next Actions:** Concrete, actionable next steps formatted as Obsidian tasks (`- [ ] Task description`).
   - **🕸️ Suggested Graph Connections:** Explicitly list 3 to 6 `[[Node Name]]` hubs (existing or new) that this thought clusters around, and briefly state *why* they connect.

3. **PART 3: Inline Metadata Tags**
   - Provide a single line of relevant Obsidian tags at the top of the entry (e.g., `#dmn #journal #insight #architecture`).

### OUTPUT FORMAT (Valid Markdown Only, No Code Fences Wrapping the Response):
## 🕒 DMN Session — {timestamp_str}
**Tags:** #dmn [add 2-4 contextual #tags]
**Linked Hubs:** [comma-separated list of top [[WikiLinks]] used]

### 🧠 DMN Synthesis & Graph Connections
**💡 Core Realizations & Insights**
- ...

**🔄 Past Solutions & Recognized Patterns**
- ...

**🎯 Future Goals & Next Actions**
- [ ] ...

**🕸️ Suggested Graph Connections**
- [[Node A]] ↔ [[Node B]]: Explanation of connection...

### 🎙️ Stream of Consciousness (Cleaned)
[Full Rambler-cleaned first-person prose with natural inline [[WikiLinks]]...]
"""


def process_audio_file(audio_path: Path):
    print(f"[*] Processing audio: {audio_path.name}")
    client = genai.Client(api_key=GEMINI_API_KEY)

    # 1. Gather existing Obsidian graph nodes
    existing_nodes = get_existing_vault_nodes(VAULT_ROOT)
    now = datetime.now()
    date_str = now.strftime("%Y-%m-%d")
    time_str = now.strftime("%I:%M %p")

    # 2. Upload audio to Gemini File API (handles 15-60+ min files in one pass)
    print(f"[*] Uploading {audio_path.name} to Gemini API...")
    uploaded_file = client.files.upload(file=str(audio_path))

    # Wait until audio file state is ACTIVE
    while uploaded_file.state.name == "PROCESSING":
        time.sleep(2)
        uploaded_file = client.files.get(name=uploaded_file.name)

    if uploaded_file.state.name != "ACTIVE":
        raise RuntimeError(f"Audio upload failed with state: {uploaded_file.state.name}")

    # 3. Run single-pass Rambler + DMN Graph synthesis
    prompt = build_prompt(existing_nodes, time_str)
    print(f"[*] Generating Rambler transcript & Knowledge Graph links...")
    response = client.models.generate_content(
        model=MODEL_ID,
        contents=[uploaded_file, prompt],
        config=types.GenerateContentConfig(
            temperature=0.2,  # Low temperature for faithful transcription + accurate linking
        ),
    )

    # 4. Delete temporary file from Google servers immediately for privacy
    client.files.delete(name=uploaded_file.name)

    # 5. Append to Daily Note (creates YYYY-MM-DD.md with YAML frontmatter if new)
    DAILY_NOTES_DIR.mkdir(parents=True, exist_ok=True)
    daily_note_path = DAILY_NOTES_DIR / f"{date_str}.md"

    if not daily_note_path.exists():
        frontmatter = (
            f"---\n"
            f"date: {date_str}\n"
            f"type: daily-dmn\n"
            f"tags:\n  - daily-note\n  - dmn\n"
            f"---\n\n"
            f"# 📅 {date_str}\n"
        )
        daily_note_path.write_text(frontmatter, encoding="utf-8")

    # Archive the audio file and embed a playback link
    AUDIO_ARCHIVE.mkdir(parents=True, exist_ok=True)
    archived_audio_name = f"{now.strftime('%Y%m%d_%H%M%S')}_{audio_path.name}"
    archived_audio_path = AUDIO_ARCHIVE / archived_audio_name
    shutil.move(str(audio_path), str(archived_audio_path))

    entry_markdown = (
        f"\n\n---\n"
        f"{response.text.strip()}\n\n"
        f"> 🎧 **Original Audio:** ![[99_Audio_Archive/{archived_audio_name}]]\n"
    )

    with open(daily_note_path, "a", encoding="utf-8") as f:
        f.write(entry_markdown)

    print(f"[+] Successfully appended DMN entry to {daily_note_path.name}")


def main_loop():
    for folder in [AUDIO_INBOX, DAILY_NOTES_DIR, ATOMIC_NODES_DIR, AUDIO_ARCHIVE]:
        folder.mkdir(parents=True, exist_ok=True)

    print(f"[*] Watching {AUDIO_INBOX} for new voice recordings...")
    while True:
        try:
            audio_files = [
                p for p in AUDIO_INBOX.iterdir()
                if p.is_file() and p.suffix.lower() in SUPPORTED_EXTENSIONS and not p.name.startswith(".syncthing")
            ]
            if audio_files and has_internet():
                for audio_file in sorted(audio_files):
                    # Ensure file finished writing/syncing (size stable for 2 seconds)
                    initial_size = audio_file.stat().st_size
                    time.sleep(2)
                    if audio_file.exists() and audio_file.stat().st_size == initial_size and initial_size > 0:
                        process_audio_file(audio_file)
        except Exception as e:
            print(f"[!] Error in daemon loop: {e}")

        time.sleep(POLL_INTERVAL_SECONDS)


if __name__ == "__main__":
    main_loop()
```

---

## 5. Phase 3: Syncthing LAN / WireGuard Sync to Ubuntu Server

### 5.1 Docker Compose for Ubuntu Server (`docker-compose.yml`)
Deploy Syncthing on your Ubuntu Server so it mirrors your Obsidian Vault directly:

```yaml
services:
  syncthing:
    image: lscr.io/linuxserver/syncthing:latest
    container_name: syncthing-obsidian
    hostname: ask-enterprises-sync
    environment:
      - PUID=1000
      - PGID=1000
      - TZ=Asia/Kolkata
    volumes:
      - ./syncthing-config:/config
      - /home/ubuntu/ObsidianVault:/data/ObsidianVault
    ports:
      - 8384:8384        # Web UI (bind to LAN/WireGuard IP if desired)
      - 22000:22000/tcp  # TCP file transfer
      - 22000:22000/udp  # QUIC file transfer
      - 21027:21027/udp  # Local LAN discovery broadcasts
    restart: unless-stopped
```

### 5.2 Offline-First / Zero-Cloud Syncthing Settings
To ensure Syncthing does **not** rely on third-party internet relay servers:
1. Open the Syncthing Web UI (`http://<ubuntu-server-ip>:8384`) and the **Syncthing-Fork** app on your Galaxy S26 Ultra.
2. Go to **Settings -> Connections**:
   - **Enable Local Discovery:** `ON`
   - **Enable NAT Traversal:** `OFF`
   - **Global Discovery:** `OFF`
   - **Enable Relaying:** `OFF`
3. When adding your Ubuntu Server device inside Syncthing-Fork on your phone, replace `dynamic` in the **Addresses** field with your explicit local LAN IP and WireGuard IP:
   - `tcp://192.168.1.XX:22000, tcp://10.X.X.1:22000`
4. Share the `ObsidianVault` folder between both devices and set **File Versioning** on the Ubuntu Server to **Staggered File Versioning** (protects your vault against accidental deletions).

---

## 6. Quick-Start Checklist for Implementation Chat
- [ ] **Step 1:** Set up Obsidian folder hierarchy and Core Audio Recorder on S26 Ultra.
- [ ] **Step 2:** Spin up the Syncthing Docker container on the Ubuntu Server and pair with S26 Ultra over LAN/WireGuard.
- [ ] **Step 3:** Obtain Google AI Studio API Key and deploy `dmn_graph_daemon.py` as a background service (either on Ubuntu Server watching `/home/ubuntu/ObsidianVault/00_Audio_Inbox` or in Termux on S26 Ultra).
- [ ] **Step 4:** Record a 10-minute test DMN voice session, let it process automatically, and verify the generated `[[WikiLinks]]` inside Obsidian's Graph View.