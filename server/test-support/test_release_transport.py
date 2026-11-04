"""Exercise private release startup without network, credentials, Java or PostgreSQL."""
import base64,hashlib,io,os,pathlib,subprocess,sys,tarfile,tempfile,unittest
SOURCE=pathlib.Path(__file__).resolve().parents[1]/'ops/start_release_from_s3.sh'
class TransportTests(unittest.TestCase):
 def fixture(self,root,private=False,tamper=False):
  bundle=io.BytesIO()
  with tarfile.open(fileobj=bundle,mode='w:gz') as archive:
   for name,content in [('dist/index.html',b'synthetic'),('server/pom.xml',b'synthetic')]:
    info=tarfile.TarInfo(name);info.size=len(content);archive.addfile(info,io.BytesIO(content))
  data=bundle.getvalue();work=root/'work';bin_dir=root/'bin';bin_dir.mkdir();store=root/'private.tgz';calls=root/'java-called'
  if private:store.write_bytes(data)
  curl='''import os,sys,pathlib
args=sys.argv[1:];store=pathlib.Path(os.environ['FAKE_STORE'])
if '--upload-file' in args:store.write_bytes(pathlib.Path(args[args.index('--upload-file')+1]).read_bytes())
else:
 if not store.exists():sys.exit(22)
 out=args[args.index('-o')+1];data=store.read_bytes()
 if os.environ.get('FAKE_TAMPER')=='true' and out=='readback.tgz':data+=b'tampered'
 pathlib.Path(out).write_bytes(data)
'''
  for name,code in [('curl',curl),('mvn','pass'),('java',"import os,pathlib;pathlib.Path(os.environ['FAKE_JAVA_CALLED']).write_text('called')")]:
   path=bin_dir/name;path.write_text('#!'+sys.executable+'\n'+code+'\n');path.chmod(0o700)
  script=SOURCE.read_text().replace('mkdir -p /opt/crm\ncd /opt/crm',f'mkdir -p {work}\ncd {work}')
  path=root/'start.sh';path.write_text(script)
  env=dict(os.environ,PATH=str(bin_dir)+os.pathsep+os.environ['PATH'],CRM_P7_RELEASE_SHA256=hashlib.sha256(data).hexdigest(),CRM_P7_RELEASE_OBJECT_KEY='system/releases/crm/synthetic.tgz',CRM_S3_ENDPOINT='https://storage.invalid',CRM_S3_BUCKET='synthetic',CRM_S3_ACCESS_KEY='synthetic',CRM_S3_SECRET_KEY='synthetic',FAKE_STORE=str(store),FAKE_JAVA_CALLED=str(calls),FAKE_TAMPER=str(tamper).lower())
  encoded=base64.b64encode(data).decode();parts=[encoded[i:i+100] for i in range(0,len(encoded),100)];env['CRM_P8_RELEASE_CHUNKS']=str(len(parts))
  for index,part in enumerate(parts,1):env[f'CRM_P8_RELEASE_{index:02}']=part
  return path,env,data,store,calls
 def test_first_start_reconstructs_verifies_persists_and_reads_back(self):
  with tempfile.TemporaryDirectory() as temp:
   path,env,data,store,calls=self.fixture(pathlib.Path(temp));r=subprocess.run(['sh',str(path)],env=env,capture_output=True,text=True)
   self.assertEqual(r.returncode,0,r.stderr);self.assertEqual(store.read_bytes(),data);self.assertTrue(calls.exists());self.assertIn('CRM_RELEASE_PRIVATE_STAGED',r.stdout)
 def test_next_start_requires_no_source_fragments(self):
  with tempfile.TemporaryDirectory() as temp:
   path,env,data,store,calls=self.fixture(pathlib.Path(temp),private=True);env={k:v for k,v in env.items() if not k.startswith('CRM_P8_RELEASE_')};r=subprocess.run(['sh',str(path)],env=env,capture_output=True,text=True)
   self.assertEqual(r.returncode,0,r.stderr);self.assertTrue(calls.exists());self.assertNotIn('CRM_RELEASE_PRIVATE_STAGED',r.stdout)
 def test_corrupt_source_never_starts_application(self):
  with tempfile.TemporaryDirectory() as temp:
   path,env,data,store,calls=self.fixture(pathlib.Path(temp));env['CRM_P8_RELEASE_01']='bm90LWEtdmFsaWQtcmVsZWFzZQ==';r=subprocess.run(['sh',str(path)],env=env,capture_output=True,text=True)
   self.assertNotEqual(r.returncode,0);self.assertFalse(calls.exists());self.assertFalse(store.exists())
 def test_corrupt_storage_readback_never_starts_application(self):
  with tempfile.TemporaryDirectory() as temp:
   path,env,data,store,calls=self.fixture(pathlib.Path(temp),tamper=True);r=subprocess.run(['sh',str(path)],env=env,capture_output=True,text=True)
   self.assertNotEqual(r.returncode,0);self.assertFalse(calls.exists())
if __name__=='__main__':unittest.main()
