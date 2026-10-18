# SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
# Required Notice: Copyright (c) 2026 Maksim Bespalov.
# Required Notice: Bmaps — https://github.com/besmax/Bmaps
# License: https://polyformproject.org/licenses/noncommercial/1.0.0
# Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.

import argparse
from collections import Counter
from dataclasses import dataclass
from html import unescape
from pathlib import Path
import re
import sys
import unicodedata
from urllib.parse import unquote, urlsplit

ROOT = Path(__file__).resolve().parents[1]
LINK = re.compile(r'!?\[([^\]\n]*)\]\(\s*(<[^>\n]+>|[^\s)]+)(?:\s+["\'][^\n]*?["\'])?\s*\)')
REFERENCE = re.compile(r'^ {0,3}\[([^\]]+)\]:\s*(<[^>\n]+>|\S+)', re.MULTILINE)
REFERENCE_USE = re.compile(r'!?\[([^\]\n]+)\]\[([^\]\n]*)\]')
MODULE_ENTRY = re.compile(r'^\*\s+\*\*`(:?[\w-]+(?::[\w-]+)*)`\*\*', re.MULTILINE)


@dataclass(frozen=True)
class Link:
    destination: str
    line: int


def markdown_text(source):
    lines = []
    fence = None
    for line in source.splitlines():
        marker = re.match(r'^ {0,3}(`{3,}|~{3,})', line)
        if fence is None and marker:
            fence = marker[1]
            lines.append('')
        elif fence is not None:
            if re.fullmatch(r' {0,3}' + re.escape(fence[0]) + '{' + str(len(fence)) + r',}\s*', line):
                fence = None
            lines.append('')
        else:
            lines.append(line)
    text = '\n'.join(lines)
    return re.sub(r'<!--[\s\S]*?-->', lambda m: '\n' * m[0].count('\n'), text)


def without_code(text):
    return re.sub(r'(?<!`)(`+)(?!`)(.*?)(?<!`)\1(?!`)',
                  lambda m: '\n' * m[0].count('\n'), text, flags=re.DOTALL)


def links(source):
    text = without_code(markdown_text(source))
    result = [Link(m[2].strip('<>'), text.count('\n', 0, m.start()) + 1) for m in LINK.finditer(text)]
    references = {' '.join(m[1].lower().split()): m[2].strip('<>') for m in REFERENCE.finditer(text)}
    result.extend(Link(m[2].strip('<>'), text.count('\n', 0, m.start()) + 1) for m in REFERENCE.finditer(text))
    for match in REFERENCE_USE.finditer(text):
        key = ' '.join((match[2] or match[1]).lower().split())
        if key in references:
            result.append(Link(references[key], text.count('\n', 0, match.start()) + 1))
    return result


def heading_slug(title):
    title = re.sub(r'<[^>]+>', '', title)
    title = re.sub(r'\[([^\]]+)\]\([^)]*\)', r'\1', title)
    title = unescape(title).strip().lower()
    return ''.join(c for c in title if c in '-_ ' or unicodedata.category(c)[0] in 'LNM').replace(' ', '-')


def anchors(source):
    text = markdown_text(source)
    result = set()
    used = set()
    lines = text.splitlines()
    for index, line in enumerate(lines):
        match = re.match(r'^ {0,3}#{1,6}\s+(.+?)\s*#*\s*$', line)
        title = match[1] if match else None
        if title is None and index and re.fullmatch(r' {0,3}(?:=+|-+)\s*', line):
            previous = lines[index - 1].strip()
            if previous and '|' not in previous:
                title = previous
        if title is None:
            continue
        base = heading_slug(title)
        slug = base
        suffix = 0
        while slug in used:
            suffix += 1
            slug = f'{base}-{suffix}'
        used.add(slug)
        result.add(slug)
    result.update(re.findall(r'<(?:a|[a-z][\w-]*)\b[^>]*\b(?:id|name)=["\']([^"\']+)["\']', text, re.I))
    return result


def local_target(source, destination):
    parsed = urlsplit(destination)
    if parsed.scheme or parsed.netloc:
        return None
    path = unquote(parsed.path)
    target = source.parent / path if path else source
    return target.resolve(), unquote(parsed.fragment)


def strip_kotlin_comments(source):
    return re.sub(r'("(?:\\.|[^"\\])*"|//[^\n]*|/\*[\s\S]*?\*/)',
                  lambda m: m[0] if m[0].startswith('"') else '\n' * m[0].count('\n'), source)


