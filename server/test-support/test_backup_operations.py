"""Deletion boundaries, failure recovery, cryptographic custody and SMTP gates.
Uses synthetic objects only; never connects to any database or bucket.
"""
import copy, importlib.util, json, os, pathlib, sys, tempfile, unittest, uuid
from datetime import datetime, timedelta, timezone
from botocore.exceptions import ClientError
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.hazmat.primitives import serialization

OPS=pathlib.Path(__file__).resolve().parents[1]/'ops'
def module(name):
    spec=importlib.util.spec_from_file_location(name,OPS/(name+'.py'));value=importlib.util.module_from_spec(spec);spec.loader.exec_module(value);return value
backup=module('backup');custody=module('backup_key_custody');smtp=module('check_smtp')
NOW=datetime(2026,10,13,10,0,tzinfo=timezone.utc)
def row(age):
    job=uuid.uuid4();stamp=NOW-timedelta(days=age);key='backups/preview/'+stamp.strftime('%Y/%m/%d/%H%M%S')+'-'+str(job)+'.snapshot.aesgcm'
    return (job,stamp+timedelta(seconds=1),key,'a'*64,100)

class Database:
    def __init__(self,rows):self.rows=rows;self.updated=[];self.jobs=[];self.fail_update=False
    def cursor(self):return self
    def __enter__(self):return self
    def __exit__(self,*args):pass
    def execute(self,sql,args=None):
        self.rowcount=1
        if sql.startswith('INSERT'):self.jobs.append('RUNNING')
        elif 'artifact_key=NULL' in sql:
            if self.fail_update:self.fail_update=False;raise RuntimeError('synthetic connection failure')
            self.updated.append(args[0]);self.rows=[r for r in self.rows if r[0]!=args[0]]
        elif "status='SUCCESS'" in sql and sql.startswith('UPDATE'):self.jobs.append('SUCCESS')
        elif "status='FAILED'" in sql:self.jobs.append('FAILED')
    def fetchall(self):return self.rows

class Store:
    def __init__(self,rows):self.objects={r[2]:{'ContentLength':r[4],'Metadata':{'sha256':r[3],'encryption':'AES-256-GCM'}} for r in rows};self.deleted=[];self.fail=False;self.ignore=False
    def head_object(self,Bucket,Key):
        if Key not in self.objects:raise ClientError({'Error':{'Code':'NoSuchKey'}},'HeadObject')
        return self.objects[Key]
    def delete_object(self,Bucket,Key):
        if self.fail:raise RuntimeError('synthetic outage')
        self.deleted.append(Key)
        if not self.ignore:del self.objects[Key]

class RetentionTests(unittest.TestCase):
    def run_policy(self,rows,store=None,db=None,**kwargs):
        db=db or Database(rows);store=store or Store(rows);result=backup.retain_backups(db,store,'synthetic',rows[0][0],True,now=NOW,**kwargs);return result,db,store
    def test_expiry_keeps_recent_and_minimum_and_audit(self):
        rows=[row(n) for n in (0,1,2,29,31,45)];result,db,store=self.run_policy(rows)
        self.assertEqual(store.deleted,[r[2] for r in rows[-2:]]);self.assertEqual(result['availableCopies'],4);self.assertEqual(db.updated,[r[0] for r in rows[-2:]]);self.assertTrue(result['auditRecordsRetained'])
    def test_long_outage_still_keeps_last_three(self):
        rows=[row(n) for n in (0,60,70,80)];_,_,store=self.run_policy(rows);self.assertEqual(store.deleted,[rows[-1][2]])
    def test_fewer_than_three_and_exact_cutoff_are_preserved(self):
        for ages in ((0,90),(0,1,2,30)):
            rows=[row(n) for n in ages];_,_,store=self.run_policy(rows);self.assertEqual(store.deleted,[])
    def test_no_rehearsal_or_unsafe_policy_cannot_delete(self):
        rows=[row(n) for n in (0,1,2,60)]
        for verified,days,minimum in ((False,30,3),(True,0,3),(True,30,1),(True,366,3)):
            store=Store(rows);db=Database(rows)
            with self.assertRaises(RuntimeError):backup.retain_backups(db,store,'synthetic',rows[0][0],verified,days,minimum,now=NOW)
            self.assertEqual(store.deleted,[])
    def test_wrong_current_job_cannot_delete(self):
        rows=[row(n) for n in (0,1,2,60)];store=Store(rows);db=Database(rows)
        with self.assertRaises(RuntimeError):backup.retain_backups(db,store,'synthetic',uuid.uuid4(),True,now=NOW)
        self.assertEqual(store.deleted,[]);self.assertEqual(db.jobs[-1],'FAILED')
    def test_invalid_namespace_uuid_date_or_hash_cannot_delete(self):
        for field,value in ((2,'preview/attachments/important.pdf'),(2,'backups/preview/2026/09/01/100000-'+str(uuid.uuid4())+'.snapshot.aesgcm'),(2,'backups/preview/2026/99/01/100000-x.snapshot.aesgcm'),(3,'invalid'),(4,-1)):
            rows=[row(n) for n in (0,1,2,60)];changed=list(rows[-1]);changed[field]=value;rows[-1]=tuple(changed);store=Store(rows)
            with self.assertRaises(RuntimeError):self.run_policy(rows,store)
            self.assertEqual(store.deleted,[])
    def test_missing_protected_or_changed_metadata_stops_all_deletes(self):
        for protected in (True,False):
            rows=[row(n) for n in (0,1,2,60,80)];store=Store(rows)
            if protected:del store.objects[rows[1][2]]
            else:store.objects[rows[-1][2]]['Metadata']['sha256']='b'*64
            with self.assertRaises(RuntimeError):self.run_policy(rows,store)
            self.assertEqual(store.deleted,[])
    def test_delete_failure_and_unconfirmed_delete_leave_pointer(self):
        for attribute in ('fail','ignore'):
            rows=[row(n) for n in (0,1,2,60)];store=Store(rows);setattr(store,attribute,True);db=Database(rows)
            with self.assertRaises(RuntimeError):self.run_policy(rows,store,db)
            self.assertEqual(db.updated,[]);self.assertEqual(db.jobs[-1],'FAILED')
    def test_interrupted_delete_is_idempotent_next_run(self):
        rows=[row(n) for n in (0,1,2,60)];store=Store(rows);db=Database(rows);db.fail_update=True
        with self.assertRaises(RuntimeError):self.run_policy(rows,store,db)
        self.assertEqual(len(store.deleted),1)
        result,_,_=self.run_policy(rows,store,db);self.assertEqual(len(store.deleted),1);self.assertEqual(result['expiredAlreadyMissing'],1)

class CustodyTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):cls.private=rsa.generate_private_key(public_exponent=65537,key_size=3072);cls.pem=cls.private.public_key().public_bytes(serialization.Encoding.PEM,serialization.PublicFormat.SubjectPublicKeyInfo)
    def test_wrap_recovers_exact_key_without_plaintext_in_packet(self):
        key=os.urandom(32);packet=custody.wrap(key,self.pem);self.assertEqual(custody.unwrap(packet,self.private),key);self.assertNotIn(key.hex(),json.dumps(packet))
    def test_wrong_recipient_tampering_or_hash_rejected(self):
        packet=custody.wrap(os.urandom(32),self.pem)
        for field in ('format','algorithm','publicKeySha256','backupKeySha256','wrappedKey'):
            changed=copy.deepcopy(packet);changed[field]='invalid'
            with self.assertRaises(Exception):custody.unwrap(changed,self.private)
    def test_weak_public_key_and_wrong_aes_size_rejected(self):
        weak=rsa.generate_private_key(public_exponent=65537,key_size=2048).public_key().public_bytes(serialization.Encoding.PEM,serialization.PublicFormat.SubjectPublicKeyInfo)
        for key,pem in ((os.urandom(32),weak),(os.urandom(16),self.pem)):
            with self.assertRaises(ValueError):custody.wrap(key,pem)
    def test_output_restricts_permissions_and_refuses_overwrite(self):
        with tempfile.TemporaryDirectory() as temp:
            file=pathlib.Path(temp)/'packet';custody.exclusive_write(file,b'encrypted');self.assertEqual(file.stat().st_mode&0o777,0o600)
            with self.assertRaises(FileExistsError):custody.exclusive_write(file,b'changed')
            self.assertEqual(file.read_bytes(),b'encrypted')

class SchemaTests(unittest.TestCase):
    def fixture(self,version):
        tables,rls=backup.schema_sets(version)
        return {'counts':{name:0 for name in tables},'migrations':[(str(i),True) for i in range(1,version+1)],'rls':[(name,True,True,'crm_migrator') for name in sorted(rls)]}
    def test_v5_through_v17_exact_schema_sets_are_supported(self):
        for version in range(5,18):self.assertEqual(backup.validate_schema(self.fixture(version)),version)
    def test_future_missing_unprotected_and_wrong_owner_are_rejected(self):
        for scenario in ('future','missing','extra','unforced','owner'):
            info=self.fixture(17)
            if scenario=='future':info['migrations'].append(('18',True))
            if scenario=='missing':del info['counts']['crm_quote']
            if scenario=='extra':info['counts']['unexpected_customer_data']=1
            if scenario=='unforced':info['rls'][0]=(info['rls'][0][0],True,False,'crm_migrator')
            if scenario=='owner':info['rls'][0]=(info['rls'][0][0],True,True,'crm_runtime')
            with self.assertRaises(RuntimeError):backup.validate_schema(info)

class MailTests(unittest.TestCase):
    def config(self):return {'CRM_SMTP_HOST':'smtp.resend.com','CRM_SMTP_PORT':'465','CRM_SMTP_FROM':'crm@example.com','CRM_SMTP_USER':'resend','CRM_SMTP_PASSWORD':'synthetic','CRM_SMTP_SSL':'true','CRM_SMTP_TLS':'false','CRM_PUBLIC_BASE_URL':'https://crm.example.com'}
    def test_valid_implicit_and_starttls_settings(self):
        env=self.config();self.assertTrue(smtp.settings(env)[-1]);env.update(CRM_SMTP_PORT='587',CRM_SMTP_SSL='false',CRM_SMTP_TLS='true');self.assertFalse(smtp.settings(env)[-1])
    def test_incomplete_plaintext_or_malicious_settings_rejected(self):
        for field,value in (('CRM_SMTP_PASSWORD',''),('CRM_SMTP_FROM','bad\r\nBcc: victim@example.com'),('CRM_SMTP_TLS','true'),('CRM_SMTP_PORT','0'),('CRM_PUBLIC_BASE_URL','http://crm.example.com'),('CRM_PUBLIC_BASE_URL','https://user:secret@example.com'),('CRM_PUBLIC_BASE_URL','https://crm.example.com/?reset=leak')):
            env=self.config();env[field]=value
            with self.assertRaises(ValueError):smtp.settings(env)

if __name__=='__main__':unittest.main()
