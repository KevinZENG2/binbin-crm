"""Package prebuilt images and tracked source, never credentials or local state."""
import hashlib
import json
import os
import pathlib
import subprocess
import tarfile

ROOT = pathlib.Path(__file__).resolve().parents[3]
RELEASE = '0.23.0'


def sha(path):
    digest = hashlib.sha256()
    with pathlib.Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def root_owned(info):
    if not (info.isfile() or info.isdir()):
        raise RuntimeError('Links and special files are not allowed in the VM bundle')
    info.uid = info.gid = 0
    info.uname = info.gname = 'root'
    info.mode = 0o755 if info.isdir() or info.mode & 0o111 else 0o644
    return info


def main():
    os.chdir(ROOT)
    source = subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()
    tag_suffix = RELEASE + '-' + source[:12]
    output = ROOT / 'vm-output'
    output.mkdir(exist_ok=False)
    images = {}
    for name, original in {'app': 'binbin-crm-preview-app:local',
                           'tools': 'binbin-crm-preview-tools:local',
                           'storage': 'binbin-crm-preview-storage:local',
                           'postgres': 'postgres:18'}.items():
        tag = 'binbin-crm-vm-' + name + ':' + tag_suffix
        subprocess.run(['docker', 'tag', original, tag], check=True)
        identifier = subprocess.check_output(['docker', 'image', 'inspect', '--format', '{{.Id}}', tag], text=True).strip()
        images[name] = {'tag': tag, 'id': identifier}
    image_file = output / 'images.tar.gz'
    with image_file.open('wb') as stream:
        save = subprocess.Popen(['docker', 'save'] + [image['tag'] for image in images.values()], stdout=subprocess.PIPE)
        compressed = subprocess.run(['gzip', '-1'], stdin=save.stdout, stdout=stream)
        save.stdout.close()
        if compressed.returncode or save.wait():
            raise RuntimeError('Image archive creation failed')
    lock = output / 'image-lock.json'
    font = ROOT / 'vm-assets/quote-font.ttf'
    font_manifest = json.loads((ROOT / 'server/ops/assets/font-source.json').read_text())
    if sha(font) != font_manifest['fontSha256']:
        raise RuntimeError('Bundled PDF font is missing or invalid')
    lock.write_text(json.dumps({'version': RELEASE, 'sourceCommit': source, 'syntheticPreviewOnly': True,
                               'images': images, 'imageArchiveSha256': sha(image_file)}, indent=2) + '\n')
    names = subprocess.check_output(['git', 'ls-files', '-z']).decode().split('\0')
    target = output / ('binbin-crm-vm-' + RELEASE + '.tar.gz')
    with tarfile.open(target, 'w:gz', compresslevel=1) as archive:
        for name in filter(None, names):
            path = pathlib.PurePosixPath(name)
            if path.is_absolute() or '..' in path.parts or path.parts[0] in ('.crm-preview', 'upload'):
                raise RuntimeError('Unexpected tracked path in deployment package')
            archive.add(ROOT / name, arcname='binbin-crm-preview/project/' + name, recursive=False, filter=root_owned)
        archive.add(image_file, arcname='binbin-crm-preview/images.tar.gz', filter=root_owned)
        archive.add(lock, arcname='binbin-crm-preview/image-lock.json', filter=root_owned)
        archive.add(font, arcname='binbin-crm-preview/assets/quote-font.ttf', filter=root_owned)
    digest = sha(target)
    (output / (target.name + '.sha256')).write_text(digest + '  ' + target.name + '\n')
    print(json.dumps({'file': target.name, 'sha256': digest, 'bytes': target.stat().st_size, 'sourceCommit': source}))


if __name__ == '__main__':
    main()
