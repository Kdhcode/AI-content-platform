"""Tests for tools/codex_review.py. Run: python -m unittest discover -s tools -p "test_*.py" -v

A fake `codex` executable is put first on PATH, so no AI service is called. It records the
arguments and prompt of every `exec` call and behaves according to FAKE_CODEX_MODE. It also answers
`features list` (like codex-cli: a feature is off when its -c override is given, unless listed in
FAKE_CODEX_FEATURES_ON or left out via FAKE_CODEX_FEATURES_MISSING) and `--version`, and prints a run
log header like the real CLI. CODEX_HOME points at a temporary model cache.
"""
import contextlib
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import textwrap
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
import codex_review as cr  # noqa: E402

FAKE_CODEX = textwrap.dedent('''\
    import json, os, sys, time
    args = sys.argv[1:]
    if '--version' in args:
        print('codex-cli 9.9.9-fake')
        sys.exit(0)
    if 'features' in args:
        on = set(filter(None, os.environ.get('FAKE_CODEX_FEATURES_ON', '').split(',')))
        missing = set(filter(None, os.environ.get('FAKE_CODEX_FEATURES_MISSING', '').split(',')))
        for name in ['apps', 'hooks', 'multi_agent', 'plugins', 'shell_tool', 'unrelated_feature']:
            if name in missing:
                continue
            off = ('features.' + name + '=false') in args and name not in on
            print(name.ljust(41) + 'stable'.ljust(19) + ('false' if off else 'true'))
        sys.exit(0)
    # The review tool writes UTF-8; read bytes so the result does not depend on the console code page.
    prompt = sys.stdin.buffer.read().decode('utf-8')
    record = os.environ['FAKE_CODEX_RECORD']
    with open(record, 'a', encoding='utf-8') as f:
        f.write(json.dumps({'args': args, 'prompt': prompt}) + '\\n')
    mode = os.environ.get('FAKE_CODEX_MODE', 'ok')
    out = args[args.index('-o') + 1]
    if mode == 'fail':
        sys.exit(1)
    if mode == 'sleep':
        time.sleep(30)
    if mode == 'sleep_with_child':
        # Like the Windows npm shim: the process that really works is a descendant.
        import subprocess
        child = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(30)'])
        with open(os.environ['FAKE_CODEX_CHILD_PID'], 'w') as f:
            f.write(str(child.pid))
        time.sleep(30)
    model = args[args.index('-m') + 1] if '-m' in args else 'unknown'
    effort = next((a.split('=', 1)[1] for a in args if a.startswith('model_reasoning_effort=')), 'none')
    print('OpenAI Codex v9.9.9-fake')
    print('model: ' + model)
    print('provider: openai')
    print('approval: never')
    print('sandbox: read-only')
    print('reasoning effort: ' + os.environ.get('FAKE_CODEX_LOG_EFFORT', effort))
    with open(out, 'w', encoding='utf-8') as f:
        f.write('' if mode == 'empty' else 'fake review body')
    print('tokens used')
    print('1,234')
    ''')

MODEL_CACHE = {'models': [{'slug': 'gpt-6.1-sol', 'default_reasoning_level': 'low',
                           'supported_reasoning_levels': [{'effort': e} for e in
                                                          ['low', 'medium', 'high', 'xhigh', 'max']]}]}


