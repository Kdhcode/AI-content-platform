"""Tests for tools/codex_review.py. Run: python -m unittest discover -s tools -p "test_*.py" -v

A fake `codex` executable is put first on PATH, so no AI service is called. It records the
arguments and prompt it received and behaves according to FAKE_CODEX_MODE.
"""
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import textwrap
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
import codex_review as cr  # noqa: E402

FAKE_CODEX = textwrap.dedent('''\
    import json, os, sys, time
    args = sys.argv[1:]
    prompt = sys.stdin.read()
    record = os.environ['FAKE_CODEX_RECORD']
    with open(record, 'a', encoding='utf-8') as f:
        f.write(json.dumps({'args': args, 'prompt': prompt}) + '\\n')
    mode = os.environ.get('FAKE_CODEX_MODE', 'ok')
    out = args[args.index('-o') + 1]
    if mode == 'fail':
        sys.exit(1)
    if mode == 'sleep':
        time.sleep(30)
    with open(out, 'w', encoding='utf-8') as f:
        f.write('' if mode == 'empty' else 'fake review body')
    ''')


def run(cmd, cwd):
    subprocess.run(cmd, cwd=cwd, check=True, capture_output=True, text=True)


class ReviewToolTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        root = Path(self.tmp.name)
        self.repo = root / 'repo'
        self.repo.mkdir()
        run(['git', 'init', '-q', '-b', 'main'], self.repo)
        run(['git', 'config', 'user.email', 't@example.com'], self.repo)
        run(['git', 'config', 'user.name', 'tester'], self.repo)
        run(['git', 'config', 'commit.gpgsign', 'false'], self.repo)
        # fake codex on PATH
        bindir = root / 'bin'
        bindir.mkdir()
        (bindir / 'fake_codex.py').write_text(FAKE_CODEX, encoding='utf-8')
        if os.name == 'nt':
            (bindir / 'codex.cmd').write_text('@"' + sys.executable + '" "%~dp0fake_codex.py" %*\n')
        else:
            shim = bindir / 'codex'
            shim.write_text('#!/bin/sh\nexec "' + sys.executable + '" "$(dirname "$0")/fake_codex.py" "$@"\n')
            shim.chmod(0o755)
        self.record = root / 'calls.jsonl'
        self.env = mock.patch.dict(os.environ, {
            'PATH': str(bindir) + os.pathsep + os.environ['PATH'],
            'FAKE_CODEX_RECORD': str(self.record), 'FAKE_CODEX_MODE': 'ok'})
        self.env.start()
        self.commit('README.md', 'readme\n', 'initial')

    def tearDown(self):
        self.env.stop()
        self.tmp.cleanup()

    # helpers
    def commit(self, path, content, message, allow_empty=False):
        if path:
            target = self.repo / path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(content, encoding='utf-8')
            run(['git', 'add', path], self.repo)
        run(['git', 'commit', '-q', '-m', message] + (['--allow-empty'] if allow_empty else []), self.repo)
        return cr.git(self.repo, 'rev-parse', 'HEAD')

    def calls(self):
        import json
        if not self.record.exists():
            return []
        return [json.loads(line) for line in self.record.read_text(encoding='utf-8').splitlines()]

    def report(self, sha):
        return cr.reviews_dir(self.repo) / (sha + '.md')

    def lock(self):
        return cr.reviews_dir(self.repo).parent / 'review.lock'

    # ------------------------------------------------------------- commit coverage

    def test_normal_commit_reviewed_with_restricted_codex(self):
        sha = self.commit('src/app.py', 'print("hello")\n', 'add app')
        cr.main(['--repo', str(self.repo)])
        self.assertTrue(self.report(sha).exists())
        call = self.calls()[0]
        self.assertIn('src/app.py', call['prompt'])
        self.assertIn('print("hello")', call['prompt'])
        for flag in ['--sandbox', 'read-only', '--ignore-user-config', '--ignore-rules',
                     'features.shell_tool=false', 'features.apps=false', 'features.plugins=false']:
            self.assertIn(flag, call['args'])
        self.assertEqual(cr.read_state(self.repo), sha)
        self.assertFalse(self.lock().exists())

    def test_root_commit_diff_against_empty_tree(self):
        sha = cr.git(self.repo, 'rev-parse', 'HEAD')
        d = cr.commit_diff(self.repo, sha)
        self.assertEqual(d['kind'], 'root')
        self.assertIn('+readme', d['patch'])

    def test_merge_commit_includes_cleanly_merged_changes(self):
        run(['git', 'checkout', '-q', '-b', 'feature'], self.repo)
        self.commit('feature.txt', 'feature line\n', 'feature work')
        run(['git', 'checkout', '-q', 'main'], self.repo)
        self.commit('main.txt', 'main line\n', 'main work')
        run(['git', 'merge', '-q', '--no-ff', '--no-edit', 'feature'], self.repo)
        merge = cr.git(self.repo, 'rev-parse', 'HEAD')
        # The old `git show` combined diff shows nothing for a clean merge.
        old = cr.git(self.repo, 'show', '--format=', merge)
        self.assertNotIn('feature line', old)
        d = cr.commit_diff(self.repo, merge)
        self.assertEqual(d['kind'], 'merge')
        self.assertIn('+feature line', d['patch'])
        cr.review(self.repo, merge)
        self.assertIn('+feature line', self.calls()[0]['prompt'])
        self.assertIn('병합 커밋', self.report(merge).read_text(encoding='utf-8'))

    def test_empty_commit_reports_without_calling_codex(self):
        sha = self.commit(None, None, 'empty', allow_empty=True)
        self.assertTrue(cr.review(self.repo, sha))
        self.assertIn('빈 커밋', self.report(sha).read_text(encoding='utf-8'))
        self.assertEqual(self.calls(), [])

    def test_commits_between_polls_are_all_reviewed_in_order(self):
        cr.main(['--repo', str(self.repo)])                 # first run: HEAD only
        a = self.commit('a.txt', 'a\n', 'A')
        b = self.commit('b.txt', 'b\n', 'B')
        cr.main(['--repo', str(self.repo)])                 # next poll sees A and B
        prompts = [c['prompt'] for c in self.calls()]
        self.assertEqual(len(prompts), 3)
        self.assertIn('COMMIT: ' + a, prompts[1])
        self.assertIn('COMMIT: ' + b, prompts[2])
        self.assertTrue(self.report(a).exists() and self.report(b).exists())
        self.assertEqual(cr.read_state(self.repo), b)

    def test_failure_in_the_middle_does_not_skip_later_retry(self):
        cr.main(['--repo', str(self.repo)])
        start = cr.read_state(self.repo)
        a = self.commit('a.txt', 'a\n', 'A')
        self.commit('b.txt', 'b\n', 'B')
        os.environ['FAKE_CODEX_MODE'] = 'fail'
        with self.assertRaises(RuntimeError):
            cr.main(['--repo', str(self.repo)])
        self.assertEqual(cr.read_state(self.repo), start)   # state not advanced past the failure
        self.assertFalse(self.report(a).exists())
        self.assertFalse(self.lock().exists())
        os.environ['FAKE_CODEX_MODE'] = 'ok'
        cr.main(['--repo', str(self.repo)])
        self.assertTrue(self.report(a).exists())

    def test_too_many_pending_commits_stops(self):
        cr.main(['--repo', str(self.repo)])
        with mock.patch.object(cr, 'MAX_PENDING', 2):
            for i in range(3):
                self.commit('f%d.txt' % i, 'x\n', 'c%d' % i)
            with self.assertRaises(RuntimeError):
                cr.main(['--repo', str(self.repo)])

    def test_rewritten_history_falls_back_to_head(self):
        cr.state_file(self.repo).write_text('0' * 40 + '\n', encoding='utf-8')
        head = cr.git(self.repo, 'rev-parse', 'HEAD')
        self.assertEqual(cr.pending_commits(self.repo, '0' * 40), [head])

    def test_commit_option_does_not_change_state(self):
        old = cr.git(self.repo, 'rev-parse', 'HEAD')
        self.commit('a.txt', 'a\n', 'A')
        cr.main(['--repo', str(self.repo), '--commit', old])
        self.assertTrue(self.report(old).exists())
        self.assertIsNone(cr.read_state(self.repo))

    # ------------------------------------------------------------- secrets and exclusions

    def test_secret_in_arbitrary_file_name_blocks_sending(self):
        fake_key = 'sk-' + 'A1b2C3d4' * 4
        sha = self.commit('config/settings.txt', 'openai=' + fake_key + '\n', 'add settings')
        with self.assertRaises(RuntimeError) as ctx:
            cr.review(self.repo, sha)
        self.assertIn('config/settings.txt:1', str(ctx.exception))
        self.assertNotIn(fake_key, str(ctx.exception))       # value never echoed
        self.assertEqual(self.calls(), [])
        self.assertFalse(self.report(sha).exists())

    def test_secret_in_context_document_blocks_sending(self):
        # Fake values are assembled at runtime so this file itself passes the scan.
        sha = self.commit('README.md', 'admin password: "' + 'hunter2' * 2 + '"\n', 'bad readme')
        with self.assertRaises(RuntimeError) as ctx:
            cr.review(self.repo, sha)
        self.assertIn('README.md', str(ctx.exception))
        self.assertEqual(self.calls(), [])

    def test_removed_secret_line_also_blocks(self):
        token = 'ghp_' + 'a' * 36
        self.commit('ci.txt', 'token ' + token + '\n', 'leak')
        sha = self.commit('ci.txt', 'token removed\n', 'remove leak')
        with self.assertRaises(RuntimeError) as ctx:
            cr.review(self.repo, sha)
        self.assertIn('삭제된 줄', str(ctx.exception))

    def test_private_key_and_yaml_password_detected(self):
        found = cr.scan_secrets([
            ('a', '-----BEGIN RSA ' + 'PRIVATE KEY-----'),
            ('b', '+  password: s3cretValue99'),
            ('c', 'AWS_KEY=' + 'AKIA' + 'ABCDEFGHIJKLMNOP'),
        ])
        self.assertEqual({loc for loc, _ in found}, {'a', 'b', 'c'})

    def test_placeholders_and_code_references_are_not_flagged(self):
        lines = [
            'password: ${DB_PASSWORD:aicontent}',
            "$env:ADMIN_PASSWORD = '<로컬 관리자 비밀번호>'",
            "ADMIN_PASSWORD='change-me'",
            'api-key: ${OPENAI_API_KEY:}',
            'String apiKey = properties.apiKey();',
            'OPENAI_API_KEY는 환경 변수로 설정한다.',
            'apiKey: "sk-test-not-a-real-key"  # codex-review: allow-secret',
        ]
        self.assertEqual(cr.scan_secrets(('x', l) for l in lines), [])

    def test_excluded_files_not_sent_and_listed(self):
        (self.repo / '.env').write_text('OPENAI_API_KEY=whatever-value-123\n', encoding='utf-8')
        (self.repo / 'app.py').write_text('x = 1\n', encoding='utf-8')
        run(['git', 'add', '-f', '.env', 'app.py'], self.repo)
        run(['git', 'commit', '-q', '-m', 'env and app'], self.repo)
        sha = cr.git(self.repo, 'rev-parse', 'HEAD')
        cr.review(self.repo, sha)
        prompt = self.calls()[0]['prompt']
        self.assertNotIn('whatever-value-123', prompt)
        self.assertIn('제외: .env', prompt)
        self.assertIn('제외: .env', self.report(sha).read_text(encoding='utf-8'))

    def test_only_excluded_files_reports_without_calling_codex(self):
        (self.repo / 'admin-ui').mkdir()
        (self.repo / 'admin-ui' / 'package-lock.json').write_text('{}\n', encoding='utf-8')
        run(['git', 'add', '.'], self.repo)
        run(['git', 'commit', '-q', '-m', 'lockfile'], self.repo)
        sha = cr.git(self.repo, 'rev-parse', 'HEAD')
        self.assertTrue(cr.review(self.repo, sha))
        self.assertIn('제외 대상', self.report(sha).read_text(encoding='utf-8'))
        self.assertEqual(self.calls(), [])

    # ------------------------------------------------------------- failure handling

    def test_cli_failure_not_marked_and_lock_released(self):
        sha = self.commit('a.txt', 'a\n', 'A')
        os.environ['FAKE_CODEX_MODE'] = 'fail'
        with self.assertRaises(RuntimeError):
            cr.main(['--repo', str(self.repo)])
        self.assertFalse(self.report(sha).exists())
        self.assertIsNone(cr.read_state(self.repo))
        self.assertFalse(self.lock().exists())

    def test_empty_output_not_marked(self):
        sha = self.commit('a.txt', 'a\n', 'A')
        os.environ['FAKE_CODEX_MODE'] = 'empty'
        with self.assertRaises(RuntimeError):
            cr.review(self.repo, sha)
        self.assertFalse(self.report(sha).exists())

    def test_timeout_not_marked_and_lock_released(self):
        sha = self.commit('a.txt', 'a\n', 'A')
        os.environ['FAKE_CODEX_MODE'] = 'sleep'
        with self.assertRaises(subprocess.TimeoutExpired):
            cr.main(['--repo', str(self.repo), '--timeout', '2'])
        self.assertFalse(self.report(sha).exists())
        self.assertFalse(self.lock().exists())

    def test_second_instance_refused_while_locked(self):
        self.lock().parent.mkdir(parents=True, exist_ok=True)
        self.lock().write_text('', encoding='utf-8')
        with self.assertRaises(SystemExit) as ctx:
            cr.main(['--repo', str(self.repo)])
        self.assertIn('Another reviewer', str(ctx.exception))
        self.assertTrue(self.lock().exists())                # someone else's lock is left alone
        self.assertEqual(self.calls(), [])

    def test_dry_run_does_not_mark_reviewed(self):
        sha = self.commit('a.txt', 'a\n', 'A')
        cr.main(['--repo', str(self.repo), '--dry-run'])
        self.assertTrue((cr.reviews_dir(self.repo) / (sha + '.prompt.txt')).exists())
        self.assertFalse(self.report(sha).exists())
        self.assertIsNone(cr.read_state(self.repo))
        self.assertEqual(self.calls(), [])


if __name__ == '__main__':
    unittest.main()
