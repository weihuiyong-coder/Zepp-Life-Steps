from pathlib import Path
import hashlib
import shutil
import zipfile

project = Path(__file__).resolve().parent.parent
build = project / 'desktop' / '.build'
output = project / 'dist'
output.mkdir(exist_ok=True)
exe = output / 'Zepp步数助手.exe'
shutil.copyfile(build / 'Zepp-Life-Steps.exe', exe)
instructions = output / 'EXE版使用说明.txt'
shutil.copyfile(project / 'docs' / 'windows-exe.txt', instructions)
files = [exe, instructions]
package = output / 'Zepp步数助手-Windows免安装版.zip'
with zipfile.ZipFile(package, 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
    for file in files:
        archive.write(file, file.name)
with zipfile.ZipFile(package) as archive:
    assert archive.testzip() is None
    assert all(archive.read(file.name) == file.read_bytes() for file in files)
for file in [*files, package]:
    print(file.name, file.stat().st_size, hashlib.sha256(file.read_bytes()).hexdigest())
print('Transfer package verified.')
