#!/usr/bin/env python3
"""Download the official self-contained ntsc-rs web build for APK packaging."""

from __future__ import annotations

import mimetypes
import re
import sys
from collections import deque
from pathlib import Path, PurePosixPath
from urllib.parse import unquote, urljoin, urlparse, urlunparse
from urllib.request import Request, urlopen

ORIGIN = "https://web.ntsc.rs/"
OUTPUT = Path("app/src/main/assets/ntsc-web")
TEXT_TYPES = ("text/", "application/javascript", "application/json", "application/manifest+json")
EXTENSIONS = r"(?:js|mjs|css|wasm|json|webmanifest|png|jpe?g|svg|ico|webp|woff2?|ttf|mp4)"
URL_PATTERN = re.compile(
    rf"(?P<url>(?:https://web\.ntsc\.rs/|/|\./|\.\./)?[^\s\"'`()<>]+?\.{EXTENSIONS}(?:\?[^\s\"'`()<>]*)?)",
    re.IGNORECASE,
)
HTML_PATTERN = re.compile(r"(?:src|href)\s*=\s*[\"']([^\"']+)[\"']", re.IGNORECASE)


def normalized(url: str, base: str) -> str | None:
    absolute = urljoin(base, url)
    parsed = urlparse(absolute)
    if parsed.scheme != "https" or parsed.netloc != "web.ntsc.rs":
        return None
    path = parsed.path or "/"
    if path.endswith("/"):
        path += "index.html"
    return urlunparse(("https", parsed.netloc, path, "", parsed.query, ""))


def output_path(url: str) -> Path:
    path = unquote(urlparse(url).path).lstrip('/') or "index.html"
    safe = PurePosixPath(path)
    if ".." in safe.parts:
        raise ValueError(f"unsafe path: {path}")
    return OUTPUT.joinpath(*safe.parts)


def main() -> int:
    OUTPUT.mkdir(parents=True, exist_ok=True)
    queue = deque([ORIGIN, urljoin(ORIGIN, "manifest.webmanifest")])
    seen: set[str] = set()
    wasm_files = 0

    while queue:
        candidate = queue.popleft()
        url = normalized(candidate, ORIGIN)
        if not url or url in seen:
            continue
        seen.add(url)
        request = Request(url, headers={"User-Agent": "MotorZoom offline packager"})
        try:
            with urlopen(request, timeout=60) as response:
                data = response.read()
                content_type = response.headers.get_content_type()
                final_url = response.geturl()
        except Exception as error:
            # Optional icons/manifests can be absent; index and WASM are checked below.
            print(f"warning: {url}: {error}", file=sys.stderr)
            continue

        destination = output_path(final_url)
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(data)
        if destination.suffix == ".wasm":
            wasm_files += 1

        guessed, _ = mimetypes.guess_type(str(destination))
        is_text = content_type.startswith(TEXT_TYPES) or (guessed or "").startswith("text/") or destination.suffix in {".js", ".mjs", ".json", ".webmanifest"}
        if not is_text:
            continue
        text = data.decode("utf-8", errors="ignore")
        links = HTML_PATTERN.findall(text) + [m.group("url") for m in URL_PATTERN.finditer(text)]
        for link in links:
            resolved = normalized(link.replace("\\/", "/"), final_url)
            if resolved and resolved not in seen:
                queue.append(resolved)

        # Absolute production URLs must point back to the local server at runtime.
        rewritten = text.replace("https://web.ntsc.rs/", "/")
        if rewritten != text:
            destination.write_text(rewritten, encoding="utf-8")

    index = OUTPUT / "index.html"
    if not index.is_file() or index.stat().st_size == 0:
        raise SystemExit("official ntsc-rs index.html was not downloaded")
    if wasm_files == 0:
        raise SystemExit("no WebAssembly module was found; refusing to build an incomplete offline APK")
    print(f"Bundled {len(seen)} ntsc-rs resources, including {wasm_files} WebAssembly module(s).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
