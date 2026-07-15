#!/usr/bin/env python3
"""
create_flare_project.py
------------------------
Flare project generator.

Run this script from inside the folder that contains:
    flare/
    flare-android-client/
    flare-web-client/
    flare_app_template/
    .gitignore
    README.md

It will:
  1. Ask for a project name and a destination folder.
  2. Create <destination>/<project_name>/
  3. Copy flare/, flare-android-client/, flare-web-client/, .gitignore,
     README.md into it EXACTLY as-is (no changes at all).
  4. Copy flare_app_template/ into <destination>/<project_name>/<project_name>/
     renaming every occurrence of "flare_app_template" / "FlareAppTemplate"
     to your new project name (folder names, file names, and file contents).
"""

import os
import re
import shutil
import sys
from pathlib import Path

# ----------------------------------------------------------------------
# Config
# ----------------------------------------------------------------------

STATIC_COPY_ITEMS = [
    "flare",
    "flare-android-client",
    "flare-web-client",
    ".gitignore",
    "README.md",
]

TEMPLATE_DIR_NAME = "flare_app_template"

# Directories we never want to copy anywhere (build artifacts / caches).
EXCLUDE_DIR_NAMES = {
    "_build", "deps", "node_modules", ".git", ".elixir_ls", "cover",
    ".dart_tool", ".idea", ".vscode", "__pycache__",
}

# File patterns we skip everywhere (stale dev DB files, OS junk).
EXCLUDE_FILE_SUFFIXES = (".db", ".db-shm", ".db-wal", ".DS_Store")

OLD_PASCAL = "FlareAppTemplate"
OLD_SNAKE = "flare_app_template"


# ----------------------------------------------------------------------
# Helpers
# ----------------------------------------------------------------------

def snake_case(name: str) -> str:
    """Turn arbitrary user input into a valid Elixir app name (snake_case)."""
    name = name.strip().lower()
    name = re.sub(r"[\s\-]+", "_", name)
    name = re.sub(r"[^a-z0-9_]", "", name)
    name = re.sub(r"_+", "_", name).strip("_")
    if not name:
        raise ValueError("Project name has no valid characters left after cleanup.")
    if name[0].isdigit():
        name = "app_" + name
    return name


def pascal_case(snake: str) -> str:
    """flare_app_template -> FlareAppTemplate"""
    return "".join(word.capitalize() for word in snake.split("_"))


def should_skip_dir(dirname: str) -> bool:
    return dirname in EXCLUDE_DIR_NAMES


def should_skip_file(filename: str) -> bool:
    return filename.endswith(EXCLUDE_FILE_SUFFIXES)


def ignore_patterns_for_static_copy(_dir, names):
    """shutil.copytree ignore callback for the untouched folders."""
    return [n for n in names if should_skip_dir(n) or should_skip_file(n)]


def is_probably_text_file(path: Path) -> bool:
    try:
        with open(path, "rb") as f:
            chunk = f.read(2048)
        chunk.decode("utf-8")
        return True
    except (UnicodeDecodeError, OSError):
        return False


# ----------------------------------------------------------------------
# Core logic
# ----------------------------------------------------------------------

def copy_static_folders(source_root: Path, dest_root: Path):
    for item in STATIC_COPY_ITEMS:
        src = source_root / item
        dst = dest_root / item
        if not src.exists():
            print(f"  ⚠️  Skipping '{item}' — not found next to this script.")
            continue

        if src.is_dir():
            shutil.copytree(src, dst, ignore=ignore_patterns_for_static_copy)
            print(f"  ✅ Copied folder: {item}/")
        else:
            shutil.copy2(src, dst)
            print(f"  ✅ Copied file:   {item}")


def process_template(template_src: Path, app_dst: Path, snake: str, pascal: str):
    """
    Walk flare_app_template/, rename any path component containing
    'flare_app_template', and replace 'FlareAppTemplate' / 'flare_app_template'
    inside every text file's contents.
    """
    for root, dirs, files in os.walk(template_src):
        # prune excluded dirs in-place so os.walk doesn't descend into them
        dirs[:] = [d for d in dirs if not should_skip_dir(d)]

        rel_root = Path(root).relative_to(template_src)
        renamed_rel_root = Path(*[
            part.replace(OLD_SNAKE, snake) for part in rel_root.parts
        ])
        target_dir = app_dst / renamed_rel_root
        target_dir.mkdir(parents=True, exist_ok=True)

        for filename in files:
            if should_skip_file(filename):
                continue

            src_file = Path(root) / filename
            new_filename = filename.replace(OLD_SNAKE, snake)
            dst_file = target_dir / new_filename

            if is_probably_text_file(src_file):
                text = src_file.read_text(encoding="utf-8")
                text = text.replace(OLD_PASCAL, pascal)
                text = text.replace(OLD_SNAKE, snake)
                dst_file.write_text(text, encoding="utf-8")
            else:
                shutil.copy2(src_file, dst_file)


