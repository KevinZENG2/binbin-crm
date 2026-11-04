"""Package the built CRM without credentials or workspace-only files."""
import base64,gzip,hashlib,io,json,pathlib,sys,tarfile
out=pathlib.Path(sys.argv[1] if len(sys.argv)>1 else 'crm-release.tgz');buffer=io.BytesIO()
with tarfile.open(fileobj=buffer,mode='w') as archive:
 for source in ('dist','server/pom.xml','server/src'):
  p=pathlib.Path(source)
  for file in ([p]+sorted(p.rglob('*')) if p.is_dir() else [p]):
   info=archive.gettarinfo(str(file),arcname=str(file));info.uid=0;info.gid=0;info.uname='';info.gname='';info.mtime=0
   if file.is_file():
    with file.open('rb') as stream:archive.addfile(info,stream)
   else:archive.addfile(info)
content=gzip.compress(buffer.getvalue(),mtime=0);out.write_bytes(content)
print(json.dumps({'file':out.name,'bytes':len(content),'sha256':hashlib.sha256(content).hexdigest()}))