def pid_alive(pid):
    if os.name == 'nt':
        out = subprocess.run(['tasklist', '/FI', 'PID eq ' + str(pid), '/NH'],
                             capture_output=True, text=True).stdout
        return str(pid) in out
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    # a killed child of an already-reaped parent may linger as a zombie until init reaps it
    try:
        with open('/proc/' + str(pid) + '/stat') as f:
            return f.read().split(')')[-1].split()[0] != 'Z'
    except OSError:
        return False


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
        self.codex_home = root / 'codex-home'
        self.codex_home.mkdir()
        (self.codex_home / 'models_cache.json').write_text(json.dumps(MODEL_CACHE), encoding='utf-8')
        self.env = mock.patch.dict(os.environ, {
            'PATH': str(bindir) + os.pathsep + os.environ['PATH'], 'CODEX_HOME': str(self.codex_home),
            'FAKE_CODEX_RECORD': str(self.record), 'FAKE_CODEX_MODE': 'ok'})
        for name in ('FAKE_CODEX_FEATURES_ON', 'FAKE_CODEX_FEATURES_MISSING', 'FAKE_CODEX_LOG_EFFORT'):
            os.environ.pop(name, None)
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
        if not self.record.exists():
            return []
        calls = [json.loads(line) for line in self.record.read_text(encoding='utf-8').splitlines()]
        for call in calls:      # on Windows the text-mode pipe turns the prompt's LF into CRLF
            call['prompt'] = call['prompt'].replace('\r\n', '\n')
        return calls

    def report(self, sha):
        return cr.reviews_dir(self.repo) / (sha + '.md')

    def lock(self):
        return cr.reviews_dir(self.repo).parent / 'review.lock'

    def main_output(self, argv):
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            cr.main(argv)
        return buf.getvalue()

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
        self.assertIn('reason=commit object missing', cr.history_log(self.repo).read_text(encoding='utf-8'))

    # regression (f2c985c review #3): the state commit EXISTS but is outside HEAD's first-parent history

    def assert_head_only_and_recorded(self, last):
        head = cr.git(self.repo, 'rev-parse', 'HEAD')
        self.assertEqual(cr.pending_commits(self.repo, last), [head])
        log = cr.history_log(self.repo).read_text(encoding='utf-8')
        self.assertIn('last_processed=' + last, log)
        self.assertIn('head=' + head, log)
        self.assertIn('reason=not in first-parent history', log)

    def test_state_commit_on_other_branch_after_switch(self):
        base = cr.git(self.repo, 'rev-parse', 'HEAD')
        run(['git', 'checkout', '-q', '-b', 'feature'], self.repo)
        feature = self.commit('f.txt', 'f\n', 'feature work')
        run(['git', 'checkout', '-q', 'main'], self.repo)
        for i in range(3):
            self.commit('m%d.txt' % i, 'm\n', 'main %d' % i)
        # old code: rev-list HEAD ^feature returned all 3 main commits instead of HEAD only
        old = cr.lines_of(cr.git(self.repo, 'rev-list', '--first-parent', 'HEAD', '^' + feature))
        self.assertEqual(len(old), 3)
        self.assertNotEqual(base, feature)
        self.assert_head_only_and_recorded(feature)

    def test_state_commit_left_behind_by_reset(self):
        dropped = self.commit('a.txt', 'a\n', 'A (will be dropped)')
        run(['git', 'reset', '-q', '--hard', 'HEAD~1'], self.repo)
        for i in range(2):
            self.commit('b%d.txt' % i, 'b\n', 'B%d' % i)
        cr.git(self.repo, 'cat-file', '-e', dropped + '^{commit}')   # object still exists
        self.assert_head_only_and_recorded(dropped)

    def test_state_commit_only_reachable_through_second_parent(self):
        run(['git', 'checkout', '-q', '-b', 'feature'], self.repo)
        feature = self.commit('f.txt', 'f\n', 'feature work')
        run(['git', 'checkout', '-q', 'main'], self.repo)
        self.commit('m.txt', 'm\n', 'main work')
        run(['git', 'merge', '-q', '--no-ff', '--no-edit', 'feature'], self.repo)
        self.assert_head_only_and_recorded(feature)

    def test_main_with_stale_state_reviews_head_once_and_moves_state(self):
        run(['git', 'checkout', '-q', '-b', 'feature'], self.repo)
        feature = self.commit('f.txt', 'f\n', 'feature work')
        run(['git', 'checkout', '-q', 'main'], self.repo)
        for i in range(3):
            self.commit('m%d.txt' % i, 'm\n', 'main %d' % i)
        head = cr.git(self.repo, 'rev-parse', 'HEAD')
        cr.state_file(self.repo).write_text(feature + '\n', encoding='utf-8')
        cr.main(['--repo', str(self.repo)])
        self.assertEqual(len(self.calls()), 1)
        self.assertIn('COMMIT: ' + head, self.calls()[0]['prompt'])
        self.assertEqual(cr.read_state(self.repo), head)

    def test_state_in_first_parent_history_still_walks_forward(self):
        start = cr.git(self.repo, 'rev-parse', 'HEAD')
        a = self.commit('a.txt', 'a\n', 'A')
        b = self.commit('b.txt', 'b\n', 'B')
        self.assertEqual(cr.pending_commits(self.repo, start), [a, b])
        self.assertEqual(cr.pending_commits(self.repo, b), [])
        self.assertFalse(cr.history_log(self.repo).exists())

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
            'password: ${DB_PASSWORD}',
            'password: ${DB_PASSWORD:}',
            'password: ${DB_PASSWORD:?DB_PASSWORD is required}',
            'password: ${DB_PASSWORD:-change-me}',
            "$env:ADMIN_PASSWORD = '<로컬 관리자 비밀번호>'",
            "ADMIN_PASSWORD='change-me'",
            'api-key: ${OPENAI_API_KEY:}',
            'String apiKey = properties.apiKey();',
            'this.password = password;',
            'if (password == null) return;',
            'boolean ok = apiKey != expectedKey;',
            'api_key = os.environ["OPENAI_API_KEY"]',
            "line = 'ADMIN_PASSWORD=' + value + ' # production'",
            'body = "admin password: \\"" + word * 2 + "\\"\\n"',
            'OPENAI_API_KEY는 환경 변수로 설정한다.',
            'apiKey: "sk-test-not-a-real-key"  # codex-review: allow-secret',
        ]
        self.assertEqual(cr.scan_secrets(('x', l) for l in lines), [])

    # regression (f2c985c review #1): trailing comments and spaces inside quotes

    SECRET = 'Pr0d' + 'Secret' + '42x'          # assembled at runtime; not a literal in this file

    def assert_flagged(self, line):
        self.assertEqual([k for _, k in cr.scan_secrets([('x', line)])], ['credential-like assignment'], line)

    def test_unquoted_value_with_trailing_comment_is_flagged(self):
        for line in ['ADMIN_PASSWORD=' + self.SECRET + ' # production',
                     'ADMIN_PASSWORD=' + self.SECRET + '# production',
                     'db.password: ' + self.SECRET + '   # local only',
                     'API_KEY=' + self.SECRET + ' // note',
                     'client_secret = ' + self.SECRET + ' ; ini comment',
                     '+  password: ' + self.SECRET + ' # added line in a diff']:
            self.assert_flagged(line)

    def test_quoted_value_with_spaces_is_flagged(self):
        phrase = 'correct horse ' + 'battery staple'
        for line in ['password = "' + phrase + '"',
                     "password: '" + phrase + "'  # yaml",
                     'ADMIN_PASSWORD="' + phrase + '" # .env with comment']:
            self.assert_flagged(line)

    def test_trailing_comment_secret_blocks_review_end_to_end(self):
        sha = self.commit('deploy/settings.ini', 'admin_password = ' + self.SECRET + ' # production\n', 'ini')
        with self.assertRaises(RuntimeError) as ctx:
            cr.review(self.repo, sha)
        self.assertIn('deploy/settings.ini:1', str(ctx.exception))
        self.assertNotIn(self.SECRET, str(ctx.exception))
        self.assertEqual(self.calls(), [])

    # regression (f2c985c review #2): ${VAR:default} defaults are checked

    def test_env_reference_default_is_checked(self):
        for line in ['password: ${DB_PASSWORD:' + self.SECRET + '}',
                     'password: ${DB_PASSWORD:-' + self.SECRET + '}',
                     'password: ${DB_PASSWORD-' + self.SECRET + '}',
                     'password: ${DB_PASSWORD:=' + self.SECRET + '}',
                     'password: "${DB_PASSWORD:' + self.SECRET + '}"',  # codex-review: allow-secret (fixture)
                     'password: ${DB_PASSWORD:${LEGACY_PASSWORD:' + self.SECRET + '}}',
                     'password: ${DB_PASSWORD:' + self.SECRET + '}  # local default']:
            self.assert_flagged(line)

    def test_env_default_secret_blocks_review_end_to_end(self):
        sha = self.commit('backend/application.yml',
                          'spring:\n  datasource:\n    password: ${DB_PASSWORD:' + self.SECRET + '}\n', 'yml')
        with self.assertRaises(RuntimeError) as ctx:
            cr.review(self.repo, sha)
        self.assertIn('backend/application.yml:3', str(ctx.exception))
        self.assertEqual(self.calls(), [])

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

    def test_timeout_kills_codex_descendants(self):
        self.commit('a.txt', 'a\n', 'A')
        pid_file = Path(self.tmp.name) / 'child.pid'
        os.environ['FAKE_CODEX_MODE'] = 'sleep_with_child'
        os.environ['FAKE_CODEX_CHILD_PID'] = str(pid_file)
        with self.assertRaises(subprocess.TimeoutExpired):
            cr.main(['--repo', str(self.repo), '--timeout', '3'])
        pid = int(pid_file.read_text())
        deadline = time.time() + 5
        while time.time() < deadline and pid_alive(pid):
            time.sleep(0.1)
        self.assertFalse(pid_alive(pid), 'codex descendant survived the timeout')

    def test_skip_next_pending_commit_only(self):
        cr.main(['--repo', str(self.repo)])                 # state = initial commit
        first = self.commit('a.txt', 'a\n', 'A')
        second = self.commit('b.txt', 'b\n', 'B')
        with self.assertRaises(RuntimeError):                # not the next pending commit
            cr.main(['--repo', str(self.repo), '--skip', second, '--reason', 'x'])
        with self.assertRaises(SystemExit):                  # reason is mandatory
            cr.main(['--repo', str(self.repo), '--skip', first])
        calls = len(self.calls())
        cr.main(['--repo', str(self.repo), '--skip', first, '--reason', 'deleted default value'])
        self.assertEqual(cr.read_state(self.repo), first)
        self.assertEqual(len(self.calls()), calls)           # nothing sent to codex
        self.assertFalse(self.report(first).exists())        # not presented as reviewed
        log = cr.history_log(self.repo).read_text(encoding='utf-8')
        self.assertIn('skipped=' + first, log)
        self.assertIn('deleted default value', log)
        cr.main(['--repo', str(self.repo)])                  # the next run reviews only B
        self.assertEqual(cr.read_state(self.repo), second)
        self.assertTrue(self.report(second).exists())
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


    # ------------------------------------------------------------- 2026-10-20: disabled features, model settings

    def test_feature_still_enabled_blocks_sending(self):
        sha = self.commit('a.txt', 'a\n', 'A')
        os.environ['FAKE_CODEX_FEATURES_ON'] = 'hooks'
        with self.assertRaises(RuntimeError) as ctx:
            cr.review(self.repo, sha)
        self.assertIn('hooks=true', str(ctx.exception))
        self.assertEqual(self.calls(), [])
        self.assertFalse(self.report(sha).exists())

    def test_feature_missing_from_list_blocks_sending(self):
        # a renamed feature: the override no longer matches anything and the CLI does not complain
        sha = self.commit('a.txt', 'a\n', 'A')
        os.environ['FAKE_CODEX_FEATURES_MISSING'] = 'shell_tool'
        with self.assertRaises(RuntimeError) as ctx:
            cr.review(self.repo, sha)
        self.assertIn('shell_tool=missing', str(ctx.exception))
        self.assertEqual(self.calls(), [])

    def test_unsupported_effort_and_unknown_model_refused_before_sending(self):
        sha = self.commit('a.txt', 'a\n', 'A')
        with self.assertRaises(RuntimeError) as ctx:
            cr.review(self.repo, sha, effort='none')
        self.assertIn('not supported', str(ctx.exception))
        with self.assertRaises(RuntimeError) as ctx:
            cr.review(self.repo, sha, model='no-such-model')
        self.assertIn('not in the Codex model cache', str(ctx.exception))
        (self.codex_home / 'models_cache.json').unlink()
        with self.assertRaises(RuntimeError) as ctx:
            cr.review(self.repo, sha)
        self.assertIn('Cannot read the Codex model cache', str(ctx.exception))
        self.assertEqual(self.calls(), [])

    def test_model_and_effort_passed_and_recorded_with_log_settings(self):
        sha = self.commit('a.txt', 'a\n', 'A')
        out = self.main_output(['--repo', str(self.repo), '--commit', sha, '--effort', 'high'])
        args = self.calls()[0]['args']
        self.assertEqual(args[args.index('-m') + 1], cr.DEFAULT_MODEL)
        self.assertIn('model_reasoning_effort=high', args)
        text = self.report(sha).read_text(encoding='utf-8')
        self.assertIn('요청한 설정: 모델 `' + cr.DEFAULT_MODEL + '`, 추론 수준 `high`, CLI `codex-cli 9.9.9-fake`', text)
        self.assertIn('모델 캐시 기본 추론 수준: `low`', text)
        self.assertIn('실행 로그에서 확인된 설정: model `' + cr.DEFAULT_MODEL + '`', text)
        self.assertIn('reasoning effort `high`', text)
        self.assertIn('tokens used `1,234`', text)
        self.assertIn('요청과 로그의 차이: 없음', text)
        self.assertIn('Result file: ' + str(self.report(sha)), out)
        self.assertIn('Log file: ', out)

    def test_default_effort_is_medium(self):
        sha = self.commit('a.txt', 'a\n', 'A')
        cr.review(self.repo, sha)
        self.assertIn('model_reasoning_effort=medium', self.calls()[0]['args'])

    def test_log_that_disagrees_with_the_request_is_reported(self):
        sha = self.commit('a.txt', 'a\n', 'A')
        os.environ['FAKE_CODEX_LOG_EFFORT'] = 'none'
        cr.review(self.repo, sha)
        self.assertIn('추론 수준 요청 `medium` / 로그 `none`', self.report(sha).read_text(encoding='utf-8'))

    # ------------------------------------------------------------- 2026-10-20: input sent to codex

    def test_prompt_follows_rules_and_carries_the_rules_text(self):
        self.commit('docs/collaboration/RULES.md', '# 공통 협업 규칙\n- RULE_MARKER_42 규칙\n', 'rules')
        sha = self.commit('a.txt', 'a\n', 'A')
        cr.review(self.repo, sha)
        prompt = self.calls()[0]['prompt']
        self.assertIn("Follow the project's collaboration rules in docs/collaboration/RULES.md", prompt)
        self.assertIn('RULES (docs/collaboration/RULES.md):\n# 공통 협업 규칙\n- RULE_MARKER_42', prompt)
        self.assertIn('policy decision is reported as a question for the user', prompt)

    def test_task_md_sends_top_block_and_latest_sections_only(self):
        text = ('# 현재 작업\n\n## 현재 상태\n- TOP_MARKER\n\n## 1. 오래된 절\nOLD_MARKER ' + 'x' * 30000 + '\n'
                '## 2. 중간 절\nMIDDLE_MARKER\n\n## 3. 최근 절\nNEW_MARKER\n')
        self.commit('docs/collaboration/TASK.md', text, 'task')
        sha = self.commit('a.txt', 'a\n', 'A')
        cr.review(self.repo, sha)
        prompt = self.calls()[0]['prompt']
        for marker in ('TOP_MARKER', 'MIDDLE_MARKER', 'NEW_MARKER'):
            self.assertIn(marker, prompt)
        self.assertNotIn('OLD_MARKER', prompt)
        self.assertIn('TASK.md 발췌: 상단 + 최근 2개 절', prompt)

    def test_task_excerpt_keeps_and_cuts_a_huge_latest_section(self):
        excerpt, whole = cr.task_excerpt('# 현재 작업\n\n## 1. 큰 절\n' + 'y' * 50000 + '\n', limit=1000)
        self.assertLess(len(excerpt), 1400)
        self.assertIn('## 1. 큰 절', excerpt)
        self.assertIn('길이 제한으로 생략', excerpt)
        self.assertGreater(whole, 50000)

    def test_full_files_come_from_the_reviewed_commit_not_the_working_tree(self):
        lines = ['TOP_CONTEXT_LINE = 1'] + ['line_%d = %d' % (i, i) for i in range(2, 30)]
        self.commit('src/mod.py', '\n'.join(lines) + '\n', 'v1')
        lines[-1] = 'line_29 = 2929'
        sha = self.commit('src/mod.py', '\n'.join(lines) + '\n', 'v2')
        (self.repo / 'src' / 'mod.py').write_text('WORKTREE_ONLY = 1\n', encoding='utf-8')   # not committed
        cr.review(self.repo, sha)
        prompt = self.calls()[0]['prompt']
        self.assertIn('--- src/mod.py (검토 대상 시점 전체 내용)', prompt)
        self.assertIn('TOP_CONTEXT_LINE', prompt)              # far outside the diff hunk
        self.assertNotIn('WORKTREE_ONLY', prompt)
        self.assertIn('전체 내용 포함 1개', self.report(sha).read_text(encoding='utf-8'))

    def test_secret_in_an_unchanged_line_of_a_changed_file_blocks_sending(self):
        secret_line = 'admin_password = ' + self.SECRET + ' # production'
        self.commit('deploy/app.ini', secret_line + '\n' + '\n'.join('k%d = %d' % (i, i) for i in range(20)) + '\n', 'v1')
        sha = self.commit('deploy/app.ini', secret_line + '\n' + '\n'.join('k%d = %d' % (i, i) for i in range(19)) +
                          '\nk19 = changed\n', 'v2')
        with self.assertRaises(RuntimeError) as ctx:
            cr.review(self.repo, sha)
        self.assertIn('deploy/app.ini (전체 내용):1', str(ctx.exception))
        self.assertNotIn(self.SECRET, str(ctx.exception))
        self.assertEqual(self.calls(), [])

    def test_large_changed_file_is_listed_as_omitted_but_its_diff_is_sent(self):
        sha = self.commit('big.txt', 'BIG_START\n' + 'z' * 500 + '\n', 'big')
        with mock.patch.object(cr, 'FULL_FILE_MAX_CHARS', 100):
            cr.review(self.repo, sha)
        prompt = self.calls()[0]['prompt']
        self.assertIn('+BIG_START', prompt)                        # diff still there
        self.assertNotIn('--- big.txt (검토 대상 시점 전체 내용)', prompt)
        self.assertIn('big.txt: ', prompt.split('OMITTED FULL FILES:')[1])
        self.assertIn('전체 내용 생략 1개: big.txt', self.report(sha).read_text(encoding='utf-8'))

    def test_excluded_file_is_never_sent_as_a_full_file(self):
        (self.repo / '.env').write_text('PLAIN_ENV_MARKER=1\n', encoding='utf-8')
        (self.repo / 'app.py').write_text('x = 1\n', encoding='utf-8')
        run(['git', 'add', '-f', '.env', 'app.py'], self.repo)
        run(['git', 'commit', '-q', '-m', 'env and app'], self.repo)
        cr.review(self.repo, cr.git(self.repo, 'rev-parse', 'HEAD'))
        self.assertNotIn('PLAIN_ENV_MARKER', self.calls()[0]['prompt'])

    # ------------------------------------------------------------- 2026-10-20: --range

    def test_range_reviews_every_commit_of_the_branch_in_one_call(self):
        cr.main(['--repo', str(self.repo)])                       # state = initial commit
        state = cr.read_state(self.repo)
        calls = len(self.calls())
        run(['git', 'checkout', '-q', '-b', 'feature'], self.repo)
        a = self.commit('a.txt', 'RANGE_A\n', 'A')
        b = self.commit('b.txt', 'RANGE_B\n', 'B')
        out = self.main_output(['--repo', str(self.repo), '--range', 'main'])
        self.assertEqual(len(self.calls()), calls + 1)
        prompt = self.calls()[-1]['prompt']
        self.assertIn('+RANGE_A', prompt)
        self.assertIn('+RANGE_B', prompt)
        self.assertIn(a[:7] + ' A', prompt)
        self.assertIn(b[:7] + ' B', prompt)
        base = cr.git(self.repo, 'rev-parse', 'main')
        report = cr.reviews_dir(self.repo) / ('range-' + base[:12] + '-' + b[:12] + '.md')
        self.assertTrue(report.exists())
        self.assertIn('범위 리뷰: ' + base[:12] + '..' + b[:12], report.read_text(encoding='utf-8'))
        self.assertIn('Result file: ' + str(report), out)
        self.assertEqual(cr.read_state(self.repo), state)           # processed-commit state untouched
        with self.assertRaises(SystemExit):
            cr.main(['--repo', str(self.repo), '--range', 'main', '--commit', b])

    def test_range_over_the_size_limit_is_refused(self):
        run(['git', 'checkout', '-q', '-b', 'feature'], self.repo)
        self.commit('a.txt', 'q' * 300 + '\n', 'A')
        with mock.patch.object(cr, 'MAX_PATCH_CHARS', 100):
            with self.assertRaises(RuntimeError) as ctx:
                cr.main(['--repo', str(self.repo), '--range', 'main'])
        self.assertIn('Diff too large', str(ctx.exception))
        self.assertEqual(self.calls(), [])

    def test_dry_run_prints_input_settings_and_prompt_file(self):
        sha = self.commit('a.txt', 'a\n', 'A')
        out = self.main_output(['--repo', str(self.repo), '--commit', sha, '--dry-run'])
        self.assertIn('Input:', out)
        self.assertIn('Settings: model ' + cr.DEFAULT_MODEL + ' | effort medium | cli codex-cli 9.9.9-fake', out)
        self.assertIn("'shell_tool': 'false'", out)
        self.assertIn('Prompt file: ', out)
        self.assertEqual(self.calls(), [])

if __name__ == '__main__':
    unittest.main()