def welcome_banner():
    print(r"""
   _____ _
  |  ___| | __ _ _ __ ___
  | |_  | |/ _` | '__/ _ \
  |  _| | | (_| | | |  __/
  |_|   |_|\__,_|_|  \___|

  Welcome to Flare — Server-Driven UI for Phoenix + DivKit 🔥
""")


def next_steps(dest_root: Path, snake: str):
    app_path = dest_root / snake
    print("\n" + "=" * 64)
    print("🎉  Project created successfully!")
    print("=" * 64)
    print(f"""
Location:
    {dest_root}

Structure:
    {dest_root.name}/
    ├── flare/                  (framework — do not modify)
    ├── flare-android-client/   (client SDK — edit package name etc. as needed)
    ├── flare-web-client/       (client SDK — edit as needed)
    ├── .gitignore
    ├── README.md
    └── {snake}/                (your Phoenix app — renamed from flare_app_template)

NOTE: node_modules/, deps/, _build/, and .git/ folders (if present in the
source) were NOT copied — they're build artifacts and will be regenerated.

--------------------------------------------------------------------
NEXT STEPS
--------------------------------------------------------------------
1) Make sure Elixir + Erlang/OTP are installed:
       elixir -v
   If not installed: https://elixir-lang.org/install.html

2) Move into your new app folder:
       cd "{app_path}"

3) Fetch dependencies:
       mix deps.get

4) Create and migrate the database:
       mix ecto.create
       mix ecto.migrate

5) Start the server:
       mix phx.server

--------------------------------------------------------------------
CLIENTS
--------------------------------------------------------------------
The flare-android-client/ and flare-web-client/ folders were copied
as-is — please update package names, bundle IDs, app names, and
WebSocket URLs in those folders to match your new project.

Happy building! 🔥
""")


def main():
    welcome_banner()

    source_root = Path(__file__).resolve().parent
    template_src = source_root / TEMPLATE_DIR_NAME
    if not template_src.exists():
        print(f"❌ Could not find '{TEMPLATE_DIR_NAME}/' next to this script.")
        print(f"   Make sure create_flare_project.py sits alongside flare/, "
              f"flare-android-client/, flare-web-client/, and {TEMPLATE_DIR_NAME}/.")
        sys.exit(1)

    # ---- Ask for project name ----
    raw_name = input("Project name: ").strip()
    try:
        snake = snake_case(raw_name)
    except ValueError as e:
        print(f"❌ {e}")
        sys.exit(1)
    pascal = pascal_case(snake)

    if snake != raw_name:
        print(f"  ℹ️  Using '{snake}' as the app name (Elixir-safe snake_case).")
    print(f"  ℹ️  Elixir module prefix will be: {pascal}")

    # ---- Ask for destination ----
    raw_dest = input("Destination folder (where the project should be created): ").strip()
    if not raw_dest:
        print("❌ Destination cannot be empty.")
        sys.exit(1)

    destination = Path(raw_dest).expanduser().resolve()
    destination.mkdir(parents=True, exist_ok=True)

    dest_root = destination / snake
    if dest_root.exists():
        overwrite = input(
            f"⚠️  '{dest_root}' already exists. Overwrite it? (y/N): "
        ).strip().lower()
        if overwrite != "y":
            print("Aborted.")
            sys.exit(0)
        shutil.rmtree(dest_root)

    dest_root.mkdir(parents=True)

    print(f"\n📁 Creating project at: {dest_root}\n")

    print("Copying framework & client folders (unchanged)...")
    copy_static_folders(source_root, dest_root)

    print(f"\nGenerating Phoenix app '{snake}' from template...")
    app_dst = dest_root / snake
    app_dst.mkdir(parents=True, exist_ok=True)
    process_template(template_src, app_dst, snake, pascal)
    print(f"  ✅ App generated at: {app_dst}")

    next_steps(dest_root, snake)


if __name__ == "__main__":
    main()