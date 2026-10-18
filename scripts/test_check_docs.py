# SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
# Required Notice: Copyright (c) 2026 Maksim Bespalov.
# Required Notice: Bmaps — https://github.com/besmax/Bmaps
# License: https://polyformproject.org/licenses/noncommercial/1.0.0
# Commercial permissions: see COMMERCIAL-LICENSE.md in the project root.

from pathlib import Path
import tempfile
import unittest

from check_docs import anchors, check, registered_modules


class DocumentationChecksTest(unittest.TestCase):
    def setUp(self):
        workspace = tempfile.TemporaryDirectory()
        self.addCleanup(workspace.cleanup)
        self.root = Path(workspace.name)
        self.write('settings.gradle.kts', 'include(":shared", ":core:storage")\nincludeBuild("build-logic")\n')
        self.write('shared/build.gradle.kts', '')
        self.write('core/storage/build.gradle.kts', '')
        self.write('docs/3_MODULES.md', '# Modules\n\n* **`:shared`**\n* **`core:storage`**\n')
        self.write('docs/guide.md', '# Guide\n\n## Current state\n')
        self.write('AGENTS.md', '# Documentation\n\n| Task | Documentation |\n| --- | --- |\n'
                   '| Modules | [Registry](docs/3_MODULES.md) |\n'
                   '| Behavior | [Guide](docs/guide.md#current-state) |\n')

    def write(self, name, text):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding='utf-8')

    def errors(self):
        return check(self.root)[0]

    def assert_error(self, expected):
        self.assertTrue(any(expected in error for error in self.errors()), self.errors())

    def test_valid_repository(self):
        self.assertEqual(self.errors(), [])

    def test_relative_encoded_and_reference_links(self):
        self.write('README.md', '[Space](<docs/file name.md#раздел>)\n'
                   '[guide][current]\n\n[current]: docs/guide.md#current-state\n'
                   '[external](https://example.invalid/missing#heading)\n')
        self.write('docs/file name.md', '# Раздел\n\n[Home](../README.md)\n')
        with (self.root / 'AGENTS.md').open('a') as guide:
            guide.write('| Extra | [Extra](<docs/file name.md>) |\n')
        self.assertEqual(self.errors(), [])

    def test_missing_file_reports_source_line(self):
        self.write('README.md', '# Readme\n\n[Broken](docs/absent.md)\n')
        self.assert_error('README.md:3: missing link target: docs/absent.md')

    def test_missing_anchor(self):
        self.write('README.md', '[Broken](docs/guide.md#removed-section)\n')
        self.assert_error('missing Markdown anchor: docs/guide.md#removed-section')

    def test_duplicate_headings_use_unique_github_anchors(self):
        self.assertEqual(anchors('# A\n## A\n## A-1\n## A\n'), {'a', 'a-1', 'a-1-1', 'a-2'})
        self.write('README.md', '## Details\n## Details\n[Second](#details-1)\n')
        self.assertEqual(self.errors(), [])

    def test_fences_comments_and_inline_examples_are_ignored(self):
        self.write('README.md', '````md\n# Fake\n[Fake](absent.md)\n```\n[Still fake](absent.md)\n````\n'
                   '<!-- [Comment](absent.md) -->\n`[Example](absent.md)`\n'
                   '~~~\n[Fake](absent.md)\n~~~\n[Real](docs/guide.md#current-state)\n')
        self.assertEqual(self.errors(), [])
        self.assertNotIn('fake', anchors((self.root / 'README.md').read_text()))

    def test_code_spans_with_backticks_and_newlines_preserve_diagnostic_line(self):
        self.write('README.md', '``[Fake `code`](missing.md)``\n'
                   '`multi\n[Example](absent.md)`\n[Real](removed.md)\n')
        self.assertEqual(self.errors(), ['README.md:4: missing link target: removed.md'])

    def test_setext_and_explicit_html_anchor(self):
        self.write('README.md', 'Readme\n======\n\n<a id="target"></a>\n[Title](#readme) [Target](#target)\n')
        self.assertEqual(self.errors(), [])

    def test_new_document_requires_task_guide_entry(self):
        self.write('docs/new.md', '# New\n')
        self.assert_error('documentation absent from task guide: docs/new.md')

    def test_maintenance_link_does_not_replace_task_guide_entry(self):
        self.write('docs/new.md', '# New\n')
        with (self.root / 'AGENTS.md').open('a') as guide:
            guide.write('\nFor tasks spanning multiple areas, read relevant sections.\n[New](docs/new.md)\n')
        self.assert_error('documentation absent from task guide: docs/new.md')

    def test_legacy_backtick_guide_path_is_checked(self):
        with (self.root / 'AGENTS.md').open('a') as guide:
            guide.write('| Missing | `docs/missing.md` |\n')
        self.assert_error('missing documentation path: docs/missing.md')

    def test_repository_escape_is_rejected(self):
        self.write('README.md', '[Outside](../outside.md)\n')
        self.assert_error('local link escapes repository')

    def test_module_registry_drift_is_reported_both_ways(self):
        self.write('docs/3_MODULES.md', '# Modules\n\n* **`:shared`**\n* **`:core:obsolete`**\n')
        self.assert_error('registered module missing from registry: :core:storage')
        self.assert_error('module not registered in settings: :core:obsolete')

    def test_registered_module_requires_build_file(self):
        (self.root / 'core/storage/build.gradle.kts').unlink()
        self.assert_error('module build file missing: :core:storage')

    def test_duplicate_module_entries(self):
        self.write('settings.gradle.kts', 'include(":shared", ":shared", ":core:storage")')
        with (self.root / 'docs/3_MODULES.md').open('a') as registry:
            registry.write('* **`:shared`**\n')
        self.assert_error('settings.gradle.kts: duplicate module: :shared')
        self.assert_error('docs/3_MODULES.md: duplicate module: :shared')

    def test_commented_includes_are_ignored(self):
        modules = registered_modules('// include(":obsolete")\n'
                                     '/* include(":old") */\n'
                                     'includeBuild("build-logic")\n'
                                     'include(":shared", /* comment */ ":core:storage",)\n')
        self.assertEqual(modules, [':shared', ':core:storage'])

    def test_dynamic_includes_fail_explicitly(self):
        self.write('settings.gradle.kts', 'include(projectModule)')
        self.assert_error('Module checks require literal include')


if __name__ == '__main__':
    unittest.main()
