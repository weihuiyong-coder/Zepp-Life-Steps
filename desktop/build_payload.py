from pathlib import Path
import hashlib
import json
import re
import zipfile

project = Path(__file__).resolve().parent.parent
build = project / 'desktop' / '.build'
standalone = project / '.next-desktop' / 'standalone'
assert 'desktopInstance' in (standalone / '.next-desktop/server/pages/api/health.js').read_text(encoding='utf-8')
payload = {}

def add_tree(source, destination):
    for file in source.rglob('*'):
        if file.is_file():
            parts = file.relative_to(source).parts
            if any(part in ('.venv', '__pycache__', '.git', 'cache') for part in parts):
                continue
            if file.name.startswith('.env') or file.suffix in ('.log', '.pyc'):
                continue
            payload[destination + '/' + file.relative_to(source).as_posix()] = file.read_bytes()

add_tree(standalone, 'app')
# File tracing can omit license texts that are required when redistributing packages.
for metadata in (standalone / 'node_modules').rglob('package.json'):
    package_relative = metadata.parent.relative_to(standalone / 'node_modules')
    source_package = project / 'node_modules' / package_relative
    if source_package.is_dir():
        for license_file in source_package.iterdir():
            if license_file.is_file() and license_file.name.lower().startswith(('license', 'licence', 'copying', 'notice')):
                payload['app/node_modules/' + package_relative.as_posix() + '/' + license_file.name] = license_file.read_bytes()
add_tree(project / '.next-desktop' / 'static', 'app/.next-desktop/static')
add_tree(project / 'public', 'app/public')
add_tree(project / 'server' / 'python', 'app/server/python')
payload['app/LICENSE'] = (project / 'LICENSE').read_bytes()
payload['app/SOURCE-README.md'] = (project / 'README.md').read_bytes()
server = payload['app/server.js'].decode('utf-8')
server = re.sub(r'"outputFileTracingRoot":"(?:[^"\\]|\\.)*"', '"outputFileTracingRoot":dir', server)
payload['app/server.js'] = server.encode('utf-8')

node = build / 'node.exe'
checksums = (build / 'node-SHASUMS256.txt').read_text()
expected = next(line.split()[0] for line in checksums.splitlines() if line.endswith('win-x64/node.exe'))
assert hashlib.sha256(node.read_bytes()).hexdigest() == expected
payload['runtime/node.exe'] = node.read_bytes()
payload['runtime/NODE-LICENSE.txt'] = (build / 'NODE-LICENSE.txt').read_bytes()
with zipfile.ZipFile(build / 'python-3.14.7-embed-amd64.zip') as python:
    assert python.testzip() is None
    for name in python.namelist():
        if not name.endswith('/'):
            payload['runtime/python/' + name] = python.read(name)
payload['runtime/python/python314._pth'] = b'python314.zip\n.\nLib/site-packages\nimport site\n'

site = project / '.venv' / 'Lib' / 'site-packages'
packages = {'requests', 'urllib3', 'certifi', 'charset_normalizer', 'idna'}
for folder in site.iterdir():
    if folder.is_dir() and (folder.name in packages or any(folder.name.startswith(p + '-') and folder.name.endswith('.dist-info') for p in packages)):
        add_tree(folder, 'runtime/python/Lib/site-packages/' + folder.name)

payload['BUILD-INFO.json'] = json.dumps({
    'frontend': 'LiuJun-tao/Zepp-Life-Steps@f1e1910',
    'login': 'miloce/Zepp-Life-Steps@1a6c240',
    'node': '24.18.0', 'python': '3.14.7', 'platform': 'Windows x64',
    'thirdPartyLicenses': 'Node: runtime/NODE-LICENSE.txt; Python: runtime/python/LICENSE.txt; Python packages: runtime/python/Lib/site-packages/*.dist-info; npm packages: app/node_modules.'
}, ensure_ascii=False, indent=2).encode('utf-8')
for name in payload:
    assert not any(p in name.split('/') for p in ('.git', '.venv', '__pycache__'))
    assert not name.endswith('.log')
manifest = ''.join(f'{hashlib.sha256(data).hexdigest()}  {name}\n' for name, data in sorted(payload.items()))
payload['MANIFEST.sha256'] = manifest.encode('utf-8')
archive = build / 'payload.zip'
with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as out:
    for name, data in sorted(payload.items()):
        out.writestr(name, data)
with zipfile.ZipFile(archive) as check:
    assert check.testzip() is None
identifier = hashlib.sha256(archive.read_bytes()).hexdigest()[:20]
(build / 'BuildInfo.cs').write_text('internal static class BuildInfo { internal const string PayloadId = "' + identifier + '"; }\n', encoding='utf-8')
print(json.dumps({'files':len(payload), 'expandedMB':round(sum(map(len,payload.values()))/1048576,1), 'archiveMB':round(archive.stat().st_size/1048576,1), 'id':identifier}))
