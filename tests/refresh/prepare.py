from pathlib import Path
import argparse
import hashlib
import json

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
parser = argparse.ArgumentParser(description='Extract production refresh/session paths for controlled host tests.')
parser.add_argument('--out', type=Path, required=True)
args = parser.parse_args()
output = args.out.resolve()
output.mkdir(parents=True, exist_ok=True)


def method(source, marker):
    start = source.index(marker)
    arguments, quote, escaped = 0, False, False
    opening = None
    for at in range(start, len(source)):
        character = source[at]
        if quote:
            if escaped:
                escaped = False
            elif character == '\\':
                escaped = True
            elif character == '"':
                quote = False
        elif character == '"':
            quote = True
        elif character == '(':
            arguments += 1
        elif character == ')':
            arguments -= 1
        elif character == '{' and arguments == 0:
            opening = at
            break
    if opening is None:
        raise ValueError('Production body missing: ' + marker)
    depth, quote, escaped = 0, False, False
    for at in range(opening, len(source)):
        character = source[at]
        if quote:
            if escaped:
                escaped = False
            elif character == '\\':
                escaped = True
            elif character == '"':
                quote = False
        elif character == '"':
            quote = True
        elif character == '{':
            depth += 1
        elif character == '}':
            depth -= 1
            if depth == 0:
                return source[start:at + 1]
    raise ValueError('Unbalanced production method: ' + marker)


platforms = [
    ('Swift', REPO / 'ios/app', 'StreamCallEngine.swift', 'StreamCallLedger.swift',
     ['    private final class TokenSession', '    func configure(', '    func setToken(',
      '    private func invalidate(', '    private func owns(', '    private func storeToken(',
      '    func logout()', '    private func refreshToken(', '    private func registerVoipToken(']),
    ('Kotlin', REPO / 'android/streamcall/src/main/java/despia/ext/streamcall',
     'StreamCallEngine.kt', 'StreamCallLedger.java',
     ['    private class TokenSession', '    fun configure(', '    private fun removeOwnedClient(',
      '    private suspend fun restorePrevious(', '    private fun queueDeviceWork(', '    private fun retire(',
      '    private suspend fun registerPush(', '    private suspend fun unregisterPush(',
      '    fun setToken(', '    private fun owns(', '    private fun invalidate(',
      '    private fun storeToken(', '    fun logout(', '    private suspend fun refreshToken(']),
]
receipt = {'scope': 'Controlled SDK, HTTP and storage boundaries. No native SDK or service connection.', 'inputs': {}, 'methods': {}}
for platform, directory, engine, ledger, markers in platforms:
    source = (directory / engine).read_text()
    extracted = '\n'.join(method(source, marker) for marker in markers)
    template = HERE / (platform + 'Engine.' + ('swift' if platform == 'Swift' else 'kt') + '.template')
    destination = output / template.stem
    rendered = template.read_text().replace('@METHODS@', extracted)
    if platform == 'Kotlin':
        rendered = rendered.replace('@PROVIDER@', method(source, '                override suspend fun loadToken('))
    destination.write_text(rendered)
    (output / ledger).write_bytes((directory / ledger).read_bytes())
    checks = HERE / (platform + 'Checks.' + ('swift' if platform == 'Swift' else 'kt'))
    (output / checks.name).write_bytes(checks.read_bytes())
    if platform == 'Kotlin':
        markers = markers + ['                override suspend fun loadToken(']
    receipt['methods'][engine] = {marker: hashlib.sha256(method(source, marker).encode()).hexdigest() for marker in markers}
    for path in [directory / engine, directory / ledger, template, checks, Path(__file__).resolve()]:
        receipt['inputs'][str(path.relative_to(REPO))] = hashlib.sha256(path.read_bytes()).hexdigest()
(output / 'extraction.json').write_text(json.dumps(receipt, indent=2) + '\n')
print('Prepared controlled host checks. No compiler or network operation was launched.')