def registered_modules(source):
    source = strip_kotlin_comments(source)
    modules = []
    for call in re.finditer(r'\binclude\s*\(([^)]*)\)', source):
        paths = re.findall(r'"(:[\w-]+(?::[\w-]+)*)"', call[1])
        remainder = re.sub(r'"(:[\w-]+(?::[\w-]+)*)"|[\s,]', '', call[1])
        if remainder or not paths:
            raise ValueError('Module checks require literal include(":module") paths in settings.gradle.kts')
        modules.extend(paths)
    if not modules:
        raise ValueError('No literal Gradle module includes found in settings.gradle.kts')
    return modules


def check(root):
    root = root.resolve()
    errors = []
    documents = sorted(set(root.glob('*.md')) | set((root / 'docs').rglob('*.md')))
    anchor_cache = {}
    link_count = 0
    for document in documents:
        for link in links(document.read_text(encoding='utf-8')):
            target = local_target(document, link.destination)
            if target is None:
                continue
            path, fragment = target
            link_count += 1
            location = f'{document.relative_to(root)}:{link.line}'
            if not path.is_relative_to(root):
                errors.append(f'{location}: local link escapes repository: {link.destination}')
            elif not path.exists():
                errors.append(f'{location}: missing link target: {link.destination}')
            elif fragment and path.suffix.lower() == '.md':
                if path not in anchor_cache:
                    anchor_cache[path] = anchors(path.read_text(encoding='utf-8'))
                if fragment not in anchor_cache[path]:
                    errors.append(f'{location}: missing Markdown anchor: {link.destination}')

    guide = root / 'AGENTS.md'
    if not guide.exists():
        errors.append('Missing documentation guide: AGENTS.md')
    else:
        guide_source = guide.read_text(encoding='utf-8')
        guide_text = markdown_text(guide_source)
        guide_text = guide_text.split('## Documentation maintenance', 1)[0]
        guide_text = guide_text.split('For tasks spanning multiple areas,', 1)[0]
        references = {target[0] for link in links(guide_text)
                      if (target := local_target(guide, link.destination)) is not None}
        for name in re.findall(r'`(docs/[^`\s#]+\.md)(?:#[^`]*)?`', guide_text):
            path = (root / name).resolve()
            references.add(path)
            if not path.is_file():
                errors.append(f'AGENTS.md: missing documentation path: {name}')
        for document in sorted((root / 'docs').rglob('*.md')):
            if document.resolve() not in references:
                errors.append(f'AGENTS.md: documentation absent from task guide: {document.relative_to(root)}')

    settings = root / 'settings.gradle.kts'
    registry = root / 'docs/3_MODULES.md'
    if not settings.is_file() or not registry.is_file():
        errors.append('Module validation requires settings.gradle.kts and docs/3_MODULES.md')
    else:
        try:
            included = registered_modules(settings.read_text(encoding='utf-8'))
        except ValueError as error:
            errors.append(str(error))
        else:
            documented = [':' + name.lstrip(':') for name in MODULE_ENTRY.findall(markdown_text(registry.read_text(encoding='utf-8')))]
            for origin, modules in [('settings.gradle.kts', included), ('docs/3_MODULES.md', documented)]:
                for module, count in Counter(modules).items():
                    if count > 1:
                        errors.append(f'{origin}: duplicate module: {module}')
            for module in sorted(set(included) - set(documented)):
                errors.append(f'docs/3_MODULES.md: registered module missing from registry: {module}')
            for module in sorted(set(documented) - set(included)):
                errors.append(f'docs/3_MODULES.md: module not registered in settings: {module}')
            for module in sorted(set(included)):
                directory = root.joinpath(*module.lstrip(':').split(':'))
                if not (directory / 'build.gradle.kts').is_file():
                    errors.append(f'settings.gradle.kts: module build file missing: {module}')
    return errors, len(documents), link_count


def main():
    parser = argparse.ArgumentParser(description='Check local Markdown links/anchors, AGENTS discovery and the Gradle module registry.')
    parser.parse_args()
    errors, documents, link_count = check(ROOT)
    for error in errors:
        print(error, file=sys.stderr)
    print(f'Checked {documents} Markdown documents and {link_count} local links; {len(errors)} issue(s).')
    return bool(errors)


if __name__ == '__main__':
    sys.exit(main())
